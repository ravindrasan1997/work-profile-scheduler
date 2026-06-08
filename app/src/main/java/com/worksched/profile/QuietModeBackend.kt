package com.worksched.profile

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Silent backend. Requires the MODIFY_QUIET_MODE permission, granted once via:
 *
 *   adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE
 *
 * The permission's protectionLevel is `signature|privileged|development|role`;
 * the `development` flag is what makes `pm grant` work for a normal app (same
 * mechanism as WRITE_SECURE_SETTINGS). Once held, [UserManager.requestQuietModeEnabled]
 * pauses/resumes the work profile in ~50 ms with no UI, no screen wake, and
 * without touching profile ownership (Intune enrolment stays intact).
 *
 * Resume-while-locked: the work profile's apps live in credential-encrypted (CE)
 * storage whose keys exist only after the device is unlocked. Resuming while
 * locked therefore needs the user credential. The 2-arg call would queue a
 * credential prompt for the next unlock; instead we pass
 * QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED so the call does nothing and
 * returns false when locked. The caller persists a pending-resume flag and retries
 * on unlock (see ResumeReconcileReceiver) — completing silently once CE keys exist.
 */
object QuietModeBackend {
    private const val TAG = "QuietModeBackend"
    const val PERMISSION = "android.permission.MODIFY_QUIET_MODE"

    // Value of UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED (API 30+).
    private const val FLAG_DISABLE_ONLY_IF_CRED_NOT_REQUIRED = 1

    fun isAvailable(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceLocked == true
    }

    /**
     * enable=true  → resume the profile (quietMode=false)
     * enable=false → pause  the profile (quietMode=true)
     *
     * Returns true if the profile reached the desired state. For resume this is
     * the OS's real result: false means "deferred — credential needed (locked)".
     * Pause always returns true (it never needs a credential).
     */
    fun toggle(context: Context, enable: Boolean): Boolean {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        val workUser = WorkProfileDetector(context).workProfileHandle()
        if (workUser == null) {
            Log.w(TAG, "no work profile present")
            return false
        }
        return try {
            if (enable) {
                // Resume = disable quiet mode. Use the credential-not-required flag so a
                // locked device defers silently (returns false) instead of queuing a prompt.
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    um.requestQuietModeEnabled(false, workUser, FLAG_DISABLE_ONLY_IF_CRED_NOT_REQUIRED)
                } else {
                    um.requestQuietModeEnabled(false, workUser)
                }
                Log.i(TAG, "resume: requestQuietModeEnabled(quiet=false) returned $ok " +
                    "(deviceLocked=${isDeviceLocked(context)})")
                ok
            } else {
                // Pause = enable quiet mode. Always succeeds, no credential.
                um.requestQuietModeEnabled(true, workUser)
                Log.i(TAG, "pause: requestQuietModeEnabled(quiet=true) issued")
                true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MODIFY_QUIET_MODE not held: $e")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "toggle failed: $t")
            false
        }
    }
}
