package com.campus.sport.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.campus.sport.LanLeaderboardActivity
import com.campus.sport.R

/**
 * Keeps the room answering while the app is in the background or the screen is
 * off.
 *
 * Without a foreground service Android is free to reclaim the process, which is
 * exactly what makes a phone-hosted room die the moment the phone locks. The
 * partial wake lock keeps the CPU awake so the socket is actually serviced
 * during Doze; the tradeoff is real battery use, so hosting is opt-in.
 */
class LanRoomService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastStatus: String = ""

    private val refreshStatus = object : Runnable {
        override fun run() {
            val port = LanRoom.hostingPort()
            val status = when {
                port == null -> getString(R.string.room_notification_starting)
                else -> {
                    val url = LanRoom.hostingUrls().firstOrNull()
                    if (url != null) getString(R.string.room_notification_url, url)
                    else getString(R.string.room_notification_port, port)
                }
            }
            if (status != lastStatus) {
                lastStatus = status
                notify(status)
            }
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, LanRoom.DEFAULT_PORT) ?: LanRoom.DEFAULT_PORT
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.room_notification_starting)))

        val started = runCatching { LanRoom.startServer(applicationContext, port) }
        if (started.isFailure) {
            notify(getString(R.string.room_notification_failed))
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        handler.removeCallbacks(refreshStatus)
        handler.post(refreshStatus)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshStatus)
        releaseWakeLock()
        LanRoom.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notification ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.room_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.room_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LanLeaderboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.room_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // ---- wake lock ---------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    companion object {
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "cadence-room"
        private const val NOTIFICATION_ID = 4321
        private const val WAKE_LOCK_TAG = "Cadence::LanRoom"
        private const val STATUS_INTERVAL_MS = 15_000L
    }
}
