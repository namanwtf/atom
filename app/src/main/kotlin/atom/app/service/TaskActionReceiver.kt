package atom.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import atom.app.graph

/** Handles the notification's Stop / Allow / Deny buttons. Not exported: only our PendingIntents reach it. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        when (intent.action) {
            ACTION_STOP -> graph.tasks.stop()
            ACTION_APPROVE, ACTION_DENY -> {
                val id = intent.getLongExtra(EXTRA_APPROVAL_ID, -1)
                if (id >= 0) graph.tasks.answer(id, approved = intent.action == ACTION_APPROVE)
                graph.notifications.cancelApproval()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "atom.app.action.STOP"
        const val ACTION_APPROVE = "atom.app.action.APPROVE"
        const val ACTION_DENY = "atom.app.action.DENY"
        const val EXTRA_APPROVAL_ID = "approval_id"
    }
}
