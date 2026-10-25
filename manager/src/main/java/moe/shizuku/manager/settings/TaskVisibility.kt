package moe.shizuku.manager.settings

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.util.Log
import moe.shizuku.manager.ShizukuSettings

/** Controls the manager task card only; never changes the Shizuku server. */
internal object TaskVisibility {
    const val KEY_HIDE_TASK = "hide_task"

    fun apply(activity: Activity, hidden: Boolean = ShizukuSettings.getPreferences()
        .getBoolean(KEY_HIDE_TASK, false)): Boolean {
        return try {
            val manager = activity.getSystemService(ActivityManager::class.java) ?: return false
            val task = manager.appTasks.firstOrNull {
                val info = it.taskInfo
                (if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id) == activity.taskId
            }
                ?: return false
            task.setExcludeFromRecents(hidden)
            true
        } catch (e: RuntimeException) {
            Log.w("TaskVisibility", "Could not update recent task visibility", e)
            false
        }
    }
}
