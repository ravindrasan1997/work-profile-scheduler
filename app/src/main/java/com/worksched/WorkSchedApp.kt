package com.worksched

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.worksched.alarm.AlarmScheduler

class WorkSchedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Re-arm alarms from persisted store; safe to call on every cold start.
        AlarmScheduler.scheduleAllAsync(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "worksched_toggle"
    }
}
