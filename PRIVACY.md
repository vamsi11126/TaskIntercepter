# TaskIntercept — Privacy Notice

**For users sideloading this APK from GitHub.**
Last updated: September 2026 · v1.0

---

## What this app does and what it observes

TaskIntercept uses Android's **Accessibility Service** to detect when you
open a monitored app (Instagram, YouTube, or any app you add via the
picker), and briefly shows your planned task before the app's UI loads.

### What the Accessibility Service actually sees

The service listens for `TYPE_WINDOW_STATE_CHANGED` events — a system
broadcast that fires whenever the foreground app changes. Each event
carries the **package name** and **activity class name** of the newly
foregrounded app. That is the full extent of what the service reads.

**Important:** to do its job correctly — specifically to detect when you
*leave* a monitored app and reset its de-duplication state — the service
must observe **all foreground app changes on your device**, not only
transitions into the two monitored apps. In practice this means it sees
the package name of every app you switch to: your launcher, your messages
app, your camera, everything.

What it does **not** see:

- Screen content, text, images, or UI elements of any kind
- Passwords, form inputs, clipboard contents, or keystrokes
- Notifications, contacts, location, or any other system data

`canRetrieveWindowContent` is set to `false` in the Accessibility Service
configuration and is never changed — this is enforced in the XML config
file, not just a runtime choice.

The service acts on this stream only to check whether the incoming package
name is in your monitored-apps list. For non-monitored apps, the package
name is read, compared against the list, and discarded. No record of it is
kept anywhere.

---

## Task data

Your tasks (titles, priorities, timestamps, done status) are stored in a
**local Room (SQLite) database on your device only**.

- No task data is transmitted to any server, ever.
- No network permission is declared or requested.
- `android:allowBackup="false"` is set in the manifest — nothing is
  uploaded to Google's Auto Backup cloud service.
- Task data does not leave your device by any path this app controls.

---

## What this app does not do

- **No analytics.** No crash reporting, usage tracking, or telemetry of
  any kind. There is no analytics SDK.
- **No ads.** No ad SDK, no ad network, no tracking identifiers.
- **No third-party SDKs** beyond the Android Jetpack libraries
  (Room, DataStore, Compose, Lifecycle) — all Google/AOSP open-source
  libraries with no network activity of their own in this context.
- **No accounts, sign-in, or cloud sync.** Everything is local.
- **No internet permission.** The app does not request
  `INTERNET` in its manifest and cannot make network connections.

---

## Permissions used and why

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Required to receive foreground-app change events — the core mechanism |
| `SYSTEM_ALERT_WINDOW` | Required to draw the task-brief overlay over other apps via WindowManager |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the accessibility service process alive under memory pressure via a visible notification |
| `POST_NOTIFICATIONS` | Needed on Android 13+ to show the above persistent notification |
| `RECEIVE_BOOT_COMPLETED` | Allows the service to restart automatically after a device reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets the user exempt this app from Doze/battery-kill so the service stays running overnight |

---

## Source code

This app is open-source. The full source is available at the GitHub
repository linked from the release page. The Accessibility Service
implementation is in `TaskInterceptAccessibilityService.kt`; the
accessibility config (with `canRetrieveWindowContent="false"`) is in
`res/xml/accessibility_service_config.xml`.
