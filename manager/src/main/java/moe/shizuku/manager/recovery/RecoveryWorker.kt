/* Boot/retry and optional TCP startup adapted from thedjchi/Shizuku (Apache-2.0). */
package moe.shizuku.manager.recovery

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.*
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val context = applicationContext

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // WorkManager persists tasks across reboot; respect the separate boot-start switch.
        val currentBoot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        if (!RecoveryPolicy.allowedForBoot(inputData.getInt(RecoveryController.TASK_BOOT, currentBoot),
                currentBoot, RecoveryController.bootEnabled(context))) return@withContext Result.success()
        val root = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
        val supported = UserHandleCompat.myUserId() == 0 && (root ||
            (Build.VERSION.SDK_INT >= 30 && ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB))
        val authorized = root || context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (RecoveryPolicy.decide(Shizuku.pingBinder(), RecoveryController.enabled(context),
                RecoveryController.paused(), supported, authorized, runAttemptCount)) {
            RecoveryPolicy.Decision.DONE -> {
                RecoveryNotifications.clearAlert(context)
                return@withContext Result.success()
            }
            RecoveryPolicy.Decision.STOP -> return@withContext Result.success()
            RecoveryPolicy.Decision.MANUAL -> {
                RecoveryNotifications.showActionRequired(context)
                return@withContext Result.failure()
            }
            else -> Unit
        }
        try {
            if (root) {
                if (!Shell.getShell().isRoot) {
                    RecoveryNotifications.showActionRequired(context)
                    return@withContext Result.failure()
                }
                ensureActive()
                if (RecoveryController.paused()) return@withContext Result.success()
                Shell.cmd(Starter.internalCommand).exec()
            } else {
                if (Build.VERSION.SDK_INT < 30) return@withContext Result.failure()
                val tcpPort = SystemProperties.getInt("service.adb.tcp.port", -1)
                    .takeIf { RecoveryController.tcpEnabled() && it in 1..65535 }
                if (tcpPort == null && !hasWifi()) return@withContext retryOrNotify(R.string.recovery_wait_wifi)
                val cr = context.contentResolver
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                val port = if (tcpPort != null) tcpPort else {
                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                    discoverPort()
                }
                ensureActive()
                if (RecoveryController.paused()) return@withContext Result.success()
                RecoveryAdbStarter.start("127.0.0.1", port)
            }
            // A completed shell command is not proof that the Binder became available.
            withTimeout(15_000) { while (!Shizuku.pingBinder()) delay(250) }
            RecoveryNotifications.clearAlert(context)
            RecoveryController.startWatchdog(context)
            Result.success()
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) retryOrNotify(R.string.recovery_retry)
            else throw e
        } catch (e: Exception) {
            Log.w("ShizukuRecovery", "Recovery attempt ${runAttemptCount + 1} failed", e)
            retryOrNotify(R.string.recovery_retry)
        }
    }

    private fun retryOrNotify(message: Int): Result {
        if (runAttemptCount + 1 >= RecoveryPolicy.MAX_ATTEMPTS) {
            RecoveryNotifications.showActionRequired(context)
            return Result.failure()
        }
        RecoveryNotifications.updateStatus(context, message)
        return Result.retry()
    }

    private fun hasWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    @RequiresApi(30)
    private suspend fun discoverPort(): Int = withTimeout(15_000) {
        val result = CompletableDeferred<Int>()
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) result.complete(port)
        }
        try {
            mdns.start()
            result.await()
        } finally {
            mdns.stop()
        }
    }

}
