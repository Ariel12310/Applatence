package com.arena.delayed_audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DelayedAudioService : Service() {
    private var engine: AudioDelayEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val latency = intent.getIntExtra(EXTRA_LATENCY_MS, savedLatency())
                startForeground(NOTIFICATION_ID, notification(latency))
                startEngine(latency)
            }
            ACTION_STOP -> {
                stopEngine()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            ACTION_SET_LATENCY -> {
                val latency = intent.getIntExtra(EXTRA_LATENCY_MS, savedLatency())
                engine?.setLatencyMs(latency)
                sharedPrefs().edit().putInt(MainActivity.KEY_LATENCY, latency).apply()
                refreshNotification(latency)
            }
            ACTION_SELECT_ROUTE -> {
                val routeId = intent.getIntExtra(EXTRA_ROUTE_ID, -1)
                engine?.selectOutputDevice(routeId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun startEngine(latencyMs: Int) {
        if (engine == null) engine = AudioDelayEngine(applicationContext)
        val routeId = sharedPrefs().getInt(MainActivity.KEY_ROUTE_ID, -1)
        engine?.start(latencyMs, routeId)
        sharedPrefs().edit().putInt(MainActivity.KEY_LATENCY, latencyMs).apply()
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
    }

    private fun savedLatency(): Int = sharedPrefs().getInt(MainActivity.KEY_LATENCY, 500)
    private fun sharedPrefs() = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio différé actif", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Maintient le traitement micro en arrière-plan."
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun refreshNotification(latencyMs: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(latencyMs))
    }

    private fun notification(latencyMs: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DelayedAudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Delayed Voice Monitor")
            .setContentText("Latence active : $latencyMs ms")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.arena.delayed_audio.START"
        const val ACTION_STOP = "com.arena.delayed_audio.STOP"
        const val ACTION_SET_LATENCY = "com.arena.delayed_audio.SET_LATENCY"
        const val ACTION_SELECT_ROUTE = "com.arena.delayed_audio.SELECT_ROUTE"
        const val EXTRA_LATENCY_MS = "latencyMs"
        const val EXTRA_ROUTE_ID = "routeId"
        private const val CHANNEL_ID = "delayed_audio_foreground"
        private const val NOTIFICATION_ID = 5001

        fun status(context: Context): Map<String, Any> {
            val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            return AudioDelayEngine.statusSnapshot().toMutableMap().apply {
                if (!containsKey("latencyMs")) put("latencyMs", prefs.getInt(MainActivity.KEY_LATENCY, 500))
            }
        }
    }
}
