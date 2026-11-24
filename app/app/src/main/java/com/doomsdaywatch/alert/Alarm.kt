package com.doomsdaywatch.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts the two kinds of alert:
 *  - alarm: alarm sound + vibration that repeats (FLAG_INSISTENT) until the person touches it,
 *           plus a full-screen intent so it takes over the lock screen like an incoming call.
 *  - info:  an ordinary heads-up notification.
 */
object Alarm {
    const val CH_ALARM = "doom_alarm_v1"
    const val CH_INFO = "doom_info_v1"
    const val ALARM_ID = 1717
    private const val INFO_ID = 1718

    val VIBRATION = longArrayOf(0, 900, 350, 900, 350, 1400, 600)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val alarm = NotificationChannel(CH_ALARM, "Tickets opened (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings and vibrates until you open it when a new theatre or date opens"
            enableVibration(true)
            vibrationPattern = VIBRATION
            enableLights(true)
            lightColor = Color.RED
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        val info = NotificationChannel(CH_INFO, "More shows added", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Extra show times at theatres that were already open"
            enableVibration(true)
        }
        nm.createNotificationChannels(listOf(alarm, info))
    }

    fun fire(ctx: Context, title: String, body: String, url: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return

        // Tapping the notification = the person is opening the app, so no extra buzzing.
        val tap = PendingIntent.getActivity(
            ctx, 1, AlertActivity.intent(ctx, title, body, url, autoBuzz = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Full-screen launch on the lock screen = nobody has touched it yet, keep buzzing.
        val fullScreen = PendingIntent.getActivity(
            ctx, 2, AlertActivity.intent(ctx, title, body, url, autoBuzz = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openDistrict = PendingIntent.getActivity(
            ctx, 3, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_doom)
            .setColor(Color.rgb(0xE2, 0x4B, 0x4A))
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(VIBRATION)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Open District", openDistrict)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT

        try {
            nm.notify(ALARM_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun info(ctx: Context, title: String, body: String, url: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, 4, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_doom)
            .setColor(Color.rgb(0x1D, 0x9E, 0x75))
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(INFO_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun stop(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ALARM_ID)

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
