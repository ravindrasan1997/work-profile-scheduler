# Work Profile Scheduler

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3ddc84.svg)](https://www.android.com/)
[![minSdk](https://img.shields.io/badge/minSdk-29-orange.svg)](app/build.gradle.kts)
[![Build](https://github.com/ravindrasan1997/work-profile-scheduler/actions/workflows/build.yml/badge.svg)](../../actions)
[![Release](https://img.shields.io/github/v/release/ravindrasan1997/work-profile-scheduler?color=blue&label=release)](../../releases/latest)

A small, single-screen Android app that automatically **pauses and resumes your work profile** on a schedule you choose — and can do it **completely silently** after a one-time ADB setup. No root, no Shizuku, no continuous background service.

> Built and tested on a Samsung Galaxy A56 (One UI 8.5, Android 16) with a Microsoft Intune-managed work profile, which is left fully intact.

## Why this exists

Stock Android already has a built-in work-profile scheduler: **Settings → Digital Wellbeing & parental controls → Work profile schedule** lets you set work hours and the OS pauses/resumes the profile automatically — silently, natively, no extra app.

The problem: **some OEMs remove this feature from their version of Digital Wellbeing.** Samsung's One UI, in particular, ships its own Digital Wellbeing without the work-profile scheduler, and Samsung has stated there are no immediate plans to add it. So on a lot of very popular phones, a feature that exists in AOSP simply isn't there — and there's no first-party way to schedule when work apps turn off in the evening and back on in the morning.

This app fills that gap: it reproduces the missing scheduler on devices where the OEM stripped it out, while staying as close as possible to how the system itself does it (the same `requestQuietModeEnabled` API the OS uses), without root or a persistent background service.

## Features

- **Custom schedule** — pick which days of the week it runs (any combination, default Mon–Fri) and one *Resume at* + one *Pause at* time. Default: resume 10:00, pause 19:00.
- **Silent operation** — after a one-time `adb` grant, pause/resume happen via a direct system API in ~50 ms with no UI and no screen wake.
- **Manual toggle** — *Resume now* / *Pause now* buttons.
- **Deferred resume** — a resume that fires while the phone is locked completes automatically on your next unlock, with no extra passcode prompt.
- **Reboot-safe** — schedule re-armed on `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, and app update.
- **Battery-friendly** — exact `AlarmManager` triggers only; no foreground service, near-zero idle cost.
- **In-app setup** — a **Setup & permissions** sheet shows live status for silent mode, the accessibility fallback, exact alarms, and battery optimization, with the one-time ADB command ready to copy.
- **Material 3 UI** — Jetpack Compose, dynamic color, edge-to-edge.

## How it works

Pausing/resuming a work profile uses the system API `UserManager.requestQuietModeEnabled()`, which requires the privileged `MODIFY_QUIET_MODE` permission. On Android that permission carries the `development` protection flag, so — like `WRITE_SECURE_SETTINGS` used by apps such as Private DNS Toggle — it **can be granted to an ordinary app with a one-time ADB command**:

```bash
adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE
```

Once granted (the grant survives reboots; only a reinstall or manual revoke clears it), the app toggles the profile directly and silently. This holds a permission in the personal profile and toggles the work profile across the profile boundary — it **never changes profile ownership**, so MDM enrolment (e.g. Intune) and compliance are unaffected.

**Fallback without the grant:** if the permission isn't granted, the app still works by automating the Quick Settings "Work apps" tile through an `AccessibilityService` (this briefly opens the panel / wakes the screen). Enable it from **⋮ → Setup & permissions**. The app upgrades itself to the silent path automatically once the permission is present.

**Deferred resume (locked screen):** a work profile's apps live in credential-encrypted storage whose keys only exist after the device is unlocked, so a *resume* cannot complete silently while locked. Instead of queuing a credential prompt, the app defers the resume and re-issues it on the next unlock (via `USER_PRESENT` plus a battery-light retry alarm), completing silently after your normal unlock. This assumes the work profile shares the device lock (unified lock — the common setup).

## Requirements

- Android 10 (API 29) or newer; built against API 35, runs on Android 16.
- A provisioned **work profile** on the device.
- A PC with `adb` for the one-time silent-mode grant (optional but recommended).

## Install

1. Download `WorkProfileScheduler.apk` from the [Releases](../../releases) page.
2. Install it:
   ```bash
   adb install -r WorkProfileScheduler.apk
   ```
3. (Recommended) Enable silent mode — one time, persists across reboots:
   ```bash
   adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE
   ```
4. Open **⋮ → Setup & permissions** in the app and allow **exact alarms** (needed for on-time firing); the same sheet also offers a battery-optimization exemption for reliable scheduling.

If you skip step 3, open **⋮ → Setup & permissions** and enable the **Work Profile Toggler** accessibility service instead.

## Usage

- The hero card shows the current state — **Work apps are on** or **Work apps are paused** — and the next scheduled pause or resume.
- Use **Resume now** / **Pause now** for an immediate toggle; each button is disabled when the profile is already in that state.
- Under **Repeat on**, tap the day chips (Mon–Sun) to choose which days the schedule runs. Default is Mon–Fri; deselecting all turns scheduling off (manual buttons still work).
- Under **Hours**, tap the **Resume at** / **Pause at** tiles to pick times with the Material 3 clock dial, then **Save schedule**.
- Open **⋮ → Setup & permissions** for live status of silent mode, the accessibility fallback, exact alarms, and battery optimization — each with its action and a **Re-check**. An inline banner appears on the main screen only when something needs attention. **How it works** and **About** are in the same **⋮** menu.

Verify the schedule from a PC:
```bash
adb shell dumpsys alarm | grep com.worksched   # two RTC_WAKEUP triggers per selected day
```

## Build from source

```bash
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

The APK published on Releases is the minified release build:

```bash
./gradlew :app:assembleRelease
# minified + resource-shrunk, debug-signed APK (~2.2 MB) at
# app/build/outputs/apk/release/app-release.apk
```

Requirements: JDK 17, Android SDK with `platforms;android-35` and `build-tools;36.0.0`. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or create a `local.properties` with `sdk.dir=...`.

A convenience script `build.sh` generates the Gradle wrapper (gradle 8.10.2) and builds; it passes `--no-validate-url` to the wrapper task because some networks/CDNs fail Gradle's distribution-URL probe even when the download works.

## Architecture

```
app/src/main/java/com/worksched/
├── WorkSchedApp.kt                   Application: notification channel + re-arm on cold start
├── MainActivity.kt                   Single-screen entry; edge-to-edge; TEST_FIRE debug hook
├── ui/MainScreen.kt                  Header + ⋮ menu, hero card, manual toggles, schedule, setup sheet + dialogs
├── ui/theme/Theme.kt                 Material 3 dynamic colour
├── data/Schedule.kt                  resume/pause times + selected days model
├── data/ScheduleStore.kt             DataStore<Preferences> (schedule + pending-resume state)
├── alarm/AlarmScheduler.kt           exact PendingIntents (2 per selected day); locale-safe next scan
├── alarm/ToggleReceiver.kt           Alarm fire → silent API (or visible fallback) + re-arm
├── alarm/ToggleActivity.kt           Screen-wake activity (visible fallback path only)
├── alarm/ResumeRetryScheduler.kt     Inexact allow-while-idle retry for a deferred resume
├── boot/BootReceiver.kt              Re-arm alarms on boot / time change / app update
├── profile/WorkProfileDetector.kt    Finds the work profile via UserManager.userProfiles
├── profile/ProfileChangeReceiver.kt  Re-arm when a managed profile is added/removed
├── profile/QuietModeBackend.kt       Silent backend: requestQuietModeEnabled() + cred-not-required flag
├── profile/ResumeReconcileReceiver.kt USER_PRESENT + retry → completes a deferred resume on unlock
├── profile/WorkProfileToggler.kt     Dispatcher: silent preferred, accessibility fallback
└── service/WorkProfileA11yService.kt  Fallback: Quick Settings "Work apps" tile gesture
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| **Setup & permissions** still shows silent mode as not set up after the grant | App cached old state, or grant hit the wrong user | Tap **Re-check**; confirm `dumpsys package com.worksched \| grep MODIFY_QUIET_MODE` shows `granted=true` for user 0 |
| `SecurityException` on toggle | Permission not actually granted | Re-run the `pm grant`; install the APK first (grant fails if the package is absent) |
| Resume shows a passcode prompt | Work profile uses a *separate* work lock (not unified) | Inherent to that setup; pause stays silent. With a unified lock, resume defers and completes silently on unlock |
| Grant gone after reinstall/OS update | Development-flag grants clear on package reinstall | Re-run the one `pm grant` line |
| Alarms drift on time | OEM battery optimization | Add the app to *Settings → Battery → Background usage → Allowed* |

A live verification log is in [`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Limitations & scope

- **Not distributable via Google Play** — it declares a privileged permission and relies on an ADB grant / accessibility automation, which Play policy doesn't allow. Distribution is via the APK in Releases.
- **Debug-signed** — releases use the Android debug key. A proper release keystore is a future addition.
- **Silent mode requires the ADB grant**; without it the app falls back to the visible accessibility path.
- **Deferred resume assumes a unified device/work lock** (the common Intune setup).

## Roadmap

- Automatic APK attach to GitHub Releases on tag.
- Optional richer scheduling (per-day times, weekend inclusion).
- F-Droid metadata for libre distribution.
- Optional release signing via CI secrets.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Ravindra.

## Disclaimer

Provided **as is**, without warranty of any kind. Not affiliated with or endorsed by Google, Samsung, or Microsoft. "Android", "Samsung", "One UI", and "Intune" are trademarks of their respective owners. Use at your own risk.
