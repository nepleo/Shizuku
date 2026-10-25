package moe.shizuku.manager.recovery

/** Bounded retry and manual-stop rules, independent of Android scheduling. */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 6
    enum class Decision { DONE, STOP, MANUAL, ATTEMPT }

    fun allowedForBoot(taskBoot: Int, currentBoot: Int, startOnBoot: Boolean) =
        taskBoot == currentBoot || startOnBoot

    fun decide(running: Boolean, enabled: Boolean, paused: Boolean,
               supported: Boolean, authorized: Boolean, attempts: Int): Decision = when {
        running -> Decision.DONE
        !enabled || paused -> Decision.STOP
        !supported || !authorized || attempts >= MAX_ATTEMPTS -> Decision.MANUAL
        else -> Decision.ATTEMPT
    }
}
