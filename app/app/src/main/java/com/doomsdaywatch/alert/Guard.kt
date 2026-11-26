package com.doomsdaywatch.alert

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Keeps the alarm armed when the app isn't open:
 *  - GuardService: a foreground service with a quiet "armed" notification, so the phone doesn't
 *    freeze or force-stop the app, and Firebase pushes always get through. It restarts itself
 *    when swiped from recents and after a reboot.
 *  - Backup check: every ~minute it asks the watcher's /status for the latest alert and rings
 *    if push delivery missed it.
 */
object Guard {
    const val CH_GUARD = "doom_guard_v1"
    private const val GUARD_ID = 1719
    private const val POLL_MS = 60_000L
    private const val FRESH_MS = 20 * 60_000L

    fun start(ctx: Context) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        } catch (e: Exception) {
            // Not allowed from this background state; the next alarm/boot/app open will start it.
            Log.w(DoomApp.TAG, "Guard start deferred: ${e.message}")
        }
        schedulePoll(ctx, 2_000)
    }

    fun schedulePoll(ctx: Context, delayMs: Long = POLL_MS) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 10, Intent(ctx, PollReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun notification(ctx: Context): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, "Alarm armed (silent)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the Doomsday alarm ready while the app is closed"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 20, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_GUARD)
            .setSmallIcon(R.drawable.ic_stat_doom)
            .setContentTitle("Doomsday alarm is armed")
            .setContentText("Watching District · Chennai. Keep this on to get the alarm.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    /** Asks the watcher for its latest alert and rings if this phone hasn't shown it yet. */
    fun pollOnce(ctx: Context) {
        if (BuildConfig.STATUS_URL.isEmpty()) return
        try {
            val conn = URL(BuildConfig.STATUS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val a = j.optJSONObject("lastAlert") ?: run {
                AlertStore.setBaseline(ctx)
                return
            }
            val id = a.optString("id")
            if (id.isEmpty()) return
            val at = runCatching { Instant.parse(a.optString("at")).toEpochMilli() }.getOrDefault(0L)
            if (!AlertStore.hasBaseline(ctx)) {
                // First check after install: whatever is there already is old news.
                AlertStore.markSeen(ctx, id)
                AlertStore.setBaseline(ctx)
                return
            }
            if (System.currentTimeMillis() - at > FRESH_MS) {
                AlertStore.markSeen(ctx, id)
                return
            }
            val url = a.optString("url").takeIf { it.startsWith("https://") } ?: BuildConfig.DISTRICT_URL
            Log.i(DoomApp.TAG, "Backup check found alert $id")
            DoomMessagingService.deliver(ctx, id, a.optString("kind", "info"), a.optString("title"), a.optString("body"), url, a.optString("series"))
        } catch (e: Exception) {
            Log.w(DoomApp.TAG, "Backup check failed: ${e.message}")
        }
    }
}

class GuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 1719, Guard.notification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        Guard.schedulePoll(this, 5_000)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped from recents: make sure the backup check (and with it this service) comes back.
        Guard.schedulePoll(this, 3_000)
        super.onTaskRemoved(rootIntent)
    }
}

/** Fires every ~minute: runs the backup check, keeps the guard service alive, reschedules. */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Guard.schedulePoll(ctx)
        try {
            // An exact alarm lets us start the foreground service from the background.
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        } catch (_: Exception) {
        }
        thread {
            try {
                Guard.pollOnce(ctx)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Re-arms after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (allSetupDone(ctx)) Guard.start(ctx)
    }
}
