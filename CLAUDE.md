# TaskIntercept — Agent Guide

An Android app that intercepts opening Instagram/YouTube and shows the user's
planned task first, before the app's UI loads, giving them a real moment of
choice instead of a default scroll.

This file is the standing reference for Claude Code across the whole build.
Built via numbered prompts given one at a time — not a self-planning agent.
Read it fully at the start of every session. Update the "Current Phase
Status" section after each prompt is actually run and confirmed — that
section is the source of truth for where the project actually is, not
commit history or memory.

## Standing constraints (apply to every phase, don't relitigate these)

- **Kotlin + Jetpack Compose only.** No Java, no XML-based UI except the
  overlay view (see exception below) and unavoidable resource/layout files.
- **Room for all local storage.** Do not introduce Firebase, Retrofit, or any
  network/cloud dependency until a phase explicitly calls for sync — there is
  no reason for this app to talk to a server before then.
- **minSdk 26, targetSdk 35, compileSdk 35.** (Started at 34, tried 36 —
  both had SDK-download failures on this machine unrelated to the project
  itself; 35 installed cleanly and is confirmed via a real
  `BUILD SUCCESSFUL`.) Don't lower minSdk to "support more devices" without
  flagging it — accessibility/overlay APIs are more predictable from 26
  onward.
- **Accessibility service reads window-state events only.**
  `canRetrieveWindowContent` must stay `false` in
  `accessibility_service_config.xml`, and the service code must never call
  APIs that inspect screen content. This is a privacy commitment and it's
  also what keeps the eventual Play Store accessibility-usage declaration
  clean. Do not "temporarily" loosen this for debugging convenience.
- **Overlay exception to the Compose rule:** the brief-screen overlay may use
  a plain inflated `View` instead of `ComposeView` if hosting Compose inside
  a raw `WindowManager` window turns out to need extra
  `ViewTreeLifecycleOwner`/`SavedStateRegistry` plumbing. Only take this
  exception if it's actually needed — don't default to it.
- **Distribution target:** sideloaded APK now, Play Store eventually. Code
  changes for Play Store readiness live in a separate phase (Phase 6) and
  should not require touching Phases 1–5's logic if those phases kept scope
  narrow as specified.

## Build approach: numbered prompts, one at a time

No self-planning agent, no autonomous phase execution. Each prompt below is
a discrete, scoped piece of work — give one to Claude Code, review/test the
result, then move to the next. Don't let Claude Code combine prompts or
jump ahead, even if it offers to.

### Phase 1 — Interception core

1. Project scaffold: new Android Studio project, Kotlin + Compose, package
   `com.akki.taskintercept`, min/target/compile SDK per constraints above.
2. Room setup: `Task` entity, `TaskDao` (single active task), `AppDatabase`
   singleton. No UI wiring yet — just confirm it compiles.
3. Basic `MainActivity`: Compose screen with a text field + save button that
   writes to Room, and displays the currently saved task on load.
4. Manifest + config: declare the `AccessibilityService` and
   `SYSTEM_ALERT_WINDOW` permission, add `accessibility_service_config.xml`
   filtered to Instagram + YouTube package names.
5. `TaskInterceptAccessibilityService` skeleton: log the foreground app's
   package name to Logcat on `TYPE_WINDOW_STATE_CHANGED` — no overlay yet,
   just confirm detection fires correctly for the two target apps and
   nothing else.
6. Permission screens: buttons in `MainActivity` that deep-link to the
   Accessibility settings page and the "Draw over other apps" settings page.
7. `OverlayManager` + `overlay_task_brief.xml`: on detecting a target app,
   draw a static placeholder overlay via `WindowManager` (no real task text
   yet, no buttons wired) — confirm it actually appears over Instagram/
   YouTube before proceeding.
8. Wire the overlay to pull the real active task from Room and display it.
9. Wire "Return to Task" (go home, dismiss overlay) and "Continue Anyway"
   (dismiss overlay, let the target app proceed) button behavior.
10. De-dup logic: overlay shouldn't re-trigger while the same app session
    stays foregrounded; should re-trigger on a fresh open.
11. Real-device test pass against the Phase 1 definition of done below —
    this one's you running the app, not a code prompt.

Later phases (device hardening, Play Store prep) will get their own
numbered prompt lists once Phase 4 is confirmed working — no need to plan
those in detail yet.

### Phase 4 — Settings & configuration

Lower-risk phase than Phase 3 — this is UI/configuration work, not
touching the accessibility service's core detection logic (aside from
prompt 1, which does touch what the service reads, so treat that one with
a bit more care than the rest).

1. Monitored-app picker: replace the hardcoded `MONITORED_PACKAGES`
   set/`accessibility_service_config.xml` package list with a
   user-configurable list, persisted via `DataStore` (a `Set<String>` of
   package names — package names, not display names, since that's what
   the service actually compares against). Default to Instagram + YouTube
   pre-selected, matching current behavior exactly if the user changes
   nothing. `TaskInterceptAccessibilityService` should read from this
   `DataStore` value rather than the hardcoded set. This is the one prompt
   this phase where a mistake could break the core mechanism (e.g. reading
   a stale/empty set on service start), so test it carefully — does the
   app still correctly monitor Instagram/YouTube by default, and does
   changing the list actually take effect without needing an app restart?
2. App picker UI: a simple screen/dialog to add/remove monitored apps from
   the installed-apps list on the device (`PackageManager.getInstalledApplications()`
   filtered to apps with a launcher intent, showing icon + label), backed
   by prompt 1's DataStore set. No need for search/filtering with a small
   list of installed apps — keep it simple.
3. Overlay duration setting: make the countdown's 10-second default
   user-adjustable (a slider or a few preset options — e.g. 5/10/15/20s),
   persisted via `DataStore`, read by `OverlayManager` when starting the
   countdown timer instead of the hardcoded value.
