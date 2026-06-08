package com.worksched.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.worksched.profile.ResumeReconcileReceiver

/**
 * Drives the deferred-resume retry. When a scheduled resume is deferred because the
 * device is locked, we schedule inexact, Doze-friendly retries that re-attempt the
 * resume. The retry succeeds silently the moment the device is unlocked (unified
 * lock → credential-encrypted storage becomes available).
 *
 * USER_PRESENT (handled by the same receiver) makes resume near-instant on unlock
 * where that broadcast fires; this alarm chain is the reliable backbone for devices
 * where it doesn't.
 *
 * Inexact + setAndAllowWhileIdle keeps battery cost negligible (Doze coalesces).
 */
object ResumeRetryScheduler {

    private const val TAG = "ResumeRetry"
    const val ACTION_RESUME_RETRY = "com.worksched.action.RESUME_RETRY"
    private const val REQUEST_CODE = 7777

    // Backoff steps in milliseconds, then steady 10-minute cadence.
    private val BACKOFF_MS = longArrayOf(60_000L, 120_000L, 300_000L)
    private const val STEADY_MS = 600_000L

    fun delayForAttempt(attempt: Int): Long =
        if (attempt < BACKOFF_MS.size) BACKOFF_MS[attempt] else STEADY_MS

    fun schedule(context: Context, attempt: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayForAttempt(attempt)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, attempt))
        Log.i(TAG, "scheduled retry #$attempt in ${delayForAttempt(attempt) / 1000}s")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Cancel any attempt index we might have used (PendingIntent equality ignores extras
        // under FLAG_UPDATE_CURRENT, but request code is constant so one cancel suffices).
        am.cancel(pendingIntent(context, 0))
        Log.i(TAG, "retry chain cancelled")
    }

    private fun pendingIntent(context: Context, attempt: Int): PendingIntent {
        val intent = Intent(context, ResumeReconcileReceiver::class.java).apply {
            action = ACTION_RESUME_RETRY
            setPackage(context.packageName)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    const val EXTRA_ATTEMPT = "attempt"
}
