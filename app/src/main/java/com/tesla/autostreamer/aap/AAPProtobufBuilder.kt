package com.tesla.autostreamer.aap

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight Zero-Dependency Protobuf Encoder for Android Auto Protocol (AAP)
 * Encodes ServiceDiscovery, Channel Management, and Touch Input messages.
 */
class AAPProtobufBuilder {
    private val buffer = ByteArrayOutputStream()

    fun writeVarint(value: Long): AAPProtobufBuilder {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            buffer.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        buffer.write((v and 0x7F).toInt())
        return this
    }

    fun writeTag(fieldNumber: Int, wireType: Int): AAPProtobufBuilder {
        return writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    fun writeInt32(fieldNumber: Int, value: Int): AAPProtobufBuilder {
        writeTag(fieldNumber, 0)
        return writeVarint(value.toLong())
    }

    fun writeInt64(fieldNumber: Int, value: Long): AAPProtobufBuilder {
        writeTag(fieldNumber, 0)
        return writeVarint(value)
    }

    fun writeString(fieldNumber: Int, value: String): AAPProtobufBuilder {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(fieldNumber, 2)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
        return this
    }

    fun writeBytes(fieldNumber: Int, bytes: ByteArray): AAPProtobufBuilder {
        writeTag(fieldNumber, 2)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
        return this
    }

    fun writeEmbedded(fieldNumber: Int, nested: AAPProtobufBuilder): AAPProtobufBuilder {
        val bytes = nested.toByteArray()
        writeTag(fieldNumber, 2)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
        return this
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        /**
         * Builds the ServiceDiscoveryResponse message required to configure
         * Google Maps and Android Auto in 1280x720 30FPS H.264 mode for Tesla
         */
        fun buildServiceDiscoveryResponse(): ByteArray {
            // 1. Video Sink Configuration (H.264 1280x720 @ 30 FPS)
            val videoConfig = AAPProtobufBuilder().apply {
                writeInt32(1, 1)                           // Codec: H.264 AVC
                writeInt32(2, AAPConstants.VIDEO_WIDTH)    // 1280 px
                writeInt32(3, AAPConstants.VIDEO_HEIGHT)   // 720 px
                writeInt32(4, AAPConstants.VIDEO_FPS)      // 30 FPS
                writeInt32(5, AAPConstants.VIDEO_DENSITY_DPI) // 160 DPI
                writeInt32(6, 0)                           // Margin Top
                writeInt32(7, 0)                           // Margin Bottom
                writeInt32(8, 0)                           // Margin Left
                writeInt32(9, 0)                           // Margin Right
            }

            val videoService = AAPProtobufBuilder().apply {
                writeInt32(1, AAPConstants.CHANNEL_VIDEO) // Channel 1
                writeEmbedded(2, videoConfig)
            }

            // 2. Touch Input Configuration (Multi-touch Touchscreen)
            val touchConfig = AAPProtobufBuilder().apply {
                writeInt32(1, 1)                           // Type: Touchscreen
                writeInt32(2, AAPConstants.VIDEO_WIDTH)
                writeInt32(3, AAPConstants.VIDEO_HEIGHT)
                writeInt32(4, 2)                           // Max touches: 2
            }

            val touchService = AAPProtobufBuilder().apply {
                writeInt32(1, AAPConstants.CHANNEL_INPUT) // Channel 4
                writeEmbedded(2, touchConfig)
            }

            // 3. Sensor Configuration (Driving Status: Unrestricted)
            val sensorConfig = AAPProtobufBuilder().apply {
                writeInt32(1, 0)                           // Driving status: Unrestricted
                writeInt32(2, 1)                           // Night mode: Day
            }

            val sensorService = AAPProtobufBuilder().apply {
                writeInt32(1, AAPConstants.CHANNEL_SENSOR)
                writeEmbedded(2, sensorConfig)
            }

            // Combine into main ServiceDiscoveryResponse
            val mainResponse = AAPProtobufBuilder().apply {
                writeInt32(1, 0)                          // Status: STATUS_OK (0)
                writeString(2, "Tesla Model 3")          // Headunit Name
                writeString(3, "TeslaAntigravity")       // Headunit Model
                writeEmbedded(4, videoService)
                writeEmbedded(5, touchService)
                writeEmbedded(6, sensorService)
            }

            val rawProtobuf = mainResponse.toByteArray()

            // Prepend 2-byte AAP Message Type (MSG_SERVICE_DISCOVERY_RESPONSE = 0x0006)
            val finalPacket = ByteBuffer.allocate(2 + rawProtobuf.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(AAPConstants.MSG_SERVICE_DISCOVERY_RESPONSE.toShort())
                put(rawProtobuf)
            }
            return finalPacket.array()
        }

        /**
         * Builds an InputEvent protobuf message for Tesla Touchscreen interactions
         */
        fun buildTouchEvent(action: Int, pixelX: Int, pixelY: Int, pointerId: Int, timestampMs: Long): ByteArray {
            val touchLocation = AAPProtobufBuilder().apply {
                writeInt32(1, pixelX)
                writeInt32(2, pixelY)
                writeInt32(3, pointerId)
            }

            val inputEvent = AAPProtobufBuilder().apply {
                writeInt64(1, timestampMs * 1000) // Microseconds
                writeInt32(2, action)             // 0: DOWN, 1: UP, 2: MOVE
                writeEmbedded(3, touchLocation)
            }

            val rawProtobuf = inputEvent.toByteArray()
            val finalPacket = ByteBuffer.allocate(2 + rawProtobuf.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(AAPConstants.MSG_INPUT_EVENT.toShort())
                put(rawProtobuf)
            }
            return finalPacket.array()
        }

        /**
         * Builds ChannelOpenResponse (Status OK)
         */
        fun buildChannelOpenResponse(status: Int = 0): ByteArray {
            val response = AAPProtobufBuilder().apply {
                writeInt32(1, status)
            }
            val raw = response.toByteArray()
            val finalPacket = ByteBuffer.allocate(2 + raw.size).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(AAPConstants.MSG_CHANNEL_OPEN_RESPONSE.toShort())
                put(raw)
            }
            return finalPacket.array()
        }
    }
}
