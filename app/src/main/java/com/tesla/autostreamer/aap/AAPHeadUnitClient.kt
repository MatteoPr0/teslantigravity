package com.tesla.autostreamer.aap

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Complete Native Android Auto Protocol (AAP) Head Unit Coordinator
 * 
 * Features:
 * 1. TCP 5277 Socket Supervisor with Auto-Recovery
 * 2. 4-byte Version Handshake (Major 1, Minor 1)
 * 3. Hardware-Accelerated TLS 1.2/1.3 Handshake via AAPSSLEngine
 * 4. Protobuf Service Discovery (1280x720 30FPS H.264 Video Sink)
 * 5. Channel Open ACKs for Video & Multi-Touch Input
 */
class AAPHeadUnitClient {
    companion object {
        private const val TAG = "TeslaAAPClient"
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val shouldKeepRunning = AtomicBoolean(false)
    
    private var sslEngine: AAPSSLEngine? = null

    private var connectionThread: Thread? = null
    private var readerThread: Thread? = null
    private var pingThread: Thread? = null

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onVideoNalChunk: ((ByteArray, Long) -> Unit)? = null

    val isConnected: Boolean
        get() = isRunning.get() && socket?.isConnected == true

    fun connect(host: String = AAPConstants.AAP_HOST, port: Int = AAPConstants.AAP_TCP_PORT) {
        if (shouldKeepRunning.get()) return
        shouldKeepRunning.set(true)
        Log.i(TAG, "Avvio monitor di connessione continua ad Android Auto ($host:$port)...")

        connectionThread = Thread({
            while (shouldKeepRunning.get()) {
                if (!isRunning.get()) {
                    try {
                        Log.d(TAG, "Tentativo di connessione al server Android Auto...")
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.receiveBufferSize = 512 * 1024
                        s.sendBufferSize = 64 * 1024
                        s.connect(InetSocketAddress(InetAddress.getByName(host), port), 2500)

                        socket = s
                        inputStream = s.getInputStream()
                        outputStream = s.getOutputStream()
                        sslEngine = AAPSSLEngine()

                        isRunning.set(true)
                        Log.i(TAG, "Socket TCP 5277 connesso! Invio handshake versione...")

                        // 1. Send Exact 4-byte Version Handshake (Major 1, Minor 1)
                        sendVersionRequest(1, 1)

                        // 2. Start Reader Thread
                        readerThread = Thread({ readLoop() }, "AAP-Reader-Thread").apply { start() }

                        // 3. Start Ping Thread
                        pingThread = Thread({ pingLoop() }, "AAP-Ping-Thread").apply { start() }

                    } catch (e: Exception) {
                        Log.d(TAG, "Server Android Auto non ancora pronto su porta $port (${e.message}). Nuovo tentativo tra 1.5s...")
                        cleanupSocket()
                    }
                }

                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "AAP-Connection-Supervisor").apply { start() }
    }

    private fun readLoop() {
        val inStream = inputStream ?: return
        try {
            while (isRunning.get() && shouldKeepRunning.get()) {
                val packet = AAPPacket.readFrom(inStream) ?: break

                when (packet.channel) {
                    AAPConstants.CHANNEL_CONTROL -> handleControlPacket(packet)
                    AAPConstants.CHANNEL_VIDEO -> handleVideoPacket(packet)
                    AAPConstants.CHANNEL_INPUT -> handleInputPacket(packet)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.w(TAG, "Disconnessione readLoop: ${e.message}")
            }
        } finally {
            cleanupSocket()
            onDisconnected?.invoke()
        }
    }

    private fun handleControlPacket(packet: AAPPacket) {
        if (packet.payload.isEmpty()) return

        // 1. VersionResponse check (4 bytes: major=1, minor=1)
        if (packet.payload.size == 4 || packet.payload.size == 6) {
            val buf = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
            val major = buf.short.toInt() and 0xFFFF
            val minor = buf.short.toInt() and 0xFFFF
            Log.i(TAG, "[AAP] Ricevuto VersionResponse ($major.$minor). Avvio TLS Handshake...")

            // Initiate SSL Handshake
            val clientHello = sslEngine?.startHandshake()
            if (clientHello != null) {
                sendSSLHandshake(clientHello)
            }
            return
        }

        val msgType = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val rawData = if (packet.payload.size > 2) packet.payload.copyOfRange(2, packet.payload.size) else ByteArray(0)

        when (msgType) {
            AAPConstants.MSG_SSL_HANDSHAKE -> {
                Log.d(TAG, "[AAP] Ricevuto SSL Handshake da Android Auto (${rawData.size} bytes)...")
                val nextPacket = sslEngine?.processServerHandshake(rawData)
                if (nextPacket != null) {
                    sendSSLHandshake(nextPacket)
                }
                if (sslEngine?.isHandshakeComplete == true) {
                    Log.i(TAG, "[AAP] TLS Handshake completato! Invio AuthComplete e ServiceDiscovery...")
                    sendAuthComplete()
                    sendServiceDiscovery()
                    onConnected?.invoke()
                }
            }
            AAPConstants.MSG_SERVICE_DISCOVERY_REQUEST -> {
                Log.i(TAG, "[AAP] Richiesta Service Discovery da Android Auto. Invio parametri Tesla 720p...")
                sendServiceDiscovery()
            }
            AAPConstants.MSG_CHANNEL_OPEN_REQUEST -> {
                Log.i(TAG, "[AAP] Ricevuto Channel Open Request sul canale ${packet.channel}. Rispondo OK...")
                sendChannelOpenResponse(packet.channel, 0)
                onConnected?.invoke()
            }
            AAPConstants.MSG_AUTH_COMPLETE -> {
                Log.i(TAG, "[AAP] Autenticazione confermata da Google!")
                sendServiceDiscovery()
                onConnected?.invoke()
            }
            AAPConstants.MSG_PING_REQUEST -> {
                sendPingResponse()
            }
        }
    }

    private fun handleVideoPacket(packet: AAPPacket) {
        if (packet.payload.isEmpty()) return

        // Check if Channel Open Request on Video Channel
        if (packet.payload.size >= 2) {
            val msgType = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            if (msgType == AAPConstants.MSG_CHANNEL_OPEN_REQUEST) {
                Log.i(TAG, "[AAP] Ricevuto Channel Open Request su Canale Video. Invio OK...")
                sendChannelOpenResponse(AAPConstants.CHANNEL_VIDEO, 0)
                return
            }
        }

        // Extract H.264 NAL unit
        val nalBytes = if (packet.payload.size > 2 && packet.payload[0] == 0x00.toByte() && packet.payload[1] == 0x02.toByte()) {
            packet.payload.copyOfRange(2, packet.payload.size)
        } else {
            packet.payload
        }

        val timestamp = System.currentTimeMillis()
        onVideoNalChunk?.invoke(nalBytes, timestamp)
    }

    private fun handleInputPacket(packet: AAPPacket) {
        if (packet.payload.size >= 2) {
            val msgType = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            if (msgType == AAPConstants.MSG_CHANNEL_OPEN_REQUEST) {
                Log.i(TAG, "[AAP] Ricevuto Channel Open Request su Canale Input Touch. Invio OK...")
                sendChannelOpenResponse(AAPConstants.CHANNEL_INPUT, 0)
            }
        }
    }

    private fun sendVersionRequest(major: Int, minor: Int) {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(major.toShort())
            putShort(minor.toShort())
        }.array()

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato VersionRequest ($major.$minor)")
    }

    private fun sendSSLHandshake(tlsBytes: ByteArray) {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(2 + tlsBytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_SSL_HANDSHAKE.toShort())
            put(tlsBytes)
        }.array()

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST or AAPConstants.FLAG_CONTROL, payload).writeTo(out)
        Log.d(TAG, "[AAP] Inviato pacchetto SSL Handshake (${payload.size} bytes)")
    }

    private fun sendAuthComplete() {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_AUTH_COMPLETE.toShort())
            putShort(0) // Status OK
        }.array()

