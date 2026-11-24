package com.atothei.roadhouse.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the overlay after a reboot — but only if the driver already
 * finished first-run setup, so a fresh install never starts itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isOnboardingDone(context)) return
        OverlayService.start(context)
    }
}
