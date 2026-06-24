package com.worksched.alarm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.data.ScheduleStore
import com.worksched.location.GeofenceManager
import com.worksched.location.LocationGate
import com.worksched.profile.WorkProfileToggler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_TOGGLE) return
        val enable = intent.getBooleanExtra(AlarmScheduler.EXTRA_ENABLE, true)
        val appCtx = context.applicationContext
        val silent = WorkProfileToggler.isSilent(context)

        if (!silent) {
            // Visible fallback path — unchanged, NOT location-gated (gating needs the silent
            // backend). Launch the screen-wake activity so the accessibility gesture has a
            // rendered Quick Settings panel to act on, then re-arm next week's alarms.
            Log.i(TAG, "alarm fired (enable=$enable); launching wake activity (visible fallback)")
            val launch = Intent(context, ToggleActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(ToggleActivity.EXTRA_ENABLE, enable)
            }
            try {
                context.startActivity(launch)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to start wake activity: $t — direct toggle")
                WorkProfileToggler.toggle(context, enable)
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    postNotification(appCtx, enable, deferred = false)
                    val schedule = ScheduleStore(appCtx).scheduleFlow.first()
                    AlarmScheduler.scheduleAll(appCtx, schedule)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // Silent path — everything runs in the coroutine so a resume can await a location read.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)
                val schedule = store.scheduleFlow.first()

                if (enable) {
                    if (schedule.hasValidLocation()) {
                        // LOCATION GATE (resume only). Resume now if on-site; otherwise arm an
                        // event-driven geofence that resumes on arrival within the on-window.
                        val loc = LocationGate.currentLocation(appCtx)
                        val inside = loc != null && LocationGate.isInside(schedule, loc)
                        if (inside) {
                            Log.i(TAG, "resume: inside radius — resuming now")
                            resumeNow(store, appCtx)
                        } else {
                            Log.i(TAG, "resume: off-site (loc=${loc != null}) — arming arrival geofence")
                            store.setPendingResume(false, 0L) // not a locked-defer
                            ResumeRetryScheduler.cancel(appCtx)
                            val armed = GeofenceManager.arm(
                                appCtx, schedule.latitude!!, schedule.longitude!!,
                                schedule.radiusMeters, LocationGate.resumeWindowEndMillis(schedule)
                            )
                            store.setGeofenceArmed(armed)
                            postNotification(appCtx, enable = true, deferred = false, locationPending = armed)
                        }
                    } else {
                        // No location gating — existing behaviour (incl. locked → deferred resume).
                        Log.i(TAG, "resume: no location gate — toggling")
                        resumeNow(store, appCtx)
                    }
                } else {
                    // PAUSE — always pause; supersede any pending resume and disarm any geofence.
                    WorkProfileToggler.toggle(appCtx, enable = false)
                    store.setPendingResume(false, 0L)
                    ResumeRetryScheduler.cancel(appCtx)
                    if (store.geofenceArmed()) {
                        GeofenceManager.disarm(appCtx)
                        store.setGeofenceArmed(false)
                    }
                    postNotification(appCtx, enable = false, deferred = false)
                }

                // Re-arm next week's slots (unchanged).
                AlarmScheduler.scheduleAll(appCtx, schedule)
            } finally {
                pending.finish()
            }
        }
    }

    /** Resume via the silent backend; defer to next unlock if the device is locked (returns false). */
    private suspend fun resumeNow(store: ScheduleStore, appCtx: Context) {
        val ok = WorkProfileToggler.toggle(appCtx, enable = true)
        if (ok) {
            store.setPendingResume(false, 0L)
            ResumeRetryScheduler.cancel(appCtx)
            postNotification(appCtx, enable = true, deferred = false)
        } else {
            val deadline = System.currentTimeMillis() + DEFER_WINDOW_MS
            store.setPendingResume(true, deadline)
            ResumeRetryScheduler.schedule(appCtx, attempt = 0)
            Log.i(TAG, "resume deferred; pendingResume set, retry chain started")
            postNotification(appCtx, enable = true, deferred = true)
        }
    }

    private fun postNotification(context: Context, enable: Boolean, deferred: Boolean, locationPending: Boolean = false) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            locationPending -> "Work apps will turn on when you reach your location."
            deferred -> "Work profile will resume when you next unlock the phone."
            enable -> "Resuming work profile"
            else -> "Pausing work profile"
        }
        val builder = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (deferred || locationPending) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        nm.notify(if (enable) 1001 else 1002, builder.build())
    }

    companion object {
        private const val TAG = "WorkSchedAlarm"
        private const val DEFER_WINDOW_MS = 6 * 60 * 60 * 1000L // give up after 6h
    }
}
