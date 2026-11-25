package atom.vdisplay.viewfinder

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import atom.overlay.ViewfinderSlot
import atom.ui.feature.HostRun
import atom.vdisplay.R
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.LeaseState

/** Fills the run capsule's viewfinder slot: a tiny live view of the hidden display; tap opens the stage. */
object BackgroundViewfinder : ViewfinderSlot {
    /** Mini view frame cap: it is a glance, not a mirror. */
    private const val MINI_FPS = 6

    /** The app each run last drove on the hidden display, for "Open screen" once the run has ended. */
    private val lastTarget = HashMap<String, String>()

    override fun shows(run: HostRun): Boolean = when (val s = DisplayRouter.state.value) {
        is LeaseState.Active -> {
            if (!s.lease.main) s.lease.targetPackage?.let { note(run.conversationId, it) }
            !s.lease.main
        }
        is LeaseState.Lost -> true // shows "Display unavailable" instead of silently vanishing
        LeaseState.Idle -> false
    }

    private fun note(conversationId: String, pkg: String) {
        synchronized(lastTarget) {
            if (lastTarget.size > 8 && conversationId !in lastTarget) lastTarget.clear()
            lastTarget[conversationId] = pkg
        }
    }

    /** The live stage while the hidden display is still up; afterwards the app it worked in, on the main screen. */
    override fun openScreen(context: Context, conversationId: String): Boolean {
        val s = DisplayRouter.state.value
        if (s is LeaseState.Active && !s.lease.main) {
            Stage.show(context, conversationId)
            return true
        }
        val pkg = synchronized(lastTarget) { lastTarget[conversationId] } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    @Composable
    override fun Viewfinder(run: HostRun, modifier: Modifier) {
        val context = LocalContext.current
        val label = stringResource(R.string.vd_viewfinder_open)
        Box(
            modifier
                .background(Color.Black)
                .semantics { contentDescription = label }
                .clickable { Stage.show(context, run.conversationId) },
        ) {
            LivePreview(MINI_FPS, Modifier.fillMaxSize())
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.vd_viewfinder_label), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
            Icon(
                VdIcons.Fullscreen,
                null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(18.dp),
            )
        }
    }
}
