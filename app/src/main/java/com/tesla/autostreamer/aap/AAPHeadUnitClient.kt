package com.tesla.autostreamer.aap

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Auto Protocol (AAP) Head Unit Client
 * 
 * Connects directly to Android Auto's built-in Head Unit Server (TCP 5277 on localhost)
 * to receive official Google Android Auto H.264 video and inject touch events.
 */
class AAPHeadUnitClient {
    companion object {
        private const val TAG = "TeslaAAPClient"
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var pingThread: Thread? = null

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onVideoNalChunk: ((ByteArray, Long) -> Unit)? = null

    val isConnected: Boolean
        get() = isRunning.get() && socket?.isConnected == true

    fun connect(host: String = AAPConstants.AAP_HOST, port: Int = AAPConstants.AAP_TCP_PORT) {
        if (isRunning.get()) return
        Log.i(TAG, "Tentativo di connessione ad Android Auto Head Unit Server ($host:$port)...")

        Thread({
            try {
                val s = Socket(host, port)
                s.tcpNoDelay = true
                s.receiveBufferSize = 256 * 1024
                s.sendBufferSize = 64 * 1024

                socket = s
                inputStream = s.getInputStream()
                outputStream = s.getOutputStream()

                isRunning.set(true)
                Log.i(TAG, "Connesso con successo al server Android Auto (porta $port)!")

                // 1. Send AAP Version Handshake (Major: 1, Minor: 6)
                sendVersionRequest(1, 6)

                onConnected?.invoke()

                // 2. Start Message Read Loop
                readerThread = Thread({ readLoop() }, "AAP-Reader-Thread").apply { start() }
                
                // 3. Start Heartbeat Ping Loop
                pingThread = Thread({ pingLoop() }, "AAP-Ping-Thread").apply { start() }

            } catch (e: Exception) {
                Log.w(TAG, "Impossibile connettersi ad Android Auto su $host:$port (il server è attivo?): ${e.message}")
                disconnect()
            }
        }, "AAP-Connect-Thread").start()
    }

    private fun readLoop() {
        val inStream = inputStream ?: return
        try {
            while (isRunning.get()) {
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
            disconnect()
        }
    }

    private fun handleControlPacket(packet: AAPPacket) {
        if (packet.payload.size < 2) return
        val msgType = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

        when (msgType) {
            AAPConstants.MSG_VERSION_RESPONSE -> {
                Log.i(TAG, "[AAP] Ricevuto Version Response da Android Auto. Invio Service Discovery...")
                sendServiceDiscoveryResponse()
            }
            AAPConstants.MSG_SERVICE_DISCOVERY_REQUEST -> {
                Log.i(TAG, "[AAP] Ricevuto Service Discovery Request. Configurazione display Tesla 1280x720...")
                sendServiceDiscoveryResponse()
            }
            AAPConstants.MSG_PING_REQUEST -> {
                sendPingResponse()
            }
            AAPConstants.MSG_PING_RESPONSE -> {
                // Heartbeat ACK
            }
        }
    }

    private fun handleVideoPacket(packet: AAPPacket) {
        if (packet.payload.isEmpty()) return

        // Extract H.264 video NAL units and dispatch to Tesla streamer
        val timestamp = System.currentTimeMillis()
        onVideoNalChunk?.invoke(packet.payload, timestamp)
    }

    private fun handleInputPacket(packet: AAPPacket) {
        // Input channel ACKs
    }

    private fun sendVersionRequest(major: Int, minor: Int) {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(6).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_VERSION_REQUEST.toShort())
            putShort(major.toShort())
            putShort(minor.toShort())
        }.array()

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.d(TAG, "[AAP] Inviato Version Request ($major.$minor)")
    }

    private fun sendServiceDiscoveryResponse() {
        val out = outputStream ?: return

        // Configure Video Sink (1280x720 @ 30 FPS, H.264 Baseline, Density 160)
        val buffer = ByteBuffer.allocate(64).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_SERVICE_DISCOVERY_RESPONSE.toShort())
            putShort(0) // Status OK
            putInt(AAPConstants.VIDEO_WIDTH)
            putInt(AAPConstants.VIDEO_HEIGHT)
            putInt(AAPConstants.VIDEO_FPS)
            putInt(AAPConstants.VIDEO_DENSITY_DPI)
        }
        val payload = buffer.array()

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato Service Discovery: Display 1280x720 @ 30fps configurato.")
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
        while (isRunning.get()) {
            try {
                Thread.sleep(2500)
                val out = outputStream ?: continue
                val payload = ByteBuffer.allocate(2).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putShort(AAPConstants.MSG_PING_REQUEST.toShort())
                }.array()
                AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                break
            }
        }
    }

    /**
     * Injects touch events from the Tesla screen into the active Android Auto session
     * @param action 0 = DOWN, 1 = UP, 2 = MOVE
     * @param normX 0.0 - 1.0
     * @param normY 0.0 - 1.0
     */
    fun sendTouchEvent(action: Int, normX: Float, normY: Float, pointerId: Int) {
        val out = outputStream ?: return
        if (!isRunning.get()) return

        val pixelX = (normX.coerceIn(0f, 1f) * AAPConstants.VIDEO_WIDTH).toInt()
        val pixelY = (normY.coerceIn(0f, 1f) * AAPConstants.VIDEO_HEIGHT).toInt()

        val buffer = ByteBuffer.allocate(18).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(AAPConstants.MSG_INPUT_EVENT.toShort())
            putInt(action)      // 0: DOWN, 1: UP, 2: MOVE
            putInt(pixelX)      // Target X
            putInt(pixelY)      // Target Y
            putInt(pointerId)   // Pointer / Touch ID
        }
        val payload = buffer.array()

        try {
            AAPPacket(AAPConstants.CHANNEL_INPUT, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Disconnessione da Android Auto...")

        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null

        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { pingThread?.interrupt() } catch (_: Exception) {}
        readerThread = null
        pingThread = null

        onDisconnected?.invoke()
    }
}
