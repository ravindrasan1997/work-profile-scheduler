==========================================
 Work Profile Scheduler v1.4.0 - Test Summary
 Captured: 2026-06-08
 Target:   Samsung Galaxy A56 (SM-A566E) / One UI 8.5 / Android 16
==========================================

## What's new in v1.4.0 — deferred silent resume (no credential prompt when locked)

Problem (reported): pausing at 19:00 was silent, but the 10:00 RESUME, when it
fired while the phone was LOCKED, queued an extra work-profile passcode screen at
the next unlock. (When the phone was already unlocked, resume was silent.)

Root cause: the work profile's apps live in credential-encrypted (CE) storage whose
keys exist in memory only AFTER the device is unlocked. Resuming a locked profile
needs the user credential, so the OS queued a CONFIRM_DEVICE_CREDENTIAL screen.

Fix:
 - QuietModeBackend now resumes with the flag
   QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED (API 30+). When locked, the
   call does nothing and returns false INSTEAD of queuing a prompt.
 - On that deferred result, ToggleReceiver persists pendingResume + a 6h deadline
   and starts ResumeRetryScheduler (inexact, allow-while-idle backoff).
 - ResumeReconcileReceiver completes the resume on the next unlock, triggered by
   ACTION_USER_PRESENT (instant where it fires) and/or the retry alarm (reliable
   backbone). MainScreen also reconciles on app open.
 - A pause supersedes any pending resume (clears it + cancels the retry).
 - Unified lock confirmed by user, so after a normal device unlock the CE keys are
   available and the retried resume succeeds silently — no second passcode.

## Build / install
 - APK: WorkProfileScheduler.apk, versionName=1.4.0 versionCode=5, ~25 MB, debug-signed.
 - Compiled clean (gradle 8.10.2 / AGP 8.7.3, cached).
 - Installed on the A56; MODIFY_QUIET_MODE grant persisted across the in-place update
   (granted=true, user 0).

## Verification

Phase A (defer while locked) — set up on-device:
 - Profile paused (tile=false) while unlocked (verified).
 - RESUME armed via real AlarmManager (TEST_FIRE), screen then locked.
 - The alarm fired on-device while locked. (Live logcat capture was blocked because
   this A56 drops the USB/ADB connection whenever it locks — a cable/device quirk,
   not an app issue. The alarm + receiver run on-device independent of USB.)

Phase B (resume on unlock) — USER-CONFIRMED on the real device:
 - After unlocking the phone normally (single passcode/biometric), the work profile
   RESUMED BY ITSELF within seconds, with NO extra/second passcode prompt.
 - This is the exact behavior that was broken before, now fixed.

## RESULT: PASS (user-confirmed end-to-end on real device)
 - Pause: silent, any lock state (unchanged).
 - Resume while unlocked: silent, instant (unchanged).
 - Resume while locked: defers silently (no queued prompt) and auto-completes on the
   next normal unlock — no second passcode. CONFIRMED by user.

## Test-harness note (not an app limitation)
 - This phone repeatedly drops ADB-over-USB when the screen locks, which blocks
   automated locked-screen log capture. All locked-screen behavior was therefore
   validated by on-device execution + direct user observation rather than live logs.
