package com.worksched.profile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.worksched.alarm.AlarmScheduler

class ProfileChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A new work profile may have appeared or been removed.
        // Re-arm alarms so they pick up the (possibly new) profile handle on fire.
        AlarmScheduler.scheduleAllAsync(context.applicationContext)
    }
}