        val encrypted = sslEngine?.encrypt(payload) ?: payload
        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, encrypted).writeTo(out)
    }

    private fun sendServiceDiscovery() {
        val out = outputStream ?: return
        val plainPayload = AAPProtobufBuilder.buildServiceDiscoveryResponse()
        val payload = sslEngine?.encrypt(plainPayload) ?: plainPayload

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato ServiceDiscoveryResponse Protobuf a Google.")
    }

    private fun sendChannelOpenResponse(channel: Int, status: Int = 0) {
        val out = outputStream ?: return
        val plainPayload = AAPProtobufBuilder.buildChannelOpenResponse(status)
        val payload = if (channel == AAPConstants.CHANNEL_CONTROL) (sslEngine?.encrypt(plainPayload) ?: plainPayload) else plainPayload

        AAPPacket(channel, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato ChannelOpenResponse (OK) per canale $channel.")
    }

    private fun sendPingResponse() {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(2).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_PING_RESPONSE.toShort())
        }.array()
        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
    }

    private fun pingLoop() {
        while (isRunning.get() && shouldKeepRunning.get()) {
            try {
                Thread.sleep(2000)
                val out = outputStream ?: continue
                val payload = ByteBuffer.allocate(2).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putShort(AAPConstants.MSG_PING_REQUEST.toShort())
                }.array()
                AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                break
            }
        }
    }

    fun sendTouchEvent(action: Int, normX: Float, normY: Float, pointerId: Int) {
        val out = outputStream ?: return
        if (!isRunning.get()) return

        val pixelX = (normX.coerceIn(0f, 1f) * AAPConstants.VIDEO_WIDTH).toInt()
        val pixelY = (normY.coerceIn(0f, 1f) * AAPConstants.VIDEO_HEIGHT).toInt()

        val payload = AAPProtobufBuilder.buildTouchEvent(
            action = action,
            pixelX = pixelX,
            pixelY = pixelY,
            pointerId = pointerId,
            timestampMs = System.currentTimeMillis()
        )

        try {
            AAPPacket(AAPConstants.CHANNEL_INPUT, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        } catch (_: Exception) {}
    }

    private fun cleanupSocket() {
        isRunning.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        sslEngine = null
    }

    fun disconnect() {
        shouldKeepRunning.set(false)
        cleanupSocket()

        try { connectionThread?.interrupt() } catch (_: Exception) {}
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { pingThread?.interrupt() } catch (_: Exception) {}
        connectionThread = null
        readerThread = null
        pingThread = null

        onDisconnected?.invoke()
    }
}
