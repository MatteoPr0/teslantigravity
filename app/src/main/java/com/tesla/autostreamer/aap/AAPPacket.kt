package com.tesla.autostreamer.aap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android Auto Protocol (AAP) Packet Framing
 * 
 * Frame Header:
 * [1 byte: Channel ID]
 * [1 byte: Flags (0x01 = First, 0x02 = Last, 0x08 = Encrypted)]
 * [2 bytes: Payload Length (Big Endian)]
 * [N bytes: Payload Data]
 */
data class AAPPacket(
    val channel: Int,
    val flags: Int,
    val payload: ByteArray
) {
    fun writeTo(outputStream: OutputStream) {
        val length = payload.size
        val header = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(channel.toByte())
            put(flags.toByte())
            putShort(length.toShort())
        }
        synchronized(outputStream) {
            outputStream.write(header.array())
            if (length > 0) {
                outputStream.write(payload)
            }
            outputStream.flush()
        }
    }

    companion object {
        fun readFrom(inputStream: InputStream): AAPPacket? {
            val headerBuf = ByteArray(4)
            var read = 0
            while (read < 4) {
                val count = inputStream.read(headerBuf, read, 4 - read)
                if (count < 0) return null // Stream closed
                read += count
            }

            val channel = headerBuf[0].toInt() and 0xFF
            val flags = headerBuf[1].toInt() and 0xFF
            val length = ByteBuffer.wrap(headerBuf, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

            val payload = ByteArray(length)
            var payloadRead = 0
            while (payloadRead < length) {
                val count = inputStream.read(payload, payloadRead, length - payloadRead)
                if (count < 0) return null
                payloadRead += count
            }

            return AAPPacket(channel, flags, payload)
        }
    }
}
