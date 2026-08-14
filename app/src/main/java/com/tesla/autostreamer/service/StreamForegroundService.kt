package com.tesla.autostreamer.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tesla.autostreamer.MainActivity
import com.tesla.autostreamer.aap.AAPConstants
import com.tesla.autostreamer.aap.AAPHeadUnitClient
import com.tesla.autostreamer.encoder.H264MediaCodecEncoder
import com.tesla.autostreamer.encoder.VideoConfig
import com.tesla.autostreamer.input.TouchInjector
import com.tesla.autostreamer.server.LocalHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamForegroundService : Service() {

    companion object {
        private const val TAG = "TeslaStreamService"
        private const val CHANNEL_ID = "tesla_stream_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.tesla.autostreamer.START"
        const val ACTION_STOP = "com.tesla.autostreamer.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _clientCount = MutableStateFlow(0)
        val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

        private val _isAAPConnected = MutableStateFlow(false)
        val isAAPConnected: StateFlow<Boolean> = _isAAPConnected.asStateFlow()
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var aapClient: AAPHeadUnitClient? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var encoder: H264MediaCodecEncoder? = null
    private var server: LocalHttpServer? = null
    private var touchInjector: TouchInjector? = null
    private var wakeLockManager: WakeLockManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamForegroundService = this@StreamForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wakeLockManager = WakeLockManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startStreaming(resultCode, resultData)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming(resultCode: Int, resultData: Intent?) {
        if (_isRunning.value) return
        Log.i(TAG, "Avvio servizio Tesla Auto Streamer con motore AAP & Server Web...")

        try {
            val notification = buildNotification("Server attivo. In attesa di connessione...")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            wakeLockManager?.acquire()

            serviceScope.launch {
                try {
                    val config = VideoConfig.PRESET_720P_30FPS
                    touchInjector = TouchInjector(this@StreamForegroundService, config.width, config.height)

                    // 1. Initialize Native AAP Client
                    aapClient = AAPHeadUnitClient().apply {
                        onConnected = {
                            _isAAPConnected.value = true
                            updateNotification("Android Auto Connesso! Streaming attivo.")
                        }
                        onDisconnected = {
                            _isAAPConnected.value = false
                        }
                        onVideoNalChunk = { nalData, ptsUs ->
                            server?.broadcastVideoFrame(nalData, ptsUs)
                        }
                    }

                    // Touch forwarding to AAP
                    touchInjector?.onTouchEventMapped = { action, _, _, pointerId ->
                        // Forward normalized touch to AAP
                    }

                    // 2. Initialize Local HTTP & WebSocket Server for Tesla
                    server = LocalHttpServer(this@StreamForegroundService, port = 8080, touchInjector = touchInjector!!).apply {
                        onClientConnected = {
                            _clientCount.value = connectedClientsCount
                            encoder?.requestKeyFrame()
                            updateNotification("Tesla connessa! Streaming attivo.")
                        }
                        onClientDisconnected = {
                            _clientCount.value = connectedClientsCount
                            if (connectedClientsCount == 0) {
                                updateNotification("Server attivo. In attesa di connessione Tesla...")
                            }
                        }
                        start()
                    }

                    // Try connecting to Android Auto Head Unit Server (TCP 5277)
                    aapClient?.connect()

                    // 3. Fallback / Auxiliary Hardware Encoder for VirtualDisplay
                    if (resultData != null && resultCode == Activity.RESULT_OK) {
                        try {
                            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                            encoder = H264MediaCodecEncoder(config).apply {
                                onNalAvailable = { nalData, ptsUs, _ ->
                                    if (!_isAAPConnected.value) {
                                        server?.broadcastVideoFrame(nalData, ptsUs)
                                    }
                                }
                                start()
                            }
                            val densityDpi = resources.displayMetrics.densityDpi
                            virtualDisplay = mediaProjection?.createVirtualDisplay(
                                "TeslaAutoVirtualDisplay",
                                config.width,
                                config.height,
                                densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                encoder?.inputSurface,
                                null,
                                null
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "VirtualDisplay non attivo: ${e.message}")
                        }
                    }

                    _isRunning.value = true
                    Log.i(TAG, "Servizio streaming avviato con successo.")
                } catch (e: Exception) {
                    Log.e(TAG, "Errore avvio streaming", e)
                    stopStreaming()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore startForeground:", e)
            stopStreaming()
        }
    }

    private fun stopStreaming() {
        if (!_isRunning.value) return
        Log.i(TAG, "Arresto servizio streaming...")

        try { aapClient?.disconnect() } catch (_: Exception) {}
        aapClient = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        try { encoder?.stop() } catch (_: Exception) {}
        encoder = null

        try { server?.stop() } catch (_: Exception) {}
        server = null

        try { wakeLockManager?.release() } catch (_: Exception) {}

        _isRunning.value = false
        _isAAPConnected.value = false
        _clientCount.value = 0

        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tesla Auto Streamer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Streaming attivo verso il browser Tesla"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StreamForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tesla Android Auto Streamer")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Arresta", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
