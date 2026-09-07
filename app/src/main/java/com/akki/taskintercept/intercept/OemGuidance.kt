package com.akki.taskintercept.intercept

import android.os.Build

/**
 * Brand-specific instructions for keeping the service alive on OEM skins
 * that kill background/accessibility services more aggressively than
 * stock Android. Deliberately just a `when` over Build.MANUFACTURER with
 * hardcoded strings — a handful of brands doesn't justify a library.
 */
object OemGuidance {

    data class Guidance(
        val brandLabel: String,
        /** True for brands known to need extra steps beyond stock Android. */
        val isAggressive: Boolean,
        val instructions: String
    )

    fun forThisDevice(): Guidance = forManufacturer(Build.MANUFACTURER)

    fun forManufacturer(manufacturer: String): Guidance = when (manufacturer.lowercase()) {
        "xiaomi", "redmi", "poco" -> Guidance(
            brandLabel = "Xiaomi / Redmi / POCO (MIUI/HyperOS)",
            isAggressive = true,
            instructions = "1. Open Settings → Apps → Manage apps → TaskIntercept.\n" +
                "2. Enable “Autostart”.\n" +
                "3. Under “Battery saver”, choose “No restrictions”.\n" +
                "4. In Recents, drag TaskIntercept down to lock it (padlock icon) so clearing recents doesn’t kill it."
        )
        "oppo" -> Guidance(
            brandLabel = "OPPO (ColorOS)",
            isAggressive = true,
            instructions = "1. Open Settings → Battery → More settings, and disable optimization for TaskIntercept.\n" +
                "2. Settings → Apps → TaskIntercept → enable “Allow auto launch”.\n" +
                "3. In Recents, tap the ⋮ on TaskIntercept’s card and choose “Lock”."
        )
        "vivo", "iqoo" -> Guidance(
            brandLabel = "vivo / iQOO (Funtouch/OriginOS)",
            isAggressive = true,
            instructions = "1. Open Settings → Battery → Background power consumption management → allow high background power for TaskIntercept.\n" +
                "2. i Manager (or Settings → Apps) → Autostart → enable TaskIntercept.\n" +
                "3. In Recents, pull TaskIntercept’s card down to lock it."
        )
        "realme" -> Guidance(
            brandLabel = "realme (realme UI)",
            isAggressive = true,
            instructions = "1. Open Settings → Battery → App battery management → TaskIntercept → “Don’t optimize”.\n" +
                "2. Settings → Apps → TaskIntercept → enable “Allow auto launch”.\n" +
                "3. In Recents, tap ⋮ on TaskIntercept’s card and choose “Lock”."
        )
        else -> Guidance(
            brandLabel = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            isAggressive = false,
            instructions = "Your device brand usually keeps accessibility services running on its own. " +
                "If the task brief ever stops appearing, check that battery optimization is off for " +
                "TaskIntercept (the row above) and that your battery saver isn’t restricting it."
        )
    }
}
