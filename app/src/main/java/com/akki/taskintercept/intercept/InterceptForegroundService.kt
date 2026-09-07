package com.akki.taskintercept.intercept

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.akki.taskintercept.MainActivity
import com.akki.taskintercept.R

/**
 * Foreground service whose only job is reliability: holding a persistent
 * low-priority notification raises the app's process priority against
 * being killed under memory pressure (especially on aggressive OEM
 * skins). It does no work of its own — detection stays entirely in
 * [TaskInterceptAccessibilityService], which starts/stops this service
 * as it connects/disconnects.
 */
class InterceptForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "taskintercept_watchdog"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, InterceptForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InterceptForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // IMPORTANCE_MIN: no sound, no peeking, collapsed to the smallest
        // form the OS allows — present but non-obtrusive by design.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Interception reliability",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps TaskIntercept running so the task brief can appear."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            // targetSdk 34+ requires an explicit type; specialUse is the
            // honest one — this service exists only to hold priority.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        // If the OS kills us anyway, ask to be recreated — that's the point.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_notification_title))
            .setContentText(getString(R.string.watchdog_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
