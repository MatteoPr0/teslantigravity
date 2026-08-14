package com.tesla.autostreamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tesla.autostreamer.MainActivity
import com.tesla.autostreamer.encoder.H264MediaCodecEncoder
import com.tesla.autostreamer.encoder.VideoConfig
import com.tesla.autostreamer.input.TouchInjector
import com.tesla.autostreamer.server.LocalHttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamForegroundService : Service() {

    companion object {
        private const val TAG = "TeslaStreamService"
        private const val CHANNEL_ID = "tesla_stream_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.tesla.autostreamer.START"
        const val ACTION_STOP = "com.tesla.autostreamer.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _clientCount = MutableStateFlow(0)
        val clientCount: StateFlow<Int> = _clientCount.asStateFlow()
    }

    private val binder = LocalBinder()
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
        wakeLockManager = WakeLockManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (_isRunning.value) return
        Log.i(TAG, "Avvio servizio di streaming Tesla...")

        val notification = buildNotification("Server attivo. In attesa di connessione Tesla...")
        startForeground(NOTIFICATION_ID, notification)
        wakeLockManager?.acquire()

        val config = VideoConfig.PRESET_720P_30FPS
        touchInjector = TouchInjector(this, config.width, config.height)

        // Initialize Embedded Server
        server = LocalHttpServer(this, port = 8080, touchInjector = touchInjector!!).apply {
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

        // Initialize Hardware Encoder
        encoder = H264MediaCodecEncoder(config).apply {
            onNalAvailable = { nalData, ptsUs, _ ->
                server?.broadcastVideoFrame(nalData, ptsUs)
            }
            start()
        }

        _isRunning.value = true
        Log.i(TAG, "Servizio streaming avviato con successo.")
    }

    private fun stopStreaming() {
        if (!_isRunning.value) return
        Log.i(TAG, "Arresto servizio di streaming Tesla...")

        encoder?.stop()
        encoder = null

        server?.stop()
        server = null

        wakeLockManager?.release()
        _isRunning.value = false
        _clientCount.value = 0

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tesla Auto Streamer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica per lo streaming attivo verso il browser Tesla"
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
