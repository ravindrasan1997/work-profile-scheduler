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
        // For the silent path, fire the fast synchronous toggle here and capture
        // its result (false on resume = deferred because the device is locked).
        val result: Boolean? = if (silent) {
            Log.i(TAG, "alarm fired (enable=$enable); silent toggle")
            WorkProfileToggler.toggle(context, enable)
        } else {
            // Fallback: launch the screen-wake activity so the accessibility gesture
            // has a rendered Quick Settings panel to act on.
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
            null // fallback path: no deferral handling
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)

                // Deferred-resume bookkeeping (silent path only).
                if (silent) {
                    if (enable && result == false) {
                        // Resume deferred — device locked. Defer silently and retry on unlock.
                        val deadline = System.currentTimeMillis() + DEFER_WINDOW_MS
                        store.setPendingResume(true, deadline)
                        ResumeRetryScheduler.schedule(appCtx, attempt = 0)
                        Log.i(TAG, "resume deferred; pendingResume set, retry chain started")
                        postNotification(appCtx, enable = true, deferred = true)
                    } else {
                        // Resume succeeded now, OR a pause (which supersedes any pending resume).
                        store.setPendingResume(false, 0L)
                        ResumeRetryScheduler.cancel(appCtx)
                        postNotification(appCtx, enable, deferred = false)
                    }
                } else {
                    postNotification(appCtx, enable, deferred = false)
                }

                // Re-arm next week's slot for this (day, action).
                val schedule = store.scheduleFlow.first()
                AlarmScheduler.scheduleAll(appCtx, schedule)
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, enable: Boolean, deferred: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            deferred -> "Work profile will resume when you next unlock the phone."
            enable -> "Resuming work profile"
            else -> "Pausing work profile"
        }
        val builder = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (deferred) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        nm.notify(if (enable) 1001 else 1002, builder.build())
    }

    companion object {
        private const val TAG = "WorkSchedAlarm"
        private const val DEFER_WINDOW_MS = 6 * 60 * 60 * 1000L // give up after 6h
    }
}
