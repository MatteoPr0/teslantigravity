package com.tesla.autostreamer.aap

/**
 * Android Auto Protocol (AAP) Constants and Message Definitions
 * Based on Google Android Auto Protocol specifications
 */
object AAPConstants {
    const val AAP_TCP_PORT = 5277
    const val AAP_HOST = "127.0.0.1"

    // Channel IDs
    const val CHANNEL_CONTROL = 0
    const val CHANNEL_VIDEO = 1
    const val CHANNEL_AUDIO_MEDIA = 2
    const val CHANNEL_AUDIO_GUIDANCE = 3
    const val CHANNEL_INPUT = 4
    const val CHANNEL_SENSOR = 5
    const val CHANNEL_BLUETOOTH = 6

    // Frame Flags
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_ENCRYPTED = 0x08
    const val FLAG_CONTROL = 0x04

    // Message Types (Control Channel)
    const val MSG_VERSION_REQUEST = 0x0001
    const val MSG_VERSION_RESPONSE = 0x0002
    const val MSG_SSL_HANDSHAKE = 0x0003
    const val MSG_AUTH_COMPLETE = 0x0004
    const val MSG_SERVICE_DISCOVERY_REQUEST = 0x0005
    const val MSG_SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val MSG_CHANNEL_OPEN_REQUEST = 0x0007
    const val MSG_CHANNEL_OPEN_RESPONSE = 0x0008
    const val MSG_PING_REQUEST = 0x000B
    const val MSG_PING_RESPONSE = 0x000C
    const val MSG_BYEBYE_REQUEST = 0x000D

    // Video Message Types
    const val MSG_VIDEO_CONFIG = 0x0001
    const val MSG_VIDEO_DATA = 0x0002
    const val MSG_VIDEO_ACK = 0x0003

    // Input Message Types
    const val MSG_INPUT_EVENT = 0x0001

    // Video Specs for Tesla Model 3 (Intel Atom MCU2)
    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720
    const val VIDEO_FPS = 30
    const val VIDEO_DENSITY_DPI = 160
}
