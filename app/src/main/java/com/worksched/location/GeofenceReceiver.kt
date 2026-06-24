package com.worksched.location

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.ScheduleStore
import com.worksched.profile.WorkProfileToggler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires when the device enters the saved geofence (the OS delivers this — no polling). Resumes the
 * work profile, provided an arrival-resume is still armed and we're still inside today's on-window.
 * Composes with the locked-resume defer: if the device is locked on arrival, the resume defers and
 * completes on the next unlock (no second passcode).
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceManager.ACTION_GEOFENCE) return
        // Only act on ENTER; ignore EXIT events.
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return

        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)
                if (!store.geofenceArmed()) return@launch
                val schedule = store.scheduleFlow.first()

                // Stale guard: gate turned off, or we're past today's window → stop listening.
                if (!schedule.hasValidLocation() || !LocationGate.inResumeWindowNow(schedule)) {
                    store.setGeofenceArmed(false)
                    GeofenceManager.disarm(appCtx)
                    Log.i(TAG, "ENTER ignored (gate off or outside window); disarmed")
                    return@launch
                }

                // Arrival happened — the geofence has done its job. Disarm regardless of outcome.
                store.setGeofenceArmed(false)
                GeofenceManager.disarm(appCtx)

                val ok = WorkProfileToggler.toggle(appCtx, enable = true)
                if (ok) {
                    store.setPendingResume(false, 0L)
                    ResumeRetryScheduler.cancel(appCtx)
                    notify(appCtx, "Work apps resumed — you're at your location.")
                    Log.i(TAG, "arrival resume succeeded")
                } else {
                    // Locked on arrival → defer to next unlock via the existing retry backbone.
                    val deadline = System.currentTimeMillis() + DEFER_WINDOW_MS
                    store.setPendingResume(true, deadline)
                    ResumeRetryScheduler.schedule(appCtx, attempt = 0)
                    notify(appCtx, "Work apps will resume when you next unlock the phone.")
                    Log.i(TAG, "arrival resume deferred (device locked)")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(1005, n)
    }

    companion object {
        private const val TAG = "WorkSchedGeofence"
        private const val DEFER_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}
