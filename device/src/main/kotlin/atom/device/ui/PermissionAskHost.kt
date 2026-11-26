package atom.device.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.AtomDialogFor
import atom.ui.components.DialogButtons
import atom.ui.permission.PermissionAsk
import atom.ui.permission.PermissionAsks

/**
 * Shows the just-in-time permission requests that tools and features raise through [PermissionAsks]: one at a
 * time, as a small dialog with the one-sentence reason, then the system prompt (or the settings page for special
 * access). Whatever the user answers is remembered, so the same permission is never prompted for again from here.
 * Placed once at the root of the app's UI; it shows nothing until something asks.
 */
@Composable
fun PermissionAskHost() {
    val context = LocalContext.current
    val queue by PermissionAsks.pending.collectAsStateWithLifecycle()
    var requesting by remember { mutableStateOf<PermissionAsk?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting?.let { PermissionAsks.answered(context, it) }
        requesting = null
    }
    // An ask the user could no longer connect to what they were doing is dropped, not shown late.
    LaunchedEffect(queue) {
        val now = System.currentTimeMillis()
        queue.filter { now - it.raisedAt > PermissionAsks.STALE_MS }.forEach(PermissionAsks::drop)
    }
    val now = System.currentTimeMillis()
    val ask = if (requesting != null) null else queue.firstOrNull { now - it.raisedAt <= PermissionAsks.STALE_MS }

    AtomDialogFor(
        ask,
        onDismiss = { ask?.let { PermissionAsks.answered(context, it) } },
        title = { "Allow ${it.name}?" },
        summary = { it.reason },
    ) { a ->
        DialogButtons(
            confirmText = if (a.permissions.isEmpty()) "Open settings" else "Continue",
            onConfirm = {
                if (a.permissions.isNotEmpty()) {
                    requesting = a
                    runCatching { launcher.launch(a.permissions.toTypedArray()) }.onFailure {
                        requesting = null
                        PermissionAsks.answered(context, a)
                    }
                } else {
                    PermissionAsks.answered(context, a)
                    a.settings?.let { context.open(it) }
                }
            },
            dismissText = "Not now",
            onDismiss = { PermissionAsks.answered(context, a) },
        )
    }
}
