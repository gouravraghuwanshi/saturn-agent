package io.github.pimobile.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app process (and therefore the pi child processes) alive while
 * any task is active, with a persistent "N tasks running" notification.
 *
 * Android kills an app that calls startForegroundService() but whose service
 * never reaches startForeground(), so this is only ever *started* via an
 * intent; updates and stops go to the live instance directly, and a stop that
 * races ahead of onStartCommand is applied right after startForeground().
 * All calls happen on the main thread.
 */
class PiService : Service() {

    companion object {
        private const val CHANNEL = "tasks"
        private const val NOTIFICATION_ID = 1
        /** Task status flaps starting -> idle -> running within ms; don't stop on a blip. */
        private const val STOP_DELAY_MS = 3_000L

        private var instance: PiService? = null
        private var starting = false
        private var wanted = 0

        /** Match the service to the number of active tasks. Main thread only. */
        fun sync(ctx: Context, active: Int) {
            wanted = active
            val live = instance
            when {
                live != null && live.foreground -> if (active > 0) live.update(active) else live.scheduleStop()
                active > 0 && !starting -> {
                    starting = true
                    // Android refuses background starts unless the app is exempt (e.g. an exact alarm
                    // just fired); the task still runs while the process lives.
                    runCatching { ctx.startForegroundService(Intent(ctx, PiService::class.java)) }.onFailure { starting = false }
                }
                // else: a start is in flight; onStartCommand reads `wanted`.
            }
        }
    }

    private var foreground = false
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopIfIdle = Runnable { if (wanted == 0) shutdown() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Running tasks", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while pi is working on your tasks" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        lastStartId = startId
        // Always honour the startForegroundService() contract first...
        startForeground(NOTIFICATION_ID, notification(maxOf(wanted, 1)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        foreground = true
        // ...then apply whatever the task count became in the meantime.
        if (wanted > 0) update(wanted) else scheduleStop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopIfIdle)
        releaseWakeLock()
        foreground = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun update(count: Int) {
        handler.removeCallbacks(stopIfIdle)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(count))
        acquireWakeLock()
    }

    private fun scheduleStop() {
        handler.removeCallbacks(stopIfIdle)
        handler.postDelayed(stopIfIdle, STOP_DELAY_MS)
    }

    private fun shutdown() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // Only stops if no newer start request has arrived since.
        stopSelfResult(lastStartId)
    }

    /** Keeps the CPU running while agents work with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pimobile:tasks")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notification(count: Int): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(io.github.pimobile.R.drawable.ic_stat_saturn)
            .setColor(0xFFF2B35B.toInt())
            .setContentTitle(if (count == 1) "1 task running" else "$count tasks running")
            .setContentText("Saturn is working — tap to open")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
