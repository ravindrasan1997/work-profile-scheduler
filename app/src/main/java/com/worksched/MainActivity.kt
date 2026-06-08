package com.worksched

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.worksched.alarm.AlarmScheduler
import com.worksched.alarm.ToggleReceiver
import com.worksched.ui.MainScreen
import com.worksched.ui.theme.WorkSchedTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            WorkSchedTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_TEST_FIRE) {
            val enable = intent.getBooleanExtra(EXTRA_ENABLE, false)
            val delay = intent.getIntExtra(EXTRA_DELAY_SEC, 25)
            scheduleOneShotTest(enable, delay)
        }
    }

    /**
     * Debug hook for adb-driven testing — arms a real AlarmManager fire so the
     * production path (ToggleReceiver → silent API or wake fallback) runs.
     *
     *   adb shell am start -n com.worksched/.MainActivity \
     *     -a com.worksched.action.TEST_FIRE --ez enable false --ei delay 25
     */
    private fun scheduleOneShotTest(enable: Boolean, delaySec: Int) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ToggleReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_TOGGLE
            putExtra(AlarmScheduler.EXTRA_ENABLE, enable)
            setPackage(packageName)
        }
        val pi = PendingIntent.getBroadcast(
            this, REQUEST_CODE_TEST_FIRE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + delaySec * 1000L
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "TEST_FIRE armed: enable=$enable, fires in ${delaySec}s, exact=$canExact")
    }

    companion object {
        private const val TAG = "WorkSchedMain"
        const val ACTION_TEST_FIRE = "com.worksched.action.TEST_FIRE"
        const val EXTRA_ENABLE = "enable"
        const val EXTRA_DELAY_SEC = "delay"
        private const val REQUEST_CODE_TEST_FIRE = 9999
    }
}
