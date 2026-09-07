package com.akki.taskintercept.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// User-configurable behavior settings (Phase 4). Separate DataStore file
// from OnboardingPrefs — that one is a one-shot first-run flag, these are
// live settings the accessibility service observes while running.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

object SettingsPrefs {

    /**
     * Pre-Phase-4 hardcoded monitored set, now the DEFAULT for the
     * user-configurable list: with no user changes, behavior is identical
     * to before (Instagram + YouTube). Package names, not display names —
     * this is what the service compares window-state events against.
     */
    val DEFAULT_MONITORED_PACKAGES = setOf(
        "com.instagram.android",
        "com.google.android.youtube"
    )

    /** Pre-Phase-4 hardcoded countdown length, now the default. */
    const val DEFAULT_OVERLAY_DURATION_SECONDS = 10

    /** Preset choices offered in the settings UI. */
    val OVERLAY_DURATION_OPTIONS = listOf(5, 10, 15, 20)

    private val KEY_MONITORED_PACKAGES = stringSetPreferencesKey("monitored_packages")
    private val KEY_OVERLAY_DURATION = intPreferencesKey("overlay_duration_seconds")
    private val KEY_INTERCEPTION_ENABLED = booleanPreferencesKey("interception_enabled")

    /**
     * The set of package names the service intercepts. Absent key (fresh
     * install, or pre-Phase-4 upgrade) falls back to the default set — an
     * explicitly saved empty set stays empty (the user unchecked everything,
     * which is a valid choice, distinct from "never configured").
     */
    fun monitoredPackages(context: Context): Flow<Set<String>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_MONITORED_PACKAGES] ?: DEFAULT_MONITORED_PACKAGES
        }

    suspend fun setMonitoredPackages(context: Context, packages: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_MONITORED_PACKAGES] = packages
        }
    }

    fun overlayDurationSeconds(context: Context): Flow<Int> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_OVERLAY_DURATION] ?: DEFAULT_OVERLAY_DURATION_SECONDS
        }

    suspend fun setOverlayDurationSeconds(context: Context, seconds: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_OVERLAY_DURATION] = seconds
        }
    }

    /**
     * Master interception switch — independent of the Accessibility
     * permission. False pauses all processing in the service without the
     * user having to revoke anything in OS settings.
     */
    fun interceptionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_INTERCEPTION_ENABLED] ?: true
        }

    suspend fun setInterceptionEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_INTERCEPTION_ENABLED] = enabled
        }
    }
}
