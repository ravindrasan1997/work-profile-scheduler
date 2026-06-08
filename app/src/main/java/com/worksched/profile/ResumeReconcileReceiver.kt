package com.worksched.profile

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.ScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Completes a deferred resume once the device is unlocked.
 *
 * Triggered by:
 *   - ACTION_USER_PRESENT (device unlocked) — best-effort instant resume.
 *   - ResumeRetryScheduler.ACTION_RESUME_RETRY — the reliable inexact-alarm backbone.
 *
 * In both cases: if a resume is pending and not past its deadline, re-issue the
 * silent resume. On success, clear the pending flag and cancel the retry chain.
 * On the alarm path, if still locked, schedule the next retry.
 */
class ResumeReconcileReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val fromRetry = action == ResumeRetryScheduler.ACTION_RESUME_RETRY
        val fromUnlock = action == Intent.ACTION_USER_PRESENT
        if (!fromRetry && !fromUnlock) return

        val attempt = intent.getIntExtra(ResumeRetryScheduler.EXTRA_ATTEMPT, 0)
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)
                if (!store.pendingResume()) {
                    Log.d(TAG, "no pending resume; ignoring ${action}")
                    return@launch
                }
                val deadline = store.pendingResumeDeadline()
                if (deadline in 1 until System.currentTimeMillis()) {
                    Log.i(TAG, "pending resume past deadline; giving up")
                    store.setPendingResume(false, 0L)
                    ResumeRetryScheduler.cancel(appCtx)
                    return@launch
                }

                val ok = WorkProfileToggler.toggle(appCtx, enable = true)
                if (ok) {
                    Log.i(TAG, "deferred resume completed (trigger=$action)")
                    store.setPendingResume(false, 0L)
                    ResumeRetryScheduler.cancel(appCtx)
                    notify(appCtx, "Work profile resumed")
                } else {
                    Log.i(TAG, "still locked (trigger=$action); resume remains pending")
                    if (fromRetry) ResumeRetryScheduler.schedule(appCtx, attempt + 1)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(1005, notif)
    }

    companion object {
        private const val TAG = "ResumeReconcile"
    }
}
