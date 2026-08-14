package com.tesla.autostreamer.aap

import android.annotation.SuppressLint
import android.util.Log
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Non-blocking TLS/SSL Engine Handler for Android Auto Protocol (AAP)
 * Encapsulates TLS Handshake frames inside Channel 0 (MSG_SSL_HANDSHAKE = 0x0003)
 */
class AAPSSLEngine {
    companion object {
        private const val TAG = "TeslaAAPSSL"
        private const val BUFFER_SIZE = 32 * 1024
    }

    private var sslEngine: SSLEngine? = null
    private var myNetData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var peerNetData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var myAppData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private var peerAppData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)

    @Volatile
    var isHandshakeComplete = false
        private set

    init {
        initSSL()
    }

    @SuppressLint("CustomX509TrustManager")
    private fun initSSL() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLSv1.2").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            sslEngine = sslContext.createSSLEngine(AAPConstants.AAP_HOST, AAPConstants.AAP_TCP_PORT).apply {
                useClientMode = true
                enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            }

            sslEngine?.beginHandshake()
            Log.i(TAG, "SSLEngine inizializzato in modalità Client per Android Auto.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore inizializzazione SSLEngine:", e)
        }
    }

    /**
     * Generates the initial TLS ClientHello packet for AAP
     */
    fun startHandshake(): ByteArray? {
        val engine = sslEngine ?: return null
        myNetData.clear()
        myAppData.clear()

        val result = engine.wrap(myAppData, myNetData)
        if (result.status == SSLEngineResult.Status.OK) {
            myNetData.flip()
            val clientHello = ByteArray(myNetData.remaining())
            myNetData.get(clientHello)
            Log.d(TAG, "Generato TLS ClientHello (${clientHello.size} bytes)")
            return clientHello
        }
        return null
    }

    /**
     * Feeds incoming TLS Server handshake packets from Android Auto (Channel 0)
     */
    fun processServerHandshake(serverData: ByteArray): ByteArray? {
        val engine = sslEngine ?: return null
        peerNetData.clear()
        peerNetData.put(serverData)
        peerNetData.flip()
        peerAppData.clear()

        var result = engine.unwrap(peerNetData, peerAppData)
        Log.d(TAG, "TLS unwrap status: ${result.status}, handshakeStatus: ${result.handshakeStatus}")

        while (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var task: Runnable?
            while (engine.delegatedTask.also { task = it } != null) {
                task?.run()
            }
        }

        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            myNetData.clear()
            myAppData.clear()
            val wrapResult = engine.wrap(myAppData, myNetData)
            if (wrapResult.status == SSLEngineResult.Status.OK) {
                myNetData.flip()
                val outgoing = ByteArray(myNetData.remaining())
                myNetData.get(outgoing)
                return outgoing
            }
        } else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                   result.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            isHandshakeComplete = true
            Log.i(TAG, "TLS Handshake con Android Auto COMPLETATO CON SUCCESSO! Canale protetto attivo.")
        }

        return null
    }

    /**
     * Encrypts outgoing AAP payload (Channel 0 Auth / ServiceDiscovery)
     */
    fun encrypt(plainData: ByteArray): ByteArray? {
        if (!isHandshakeComplete) return plainData
        val engine = sslEngine ?: return plainData

        myAppData.clear()
        myAppData.put(plainData)
        myAppData.flip()

        myNetData.clear()
        val result = engine.wrap(myAppData, myNetData)
        if (result.status == SSLEngineResult.Status.OK) {
            myNetData.flip()
            val encrypted = ByteArray(myNetData.remaining())
            myNetData.get(encrypted)
            return encrypted
        }
        return plainData
    }

    /**
     * Decrypts incoming encrypted AAP payload from Android Auto
     */
    fun decrypt(cipherData: ByteArray): ByteArray? {
        if (!isHandshakeComplete) return cipherData
        val engine = sslEngine ?: return cipherData

        peerNetData.clear()
        peerNetData.put(cipherData)
        peerNetData.flip()

        peerAppData.clear()
        val result = engine.unwrap(peerNetData, peerAppData)
        if (result.status == SSLEngineResult.Status.OK) {
            peerAppData.flip()
            val decrypted = ByteArray(peerAppData.remaining())
            peerAppData.get(decrypted)
            return decrypted
        }
        return cipherData
    }
}
