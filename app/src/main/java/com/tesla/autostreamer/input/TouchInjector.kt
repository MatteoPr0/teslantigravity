package com.tesla.autostreamer.input

import android.content.Context
import android.util.Log

data class TeslaTouchEvent(
    val action: Int, // 0: Down, 1: Up, 2: Move, 3: Cancel
    val x: Float,    // Normalized 0.0 - 1.0
    val y: Float,    // Normalized 0.0 - 1.0
    val pointerId: Int,
    val timestamp: Long
)

/**
 * Touch Event Processor
 * 
 * Maps touch events originating from the Tesla browser touchscreen
 * into native coordinates for Android Auto / Android Display.
 */
class TouchInjector(
    private val context: Context,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 720
) {
    companion object {
        private const val TAG = "TeslaTouchInjector"
    }

    var onTouchEventMapped: ((action: Int, pixelX: Float, pixelY: Float, pointerId: Int) -> Unit)? = null

    fun injectTouch(event: TeslaTouchEvent) {
        // Clamp and scale normalized coordinates to target resolution
        val clampedX = event.x.coerceIn(0.0f, 1.0f)
        val clampedY = event.y.coerceIn(0.0f, 1.0f)

        val pixelX = clampedX * targetWidth
        val pixelY = clampedY * targetHeight

        // Forward to listener (Android Auto AAP input channel or VirtualDisplay injector)
        onTouchEventMapped?.invoke(event.action, pixelX, pixelY, event.pointerId)
    }
}
