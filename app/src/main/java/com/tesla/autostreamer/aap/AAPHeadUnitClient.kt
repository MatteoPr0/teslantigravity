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
 * Communication Error 2 Fix:
 * Correct 4-byte VersionRequest struct [Major (16-bit) = 1, Minor (16-bit) = 1]
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
        Log.i(TAG, "Connessione al server Android Auto ($host:$port)...")

        Thread({
            try {
                val s = Socket(host, port)
                s.tcpNoDelay = true
                s.receiveBufferSize = 512 * 1024
                s.sendBufferSize = 64 * 1024

                socket = s
                inputStream = s.getInputStream()
                outputStream = s.getOutputStream()

                isRunning.set(true)
                Log.i(TAG, "Socket TCP 5277 connesso! Invio handshake versione...")

                // 1. Send Exact 4-byte AAP Version Handshake (Major: 1, Minor: 1)
                sendVersionRequest(1, 1)

                // 2. Start Message Read Loop
                readerThread = Thread({ readLoop() }, "AAP-Reader-Thread").apply { start() }

                // 3. Start Heartbeat Ping Loop
                pingThread = Thread({ pingLoop() }, "AAP-Ping-Thread").apply { start() }

            } catch (e: Exception) {
                Log.w(TAG, "Impossibile connettersi ad Android Auto su $host:$port: ${e.message}")
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
        if (packet.payload.isEmpty()) return

        // Check for 4-byte VersionResponse
        if (packet.payload.size >= 4) {
            val buf = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
            val major = buf.short.toInt() and 0xFFFF
            val minor = buf.short.toInt() and 0xFFFF
            val status = if (packet.payload.size >= 6) buf.short.toInt() and 0xFFFF else 0

            Log.i(TAG, "[AAP] Ricevuto VersionResponse: $major.$minor (status: $status). Invio Service Discovery...")
            sendServiceDiscovery()
            onConnected?.invoke()
            return
        }

        val msgType = ByteBuffer.wrap(packet.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

        when (msgType) {
            AAPConstants.MSG_SERVICE_DISCOVERY_REQUEST -> {
                Log.i(TAG, "[AAP] Richiesta Service Discovery. Invio parametri Tesla 1280x720 30FPS...")
                sendServiceDiscovery()
            }
            AAPConstants.MSG_CHANNEL_OPEN_REQUEST -> {
                Log.i(TAG, "[AAP] Ricevuto Channel Open Request. Rispondo OK...")
                sendChannelOpenResponse()
                onConnected?.invoke()
            }
            AAPConstants.MSG_AUTH_COMPLETE -> {
                Log.i(TAG, "[AAP] Autenticazione completata con successo!")
                onConnected?.invoke()
            }
            AAPConstants.MSG_PING_REQUEST -> {
                sendPingResponse()
            }
        }
    }

    private fun handleVideoPacket(packet: AAPPacket) {
        if (packet.payload.isEmpty()) return

        // Skip 2-byte AAP video message header if present (MSG_VIDEO_DATA = 0x0002)
        val nalBytes = if (packet.payload.size > 2 && packet.payload[0] == 0x00.toByte() && packet.payload[1] == 0x02.toByte()) {
            packet.payload.copyOfRange(2, packet.payload.size)
        } else {
            packet.payload
        }

        val timestamp = System.currentTimeMillis()
        onVideoNalChunk?.invoke(nalBytes, timestamp)
    }

    private fun handleInputPacket(packet: AAPPacket) {
        // Channel ACKs
    }

    /**
     * Exact 4-byte AAP Version Request struct:
     * [2 bytes: Major Version (Big Endian = 1)]
     * [2 bytes: Minor Version (Big Endian = 1)]
     */
    private fun sendVersionRequest(major: Int, minor: Int) {
        val out = outputStream ?: return
        val payload = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(major.toShort())
            putShort(minor.toShort())
        }.array()

        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato VersionRequest pulito di 4 bytes ($major.$minor)")
    }

    private fun sendServiceDiscovery() {
        val out = outputStream ?: return
        val payload = AAPProtobufBuilder.buildServiceDiscoveryResponse()
        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
        Log.i(TAG, "[AAP] Inviato ServiceDiscoveryResponse Protobuf a Google Maps / Android Auto.")
    }

    private fun sendChannelOpenResponse() {
        val out = outputStream ?: return
        val payload = AAPProtobufBuilder.buildChannelOpenResponse(0)
        AAPPacket(AAPConstants.CHANNEL_CONTROL, AAPConstants.FLAG_FIRST or AAPConstants.FLAG_LAST, payload).writeTo(out)
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

    fun disconnect() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Disconnessione client AAP...")

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
