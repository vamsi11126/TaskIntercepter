package com.akki.taskintercept.intercept

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * True if this app's accessibility service is enabled in the OS-level
 * ENABLED_ACCESSIBILITY_SERVICES colon-separated list. Shared by
 * MainActivity (Setup status row) and BootReceiver (post-reboot check).
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, TaskInterceptAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { entry ->
        ComponentName.unflattenFromString(entry) == expected
    }
}
