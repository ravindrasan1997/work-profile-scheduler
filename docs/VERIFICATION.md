==========================================
 Work Profile Scheduler — On-device verification
 Target: Samsung Galaxy A56 (SM-A566E) / One UI 8.5 / Android 16
==========================================

Verified end-to-end on a real device with a Microsoft Intune-managed work profile
(unified device/work lock), which is left fully intact.

## Silent pause / resume

With the one-time `MODIFY_QUIET_MODE` grant in place (granted=true, user 0), the app
pauses and resumes the work profile directly through
`UserManager.requestQuietModeEnabled()`:
 - Pause: silent in any lock state.
 - Resume while unlocked: silent and instant.

No screen wake, no UI, and no change to profile ownership — MDM enrolment and compliance
are unaffected.

## Deferred resume while locked

A work profile's apps live in credential-encrypted (CE) storage whose keys exist only
after the device is unlocked, so a resume cannot complete silently while the phone is
locked. Rather than queue a credential prompt, the app resumes with
`QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED` (API 30+) — which no-ops while
locked — and defers: `ToggleReceiver` records the pending resume (with a 6h deadline) and
arms `ResumeRetryScheduler`; `ResumeReconcileReceiver` completes it on the next unlock via
`ACTION_USER_PRESENT` plus a battery-light retry alarm. A pause supersedes any pending
resume, and `MainScreen` also reconciles on app open.

Confirmed on device: a resume armed while the screen was locked completes by itself within
seconds of a normal unlock (single passcode / biometric), with no extra work-profile
passcode prompt.

## Scheduling

Two exact `RTC_WAKEUP` `AlarmManager` triggers per selected day (one resume, one pause).
The schedule is re-armed on `BOOT_COMPLETED`, time / timezone change, and app update.
Verify from a PC:
 - `adb shell dumpsys alarm | grep com.worksched`

## Visible fallback

Without the grant, the app automates the Quick Settings "Work apps" tile through an
accessibility service, and upgrades to the silent path automatically once the permission
is present.

## Resume at a place (optional location gate)

Only the *resume* is gated (pause and the manual buttons are never gated; off by default, so
existing schedules behave unchanged):
 - At the scheduled resume, inside the chosen radius → resume now.
 - Off-site → no resume; an event-driven proximity alert (`LocationManager.addProximityAlert`) is
   armed, expiring at that day's pause time so monitoring is bounded — no polling.
 - On arrival the geofence fires and the profile resumes, composing with the deferred resume above
   when the phone is locked on arrival.
 - The scheduled pause clears any pending arrival-resume and disarms the alert.

The chosen spot is reverse-geocoded to a readable address via the system `Geocoder`. No Google Play
Services, no Maps SDK, no API key.

Confirmed on device: an inside-radius resume turns the profile on immediately; an off-site resume
arms the proximity alert (auto-expiry at the pause time) and does not turn it on; the scheduled
pause disarms it; the location shows as its street/area address. The proximity alert is registered
and removed by the OS; the boundary-crossing ENTER is delivered by the platform on arrival.

## Build / install
 - APK: WorkProfileScheduler.apk, versionName=1.7.0, ~2.5 MB, minified (R8 + resource
   shrink), debug-signed.
 - Installs as an in-place update; the `MODIFY_QUIET_MODE` grant persists across updates
   (granted=true, user 0).

## Test-harness note (not an app limitation)
 - This A56 drops ADB-over-USB whenever the screen locks, which blocks automated
   locked-screen log capture. Locked-screen behaviour is therefore validated by on-device
   execution plus direct observation rather than live logs.

## RESULT: PASS (verified end-to-end on a real device)
 - Pause: silent, any lock state.
 - Resume while unlocked: silent, instant.
 - Resume while locked: defers silently and auto-completes on the next normal unlock —
   no second passcode.
 - Resume at a place (optional): inside → resume now; off-site → arms an arrival geofence that
   expires at the pause time; pause → disarms it. Off by default.
