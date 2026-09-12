# TaskIntercept

**Catch yourself before the scroll.**

TaskIntercept is an Android app that intercepts opening Instagram or
YouTube and shows you what you *planned* to do first — before the app's
own interface even loads. You get a real moment of choice: return to your
task, or continue anyway. Nothing is blocked. It just makes the automatic
choice conscious again.

## Why

Plans get made during moments of intention — usually when you're not
free. Then free time arrives, and the default behavior is to open a
social app instead. The plan and the moment of temptation live in
completely separate mental spaces, with nothing connecting them.
TaskIntercept reconnects them, right at the exact moment the choice is
currently being made without thinking.

## How it works

- Set what you're planning to do, with a priority (High / Medium / Low).
  You can track more than one thing at a time.
- Open Instagram or YouTube — TaskIntercept detects it via Android's
  Accessibility API and shows a brief overlay with your top task(s)
  before the app's own UI is visible.
- A 10-second countdown gives you a pacing cue — nothing happens
  automatically at zero. You choose:
  - **Return to Task** — sends you home
  - **Continue Anyway** — lets the app through, no shame, no lockout
- Which apps get monitored, and the overlay's duration, are both
  configurable from the app's Settings.

## Screenshots

*(add a few here once you have them — the overlay in action and the main
task screen are the two worth showing)*

## Installing

TaskIntercept isn't on any app store — it's distributed directly via
[GitHub Releases](../../releases). Download the latest `.apk` from the
[Releases page](../../releases), then:

1. Open the downloaded `.apk` on your Android phone
2. Allow "Install unknown apps" for whichever app you used to open it, if
   prompted
3. You'll likely see a **Google Play Protect** warning during install —
   this is expected for any app requesting Accessibility + "Draw over
   other apps" permissions outside the Play Store, not a sign anything is
   wrong. See [Permissions](#permissions) below for exactly why this app
   needs them.
4. After installing, open the app and follow the onboarding — it walks
   you through granting both permissions with an explanation of why each
   is needed.

## Permissions

| Permission | Why |
|---|---|
| **Accessibility Service** | The only way for an Android app to detect when another app opens, before its UI loads. TaskIntercept reads *window-state change events only* — which app just came to the foreground — never screen content. `canRetrieveWindowContent` is explicitly disabled in the app's accessibility config. |
| **Display over other apps** | Needed to draw the brief overlay screen on top of the app you just opened. |
| **Ignore battery optimizations** (optional) | Some phone manufacturers aggressively kill background accessibility services to save battery. Granting this exemption helps TaskIntercept keep working reliably; the app works without it too, just less consistently on some devices. |

Full detail on exactly what data is read, stored, and (not) transmitted
is in [`PRIVACY.md`](./PRIVACY.md).

## Privacy, in short

- All task data stays on your device — nothing is sent anywhere, ever.
- No analytics, no ads, no third-party SDKs.
- Auto Backup is disabled — your data is never uploaded to Google's
  backup service.
- The accessibility service sees the package name of whatever app is in
  the foreground (needed to detect app switches correctly) but never
  reads what's on screen.

See [`PRIVACY.md`](./PRIVACY.md) for the full, honest breakdown.

## Building from source

Requirements: Android Studio, JDK 17+ (via Android Studio's bundled JBR),
Android SDK with platform 35 installed.

```bash
git clone https://github.com/<your-username>/TaskIntercept.git
cd TaskIntercept
./gradlew assembleDebug
```

The release build requires a signing key, which isn't included in this
repo for obvious reasons — generate your own via `keytool` and configure
`keystore.properties` (see `app/build.gradle.kts` for the expected keys)
if you want to build a signed release APK yourself.

## Known limitations

- Currently monitors apps you explicitly add via the in-app picker
  (Instagram and YouTube by default) — not a system-wide "block
  everything" tool.
- The overlay may show a brief flash of the target app's own splash
  screen before it appears, on some devices — this is a characteristic
  of how Android delivers accessibility events, not something the app
  can fully eliminate.
- Tested primarily on stock-ish Android and ColorOS/Realme UI. Other
  manufacturer skins (MIUI, One UI, etc.) may behave differently,
  especially around background process killing — the in-app Settings
  screen includes manufacturer-specific guidance where possible.

## Feedback & issues

Found a bug, or have a suggestion? Please open an
[Issue](../../issues) — real usage feedback is genuinely how this gets
better.

## License

MIT — see [`LICENSE`](./LICENSE).