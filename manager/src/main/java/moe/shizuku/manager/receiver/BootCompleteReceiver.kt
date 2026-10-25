/* Boot recovery adapted from thedjchi/Shizuku (Apache-2.0). */
package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.recovery.RecoveryController
import moe.shizuku.manager.utils.UserHandleCompat

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // WorkManager uses credential-encrypted storage. Wait until the first unlock.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || UserHandleCompat.myUserId() > 0) return
        RecoveryController.setPaused(false)
        RecoveryController.startWatchdog(context)
        RecoveryController.enqueue(context)
    }
}
