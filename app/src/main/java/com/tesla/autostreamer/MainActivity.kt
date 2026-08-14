package com.tesla.autostreamer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, StreamForegroundService::class.java).apply {
                action = StreamForegroundService.ACTION_START
                putExtra(StreamForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // Start service anyway with AAP mode
            val serviceIntent = Intent(this, StreamForegroundService::class.java).apply {
                action = StreamForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
                    onStartClick = { requestScreenCaptureAndStart() },
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

    private fun requestScreenCaptureAndStart() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
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
