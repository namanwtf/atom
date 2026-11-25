package atom.context.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import atom.context.notifications.AtomNotificationListener
import atom.context.notifications.NotificationRecord
import atom.context.notifications.Notifications
import atom.context.search.one
import atom.context.sources.When
import atom.ui.components.AtomCard
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.StatusLine
import atom.ui.feature.FeatureNav
import atom.ui.theme.extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search the local notification history. Loads one page per query; nothing refreshes on its own. */
@Composable
fun NotificationHistoryScreen(nav: FeatureNav) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Notifications.accessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = Notifications.accessGranted(context); tick++ }
    val page by produceState(initialValue = emptyList<NotificationRecord>() to 0, query, tick) {
        value = withContext(Dispatchers.IO) { Notifications.history(context).search(query, null, null, 0, 100) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear notification history?") },
            text = { Text("Deletes every recorded notification from this phone. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Notifications.history(context).clear() }
                        tick++
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    LargeTitleScreen(title = "Notification history", onBack = nav::back) {
        item {
            AtomCard {
                StatusLine(
                    if (granted) MaterialTheme.extras.success else MaterialTheme.extras.warning,
                    if (granted) "Recording · ${page.second} matching" else "Not recording: notification access is off",
                )
                Text(
                    "Atom keeps the last 7 days (up to 1000 notifications) on this phone only, and reads it only when you ask.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted) {
                    CompactButton("Turn on", {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, AtomNotificationListener.component(context).flattenToString())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    })
                } else {
                    CompactButton("Clear history", { confirmClear = true }, primary = false)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search notifications") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            if (page.first.isEmpty()) {
                Text("Nothing here yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Group {
                    page.first.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider(18.dp)
                        GroupRow(
                            title = "${r.appLabel} · ${one(r.title, 60)}",
                            subtitle = "${When.ago(r.postedAt)} — ${one(r.text, 160)}",
                        )
                    }
                }
            }
        }
    }
}
