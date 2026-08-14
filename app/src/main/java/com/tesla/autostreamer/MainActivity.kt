package com.tesla.autostreamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.tesla.autostreamer.service.StreamForegroundService
import com.tesla.autostreamer.ui.MainScreen
import com.tesla.autostreamer.ui.theme.TeslaStreamerTheme
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            val isRunning by StreamForegroundService.isRunning.collectAsState()
            val clientCount by StreamForegroundService.clientCount.collectAsState()
            val isAAPConnected by StreamForegroundService.isAAPConnected.collectAsState()
            val hotspotIp = getHotspotIpAddress()

            TeslaStreamerTheme {
                MainScreen(
                    isRunning = isRunning,
                    clientCount = clientCount,
                    isAAPConnected = isAAPConnected,
                    hotspotIp = hotspotIp,
                    onStartClick = { startStreamService() },
                    onStopClick = { stopStreamService() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startStreamService() {
        val intent = Intent(this, StreamForegroundService::class.java).apply {
            action = StreamForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopStreamService() {
        val intent = Intent(this, StreamForegroundService::class.java).apply {
            action = StreamForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * Resolves the smartphone's local Hotspot IP address (defaults to 192.168.43.1)
     */
    private fun getHotspotIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("ap") || intf.name.contains("swlan")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "192.168.43.1"
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
