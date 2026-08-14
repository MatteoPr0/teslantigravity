package com.tesla.autostreamer.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Manages CPU WakeLock and Wi-Fi Multicast / High-Performance Lock
 * to ensure uninterrupted streaming during vehicle operation.
 */
class WakeLockManager(private val context: Context) {
    companion object {
        private const val TAG = "TeslaWakeLockManager"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TeslaAutoStreamer::CpuWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "CPU WakeLock acquisito.")
        }

        if (wifiLock == null) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "TeslaAutoStreamer::WifiHighPerfLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Wi-Fi High-Performance Lock acquisito.")
        }
    }

    fun release() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        wifiLock = null

        Log.d(TAG, "WakeLock e Wi-Fi Lock rilasciati.")
    }
}
