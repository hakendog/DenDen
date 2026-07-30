package com.tensal.denden

import android.app.Application
import com.tensal.denden.setup.DirectPairingStore
import com.tensal.denden.setup.PairingState
import com.tensal.denden.setup.scheduleDirectPairing
import com.tensal.denden.setup.initializeDirectFirebaseRuntime
import com.tensal.denden.branding.DirectBrandStore
import com.tensal.denden.branding.retryPendingShortcutUpdate
import com.tensal.denden.messaging.scheduleLocalTrashCleanup
import com.tensal.denden.messaging.reconcileDirectMessages
import com.tensal.denden.automation.reconcileLocalAutomation

class DenDenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val directPairing = DirectPairingStore(this).snapshot()
        if (directPairing.state == PairingState.PENDING || directPairing.state == PairingState.ACTIVE || directPairing.state == PairingState.ERROR) {
            runCatching { initializeDirectFirebaseRuntime(this) }
            scheduleDirectPairing(this)
        }
        runCatching {
            directPairing.active?.pairingId?.let { DirectBrandStore(this).activatePairing(it) }
                ?: DirectBrandStore(this).clearPairing()
        }
        retryPendingShortcutUpdate(this)
        scheduleLocalTrashCleanup(this)
        reconcileDirectMessages(this)
        reconcileLocalAutomation(this)
    }
}
