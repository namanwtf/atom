package atom.app.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import atom.app.graph

/** Handles the notification buttons. Not exported: only our PendingIntents reach it. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        when (intent.action) {
            ACTION_STOP -> if (conversationId != null) graph.tasks.stop(conversationId) else graph.tasks.stopAll()
            ACTION_STOP_ALL -> graph.tasks.stopAll()
            ACTION_PAUSE -> conversationId?.let(graph.tasks::pause)
            ACTION_RESUME -> conversationId?.let(graph.tasks::resume)
            ACTION_APPROVE, ACTION_DENY -> {
                // Allow asks for the device credential on Android 12+; on older versions an Allow tapped on the
                // lock screen is ignored and the notification stays, so it can be answered after unlocking.
                if (intent.action == ACTION_APPROVE && context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) return
                val id = intent.getLongExtra(EXTRA_APPROVAL_ID, -1)
                if (id >= 0) graph.tasks.answer(id, approved = intent.action == ACTION_APPROVE)
                graph.notifications.cancelApproval()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "atom.app.action.STOP"
        const val ACTION_STOP_ALL = "atom.app.action.STOP_ALL"
        const val ACTION_PAUSE = "atom.app.action.PAUSE"
        const val ACTION_RESUME = "atom.app.action.RESUME"
        const val ACTION_APPROVE = "atom.app.action.APPROVE"
        const val ACTION_DENY = "atom.app.action.DENY"
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
