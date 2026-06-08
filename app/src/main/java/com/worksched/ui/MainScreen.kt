package com.worksched.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.worksched.alarm.AlarmScheduler
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.Schedule
import com.worksched.data.ScheduleStore
import com.worksched.profile.QuietModeBackend
import com.worksched.profile.WorkProfileDetector
import com.worksched.profile.WorkProfileToggler
import com.worksched.service.WorkProfileA11yService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val store = remember { ScheduleStore(ctx) }
    val detector = remember { WorkProfileDetector(ctx) }
    val scope = rememberCoroutineScope()

    var schedule by remember { mutableStateOf(Schedule.default()) }
    var status by remember { mutableStateOf("checking…") }
    var silent by remember { mutableStateOf(QuietModeBackend.isAvailable(ctx)) }
    var a11yOn by remember { mutableStateOf(isA11yEnabled(ctx)) }
    var canExact by remember { mutableStateOf(canScheduleExact(ctx)) }
    var pendingResume by remember { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(0L) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        schedule = store.scheduleFlow.first()
        status = readStatus(detector)
        silent = QuietModeBackend.isAvailable(ctx)
        a11yOn = isA11yEnabled(ctx)
        canExact = canScheduleExact(ctx)

        // Reconcile a deferred resume: if one is pending and the device is now
        // unlocked (the app is open, so it is), complete it silently right now.
        if (store.pendingResume() && !QuietModeBackend.isDeviceLocked(ctx)) {
            if (WorkProfileToggler.toggle(ctx, enable = true)) {
                store.setPendingResume(false, 0L)
                ResumeRetryScheduler.cancel(ctx)
            }
        }
        pendingResume = store.pendingResume()

        while (true) {
            delay(30_000)
            status = readStatus(detector)
            pendingResume = store.pendingResume()
            nowTick = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Work Profile Scheduler")
                        Text(
                            "v${com.worksched.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status + mode + next-run card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (silent) "Mode: Silent ✓ (no screen wake)"
                        else "Mode: Visible — finish 1-time setup below for silent operation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (silent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    val (nextMs, nextEnable) = nextRun(schedule, nowTick)
                    Text(
                        "Next: ${formatNextRun(nextMs)} — ${if (nextEnable) "Resume" else "Pause"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (pendingResume) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Resume deferred — will complete silently the next time you unlock.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "10 exact alarms armed for Mon–Fri. Survives reboot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Schedule editor — two clickable time pills
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Weekday schedule", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Mon–Fri only. Sat/Sun are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TimePill(
                            label = "Enable at",
                            hour = schedule.enableHour,
                            minute = schedule.enableMinute,
                            modifier = Modifier.weight(1f),
                            onChange = { h, m -> schedule = schedule.copy(enableHour = h, enableMinute = m) }
                        )
                        TimePill(
                            label = "Disable at",
                            hour = schedule.disableHour,
                            minute = schedule.disableMinute,
                            modifier = Modifier.weight(1f),
                            onChange = { h, m -> schedule = schedule.copy(disableHour = h, disableMinute = m) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                store.save(schedule)
                                AlarmScheduler.scheduleAll(ctx, schedule)
                                savedTick = System.currentTimeMillis()
                                nowTick = savedTick
                            }
                        }
                    ) { Text("Save and re-arm alarms") }
                    if (savedTick > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Saved at ${formatClock(savedTick)} — 10 alarms re-armed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Manual toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { WorkProfileToggler.toggle(ctx, enable = true) }
                ) { Text("Resume now") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { WorkProfileToggler.toggle(ctx, enable = false) }
                ) { Text("Pause now") }
            }

            // Silent-setup card — shown until MODIFY_QUIET_MODE is granted.
            if (!silent) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Enable silent mode (one-time)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Run this once from a PC with the phone connected. After it, " +
                                "toggles are instant and silent — no screen, no Quick Settings panel. " +
                                "It survives reboot.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                "adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            silent = QuietModeBackend.isAvailable(ctx)
                            a11yOn = isA11yEnabled(ctx)
                            canExact = canScheduleExact(ctx)
                        }) { Text("Re-check") }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Until then, the app falls back to the visible Quick Settings " +
                                "gesture, which needs the Accessibility service" +
                                if (a11yOn) " (enabled ✓)." else " (NOT enabled).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!a11yOn) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text("Open Accessibility settings") }
                        }
                    }
                }
            }

            // Exact-alarm setup (needed in all modes).
            if (!canExact) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Allow exact alarms", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Needed so the schedule fires on time.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Open exact-alarm settings") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { canExact = canScheduleExact(ctx) }) { Text("Re-check") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePill(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    onChange: (Int, Int) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    OutlinedCard(modifier = modifier, onClick = { show = true }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text("%02d:%02d".format(hour, minute), style = MaterialTheme.typography.headlineMedium)
        }
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour, state.minute)
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            title = { Text(label) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = state)
                }
            }
        )
    }
}

// ----- helpers -------------------------------------------------------------

private fun readStatus(detector: WorkProfileDetector): String {
    val present = detector.isWorkProfilePresent()
    if (!present) return "No work profile on this device"
    return when (detector.isWorkProfileQuiet()) {
        true -> "Work profile: paused"
        false -> "Work profile: active"
        null -> "Work profile: state unknown"
    }
}

private fun nextRun(s: Schedule, @Suppress("UNUSED_PARAMETER") nowMs: Long): Pair<Long, Boolean> {
    val days = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY
    )
    var best = Long.MAX_VALUE
    var enable = true
    for (d in days) {
        val tEnable = AlarmScheduler.nextOccurrenceMillis(d, s.enableMinutesOfDay())
        if (tEnable < best) { best = tEnable; enable = true }
        val tDisable = AlarmScheduler.nextOccurrenceMillis(d, s.disableMinutesOfDay())
        if (tDisable < best) { best = tDisable; enable = false }
    }
    return best to enable
}

private fun formatNextRun(ms: Long): String {
    val now = System.currentTimeMillis()
    val delta = ms - now
    if (delta < 0L) return "—"
    val mins = delta / 60_000L
    val hours = mins / 60
    val days = hours / 24
    val absolute = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))
    val rel = when {
        days >= 1 -> "in ${days}d ${hours % 24}h"
        hours >= 1 -> "in ${hours}h ${mins % 60}m"
        mins >= 1 -> "in ${mins}m"
        else -> "in <1m"
    }
    return "$absolute ($rel)"
}

private fun formatClock(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun isA11yEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val target = WorkProfileA11yService::class.java.name
    return am.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
    ).any { it.id.endsWith(target) || it.resolveInfo?.serviceInfo?.name == target }
}

private fun canScheduleExact(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
}
