package com.tesla.autostreamer.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Hardware-Accelerated H.264 Encoder using Android MediaCodec
 * 
 * Tuned for zero-latency streaming to Tesla Chromium on Intel Atom (MCU2):
 * - H.264 Baseline Profile Level 3.1
 * - Constant Bitrate (CBR)
 * - 1-second I-Frame sync interval
 * - Input Surface (Zero CPU copy)
 */
class H264MediaCodecEncoder(
    val config: VideoConfig = VideoConfig.PRESET_720P_30FPS
) {
    companion object {
        private const val TAG = "TeslaH264Encoder"
    }

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isRunning = false
    private var encoderThread: Thread? = null

    // Callback for outgoing H.264 NAL chunks: (data, timestampUs, isKeyFrame)
    var onNalAvailable: ((ByteArray, Long, Boolean) -> Unit)? = null

    // Cached SPS / PPS parameter sets
    private var spsPpsHeader: ByteArray? = null

    fun start() {
        if (isRunning) return
        Log.i(TAG, "Avvio H.264 MediaCodec Encoder [${config.width}x${config.height} @ ${config.fps}fps, ${config.bitrateBps / 1000} kbps]...")

        try {
            val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)

                // H.264 Baseline Profile Level 3.1 (Optimal for Intel Atom MCU2)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

                // Android Low-Latency extensions
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Exception) {}
            }

            mediaCodec = MediaCodec.createEncoderByType(config.mimeType).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isRunning = true
            encoderThread = Thread({ drainEncoder() }, "H264-Encoder-Drain-Thread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "Encoder MediaCodec avviato con successo.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'avvio di MediaCodec", e)
            stop()
            throw e
        }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout

                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            // Cache SPS/PPS parameter sets
                            spsPpsHeader = chunk
                            Log.d(TAG, "Ricevuti e memorizzati parametri SPS/PPS (${chunk.size} bytes)")
                        } else {
                            val finalChunk = if (isKeyFrame && spsPpsHeader != null) {
                                // Prepend SPS/PPS to keyframe for seamless browser decoding
                                ByteArrayOutputStream().apply {
                                    write(spsPpsHeader!!)
                                    write(chunk)
                                }.toByteArray()
                            } else {
                                chunk
                            }

                            onNalAvailable?.invoke(finalChunk, bufferInfo.presentationTimeUs, isKeyFrame)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Nuovo formato output encoder: $newFormat")
                    val csd0 = newFormat.getByteBuffer("csd-0") // SPS
                    val csd1 = newFormat.getByteBuffer("csd-1") // PPS
                    if (csd0 != null && csd1 != null) {
                        val header = ByteArrayOutputStream()
                        val sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                        val pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                        header.write(sps)
                        header.write(pps)
                        spsPpsHeader = header.toByteArray()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Eccezione durante drainEncoder: ${e.message}")
                }
            }
        }
    }

    /**
     * Forces the encoder to generate an immediate I-Frame / IDR sync frame
     * (Called whenever a Tesla browser connects or requests resync)
     */
    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Log.d(TAG, "Richiesto I-Frame immediato (Sync Frame)")
        } catch (e: Exception) {
            Log.w(TAG, "Impossibile richiedere sync frame: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        try {
            encoderThread?.interrupt()
            encoderThread?.join(500)
        } catch (_: Exception) {}
        encoderThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        Log.i(TAG, "Encoder H.264 arrestato.")
    }
}
