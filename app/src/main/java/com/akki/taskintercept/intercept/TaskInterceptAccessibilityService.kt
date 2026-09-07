package com.akki.taskintercept.intercept

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.akki.taskintercept.data.AppDatabase
import com.akki.taskintercept.data.SettingsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskInterceptAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TaskIntercept"

        /**
         * Packages whose window-state events are pure noise for foreground
         * tracking: system surfaces that flash brief windows DURING in-app
         * transitions without the user actually leaving the current app
         * (confirmed via logcat for Instagram's profile-modal open/close).
         * Events from these must be invisible to the tracker entirely — if
         * they updated lastForegroundPackage, a later same-package Activity
         * change inside the monitored app would misread as a fresh open.
         */
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.googlequicksearchbox"
        )

        /**
         * How long after leaving a monitored app to keep ignoring
         * window-state events from that specific package — backgrounded
         * apps keep emitting spurious events shortly after a genuine exit
         * (~0.4s observed via logcat), which would otherwise read as a
         * fresh open and falsely re-trigger the overlay.
         */
        private const val RESIDUAL_SETTLE_MS = 2000L

        /**
         * Navigation debounce window: while a "Return to Task" navigation is
         * in flight, all overlay triggers are suppressed to prevent residual
         * window transition events from showing a ghost overlay over the home
         * launcher. 1200ms covers observed YouTube backgrounding transition
         * noise on heavy OEM skins with extended window animations.
         */
        private const val HOME_NAVIGATION_DEBOUNCE_MS = 1200L
    }

    private val overlayManager: OverlayManager by lazy {
        OverlayManager(this, onReturnToTask = {
            // SYNCHRONOUS IMMEDIATE DISMISSAL: Cancel pending shows and hide
            // the overlay BEFORE any navigation happens. This prevents the
            // overlay from being visible during the home transition, which
            // would otherwise allow residual events to re-trigger it over
            // the home launcher.
            pendingShowJob?.cancel()
            this@TaskInterceptAccessibilityService.overlayManager.hideOverlay()

            // Enable navigation debounce: suppress all overlay triggers
            // for HOME_NAVIGATION_DEBOUNCE_MS to prevent ghost overlays
            // from residual window transition events appearing over the
            // home launcher.
            isNavigatingHome = true
            navigatingHomeSince = SystemClock.elapsedRealtime()

            // Stamp the settle state so residual background events from
            // the just-left app (~0.4s observed) are still absorbed. Uses
            // the full RESIDUAL_SETTLE_MS (2000ms) window, not a compressed
            // one: backdating the timestamp to shrink the window is
            // timing-fragile since residual events don't arrive until after
            // the real home transition completes (~0.4-0.5s later), which
            // can consume most of a compressed window before the residual
            // event even arrives. The trade-off: a user who taps Return to
            // Task and immediately reopens the same app within 2 seconds
            // will not retrigger (same accepted behavior as organic exits).
            settledFromPackage = lastForegroundPackage
            settledAt = SystemClock.elapsedRealtime()
            // Reset the tracker to a neutral value so the next genuine
            // open of any monitored app mismatches and triggers correctly.
            lastForegroundPackage = ""

            // Navigate home with two-path fallback:
            // 1. performGlobalAction is the more reliable path: it goes
            //    through the accessibility framework and works even during
            //    window animations. Returns false if the service declines.
            // 2. Explicit home Intent as the fallback for cases where the
            //    system ignores GLOBAL_ACTION_HOME (confirmed edge case).
            val navigated: Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
            if (!navigated) {
                Log.d(TAG, "GLOBAL_ACTION_HOME declined — falling back to home Intent")
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Live mirrors of the two DataStore settings the event handler needs.
     * onAccessibilityEvent is a synchronous main-thread callback, so it
     * can't suspend on a Flow read per event — instead these fields are
     * kept current by collectors started in [onServiceConnected], and each
     * event reads the latest value. @Volatile because the collectors write
     * from an IO dispatcher while events read from the main thread.
     *
     * Initial values match pre-Phase-4 behavior (default set, enabled), so
     * the sub-millisecond window between service connect and the first
     * DataStore emission behaves exactly like the old hardcoded constants.
     */
    @Volatile
    private var monitoredPackages: Set<String> = SettingsPrefs.DEFAULT_MONITORED_PACKAGES

    @Volatile
    private var interceptionEnabled: Boolean = true

    /**
     * The settings collectors, kept so a reconnect can cancel the previous
     * pair before launching fresh ones. onServiceConnected fires again if
     * the user toggles the accessibility service off/on in OS settings,
     * and serviceScope survives that (only onDestroy cancels it) — without
     * this, each reconnect would stack another pair of live collectors.
     */
    private var settingsJobs: List<Job> = emptyList()

    /**
     * In-flight overlay-trigger coroutine. Tracked so a foreground change
     * that arrives between trigger and show (rapid app-switching, lock
     * screen) can cancel the now-stale show instead of overlaying the
     * wrong app.
     */
    private var pendingShowJob: Job? = null

    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    /**
     * Screen-off safety net: if the overlay is up when the display turns
     * off, dismiss it. Otherwise it would still be showing when the device
     * wakes into the keyguard, drawn over the lock screen — and its state
     * (countdown finished, tracker assumptions) would be stale by unlock
     * time anyway. Registered in onServiceConnected, unregistered in
     * onDestroy. SCREEN_OFF cannot be declared in the manifest; a runtime
     * receiver is the only option, and this process is long-lived by
     * definition while the service is connected.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            pendingShowJob?.cancel()
            if (overlayManager.isOverlayShowing) {
                Log.d(TAG, "Screen off with overlay showing — dismissing")
                overlayManager.hideOverlay()
            }
        }
    }
    private var screenOffReceiverRegistered = false

    /**
     * The accessibility service's lifecycle is the source of truth for the
     * reliability foreground service: watchdog runs exactly while the OS
     * has this service connected.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        InterceptForegroundService.start(this)
        // Pay the overlay's one-time inflation cost now, at a quiet moment,
        // instead of inside the first trigger's race against the target
        // app's first frame. onServiceConnected runs on the main thread.
        overlayManager.warmUp()
        if (!screenOffReceiverRegistered) {
            // RECEIVER_NOT_EXPORTED: SCREEN_OFF is a protected system
            // broadcast (exempt from the targetSdk 34+ flag requirement),
            // but declaring it explicitly keeps lint clean and intent
            // obvious.
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenOffReceiverRegistered = true
        }
        // Observe (not one-shot read) both settings for the service's whole
        // lifetime: user edits in the app take effect on the very next
        // event, with no service restart and no stale cached copy.
        // Cancel the previous pair first — onServiceConnected re-fires on
        // an OS-settings toggle off/on while serviceScope survives, which
        // would otherwise stack duplicate collectors per reconnect.
        settingsJobs.forEach { it.cancel() }
        settingsJobs = listOf(
            serviceScope.launch {
                SettingsPrefs.monitoredPackages(this@TaskInterceptAccessibilityService)
                    .collect { monitoredPackages = it }
            },
            serviceScope.launch {
                SettingsPrefs.interceptionEnabled(this@TaskInterceptAccessibilityService)
                    .collect { interceptionEnabled = it }
            }
        )
    }

    /**

    
     * Last package seen in a window-state event. Comparing the incoming
     * package against this distinguishes "same app, duplicate event"
     * (double-fire: same package again → suppress) from "user left and
     * came back" (something else was foregrounded in between → the tracked
     * value changed → fresh open → re-trigger).
     */
    private var lastForegroundPackage: String? = null

    /**
     * Which monitored app was most recently LEFT, and when (elapsedRealtime;
     * 0 = never). Set on any genuine transition out of a monitored app —
     * whether detected via a real window-state event (home gesture, app
     * switch) or stamped explicitly by the "Return to Task" callback (where
     * no reliable event arrives). For [RESIDUAL_SETTLE_MS] after that
     * moment, events from that specific package are treated as residual
     * background noise, not a fresh open.
     */
    private var settledFromPackage: String? = null
    private var settledAt: Long = 0L

    /**
     * Transient flag indicating a "Return to Task" navigation is in flight.
     * While true, all overlay triggers are suppressed to prevent ghost
     * overlays from residual window transition events appearing over the
     * home launcher. Cleared after [HOME_NAVIGATION_DEBOUNCE_MS].
     */
    private var isNavigatingHome: Boolean = false
    private var navigatingHomeSince: Long = 0L

    /**
     * Cached set of home launcher package names, resolved once on first
     * access via [getHomeLauncherPackages]. Used to detect when the user
     * has navigated to the home screen (either explicitly via "Return to
     * Task" or organically via system gestures) so the overlay can be
     * dismissed and the deduplication tracker reset.
     */
    private var homeLauncherPackages: Set<String>? = null

    private fun getHomeLauncherPackages(): Set<String> {
        homeLauncherPackages?.let { return it }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(homeIntent, 0)
        val packages = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
        homeLauncherPackages = packages
        Log.d(TAG, "Resolved home launcher packages: $packages")
        return packages
    }

    private fun isHomePackage(packageName: String): Boolean {
        // SystemUI is part of the home transition on many devices
        if (packageName == "com.android.systemui") return true
        // Check against resolved home launcher packages
        return packageName in getHomeLauncherPackages()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        // Master switch (Phase 4): when interception is paused, the service
        // is truly inert — no trigger, no tracker updates, no settle
        // stamping. Deliberately placed before ALL of the guards below:
        // running the tracker while disabled would accumulate state that's
        // stale by re-enable time anyway (re-enabling mid-session of a
        // monitored app then not triggering until a fresh open is the
        // correct, unsurprising behavior).
        if (!interceptionEnabled) return

        // Ignore our OWN windows entirely. The overlay's WindowManager view
        // generates TYPE_WINDOW_STATE_CHANGED events both when it's added
        // and when it's removed — if those updated lastForegroundPackage,
        // the tracker would read "taskintercept" after a dismiss, making the
        // still-foregrounded monitored app look like a fresh transition and
        // falsely re-triggering the overlay. Our package can never be a
        // trigger target either, so skip the whole handler early: the
        // tracker must only ever reflect genuine third-party transitions.
        if (packageName == applicationContext.packageName) return

        // Transient system surfaces (status bar, gesture-nav/quick-search
        // flashes) fire brief window-state events during in-app transitions
        // WITHOUT the user leaving the current app. Skip them before they
        // can touch anything — tracker, settle stamp, or trigger check.
        if (packageName in TRANSIENT_SYSTEM_PACKAGES) return

        // Keyguard guard: while the device is locked, events (e.g. a
        // monitored app resuming behind the lock screen after screen-on)
        // must be fully invisible — triggering would draw the overlay over
        // the keyguard, and updating the tracker would corrupt de-dup
        // state established before the lock. When the user actually
        // unlocks into the app, its activity resume fires a fresh
        // window-state event with the keyguard down, which processes
        // normally.
        if (keyguardManager.isKeyguardLocked) {
            Log.d(TAG, "Keyguard locked, ignoring event from $packageName")
            return
        }

        // CRITICAL HOME PACKAGE GUARD: If a home launcher or system UI
        // component is detected, force-dismiss any visible/pending overlay
        // and reset deduplication state IMMEDIATELY. This must sit at the
        // top of the event evaluation flow before any DB reads or trigger
        // checks. Prevents ghost overlays from appearing over the home
        // screen during "Return to Task" transitions.
        if (isHomePackage(packageName)) {
            Log.d(TAG, "Home package detected: $packageName")
            pendingShowJob?.cancel()
            if (overlayManager.isOverlayShowing) {
                Log.d(TAG, "Force-dismissing overlay for home package")
                overlayManager.hideOverlay()
            }
            // Stamp settle-window state BEFORE resetting the tracker: if the
            // package being left (lastForegroundPackage) was a monitored app,
            // residual events from it that arrive after this home detection
            // (~0.4s observed post-backgrounding) must still be caught by the
            // residual-noise check. Without this stamp, those events bypass
            // the settle guard entirely (settledFromPackage would be null) and
            // falsely re-trigger since lastForegroundPackage is about to be
            // reset to null, making the comparison mismatch.
            if (lastForegroundPackage in monitoredPackages) {
                settledFromPackage = lastForegroundPackage
                settledAt = SystemClock.elapsedRealtime()
            }
            // Reset deduplication tracker: user has left all monitored apps,
            // so the next open of any monitored app should trigger cleanly.
            // This fixes Bug 2: gesture exit → immediate reopen.
            lastForegroundPackage = null
            // Clear the navigation debounce flag if it was set — the home
            // launcher event confirms the navigation completed successfully.
            isNavigatingHome = false
            return
        }

        // Navigation debounce: while a "Return to Task" navigation is in
        // flight, suppress ALL overlay triggers to prevent ghost overlays
        // from residual window transition events. Cleared after the debounce
        // window expires or when a home launcher event confirms arrival.
        if (isNavigatingHome) {
            val debounceElapsed = SystemClock.elapsedRealtime() - navigatingHomeSince
            if (debounceElapsed < HOME_NAVIGATION_DEBOUNCE_MS) {
                Log.d(TAG, "Navigation debounce active, ignoring event from $packageName")
                return
            } else {
                // Debounce window expired — clear the flag and process normally.
                isNavigatingHome = false
            }
        }

        // Detect leaving a monitored app: this event's package differs from
        // the tracked one, and the tracked one was monitored. Stamp it as
        // "recently settled" BEFORE the trigger check and tracker update —
        // residual events from that package within the settle window are
        // background noise, not a fresh open. (The "Return to Task" path
        // stamps this explicitly in the callback instead, since Android
        // doesn't reliably deliver an event for that transition.)
        val previousPackage = lastForegroundPackage
        if (packageName != previousPackage && previousPackage in monitoredPackages) {
            settledFromPackage = previousPackage
            settledAt = SystemClock.elapsedRealtime()
        }

        // Residual-noise check: an event from the specific package we just
        // left, inside its settle window, is background noise — not a fresh
        // open. Ignore it ENTIRELY (no trigger, no tracker update): letting
        // it update lastForegroundPackage back to the monitored package
        // would make the next genuine reopen compare equal and be wrongly
        // suppressed, since a launcher event between now and then isn't
        // guaranteed. Scoped to settledFromPackage only — events from any
        // OTHER package during the window still process normally.
        if (packageName == settledFromPackage &&
            (SystemClock.elapsedRealtime() - settledAt) < RESIDUAL_SETTLE_MS
        ) {
            Log.d(TAG, "Residual event from just-left $packageName, ignoring")
            return
        }

        // A genuine foreground change makes any overlay currently up (or
        // about to go up from a still-in-flight trigger) stale: the app it
        // was covering is no longer what the user sees. Covers incoming
        // calls, system dialogs, and rapid app-switching — the new surface
        // takes over cleanly instead of sitting under/behind a leftover
        // overlay. Done BEFORE the trigger check so a switch straight into
        // another monitored app hides the old overlay first, then shows a
        // fresh one with a fresh countdown.
        if (packageName != previousPackage) {
            pendingShowJob?.cancel()
            if (overlayManager.isOverlayShowing) {
                Log.d(TAG, "Foreground changed to $packageName with overlay up — dismissing")
                overlayManager.hideOverlay()
            }
        }

        // Trigger only on a real transition INTO a monitored app: the
        // package must be monitored AND differ from the last one seen.
        // Order matters — compare BEFORE updating the tracked package,
        // or we'd always compare against the value we just set.
        if (packageName in monitoredPackages && packageName != lastForegroundPackage) {
            Log.d(TAG, "Detected target app: $packageName")
            pendingShowJob = serviceScope.launch {
                // Room query and DataStore read are independent — run them
                // concurrently; the overlay show waits only on the slower
                // of the two, not their sum.
                val tasksDeferred = async {
                    AppDatabase.getDatabase(this@TaskInterceptAccessibilityService)
                        .taskDao()
                        .getActiveTasksByPriorityOnce(OverlayManager.MAX_OVERLAY_TASKS)
                }
                // Read the countdown length fresh per show (one-shot, not a
                // cached copy) so a settings change applies to the very next
                // overlay without a service restart.
                val durationDeferred = async {
                    SettingsPrefs
                        .overlayDurationSeconds(this@TaskInterceptAccessibilityService)
                        .first()
                }
                val tasks = tasksDeferred.await()
                val durationSeconds = durationDeferred.await()
                // WindowManager view operations must run on the main thread.
                withContext(Dispatchers.Main) {
                    // Re-validate on Main, just before adding the view: the
                    // async reads took real time, and the world may have
                    // moved on — another app foregrounded (tracker no
                    // longer holds our package) or the device locked.
                    // Showing anyway would overlay the wrong surface.
                    if (lastForegroundPackage != packageName) {
                        Log.d(TAG, "Stale trigger for $packageName, foreground moved on — skipping show")
                        return@withContext
                    }
                    if (keyguardManager.isKeyguardLocked) {
                        Log.d(TAG, "Keyguard locked at show time — skipping show")
                        return@withContext
                    }
                    overlayManager.showOverlay(tasks, durationSeconds)
                }
            }
        }

        // Update tracking on EVERY event, monitored or not — seeing a
        // non-monitored package (launcher, other apps) is what marks the
        // user as having left, so the next monitored open re-triggers.
        lastForegroundPackage = packageName
    }

    override fun onInterrupt() {
        // No feedback to interrupt.
    }

    override fun onDestroy() {
        // If the service dies (user toggles it off in OS settings, process
        // teardown) while the overlay is up, the window would otherwise be
        // orphaned on screen with no owner left to remove it. hideOverlay
        // is a cheap no-op when nothing was ever shown (it won't inflate
        // the view just to hide it).
        overlayManager.hideOverlay()
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        InterceptForegroundService.stop(this)
        serviceScope.cancel()
        super.onDestroy()
    }
}