4. Enable/disable toggle: a master switch in the main screen (separate
   from the Accessibility permission itself) that lets the user pause
   interception entirely without revoking permissions — useful for
   temporarily turning the whole thing off without going through OS
   settings. `TaskInterceptAccessibilityService` should check this flag
   early in `onAccessibilityEvent` and skip all further processing if
   disabled.
5. Real-device test pass (below) — you running the app, not a code
   prompt.

## Phase 4 definition of done

1. Default behavior unchanged: fresh install (or after Phase 3's
   uninstall/reinstall testing) still monitors Instagram + YouTube exactly
   as before, with no configuration needed.
2. Add a third app (anything installed) via the picker, confirm it's now
   correctly intercepted; remove Instagram from the list, confirm it's no
   longer intercepted (and YouTube still is) — without restarting the app.
3. Change the overlay duration setting, confirm the next overlay actually
   uses the new duration.
4. Toggle the master switch off, confirm no interception happens at all
   for any app (including apps still in the monitored list); toggle back
   on, confirm it resumes correctly.
5. Full regression: with default settings restored, confirm the
   Phase 1-3 sequence (permissions, trigger, both buttons, de-dup —
   including the two specific bug scenarios fixed in Phase 3) all still
   work correctly after Phase 4's changes.

### Phase 3 — Permissions, onboarding, reliability

