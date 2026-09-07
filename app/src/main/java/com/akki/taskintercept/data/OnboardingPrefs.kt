package com.akki.taskintercept.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// App-level DataStore for one-off flags. Deliberately not Room — this is
// device/UI state, not user data, and shouldn't ride along in DB migrations.
private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

object OnboardingPrefs {

    private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    /** Emits false until the user finishes the first-run onboarding screen. */
    fun isComplete(context: Context): Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] ?: false
        }

    suspend fun setComplete(context: Context) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] = true
        }
    }
}
