package moe.shizuku.manager.recovery

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.shizuku.manager.recovery.RecoveryPolicy.Decision.*

class RecoveryPolicyTest {
    @Test fun persistedWorkMustRespectDisabledBootStartup() {
        assertEquals(false, RecoveryPolicy.allowedForBoot(7, 8, false))
        assertEquals(true, RecoveryPolicy.allowedForBoot(7, 8, true))
        assertEquals(true, RecoveryPolicy.allowedForBoot(8, 8, false))
    }
    @Test fun runningServiceMustNotBeStartedAgainEvenAfterTimeout() {
        assertEquals(DONE, RecoveryPolicy.decide(true, true, false, true, true, 6))
    }
    @Test fun manualStopAndDisabledRecoveryPreventRestart() {
        assertEquals(STOP, RecoveryPolicy.decide(false, true, true, true, true, 0))
        assertEquals(STOP, RecoveryPolicy.decide(false, false, false, true, true, 0))
    }
    @Test fun missingPermissionAndUnsupportedModesNeedManualAction() {
        assertEquals(MANUAL, RecoveryPolicy.decide(false, true, false, true, false, 0))
        assertEquals(MANUAL, RecoveryPolicy.decide(false, true, false, false, true, 0))
    }
    @Test fun recoveryHasABoundedRetryBudget() {
        assertEquals(ATTEMPT, RecoveryPolicy.decide(false, true, false, true, true, 0))
        assertEquals(ATTEMPT, RecoveryPolicy.decide(false, true, false, true, true, 5))
        assertEquals(MANUAL, RecoveryPolicy.decide(false, true, false, true, true, 6))
    }
}
