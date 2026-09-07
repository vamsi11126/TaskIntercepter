package com.akki.taskintercept

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed, launchable app as shown in the picker. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Loads all launchable installed apps (label + icon), sorted by label.
 * Excludes this app itself — intercepting TaskIntercept would fight the
 * overlay's own windows. Runs on IO: PackageManager label/icon loads are
 * slow enough to jank the main thread with many apps installed.
 */
private suspend fun loadLaunchableApps(context: Context): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            // "Has a launcher intent" is the picker's definition of an app
            // the user can actually open — filters out system components.
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    icon = pm.getApplicationIcon(it)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

/**
 * Dialog for choosing which installed apps get intercepted. Selection is
 * local state while open; [onConfirm] receives the final set only on
 * "Save" — cancelling discards changes, and DataStore is written exactly
 * once per edit rather than per checkbox toggle.
 */
@Composable
fun AppPickerDialog(
    currentSelection: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var selection by remember { mutableStateOf(currentSelection) }

    LaunchedEffect(Unit) {
        apps = loadLaunchableApps(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monitored apps") },
        text = {
            val loaded = apps
            if (loaded == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                // Height-capped so the dialog doesn't grow past the screen
                // on devices with many apps; the list scrolls inside it.
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(loaded, key = { it.packageName }) { app ->
                        val checked = app.packageName in selection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = remember(app.packageName) {
                                    app.icon.toBitmap(96, 96).asImageBitmap()
                                },
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = app.label,
                                modifier = Modifier.weight(1f)
                            )
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { nowChecked ->
                                    selection = if (nowChecked) {
                                        selection + app.packageName
                                    } else {
                                        selection - app.packageName
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
