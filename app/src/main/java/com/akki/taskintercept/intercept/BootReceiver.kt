package com.akki.taskintercept.intercept

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Defensive post-reboot check. On stock Android the OS itself restores
 * enabled accessibility services after boot — this receiver cannot (and
 * does not try to) enable the accessibility service, since only the user
 * can do that through Settings. What it CAN do is restart the reliability
 * foreground service early if the accessibility service is enabled,
 * rather than waiting for the accessibility framework to reconnect it,
 * and leave a Logcat breadcrumb either way so reboot testing has ground
 * truth about what state the device actually came back in.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = isAccessibilityServiceEnabled(context)
        Log.d("TaskIntercept", "Boot completed; accessibility service enabled=$enabled")
        if (enabled) {
            // BOOT_COMPLETED is exempt from background-start restrictions,
            // so starting the watchdog here is allowed. If the
            // accessibility service reconnects later it will call start()
            // again — startForegroundService on an already-running service
            // is a harmless no-op re-delivery.
            InterceptForegroundService.start(context)
        }
    }
}
