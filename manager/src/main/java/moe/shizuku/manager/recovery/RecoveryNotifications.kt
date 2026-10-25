package moe.shizuku.manager.recovery

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemProperties
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku

/** Only foreground status and actionable failures belong here; pairing owns its own channel. */
object RecoveryNotifications {
    const val STATUS_ID = 1448
    private const val ALERT_ID = 1447
    private const val STATUS_CHANNEL = "shizuku_watchdog_status"
    private const val ALERT_CHANNEL = "shizuku_recovery_alerts"
    private const val LEGACY_CHANNEL = "shizuku_recovery"

    /** Keep user settings on current channels. Defer deletion if an old foreground service uses it. */
    fun initialize(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL,
            context.getString(R.string.recovery_status_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,
            context.getString(R.string.recovery_alert_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(LEGACY_CHANNEL) != null) {
            val inForeground = manager.activeNotifications.any {
                it.notification.channelId == LEGACY_CHANNEL &&
                    (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
            }
            if (inForeground) return false
            manager.deleteNotificationChannel(LEGACY_CHANNEL)
        }
        return true
    }

    private fun statusMessage(context: Context): Int {
        if (Shizuku.pingBinder()) return R.string.recovery_watching
        val needsWifi = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
            (!RecoveryController.tcpEnabled() || SystemProperties.getInt("service.adb.tcp.port", -1) <= 0)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val hasWifi = cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return if (needsWifi && !hasWifi) R.string.recovery_wait_wifi else R.string.recovery_retry
    }

    private fun build(context: Context, message: Int, alert: Boolean): Notification {
        initialize(context)
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, if (alert) ALERT_CHANNEL else STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.recovery_title))
            .setContentText(context.getString(message))
            .setContentIntent(intent)
            .setOngoing(!alert).setAutoCancel(alert)
            .setSilent(!alert).setOnlyAlertOnce(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (alert) Notification.CATEGORY_ERROR else Notification.CATEGORY_SERVICE)
        if (alert) builder.setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(message)))
        return builder.build()
    }

    fun statusNotification(context: Context): Notification = build(context, statusMessage(context), false)

    private fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun updateStatus(context: Context, message: Int = statusMessage(context)) {
        if (!WatchdogService.isRunning || !RecoveryController.watchdogEnabled() || RecoveryController.paused()) return
        if (canPost(context)) context.getSystemService(NotificationManager::class.java)
            .notify(STATUS_ID, build(context, message, false))
    }

    fun showActionRequired(context: Context) {
        updateStatus(context, R.string.recovery_action_required)
        if (canPost(context)) context.getSystemService(NotificationManager::class.java)
            .notify(ALERT_ID, build(context, R.string.recovery_manual, true))
    }

    fun clearAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        updateStatus(context)
    }
}
