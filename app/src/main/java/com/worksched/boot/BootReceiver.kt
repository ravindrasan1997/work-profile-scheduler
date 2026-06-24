package com.worksched.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.worksched.alarm.AlarmScheduler
import com.worksched.data.ScheduleStore
import com.worksched.location.GeofenceManager
import com.worksched.location.LocationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val appCtx = context.applicationContext
                AlarmScheduler.scheduleAllAsync(appCtx)
                reArmGeofenceIfPending(appCtx)
            }
        }
    }

    /** Proximity alerts don't survive reboot — restore an in-flight arrival-watch if still valid. */
    private fun reArmGeofenceIfPending(appCtx: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)
                if (!store.geofenceArmed()) return@launch
                val s = store.scheduleFlow.first()
                if (s.hasValidLocation() && LocationGate.inResumeWindowNow(s)) {
                    GeofenceManager.arm(
                        appCtx, s.latitude!!, s.longitude!!,
                        s.radiusMeters, LocationGate.resumeWindowEndMillis(s)
                    )
                } else {
                    store.setGeofenceArmed(false)
                    GeofenceManager.disarm(appCtx)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
