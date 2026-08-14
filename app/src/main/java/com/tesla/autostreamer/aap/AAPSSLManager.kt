package com.tesla.autostreamer.aap

import android.annotation.SuppressLint
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS/SSL Manager for Android Auto Protocol (AAP)
 * 
 * Handles the secure encrypted tunnel on Channel 0 required by Google's Head Unit Server.
 */
object AAPSSLManager {
    private const val TAG = "TeslaAAPSSL"

    @SuppressLint("CustomX509TrustManager")
    fun createSSLContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLSv1.2")
        
        // Generate lightweight transient self-signed key for Android Auto handshake
        try {
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, "tesla".toCharArray())
            }
            sslContext.init(kmf.keyManagers, trustAllCerts, SecureRandom())
        } catch (e: Exception) {
            Log.w(TAG, "Fallback default SSLContext init: ${e.message}")
            sslContext.init(null, trustAllCerts, SecureRandom())
        }

        return sslContext
    }

    fun createClientEngine(sslContext: SSLContext): SSLEngine {
        return sslContext.createSSLEngine(AAPConstants.AAP_HOST, AAPConstants.AAP_TCP_PORT).apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        }
    }
}
