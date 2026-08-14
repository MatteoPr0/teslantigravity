package com.tesla.autostreamer.encoder

/**
 * Video configuration tuned specifically for Tesla Model 3 (Intel Atom MCU2)
 */
data class VideoConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateBps: Int = 2800 * 1000, // 2.8 Mbps CBR
    val iFrameIntervalSec: Int = 1,     // 1 second keyframe interval
    val mimeType: String = "video/avc"  // H.264 / AVC
) {
    companion object {
        val PRESET_720P_30FPS = VideoConfig(width = 1280, height = 720, fps = 30, bitrateBps = 2800 * 1000)
        val PRESET_720P_45FPS = VideoConfig(width = 1280, height = 720, fps = 45, bitrateBps = 3200 * 1000)
        val PRESET_TESLA_WIDE = VideoConfig(width = 1280, height = 640, fps = 30, bitrateBps = 2500 * 1000)
        val PRESET_480P_ECO = VideoConfig(width = 854, height = 480, fps = 30, bitrateBps = 1500 * 1000)
    }
}
