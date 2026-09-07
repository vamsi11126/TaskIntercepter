package com.akki.taskintercept

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First-run explanation of why the app needs its two unusual permissions.
 * Education only — no permission mechanics live here; "Get started" hands
 * off to the main screen, whose existing Setup rows do the actual
 * deep-linking into Settings.
 */
@Composable
fun OnboardingScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "TaskIntercept",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "A moment of choice before the scroll.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "When you open Instagram or YouTube, TaskIntercept shows " +
                "your planned tasks first — so continuing is a decision, " +
                "not a default. To do that it needs two permissions:",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(20.dp))
        PermissionExplainerCard(
            title = "Accessibility service",
            body = "This is how TaskIntercept notices that Instagram or " +
                "YouTube just came to the foreground. It listens to " +
                "window-change events only — it cannot and does not read " +
                "anything on your screen: no messages, no passwords, no " +
                "content. That capability is disabled at the system level " +
                "in this app’s configuration."
        )
        Spacer(modifier = Modifier.height(12.dp))
        PermissionExplainerCard(
            title = "Display over other apps",
            body = "This lets the task brief appear on top of the app you " +
                "just opened. It’s only ever shown at that moment, and " +
                "both buttons on it are always a real choice — nothing is " +
                "blocked, nothing is forced."
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "You’ll grant both from the Setup section on the next " +
                "screen — each row has a button that takes you straight to " +
                "the right Settings page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get started")
        }
    }
}

@Composable
private fun PermissionExplainerCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