This phase is the first one that **requires a physical device**, not just
the emulator — OEM battery-killing behavior, autostart settings, and real
reboot persistence can't be meaningfully tested on an emulator. This is
also the natural point to finally do the real Instagram check that's been
deferred since Phase 1 (emulator couldn't install it at all).

1. Onboarding flow: a first-run screen (shown once, before the main
   screen — track via a `DataStore` boolean, not a Room table) that
   explains *why* the two permissions are needed in plain language, before
   handing off to the existing "Open Settings" buttons from Phase 1's
   prompt 6. Don't duplicate the permission-granting logic — this prompt
   is framing/education around what already works, not new permission
   mechanics.
2. Foreground service + persistent notification: wrap the accessibility
   service's operation with a visible, low-priority notification
   ("TaskIntercept is watching for Instagram/YouTube" or similar) via a
   foreground `Service` pattern. This raises the process's priority to
   Android's OS scheduler, making it meaningfully less likely to be killed
   under memory pressure — purely a reliability measure, not a new
   feature the user interacts with.
3. OEM detection + guidance: read `Build.MANUFACTURER`, and for known
   aggressive OEMs (Xiaomi, Oppo, Vivo, Realme, and similar — common in
   the Indian Android market) show brand-specific instructions for
   enabling "Autostart" and disabling battery optimization, since these
   OEM skins kill background accessibility services more aggressively
   than stock Android. A simple `when` branch with a few hardcoded
   instruction strings is sufficient — no need for a general-purpose
   OEM-detection library for 3-4 brands.
4. Battery optimization exemption: add a button that launches
   `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this app
   specifically, alongside the existing two permission rows in the Setup
   section — same status-check-on-resume pattern as the overlay
   permission from Phase 1's prompt 6.
5. Reboot persistence: verify (and if needed, ensure via a
   `BOOT_COMPLETED` broadcast receiver) that the accessibility service
   resumes automatically after a device reboot without the user having to
   manually re-enable it. Accessibility services often restart
   automatically on stock Android, but this needs actual reboot-testing
   to confirm — don't assume it works.
6. Real-device test pass (below) — you running the app on a physical
   phone, not a code prompt. This is the big one for this phase.

## Phase 3 definition of done

1. Fresh install on a physical device shows the onboarding flow before
   the main screen, only once (reinstalling/reopening doesn't repeat it).
2. A persistent notification is visible while the service is active,
   without being obtrusive.
3. OEM-specific guidance appears correctly for your specific device brand
   (or a sensible generic fallback if the brand isn't in the hardcoded
   list).
4. Battery optimization exemption can be granted from within the app,
   status reflects correctly.
5. Reboot the physical device, confirm the accessibility service is still
   active afterward without manual re-enabling (or, if it isn't, that's
   the real finding this phase needs to surface — not a failure of the
   test, but useful information).
6. **Real Instagram check, finally**: install Instagram on the physical
   device, repeat the full Phase 1 + Phase 2 sequence against it (trigger,
   overlay with real tasks, both buttons, de-dup) — first genuine
   confirmation since Phase 1 marked this as "logically covered, not
   directly tested."
7. Leave the app running in the background for an extended period
   (ideally overnight) on the physical device with normal usage in
   between — confirm the service is still functioning the next time you
   open a monitored app, not silently killed.

### Phase 2 — Task system & overlay UX

1. Task history screen: `TaskDao` already stores every saved task as a
   separate Room row (autoincrement id, `createdAt`) — `getActiveTask()`
   just reads the most recent one. Add a simple list screen (or an
   expandable section on the main screen) showing past tasks, most recent
   first. No editing/deleting yet — just visibility into history that
   already silently exists in the DB.
2. Active-task actions: add the ability to mark the current active task
   "done" (clears it so `getActiveTask()` returns null / falls back to "no
   task set") and to edit it in place, rather than only being able to
   overwrite it by typing a brand-new one.
3. Overlay visual redesign: replace the plain near-opaque black background
   and default `Button` styling in `overlay_task_brief.xml` with an
   intentional look — this is the screen the user sees dozens of times a
   day, worth real design attention. Keep it a plain `View`/XML layout
   (not Compose, per the standing overlay exception) but make it feel
   designed: typography hierarchy (task text should dominate), clear
   visual distinction between the two buttons (e.g. "Return to Task" as
   the more prominent/primary action, "Continue Anyway" as visually
   secondary — nudging without forbidding, per the concept doc's "not a
   hard block" principle).
4. Countdown timer: per the original concept doc's "roughly 10 seconds"
   brief-screen framing, add a visible countdown on the overlay. Decide
   and implement one clear behavior for what happens at zero (e.g. nothing
   forced — it's just a visual pacing cue, not an auto-action — since
   auto-continuing OR auto-returning would both be paternalistic in
   different directions and the concept doc frames this as always a real
   choice, not a timed decision).
5. Main app UI polish: basic Material3 theming pass, spacing/typography
   cleanup on the task input screen now that it has more sections (Setup,
   task input, history). No app icon yet — that's explicitly deferred
   per the "Known environment notes" section below until polish is
   actually relevant, and this prompt is that moment if you want it, but
   optional.
6. Real-device test pass against Phase 2's definition of done (below) —
   you running the app, not a code prompt.

## Phase 2 definition of done

1. Set a task, complete it (or let it get replaced), confirm it appears
   in task history afterward.
2. Confirm the active task can be edited/cleared without only ever typing
   a brand new one over it.
3. Overlay looks intentionally designed, not like an unstyled placeholder
   — task text is the visual focus, the two buttons are visually
   distinguishable from each other.
4. Countdown is visible and behaves as decided in prompt 4 — no jarring
   forced action at zero.
5. Confirm the whole Phase 1 sequence (permissions, YouTube open →
   overlay → both button paths, de-dup) still works correctly after all
   of Phase 2's changes — regressions here matter more than new features
   working.

## Phase 2 extension — Priority-based tasks: COMPLETE

3-level priority (High/Medium/Low), overlay shows top 2-3 active tasks
ranked by priority. Implemented as one combined pass (all 3 code steps),
fully verified on-device across all 6 checklist items:

1. **v2→v3 migration**: confirmed clean upgrade over the existing install,
   old tasks defaulted to Medium, no crash, no data loss.
2. **Multi-priority ordering**: main screen and overlay both show the
   correct top-3, correctly ranked (High first via the CASE-based query
   ordering — plain alphabetical `ORDER BY priority` would have been wrong
   since "HIGH" < "LOW" < "MEDIUM" alphabetically; caught during drafting).
3. **Promotion**: marking the top task "Done" correctly promoted the next
   task into the top slot, both on the main screen and on the next overlay
   trigger.
4. **Empty state**: confirmed clean designed fallback ("Before you
   scroll…" / "No task set — open TaskIntercept to add one.") with no
   ghost rows when all tasks are done.
5. **Phase 1 regression**: fresh-open trigger, both buttons, and de-dup
   all confirmed still working after the service/overlay rewiring — the
   `TaskInterceptAccessibilityService` change was a single call-site edit
   (`getActiveTaskOnce()` → `getActiveTasksByPriorityOnce(3)`), everything
   else byte-identical to Phase 1's validated logic.
6. **Visual check**: priority dots (tinted ovals) render cleanly on the
   overlay.

Data layer: `Priority` enum stored as name String (Room-native, no
TypeConverter), `getActiveTasksByPriority`/`...Once(limit)` replacing the
old single-task queries entirely (removed rather than left dangling).
`updateTitle` became `updateTask(id, title, priority)` so edits don't
silently drop a priority change.

Overlay: fixed 3-slot layout (not dynamic/ListView) to preserve the
one-time-inflation performance property from Phase 1's timing work, with
per-slot typography for visual hierarchy (26sp/19sp/16sp, decreasing
opacity). Context line adapts: "You planned to:" for one task, "Your
plan:" for several.

**Known limitation, not a bug:** the overlay queries tasks at show-time,
not via a live-observed Flow — so if priorities change while the overlay
is already visible, it won't update mid-display. Not tested as an edge
case, unlikely to matter in practice (overlay is only visible for ~10
seconds), but worth remembering if it ever comes up.

```
app/src/main/
├── AndroidManifest.xml           # declares the accessibility service + overlay permission
├── java/com/akki/taskintercept/
│   ├── MainActivity.kt           # set active task, jump to the two permission screens
│   ├── data/
│   │   ├── Task.kt               # Room entity
│   │   ├── TaskDao.kt            # single-active-task queries
│   │   └── AppDatabase.kt        # Room singleton
│   └── intercept/
│       ├── TaskInterceptAccessibilityService.kt  # detects foreground app, triggers overlay
│       └── OverlayManager.kt     # WindowManager add/remove of the brief-screen view
├── res/layout/overlay_task_brief.xml   # overlay layout
├── res/xml/accessibility_service_config.xml   # event types + monitored packages
└── res/values/{strings,themes}.xml
```

## Current Phase Status

**Phase: 6 — Release build & direct distribution**
**State: Steps 1 and 2 complete. Remaining items pending.**

### Step 1 — Release signing config: COMPLETE

`app/build.gradle.kts` has a `signingConfigs { release { ... } }` block
reading credentials from `keystore.properties` (gitignored, project root).
`release` buildType wired to `signingConfigs.getByName("release")`.
`keystore.properties`, `*.keystore`, `*.jks` added to `.gitignore`.

**Keystore location:**
`C:\Users\vamsi\Desktop\Projects_2.0\taskintercept-release.keystore`
(one directory ABOVE the project root — outside the git repo entirely)

**Signing identity (record alongside keystore backup):**
`CN=Krishnavamsi Manukinda, OU=vamsi, O=vamsi, L=chinnavaduguru, ST=Andhra Pradesh, C=IN`
SHA-256: `dc0f857aff399ed1f5c649da5794f420b80cd047cdd124f18123e35f16e86a30`

`assembleRelease` → BUILD SUCCESSFUL (Gradle 8.7, AGP 8.5.2).
APK signature verified via `apksigner verify --print-certs` — certificate
identity confirmed matching the keytool-generated key.

**Keep safe (losing either = can never update for existing users):**
keystore file + passwords (in a password manager / secure note).

### Step 2 — TEMP logging removed: DONE

Two diagnostic log lines removed from `TaskInterceptAccessibilityService.kt`:
- **Removed (line 283–287 pre-edit):** unconditional per-event `Log.d` that
  logged every single `TYPE_WINDOW_STATE_CHANGED` event with pkg/class/type/
  time — fired on every event regardless of filtering, real overhead in a
  shipped app.
- **Removed (line 465–468 pre-edit):** `"Trigger→show latency: Nms"` log
  added during Phase 5's hardening pass — diagnostic-only.
- **Also removed:** `val triggerAt = SystemClock.elapsedRealtime()` —
  its only use was the latency log, so it became dead code.
- **No orphaned imports:** `SystemClock` still used elsewhere (settle
  stamping, debounce timing); `Log` still used by ~10 kept state-transition
  logs; `System.currentTimeMillis()` needed no import (java.lang).

Kept (meaningful, cheap, field-debuggable):
`"Detected target app"`, `"Residual event... ignoring"`, `"Home package
detected"`, `"Force-dismissing overlay"`, `"Navigation debounce active"`,
`"Keyguard locked"`, `"Stale trigger... skipping show"`, `"Keyguard locked
at show time"`, `"Screen off with overlay showing"`, `"GLOBAL_ACTION_HOME
declined"`, all `OverlayManager` warn/error logs.

### Step 3 — Auto Backup: COMPLETE

**Decision: disabled.** `android:allowBackup="false"` +
`android:fullBackupContent="@null"` added to `<application>` in
`AndroidManifest.xml`. Task data (Room DB, DataStore preferences) will
NOT be silently backed up to Google's cloud backup service.

Rationale: task lists are personal, ephemeral, and device-local by design.
Automatic cloud backup without an explicit user choice is the worse default
for this app. No `backup_rules.xml` or `data_extraction_rules.xml` existed
beforehand — no dangling references. `fullBackupContent="@null"` covers
API 23–30 where the XML-rule attribute is read independently of
`allowBackup` on some device skins. Play Store Data Safety form can now
accurately state "no data backed up to Google."

### Step 4 — App icon: COMPLETE

**App icon: done and confirmed on-device.** Custom launcher icon (target
ring + priority-colored dots + checkmark, tying into the app's existing
priority-dot visual language) generated via Android Studio's Image Asset
Studio, adaptive icon (foreground/background split). One real bug hit and
fixed: `AndroidManifest.xml`'s `<application>` tag had no `android:icon`
or `android:roundIcon` attribute at all (likely never added since Phase 1's
manual scaffold) — icon resources existed correctly but nothing referenced
them, so Android silently fell back to its default icon. Fixed by adding
both attributes.

**Also discovered during this:** Gradle's `installDebug` task was silently
failing to actually deploy on this device (ColorOS/CPH2109) despite
reporting `BUILD SUCCESSFUL` — direct `adb install -r <apk>` works
reliably where `gradle installDebug` doesn't. Use `adb install` directly
for this device going forward.

### Step 5 — Versioning: CONFIRMED

`versionCode = 1`, `versionName = "1.0"` — still the project defaults,
correct for the first real distributed release. No change needed.
Convention going forward: increment `versionCode` by 1 per release,
use semantic `versionName` (1.1, 1.2, 2.0 etc.) updated in
`app/build.gradle.kts` before each `assembleRelease`.

### Step 6 — Privacy notice: COMPLETE

`PRIVACY.md` created at the project root. Covers:
- What the Accessibility Service actually observes: ALL foreground app
  changes (package name + class name only, no screen content) — the
  honest broadened-scope description from Phase 1 p.10, not a narrower
  version that undersells the permission.
- `canRetrieveWindowContent="false"` in the XML config.
- Task data stored locally in Room only, never transmitted.
- `android:allowBackup="false"` — nothing backed up to Google.
- No analytics, no ads, no third-party SDKs beyond Jetpack.
- Permissions table explaining every declared permission and its purpose.
- Source code link note for technical readers.

### Step 7 — Minification: ENABLED, NOT YET DEVICE-TESTED

`isMinifyEnabled = true` and `isShrinkResources = true` set in the
`release` buildType in `app/build.gradle.kts`. `proguard-rules.pro`
intentionally left with no active rules — see below.

**R8 keep-rule analysis (why no explicit rules were needed):**
- **Room / `Task` entity fields:** Room uses KSP to generate
  `TaskDao_Impl` / `AppDatabase_Impl` at compile time. These generated
  classes reference every `@Entity` field directly by name in Kotlin/Java
  code — R8 sees them as normal static references, not string-based
  reflection, and keeps the fields automatically.
- **`Priority` enum:** `Priority.valueOf(string)` is called from the
  KSP-generated cursor-mapping code. R8 traces the static call site and
  keeps the enum constants.
- **`AppDatabase::class.java`** in `Room.databaseBuilder(...)`: a class
  literal, not a string lookup — R8 traces it as a direct reference.
- **Android components** (accessibility service, foreground service, boot
  receiver, main activity): kept automatically by AGP's built-in rules for
  manifest-declared components.
- **DataStore, Compose, Coroutines**: consumer ProGuard rules are bundled
  in each library's AAR and applied automatically.
- **No `Class.forName`, `getDeclaredMethod`, `getDeclaredField`, or
  `newInstance()` calls** anywhere in the project — confirmed by
  searching all `.kt` files. Zero results.

**⚠️ NOT YET DEVICE-TESTED.** R8 stripping issues manifest as runtime
crashes, not build failures. Before the first distributed release, do a
full regression pass with the minified release APK:
1. `assembleRelease` → install via `adb install -r app-release.apk`
2. Full Phase 1–4 sequence: permissions, task save, trigger, both buttons,
   de-dup, monitored-app picker, overlay duration, master switch
3. Task history and priority ordering
4. Check logcat for any R8/ClassNotFound/MethodNotFound errors

### Remaining Phase 6 items

- **GitHub Releases hosting:** package and host the signed APK (with
  `PRIVACY.md` linked from the release notes) for sideloaded distribution.
  Do the minification device-test pass first.

### Keytool walkthrough (run this yourself in a terminal)

**Full command — paste as-is, then answer the prompts:**
```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore ..\taskintercept-release.keystore -alias taskintercept -keyalg RSA -keysize 2048 -validity 10000
```
Run from the project root (`C:\Users\vamsi\Desktop\Projects_2.0\TaskIntercepter`)
so `..` resolves to `C:\Users\vamsi\Desktop\Projects_2.0\` — one level up,
outside the git repo.

**Prompts you'll see and what to enter:**

| Prompt | What it means | Recommendation |
|---|---|---|
| `Enter keystore password:` | Passphrase for the `.keystore` file itself | Choose a strong password; store in a password manager |
| `Re-enter new password:` | Confirmation | Same as above |
| `What is your first and last name?` | Goes into the signing certificate's CN field; not shown to users | Your name or the app name |
| `What is the name of your organizational unit?` | Certificate OU field; not shown to users | Can leave blank (press Enter) |
| `What is the name of your organization?` | Certificate O field | Your name, "Akki", or anything |
| `What is the name of your City or Locality?` | Certificate field | Your city |
| `What is the name of your State or Province?` | Certificate field | Your state |
| `What is the two-letter country code for this unit?` | ISO 3166 country code | `IN` for India |
| `Is CN=... correct?` | Confirmation of the fields you entered | Type `yes` and press Enter |
| `Enter key password for <taskintercept>:` | Per-key password (separate from the keystore password) | Press Enter to reuse the keystore password (simpler; both values go into `keystore.properties` anyway) |

After keytool exits, fill in `keystore.properties` in the project root with
the real values. The file has placeholder comments explaining each field.
Then do a test `assembleRelease` in Android Studio and confirm the APK in
`app/build/outputs/apk/release/` is signed (right-click → Analyze APK in
Android Studio, or `keytool -printcert -jarfile app-release.apk`).

---

**Phase: 5 — Hardening & resilience (stress-proofing pass)**
**State: code implemented, NOT built or device-verified.** This pass sits
ON TOP of Phase 4's still-unverified changes — so the pending device test
now covers both phases at once, and Phase 4's "default-behavior check
comes first" instruction still applies before anything Phase 5-specific.
No Gradle wrapper/SDK in the agent shell, so even `assembleDebug` hasn't
run — **first step is a clean build in Android Studio.**

Note: TEMP verbose per-event logging and the Phase 5 latency log were both
removed during Phase 6 prep (before the first `assembleRelease`).

What Phase 5 changed, all in `OverlayManager.kt` +
`TaskInterceptAccessibilityService.kt` (no schema, DataStore, manifest,
or accessibility-scope changes):

**OverlayManager hardening:**
- `addView` wrapped in try/catch: if `SYSTEM_ALERT_WINDOW` is revoked
  while the service runs, the show fails with a log line instead of
  crashing the whole accessibility service process.
- `removeView` catches `IllegalArgumentException` (view already detached
  — OS-side teardown), and `hideOverlay` clears `isShowing`/timer FIRST
  so a throw can't leave `isShowing=true` permanently suppressing all
  future overlays.
- `showOverlay` resyncs from `overlayView.isAttachedToWindow` if
  `isShowing` drifted out of sync — prevents `IllegalStateException`
  ("view already added") from double-adds.
- Lazy delegate exposed via `isInitialized()`: cleanup paths
  (`onDestroy`, screen-off) no longer inflate a never-shown view just to
  hide it.
- `warmUp()` pre-inflates the view + row lookups at `onServiceConnected`
  — first trigger's event→addView latency no longer includes inflation.
- `isOverlayShowing` read-only property for the service's dismissal
  logic.

**Service hardening:**
- **Double-collector fix (was a real latent bug):** `onServiceConnected`
  re-fires when the accessibility service is toggled off/on in OS
  settings, but `serviceScope` survives — each reconnect stacked another
  pair of DataStore collectors forever. Now tracked in `settingsJobs`
  and cancelled before relaunching. (This was exactly the "watch for
  doubled collectors" concern flagged in Phase 4's notes — confirmed
  real by inspection, fixed.)
- **Keyguard guard:** events arriving while `KeyguardManager
  .isKeyguardLocked` are fully ignored (no trigger, no tracker update) —
  no overlay over the lock screen, no de-dup corruption from
  behind-keyguard resumes. Unlocking into a monitored app processes its
  post-unlock window-state event normally.
- **Screen-off receiver** (runtime-registered `ACTION_SCREEN_OFF`, via
  `ContextCompat.registerReceiver` NOT_EXPORTED): dismisses a visible
  overlay and cancels any in-flight show when the display turns off.
  Unregistered in `onDestroy`.
- **Foreground-change dismissal:** any genuine package transition while
  the overlay is up hides it (and cancels a pending show) BEFORE the
  trigger check — covers incoming calls, system dialogs, and rapid
  app-to-app switching; a switch into another monitored app cleanly gets
  a fresh overlay/countdown rather than a leftover one.
- **Stale-show guard:** the trigger coroutine (`pendingShowJob`, tracked
  + cancellable) re-validates on Main right before `showOverlay` — if
  `lastForegroundPackage` moved on during the async DB/DataStore reads,
  or the keyguard came up, the show is skipped with a log line.
- **`onDestroy` orphan cleanup:** hides the overlay if the service dies
  while it's visible (OS-settings toggle-off, process teardown) —
  previously the window would outlive its owner.
- **Latency:** Room query + DataStore duration read now run concurrently
  (`async`/`await` — wait is max, not sum), and a
  `"Trigger→show latency: Nms"` log line measures event→addView per
  show for the device-test audit.

**Deliberately NOT addressed (reasoned, not forgotten):**
- Rotation/split-screen/PiP: the overlay is MATCH_PARENT with
  gravity-centered content and no config-dependent resources — the
  window resizes with the display; no Activity is involved so there's no
  recreation risk. Verify visually on-device, but no code change needed.
- Task-swipe survival: accessibility services don't live in the
  Activity task; swiping the app away doesn't kill the service (already
  the Phase 3 overnight-test territory). `START_STICKY` watchdog
  unchanged.
- Emergency/incoming calls: `TYPE_APPLICATION_OVERLAY` cannot draw over
  the system call UI by design; the new foreground-change dismissal
  additionally removes the overlay when the call surface takes over.

**Phase 5 device-test checklist (the pass that closes this phase — do
Phase 4's checklist first, then):**
1. Clean `assembleDebug` in Android Studio (DoD 1).
2. Rapid open/close/reopen of a monitored app ×10 — no duplicate
   overlays, no crash, `isShowing` never wedges (overlay still appears
   on later opens).
3. Lock the device with the overlay visible → overlay gone on wake;
   screen-on into the lock screen with a monitored app behind it → no
   overlay over keyguard; unlock into the app → clean state.
4. Revoke "Draw over other apps" while the service is running, open
   YouTube → no crash, log shows the caught failure; re-grant → works.
5. Toggle the accessibility service off/on in OS settings twice → logs
   show collectors NOT doubling; overlay still triggers; no orphaned
   overlay left from the toggle-off.
6. Rotate the device with the overlay visible → overlay fills the
   screen correctly, buttons still work.
7. Receive a call (or trigger any full-screen system surface) with the
   overlay up → overlay dismisses via the foreground-change path.
8. Grep logcat for `Trigger→show latency` — record typical ms for the
   audit.
9. Phase 1–3 regression: both buttons, de-dup, residual-event and
   transient-package scenarios all still correct (the event-handler
   guard ORDER changed — keyguard check now sits between the transient
   guard and the settle stamping — so the two Phase 3 bug scenarios
   specifically need re-confirmation).

**TEMP verbose per-event logging still in place** (now joined by the
latency log line, which should also be demoted/removed before Phase 6).

Phase 4 status below still stands — its device pass has not happened yet.

---

**Phase: 4 — Settings & configuration**
**State: code steps 1–4 implemented in one combined pass — NOT yet
device-verified.** Nothing in this phase should be trusted until the
on-device test pass (prompt 5 / definition of done) runs, and **step 1's
default-behavior check comes first**: confirm a build over the existing
install still intercepts Instagram + YouTube with zero configuration
before testing anything else, since the hardcoded `MONITORED_PACKAGES`
constant is gone entirely and everything now flows through DataStore.

What was implemented, per step:

1. **Monitored-app set → DataStore** (`data/SettingsPrefs.kt`, new file —
   `settings` preferences DataStore, separate from `onboarding`).
   `SettingsPrefs.monitoredPackages()` defaults to Instagram + YouTube
   when the key is absent; an explicitly-saved empty set stays empty
   (user unchecked everything — a valid state distinct from "never
   configured"). The service does NOT read the Flow per event (the
   callback is synchronous) — instead `onServiceConnected` launches a
   collector for the service's lifetime into a `@Volatile
   monitoredPackages` field, initialized to the default set so the gap
   before the first DataStore emission behaves identically to the old
   constant. Both `in MONITORED_PACKAGES` sites in `onAccessibilityEvent`
   (leave-detection stamping and the trigger check) now read this field;
   no other logic changed. Settings changes take effect on the next
   event, no service or app restart.
2. **App picker UI** (`AppPickerDialog.kt`, new file): AlertDialog listing
   launcher-intent apps (icon + label, sorted, loaded on IO, self
   excluded), checkbox per app, writes DataStore once on Save. Opened
   from a new "Interception" card on the main screen. Required a
   `<queries>` launcher-intent block in the manifest for Android 11+
   package visibility (deliberately not `QUERY_ALL_PACKAGES` — cleaner
   for Phase 6 Play review).
3. **Overlay duration**: preset chips 5/10/15/20s in the Interception
   card → `SettingsPrefs.overlayDurationSeconds()` (default 10).
   `OverlayManager.showOverlay(tasks, countdownSeconds)` now takes the
   duration as a parameter; the service reads it with a one-shot
   `.first()` inside the existing trigger coroutine (alongside the Room
   query, off the main thread), so each overlay uses the value current
   at show time. `COUNTDOWN_SECONDS` constant removed.
4. **Master switch**: `SettingsPrefs.interceptionEnabled()` (default
   true), same collector-into-`@Volatile`-field pattern as step 1. The
   check is the FIRST guard in `onAccessibilityEvent` after the
   event-type/null-package unwrapping — before the own-package guard,
   the transient-system-package guard, the settle stamping, the
   residual-noise check, the trigger, and the tracker update. Disabled
   means truly inert: no state is touched at all, so the tracker
   contains pre-disable values when re-enabled (already in a monitored
   app at re-enable time → no trigger until a fresh open — deliberate).

Also touched: `watchdog_notification_text` and the accessibility service
description string no longer hardcode "Instagram and YouTube" (they say
"monitored apps" / "by default, configurable"); the comment in
`accessibility_service_config.xml` now points at SettingsPrefs instead of
the deleted constant.

**Device-test focus beyond the phase's definition of done:** the
`onServiceConnected` collectors are new territory — verify the service
still intercepts correctly after the accessibility service is toggled
off/on in OS settings (collectors are relaunched on reconnect; the old
serviceScope survives, so watch for doubled collectors in logs — harmless
if both write the same value, but confirm) and after a device reboot.

Phases 1, 2 (+ priority-tasks extension), and 3 are complete — records
below, kept for history/context.

---

**Phase 3 — Permissions, onboarding, reliability: COMPLETE.** All 5 steps
implemented in one combined pass, full on-device checklist confirmed:
onboarding shows once on genuine first-run and correctly persists past
reopening; foreground notification visible and correctly low-priority;
OEM guidance card correctly identifies device brand; battery optimization
exemption row grants and reflects status correctly; reboot persistence
confirmed. Two significant de-dup bugs were found and fixed during this
phase's physical-device testing — full technical record below, since they
took real diagnostic work and are worth remembering if the mechanism is
ever touched again.

**Important finding during onboarding testing — Android Auto Backup for
Apps.** `pm clear` failed outright on this device (`SecurityException`,
some OEM skins block this over ADB) — worked around via
`adb shell bmgr enable false` before uninstall/reinstall. But the more
important discovery: **a plain uninstall + reinstall did NOT produce a
clean first-run state** — all task history was still present. This is
Android's Auto Backup for Apps silently restoring the app's data
(including the Room DB) from Google's backup service, since nothing in
the manifest currently opts out of it. Confirmed by disabling the backup
transport (`bmgr enable false`) before reinstalling, which then did
produce a genuinely empty state.

**This is a real product decision to make deliberately, not just a
testing inconvenience — flagged for a future prompt, not yet decided:**
should TaskIntercept's task data be cloud-backed-up by default? Arguments
both ways (convenient across device changes, vs. personal task data going
to Google's backup silently without an explicit user choice).
`android:allowBackup="false"` in the manifest disables this entirely, or
`android:fullBackupContent` can scope it more precisely (e.g. exclude the
Room DB specifically while allowing other backups). Decide and implement
this before Phase 6 at the latest, since Play Store's Data Safety form
will need to accurately describe whatever the actual behavior is.

---

**Major bug found and fixed during Phase 3 physical-device testing — the
residual background-event problem.** Worth a full record since it took
several iterations to correctly diagnose:

**Symptom:** the overlay would sometimes reappear seconds after being
dismissed, with no user interaction — both after "Return to Task" and
after a genuine home-gesture exit while using "Continue Anyway."

**Root cause (confirmed via extensive logcat capture with `className`
logging):** monitored apps (confirmed for YouTube, likely general) emit
residual `TYPE_WINDOW_STATE_CHANGED` accessibility events ~0.3-0.5s after
being backgrounded, still tagged with their own package name, even though
no real foreground transition happened. The de-dup tracker
(`lastForegroundPackage`) has no way to distinguish these from a genuine
reopen using package identity alone.

A secondary discovery during diagnosis: **`Intent(ACTION_MAIN,
CATEGORY_HOME)` navigation (used by "Return to Task") does not reliably
generate a `launcher` accessibility event** — unlike a real user home
gesture, which does. This meant the tracker could get stuck holding the
just-left app's package indefinitely after "Return to Task," silently
suppressing the next genuine reopen.

**Final fix (general, not path-specific):**
- `settledFromPackage: String?` + `settledAt: Long` (via
  `SystemClock.elapsedRealtime()`, not `currentTimeMillis()` — monotonic,
  immune to wall-clock jumps).
- Whenever a genuine transition is detected (`packageName !=
  lastForegroundPackage` AND the old value was a monitored package),
  stamp `settledFromPackage`/`settledAt` **before** updating the tracker.
- The "Return to Task" callback does the same stamping explicitly (since
  it can't rely on a real transition event arriving), then still resets
  `lastForegroundPackage = ""` as a separate, still-necessary measure.
- New check, as an early `return` (not just a trigger-condition clause —
  deliberately, so residual events can't corrupt the tracker on their way
  through): if `packageName == settledFromPackage` and less than
  `RESIDUAL_SETTLE_MS` (2000ms) has passed since `settledAt`, ignore the
  event entirely — no trigger, no tracker update.

**Verified on-device, all four cases, in one clean log capture:**
residual event after "Return to Task" ignored; residual event after a
real home-gesture exit ignored (previously wasn't — this was the bug that
led to generalizing the fix); genuine reopen after the 2s window still
triggers correctly in both cases; direct YouTube→Instagram app switch
(no home screen in between) triggers Instagram immediately, unaffected by
YouTube's settling window.

**Known accepted trade-off:** reopening the *same* app within 2 seconds of
leaving it (via either path) is treated as residual noise and suppressed.
Inherent to any time-based filter; considered acceptable since the
practical scenarios this affects are edge cases, not the core flows.

**TEMP verbose per-event logging is still in the codebase** — remove it
before Phase 6 (or earlier, once you're confident no more de-dup issues
will surface). It was invaluable for this diagnosis and is currently
harmless (just log noise), but shouldn't ship.

**Second de-dup bug found and fixed — transient system package pollution.**
Distinct root cause from the residual-event bug above, surfaced by the
Instagram profile-navigation case specifically:

**Symptom:** navigating within Instagram (e.g. opening a creator's profile
from a reel, then returning to the reels feed) sometimes caused the
overlay to incorrectly reappear, even though the user never left
Instagram.

**Root cause:** transient system-level packages — confirmed
`com.android.systemui` (status bar/modal transition animations) and
`com.google.android.googlequicksearchbox` — fire brief
`TYPE_WINDOW_STATE_CHANGED` events during in-app transitions within a
monitored app. The tracker was treating these as genuine "user left the
app" transitions, polluting `lastForegroundPackage`. A later, still-in-app
screen change (different Activity, same Instagram package) would then read
as `instagram != systemui` — a false fresh-open.

**Fix:** `TRANSIENT_SYSTEM_PACKAGES` set (currently just the two confirmed
above), checked as an early return immediately after the own-package
guard — before leave-detection stamping, the residual-noise check, the
trigger check, or the tracker update. These events are now fully invisible
to all state, not just excluded from triggering.

**Confirmed still correct (reasoned through, not yet device-tested since
it requires a second real app and a notification):** switching apps via
notification tap (e.g. YouTube → swipe shade → tap a WhatsApp notification)
still works, since the destination app's own window-state event — not
SystemUI's — is what the tracker actually keys on.

**Known blind spot, flagged but not yet hit:** if a device's home-gesture
transition itself reports as `com.google.android.googlequicksearchbox`
(rather than a `launcher`-named package) instead of just flashing briefly
alongside a real launcher event, that transition would be incorrectly
swallowed by this fix, silently suppressing the next reopen. Not observed
on this device so far — Pixel-family devices typically report the actual
home surface under a different package. If this ever surfaces, the fix
would need to distinguish by `event.className` (already being logged)
rather than blanket-excluding the package. Worth remembering if testing
ever moves to a different physical device, especially other OEM skins
being covered in this same phase's OEM-guidance work.

**Verified on-device:** repeated Instagram profile-open/close cycles (twce
in the same session) produced zero spurious re-triggers, with the
transient-package events still visible in logs (proving the guard doesn't
just hide the problem by suppressing logging) but correctly inert.

**Both de-dup bugs together mean the mechanism has now been stress-tested
against:** button-tap exit, real home-gesture exit, direct app-to-app
switching, and in-app sub-navigation — a meaningfully more thorough
validation than Phase 1's original emulator-only testing achieved. Worth
remembering that physical-device testing surfaced real bugs the emulator
never could — a good argument for not skipping it in any future phase that
touches this mechanism again.

---

**Phase 2 (+ priority-tasks extension): COMPLETE.** Full record above
under "Phase 2 extension — Priority-based tasks: COMPLETE" and the
original Phase 2 prompts. History, done/edit, overlay redesign, countdown,
main UI polish, and multi-task priority ranking all implemented and
verified on-device.

---

**Phase 1 — Interception core: COMPLETE.** All 11 prompts done, all 9
definition-of-done checks confirmed on-device against YouTube (Pixel 6a
emulator, API 35, Google Play image). Full sequence verified: permissions
granted correctly, task set and displayed accurately on the overlay,
overlay timing acceptable (brief splash flash, not full content, not
blocking), both buttons (Return to Task → home, Continue Anyway → reveals
app) work correctly, de-dup logic correctly suppresses same-session
re-triggers while still re-triggering on genuine fresh opens, negative
control (unmonitored apps) confirmed no false triggers.

**Instagram: not emulator-tested, logically covered.** Play Store failed
to install Instagram on this emulator (`Can't install Instagram` — a known
category of issue, likely Play Integrity/device-attestation checks the
emulator fails, not specific to this project). The detection/overlay code
treats `com.instagram.android` and `com.google.android.youtube` identically
— same `MONITORED_PACKAGES` check, no per-app special-casing anywhere in
`TaskInterceptAccessibilityService` or `OverlayManager` — so YouTube's full
pass is strong evidence the mechanism works for Instagram too, but this is
inference, not direct confirmation. **Do a real Instagram check on a
physical device during Phase 3**, which already requires physical-device
testing for OEM battery/autostart behavior anyway — no need to block Phase
1 completion on this.

