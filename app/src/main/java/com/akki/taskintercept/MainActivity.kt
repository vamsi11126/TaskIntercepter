package com.akki.taskintercept

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akki.taskintercept.data.AppDatabase
import com.akki.taskintercept.data.OnboardingPrefs
import com.akki.taskintercept.data.Priority
import com.akki.taskintercept.data.SettingsPrefs
import com.akki.taskintercept.data.Task
import com.akki.taskintercept.data.TaskDao
import com.akki.taskintercept.intercept.OemGuidance
import com.akki.taskintercept.intercept.isAccessibilityServiceEnabled
import com.akki.taskintercept.ui.theme.TaskInterceptTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    // Android 13+ needs runtime consent before the reliability
    // notification can show. Result deliberately ignored: denial just
    // hides the notification; the foreground service itself still runs.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val taskDao = AppDatabase.getDatabase(this).taskDao()
        setContent {
            TaskInterceptTheme {
                // null = still reading DataStore; render nothing rather
                // than flashing the wrong screen for a frame.
                val onboardingComplete by OnboardingPrefs.isComplete(this)
                    .collectAsState(initial = null)
                val scope = rememberCoroutineScope()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (onboardingComplete) {
                        null -> {}
                        false -> OnboardingScreen(
                            onGetStarted = {
                                scope.launch { OnboardingPrefs.setComplete(this@MainActivity) }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                        true -> TaskScreen(
                            taskDao = taskDao,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

/** True if the app is exempt from battery optimization (Doze whitelisting). */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(Context.POWER_SERVICE)
        .let { it as PowerManager }
        .isIgnoringBatteryOptimizations(context.packageName)

/** Indicator color per priority — matches the overlay's dot colors. */
private fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.HIGH -> Color(0xFFE05B4B)
    Priority.MEDIUM -> Color(0xFFE0A84B)
    Priority.LOW -> Color(0xFF6B9E6E)
}

private fun priorityLabel(priority: Priority): String = when (priority) {
    Priority.HIGH -> "High"
    Priority.MEDIUM -> "Medium"
    Priority.LOW -> "Low"
}

@Composable
fun TaskScreen(taskDao: TaskDao, modifier: Modifier = Modifier) {
    val activeTasks by taskDao.getActiveTasksByPriority(3)
        .collectAsState(initial = emptyList())
    val allTasks by taskDao.getAllTasksDesc().collectAsState(initial = emptyList())
    var titleInput by remember { mutableStateOf("") }
    var priorityInput by remember { mutableStateOf(Priority.MEDIUM) }

    // Non-null while the user is editing an active task in place (holds
    // its row id). Save then UPDATEs that row instead of inserting a new one.
    var editingTaskId by remember { mutableStateOf<Int?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Re-check all three permissions whenever the user returns from the
    // settings screens, so the status indicators update without any
    // manual refresh.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = isAccessibilityServiceEnabled(context)
                overlayGranted = Settings.canDrawOverlays(context)
                batteryExempt = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // If the task being edited leaves the active list (marked done, or
    // pushed out of the top 3 by higher-priority saves), the edit no
    // longer targets anything visible — abandon it rather than silently
    // editing an off-screen row.
    LaunchedEffect(activeTasks) {
        if (editingTaskId != null && activeTasks.none { it.id == editingTaskId }) {
            editingTaskId = null
            titleInput = ""
            priorityInput = Priority.MEDIUM
        }
    }

    // Hoisted above the LazyColumn: remember state declared inside an
    // item {} block would be discarded whenever that item scrolls out of
    // composition, silently collapsing the card mid-scroll.
    val oemGuidance = remember { OemGuidance.forThisDevice() }
    var showOemGuidance by remember { mutableStateOf(oemGuidance.isAggressive) }

    // Phase 4 settings — collected as Flows so the UI always reflects what
    // the accessibility service is actually reading. null initials mean
    // "still loading from DataStore"; controls render disabled-ish blanks
    // for that frame rather than flashing defaults that might be wrong.
    val monitoredPackages by SettingsPrefs.monitoredPackages(context)
        .collectAsState(initial = null)
    val overlayDuration by SettingsPrefs.overlayDurationSeconds(context)
        .collectAsState(initial = null)
    val interceptionEnabled by SettingsPrefs.interceptionEnabled(context)
        .collectAsState(initial = null)
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker && monitoredPackages != null) {
        AppPickerDialog(
            currentSelection = monitoredPackages!!,
            onConfirm = { newSelection ->
                scope.launch { SettingsPrefs.setMonitoredPackages(context, newSelection) }
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    // One top-level LazyColumn for the whole screen. The sections above
    // History outgrew a single screen on real devices, and a
    // verticalScroll Column can't host the History LazyColumn (a lazy
    // list inside an unbounded-height scrollable is an IllegalState) —
    // so the fixed sections become item {} blocks and History's rows are
    // this list's items() directly, replacing the old inner LazyColumn.
    LazyColumn(modifier = modifier.padding(horizontal = 20.dp)) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader("Setup")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PermissionRow(
                        label = "Accessibility service",
                        granted = accessibilityGranted,
                        onOpenSettings = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionRow(
                        label = "Draw over other apps",
                        granted = overlayGranted,
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    PermissionRow(
                        label = "Battery optimization off",
                        granted = batteryExempt,
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                    )
                }
            }
        }

        item {
            // Extra keep-alive steps for OEM skins that kill background
            // services aggressively; collapsed behind a toggle for everyone
            // else so it doesn't clutter the common path.
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOemGuidance = !showOemGuidance }
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Keep alive on ${oemGuidance.brandLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (showOemGuidance) "Tap to hide" else "Tap for steps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showOemGuidance) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = oemGuidance.instructions,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Interception")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Master switch — pauses the service's processing
                    // entirely, without touching the OS-level permission.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Interception active",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (interceptionEnabled == false) {
                                    "Paused — no overlays will appear"
                                } else {
                                    "Overlay shows when a monitored app opens"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = interceptionEnabled ?: true,
                            enabled = interceptionEnabled != null,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    SettingsPrefs.setInterceptionEnabled(context, enabled)
                                }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Monitored apps — which packages trigger the overlay.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Monitored apps",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = when (val count = monitoredPackages?.size) {
                                    null -> "Loading…"
                                    0 -> "None selected"
                                    1 -> "1 app"
                                    else -> "$count apps"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showAppPicker = true },
                            enabled = monitoredPackages != null
                        ) {
                            Text("Choose")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Overlay duration — countdown length presets.
                    Text(
                        text = "Overlay countdown",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        SettingsPrefs.OVERLAY_DURATION_OPTIONS.forEach { seconds ->
                            FilterChip(
                                selected = overlayDuration == seconds,
                                enabled = overlayDuration != null,
                                onClick = {
                                    scope.launch {
                                        SettingsPrefs.setOverlayDurationSeconds(context, seconds)
                                    }
                                },
                                label = { Text("${seconds}s") },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader(if (editingTaskId != null) "Edit task" else "New task")
            OutlinedTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                label = { Text("What should you be doing?") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Priority.entries.forEach { priority ->
                    FilterChip(
                        selected = priorityInput == priority,
                        onClick = { priorityInput = priority },
                        label = { Text(priorityLabel(priority)) },
                        leadingIcon = {
                            PriorityDot(priority = priority, size = 8.dp)
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val title = titleInput.trim()
                        if (title.isNotEmpty()) {
                            scope.launch {
                                val idBeingEdited = editingTaskId
                                if (idBeingEdited != null) {
                                    taskDao.updateTask(idBeingEdited, title, priorityInput)
                                } else {
                                    taskDao.insert(
                                        Task(
                                            title = title,
                                            createdAt = System.currentTimeMillis(),
                                            priority = priorityInput
                                        )
                                    )
                                }
                                titleInput = ""
                                priorityInput = Priority.MEDIUM
                                editingTaskId = null
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (editingTaskId != null) "Update Task" else "Save Task")
                }
                if (editingTaskId != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            editingTaskId = null
                            titleInput = ""
                            priorityInput = Priority.MEDIUM
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Active tasks")
            if (activeTasks.isEmpty()) {
                Text(
                    text = "No active tasks set",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                activeTasks.forEachIndexed { index, task ->
                    if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                    ActiveTaskCard(
                        task = task,
                        onEdit = {
                            titleInput = task.title
                            editingTaskId = task.id
                            priorityInput = task.priority
                        },
                        onDone = { scope.launch { taskDao.markDone(task.id) } }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("History")
            if (allTasks.isEmpty()) {
                Text(
                    text = "No tasks saved yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(allTasks, key = { it.id }) { task ->
            HistoryRow(task = task)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ActiveTaskCard(
    task: Task,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            // Tap to edit in place: pre-fill the field with the current
            // title so the user edits rather than retypes.
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PriorityDot(priority = task.priority, size = 10.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${priorityLabel(task.priority)} · Tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDone) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun PriorityDot(
    priority: Priority,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .size(size)
            .background(color = priorityColor(priority), shape = CircleShape)
    )
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

private val historyTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")

@Composable
private fun HistoryRow(task: Task, modifier: Modifier = Modifier) {
    val timestamp = remember(task.createdAt) {
        Instant.ofEpochMilli(task.createdAt)
            .atZone(ZoneId.systemDefault())
            .format(historyTimestampFormatter)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PriorityDot(priority = task.priority, size = 8.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = task.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (task.isDone) "$timestamp · Done" else timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}
