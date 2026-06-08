package com.worksched.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.worksched.data.Schedule
import com.worksched.data.ScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

object AlarmScheduler {

    const val ACTION_TOGGLE = "com.worksched.action.TOGGLE"
    const val EXTRA_ENABLE = "enable"
    const val EXTRA_DAY = "day"

    private val WEEKDAYS = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY
    )

    fun scheduleAllAsync(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val store = ScheduleStore(context.applicationContext)
            val schedule = store.scheduleFlow.first()
            scheduleAll(context.applicationContext, schedule)
        }
    }

    fun scheduleAll(context: Context, schedule: Schedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        WEEKDAYS.forEach { day ->
            scheduleSlot(context, am, day, enable = true, schedule.enableMinutesOfDay())
            scheduleSlot(context, am, day, enable = false, schedule.disableMinutesOfDay())
        }
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        WEEKDAYS.forEach { day ->
            am.cancel(pendingIntent(context, day, enable = true))
            am.cancel(pendingIntent(context, day, enable = false))
        }
    }

    private fun pendingIntent(context: Context, day: Int, enable: Boolean): PendingIntent {
        val intent = Intent(context, ToggleReceiver::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_ENABLE, enable)
            putExtra(EXTRA_DAY, day)
            setPackage(context.packageName)
        }
        val rc = day * 10 + if (enable) 1 else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, rc, intent, flags)
    }

    private fun scheduleSlot(context: Context, am: AlarmManager, day: Int, enable: Boolean, minutes: Int) {
        val pi = pendingIntent(context, day, enable)
        val triggerMs = nextOccurrenceMillis(day, minutes)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun nextOccurrenceMillis(dayOfWeek: Int, minutesOfDay: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(Calendar.MINUTE, minutesOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var attempts = 0
        while (target.timeInMillis <= now.timeInMillis && attempts < 8) {
            target.add(Calendar.DAY_OF_YEAR, 7)
            attempts++
        }
        return target.timeInMillis
    }
}