**Full history of prompts, in actual execution order:** 1, 2, 3, 4, 5, 6,
7, 7b (optimization), 9 (before 8, since 7's overlay had no dismiss path),
8, 10, 11. All confirmed working; two real bugs were caught and fixed
along the way (a stray import shadowing `RowScope.weight` in prompt 6; a
main-thread dispatch bug in prompt 8 where a Room query on IO tried to
touch `WindowManager`/`TextView` without hopping back to `Dispatchers.Main`).
One necessary architecture change: prompt 10 required removing
`android:packageNames` from `accessibility_service_config.xml` and moving
package filtering into code, since de-dup logic needs visibility into
*leaving* a monitored app, which OS-level config filtering hides. This
broadens what the service technically observes (all foreground-app
changes, not just the two monitored ones) — privacy posture is still
accurate (`canRetrieveWindowContent` false, no content ever read) but
**Phase 6's privacy policy and Play Store accessibility declaration must
describe this scope accurately**, not the narrower original description.

**Known cosmetic warnings (non-blocking, carry into Phase 2):** AGP 8.5.2
warns it was only tested to compileSdk 34 (35 works in practice); Java
compileOptions/kotlinOptions still on source/target 8, which JDK 21 flags
as obsolete — bump to 17 when convenient. An orphaned system image
(`android-37.0` CinnamonBun, ~2GB) may still be on disk from early emulator
setup attempts — safe to delete via SDK Manager, not urgent.

## Known environment notes

- This project cannot be built or run inside a sandboxed/CI shell — it
  needs the Android Gradle Plugin, Android SDK platform tools, and (for
  AccessibilityService testing specifically) a real device or an emulator
  image with Google Play Services. Open in Android Studio to build/test.
- No launcher icon asset exists yet — add one when app polish becomes
  relevant, not before.