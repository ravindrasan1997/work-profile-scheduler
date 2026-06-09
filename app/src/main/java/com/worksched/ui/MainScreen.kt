package com.worksched.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.worksched.alarm.AlarmScheduler
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.Schedule
import com.worksched.data.ScheduleStore
import com.worksched.profile.QuietModeBackend
import com.worksched.profile.WorkProfileDetector
import com.worksched.profile.WorkProfileToggler
import com.worksched.service.WorkProfileA11yService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val snackbar = remember { SnackbarHostState() }

    var schedule by remember { mutableStateOf(Schedule.default()) }
    var quiet by remember { mutableStateOf<Boolean?>(null) }   // true = paused, false = on
    var present by remember { mutableStateOf(true) }
    var silent by remember { mutableStateOf(QuietModeBackend.isAvailable(ctx)) }
    var a11yOn by remember { mutableStateOf(isA11yEnabled(ctx)) }
    var canExact by remember { mutableStateOf(canScheduleExact(ctx)) }
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(ctx)) }
    var pendingResume by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    var toggling by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    fun refresh() {
        present = detector.isWorkProfilePresent()
        quiet = detector.isWorkProfileQuiet()
        silent = QuietModeBackend.isAvailable(ctx)
        a11yOn = isA11yEnabled(ctx)
        canExact = canScheduleExact(ctx)
        batteryOk = isBatteryUnrestricted(ctx)
        nowTick = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) {
        schedule = store.scheduleFlow.first()
        refresh()
        if (store.pendingResume() && !QuietModeBackend.isDeviceLocked(ctx)) {
            if (WorkProfileToggler.toggle(ctx, enable = true)) {
                store.setPendingResume(false, 0L); ResumeRetryScheduler.cancel(ctx)
            }
        }
        pendingResume = store.pendingResume()
        while (true) {
            delay(30_000); refresh(); pendingResume = store.pendingResume()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh(); scope.launch { pendingResume = store.pendingResume() }
    }

    // A toggle that disables the buttons while in flight and shows exactly one toast.
    fun doToggle(enable: Boolean) {
        if (toggling) return
        toggling = true
        scope.launch {
            withContext(Dispatchers.Default) { WorkProfileToggler.toggle(ctx, enable) }
            refresh()
            snackbar.currentSnackbarData?.dismiss()
            val msg = when {
                enable && silent -> "Resumed"
                enable -> "Resuming · opening Quick Settings"
                silent -> "Paused"
                else -> "Pausing · opening Quick Settings"
            }
            toggling = false
            snackbar.showSnackbar(msg)
        }
    }

    val needsSetup = !silent || !canExact || !batteryOk

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppHeader(
                onSetup = { showSetup = true },
                onHelp = { showHelp = true },
                onAbout = { showAbout = true }
            )

            HeroCard(present = present, quiet = quiet, pendingResume = pendingResume, schedule = schedule, nowTick = nowTick)

            // Manual actions — disabled when not applicable or while toggling
            val onNow = quiet == false
            val paused = quiet == true
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = !toggling && !onNow,
                    onClick = { doToggle(true) }
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp)); Text("Resume now")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).height(52.dp),
                    enabled = !toggling && !paused,
                    onClick = { doToggle(false) }
                ) {
                    PauseGlyph(MaterialTheme.colorScheme.primary, Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp)); Text("Pause now")
                }
            }

            ScheduleCard(
                schedule = schedule,
                savedFlash = savedFlash,
                onToggleDay = { day ->
                    schedule = if (day in schedule.days) schedule.copy(days = schedule.days.filter { it != day })
                    else schedule.copy(days = schedule.days + day)
                },
                onEnableTime = { h, m -> schedule = schedule.copy(enableHour = h, enableMinute = m) },
                onDisableTime = { h, m -> schedule = schedule.copy(disableHour = h, disableMinute = m) },
                onSave = {
                    scope.launch {
                        val ordered = Schedule.ORDER.filter { it in schedule.days }
                        val toSave = schedule.copy(days = ordered)
                        store.save(toSave); AlarmScheduler.scheduleAll(ctx, toSave)
                        schedule = toSave; nowTick = System.currentTimeMillis()
                        savedFlash = true; delay(2000); savedFlash = false
                    }
                }
            )

            // Inline action banner — only when something needs setting up
            if (needsSetup) {
                SetupBanner(
                    silent = silent, canExact = canExact, batteryOk = batteryOk,
                    onClick = { showSetup = true }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSetup) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSetup = false }, sheetState = sheetState) {
            SetupSheet(
                silent = silent, a11yOn = a11yOn, canExact = canExact, batteryOk = batteryOk,
                onOpenA11y = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                onOpenExact = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onAllowBattery = { requestIgnoreBattery(ctx) },
                onRecheck = {
                    refresh()
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(if (QuietModeBackend.isAvailable(ctx)) "Silent mode active" else "Grant not found — using the visible fallback")
                    }
                }
            )
        }
    }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

