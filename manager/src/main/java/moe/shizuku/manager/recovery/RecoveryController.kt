/* Recovery features adapted from thedjchi/Shizuku (Apache-2.0). */
package moe.shizuku.manager.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemProperties
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.work.*
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.receiver.BootCompleteReceiver
import java.util.concurrent.TimeUnit

object RecoveryController {
    const val WATCHDOG = "recovery_watchdog"
    const val TCP = "recovery_tcp"
    private const val PAUSED = "recovery_paused"
    private const val WORK = "shizuku_auto_recovery"
    const val TASK_BOOT = "recovery_task_boot"

    fun watchdogEnabled() = ShizukuSettings.getPreferences().getBoolean(WATCHDOG, false)
    fun tcpEnabled() = ShizukuSettings.getPreferences().getBoolean(TCP, false)
    fun paused() = ShizukuSettings.getPreferences().getBoolean(PAUSED, false)
    fun bootEnabled(context: Context) = context.packageManager.isComponentEnabled(
        ComponentName(context, BootCompleteReceiver::class.java))
    fun enabled(context: Context) = watchdogEnabled() || bootEnabled(context)

    fun setPaused(value: Boolean) {
        // Persist before stopping the privileged process, including on immediate process death.
        check(ShizukuSettings.getPreferences().edit().putBoolean(PAUSED, value).commit())
    }

    fun enqueue(context: Context) {
        if (paused() || !enabled(context) ||
            !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val work = OneTimeWorkRequestBuilder<RecoveryWorker>()
            .setInputData(workDataOf(TASK_BOOT to
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB &&
                    (!tcpEnabled() || SystemProperties.getInt("service.adb.tcp.port", -1) <= 0))
                    NetworkType.UNMETERED else NetworkType.NOT_REQUIRED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, work)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        RecoveryNotifications.clearAlert(context)
    }

    fun pause(context: Context) {
        setPaused(true)
        cancel(context)
        context.stopService(Intent(context, WatchdogService::class.java))
    }

    fun startWatchdog(context: Context) {
        if (!watchdogEnabled() || paused()) return
        try {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: RuntimeException) {
            Log.w("ShizukuRecovery", "Foreground watchdog start rejected", e)
        }
    }

}
