/* Foreground Binder watchdog adapted from thedjchi/Shizuku (Apache-2.0). */
package moe.shizuku.manager.recovery

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

class WatchdogService : Service() {
    companion object {
        @Volatile var isRunning = false
            private set
    }
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { RecoveryNotifications.updateStatus(this@WatchdogService) }
        override fun onLost(network: Network) { RecoveryNotifications.updateStatus(this@WatchdogService) }
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (RecoveryController.watchdogEnabled() && !RecoveryController.paused()) {
            RecoveryNotifications.updateStatus(this, R.string.recovery_retry)
            RecoveryController.enqueue(this)
        }
    }
    private val received = Shizuku.OnBinderReceivedListener {
        RecoveryNotifications.clearAlert(this)
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addBinderReceivedListenerSticky(received)
        try {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            networkCallbackRegistered = true
        } catch (e: RuntimeException) {
            android.util.Log.w("ShizukuRecovery", "Could not monitor Wi-Fi status", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = RecoveryNotifications.statusNotification(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(RecoveryNotifications.STATUS_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(RecoveryNotifications.STATUS_ID, notification)
        isRunning = true
        if (!RecoveryNotifications.initialize(this)) {
            // One deferred migration after switching the old foreground notification's channel.
            android.os.Handler(mainLooper).postDelayed({ RecoveryNotifications.initialize(applicationContext) }, 1_000)
        }
        if (!RecoveryController.watchdogEnabled() || RecoveryController.paused()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Shizuku.pingBinder()) RecoveryController.enqueue(this)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeBinderReceivedListener(received)
        // Destruction by the OS must not switch off the user's persistent preference.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