// ---------------- Header ----------------

@Composable
private fun AppHeader(onSetup: () -> Unit, onHelp: () -> Unit, onAbout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                ClockGlyph(tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Work Profile Scheduler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("AUTO PAUSE / RESUME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Setup & permissions") }, onClick = { menu = false; onSetup() })
                DropdownMenuItem(text = { Text("How it works") }, onClick = { menu = false; onHelp() })
                DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; onAbout() })
            }
        }
    }
}

// ---------------- Hero ----------------

@Composable
private fun HeroCard(present: Boolean, quiet: Boolean?, pendingResume: Boolean, schedule: Schedule, nowTick: Long) {
    val sc = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = sc.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            val paused = quiet == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = sc.surfaceContainerHighest, shape = CircleShape, modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (paused) MoonGlyph(sc.onSurfaceVariant, sc.surfaceContainerHighest, Modifier.size(23.dp))
                        else SunGlyph(sc.onSurfaceVariant, Modifier.size(23.dp))
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column {
                    val headline = when {
                        !present -> "No work profile"
                        quiet == null -> "Work profile"
                        paused -> "Work apps are paused"
                        else -> "Work apps are on"
                    }
                    Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = sc.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(heroSub(schedule, quiet, pendingResume), style = MaterialTheme.typography.bodyMedium, color = sc.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------- Schedule ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    schedule: Schedule, savedFlash: Boolean,
    onToggleDay: (Int) -> Unit, onEnableTime: (Int, Int) -> Unit, onDisableTime: (Int, Int) -> Unit, onSave: () -> Unit
) {
    val sc = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = sc.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("REPEAT ON", style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                Text(schedule.daysLabel().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = sc.onSurface, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Schedule.ORDER.forEach { day ->
                    val selected = day in schedule.days
                    Surface(
                        modifier = Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).clickable { onToggleDay(day) },
                        color = if (selected) sc.primary else sc.surfaceContainerHighest
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text((Schedule.SHORT[day] ?: "?").take(1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = if (selected) sc.onPrimary else sc.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("HOURS", style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HourTile(sun = true, label = "RESUME AT", hour = schedule.enableHour, minute = schedule.enableMinute, modifier = Modifier.weight(1f), onChange = onEnableTime)
                HourTile(sun = false, label = "PAUSE AT", hour = schedule.disableHour, minute = schedule.disableMinute, modifier = Modifier.weight(1f), onChange = onDisableTime)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(50.dp), onClick = onSave,
                colors = if (savedFlash) ButtonDefaults.buttonColors(containerColor = sc.secondaryContainer, contentColor = sc.onSecondaryContainer) else ButtonDefaults.buttonColors()
            ) { Text(if (savedFlash) "✓  Schedule saved" else "Save schedule") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HourTile(sun: Boolean, label: String, hour: Int, minute: Int, modifier: Modifier = Modifier, onChange: (Int, Int) -> Unit) {
    val sc = MaterialTheme.colorScheme
    var show by remember { mutableStateOf(false) }
    Surface(modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable { show = true }, color = sc.surfaceContainerHighest) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sun) SunGlyph(sc.onSurfaceVariant, Modifier.size(15.dp)) else MoonGlyph(sc.onSurfaceVariant, sc.surfaceContainerHighest, Modifier.size(15.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, letterSpacing = 0.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(fmt12(hour, minute), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
    if (show) {
        val tps = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { onChange(tps.hour, tps.minute); show = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            title = { Text(if (label == "RESUME AT") "Resume at" else "Pause at") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { TimePicker(state = tps) } }
        )
    }
}

// ---------------- Setup banner + sheet ----------------

@Composable
private fun SetupBanner(silent: Boolean, canExact: Boolean, batteryOk: Boolean, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    val msg = when {
        !silent -> "Finish silent-mode setup for instant, screen-free toggles"
        !canExact -> "Allow exact timing so the schedule fires on time"
        !batteryOk -> "Exempt from battery optimization for reliable scheduling"
        else -> ""
    }
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = sc.tertiaryContainer)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = sc.onTertiaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = sc.onTertiaryContainer, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = sc.onTertiaryContainer)
        }
    }
}

@Composable
private fun SetupSheet(
    silent: Boolean, a11yOn: Boolean, canExact: Boolean, batteryOk: Boolean,
    onOpenA11y: () -> Unit, onOpenExact: () -> Unit, onAllowBattery: () -> Unit, onRecheck: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Setup & permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SetupItem(
            title = "Silent mode", ok = silent,
            okText = "Active — toggles are instant and screen-free.",
            notText = "Run this once from a PC (survives reboot). No root."
        ) {
            if (!silent) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text("adb shell pm grant com.worksched android.permission.MODIFY_QUIET_MODE",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Without it, the app falls back to the visible Quick Settings gesture (needs Accessibility, below).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SetupItem(
            title = "Accessibility service", ok = a11yOn,
            okText = "Enabled — used as the visible fallback.",
            notText = "Only needed if you skip silent mode."
        ) {
            if (!a11yOn) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onOpenA11y) { Text("Open Accessibility settings") } }
        }

        SetupItem(
            title = "Exact alarms", ok = canExact,
            okText = "Allowed — schedule fires on time.",
            notText = "Needed so the schedule fires at the exact minute."
        ) {
            if (!canExact) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onOpenExact) { Text("Open exact-alarm settings") } }
        }

        SetupItem(
            title = "Battery optimization", ok = batteryOk,
            okText = "Exempt — scheduler runs reliably.",
            notText = "Exempting the app keeps the schedule reliable in deep sleep."
        ) {
            if (!batteryOk) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onAllowBattery) { Text("Allow") } }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRecheck) { Text("Re-check") }
    }
}

@Composable
private fun SetupItem(title: String, ok: Boolean, okText: String, notText: String, extra: @Composable () -> Unit) {
    val sc = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✓" else "•", color = if (ok) sc.primary else sc.error, fontWeight = FontWeight.Bold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        Text(if (ok) okText else notText, style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp))
        Box(modifier = Modifier.padding(start = 26.dp)) { extra() }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How it works") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HelpPara("Schedule", "Pick the days and the resume/pause times. The app arms exact alarms that survive reboot — no app needs to stay running.")
                HelpPara("Silent mode", "After a one-time ADB grant (MODIFY_QUIET_MODE), the app pauses/resumes your work profile via a direct system call — instant, no screen, no Quick Settings panel. No root.")
                HelpPara("Visible fallback", "Without the grant, it taps the Quick Settings ‘Work apps’ tile via an Accessibility service, which briefly shows the panel.")
                HelpPara("Resume while locked", "Resuming needs the profile unlocked, so if a resume is scheduled while the phone is locked it defers and completes silently the moment you next unlock — no extra passcode.")
            }
        }
    )
}

@Composable
private fun HelpPara(h: String, b: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(h, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(b, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Work Profile Scheduler") },
        text = {
            Column {
                Text("Version ${com.worksched.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Pauses and resumes your Android work profile on a schedule. No root. Open source on GitHub, Apache-2.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

// ---------------- logic helpers ----------------

private fun heroSub(schedule: Schedule, quiet: Boolean?, pendingResume: Boolean): String {
    if (pendingResume) return "Resuming the next time you unlock"
    if (schedule.days.isEmpty()) return "Scheduling off · manual only"
    val next = nextRelevant(schedule, quiet) ?: return "Scheduling off · manual only"
    return "${if (next.second) "Resuming" else "Pausing"} ${whenPhrase(next.first)}"
}

/** Next state-changing event: if paused → next resume; if on → next pause; if unknown → soonest. */
private fun nextRelevant(s: Schedule, quiet: Boolean?): Pair<Long, Boolean>? {
    if (s.days.isEmpty()) return null
    if (quiet == null) {
        var best = Long.MAX_VALUE; var enable = true
        for (d in s.days) {
            val te = AlarmScheduler.nextOccurrenceMillis(d, s.enableMinutesOfDay()); if (te < best) { best = te; enable = true }
            val td = AlarmScheduler.nextOccurrenceMillis(d, s.disableMinutesOfDay()); if (td < best) { best = td; enable = false }
        }
        return best to enable
    }
    val wantEnable = quiet == true
    val minutes = if (wantEnable) s.enableMinutesOfDay() else s.disableMinutesOfDay()
    var best = Long.MAX_VALUE
    for (d in s.days) { val t = AlarmScheduler.nextOccurrenceMillis(d, minutes); if (t < best) best = t }
    return best to wantEnable
}

private fun whenPhrase(ms: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ms))
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    return when (dayDiff(now, t)) {
        0 -> "at $time today"
        1 -> "at $time tomorrow"
        else -> "${SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(ms))} at $time"
    }
}

private fun dayDiff(a: Calendar, b: Calendar): Int {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return ((midnight(b).timeInMillis - midnight(a).timeInMillis) / 86_400_000L).toInt()
}

private fun fmt12(hour: Int, minute: Int): String {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0) }
    return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.time)
}

private fun isA11yEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val target = WorkProfileA11yService::class.java.name
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        .any { it.id.endsWith(target) || it.resolveInfo?.serviceInfo?.name == target }
}

private fun canScheduleExact(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
}

private fun isBatteryUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun requestIgnoreBattery(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------- hand-drawn monochrome glyphs ----------------

@Composable
private fun SunGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2); val core = size.minDimension * 0.26f
        drawCircle(tint, radius = core, center = c)
        val rayIn = size.minDimension * 0.36f; val rayOut = size.minDimension * 0.48f; val s = size.minDimension * 0.09f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble()); val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            drawLine(tint, Offset(c.x + dx * rayIn, c.y + dy * rayIn), Offset(c.x + dx * rayOut, c.y + dy * rayOut), strokeWidth = s, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun MoonGlyph(tint: Color, carveColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension * 0.42f; val c = Offset(size.width / 2, size.height / 2)
        drawCircle(tint, radius = r, center = c)
        drawCircle(carveColor, radius = r * 0.92f, center = Offset(c.x + r * 0.55f, c.y - r * 0.30f))
    }
}

@Composable
private fun ClockGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.42f; val sw = size.minDimension * 0.09f
        drawCircle(tint, radius = r, center = c, style = Stroke(width = sw))
        drawLine(tint, c, Offset(c.x, c.y - r * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, c, Offset(c.x + r * 0.45f, c.y), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barW = size.width * 0.26f; val gap = size.width * 0.20f
        val left = (size.width - (barW * 2 + gap)) / 2; val top = size.height * 0.12f; val barH = size.height * 0.76f
        val r = CornerRadius(barW * 0.4f, barW * 0.4f)
        drawRoundRect(tint, topLeft = Offset(left, top), size = Size(barW, barH), cornerRadius = r)
        drawRoundRect(tint, topLeft = Offset(left + barW + gap, top), size = Size(barW, barH), cornerRadius = r)
    }
}
