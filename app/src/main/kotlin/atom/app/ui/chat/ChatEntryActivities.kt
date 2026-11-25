package atom.app.ui.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import atom.app.ChatRequest
import atom.app.data.Attachment
import atom.app.data.Attachments
import atom.app.graph
import atom.app.ui.MainActivity
import kotlinx.coroutines.launch

/** Opens one conversation from a notification: hands the id to the chat and brings the app forward. */
class ChatLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { graph.postChatRequest(ChatRequest.Open(it)) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "atom.app.EXTRA_CONVERSATION_ID"

        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, ChatLinkActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Share-to-Atom: accepts one or many items of any type. Images become image attachments, other files become
 * paths (copied into Atom's shared folder, since share grants are temporary), text prefills the composer.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = intent
        val app = applicationContext
        val uris = when (share.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(share, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = listOfNotNull(
            share.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            share.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        val type = share.type.orEmpty()
        // Grants on shared URIs last only as long as this activity, so read them before it finishes.
        graph.appScope.launch {
            val attachments = ArrayList<Attachment>()
            var rejected = 0
            var images = 0
            for (uri in uris) {
                val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: type
                val outcome = if (mime.startsWith("image/") && images < Attachments.MAX_IMAGES) {
                    images++
                    Attachments.importImage(app, uri)
                } else {
                    Attachments.importFile(app, uri)
                }
                when (outcome) {
                    is Attachments.Outcome.Added -> attachments += outcome.attachment
                    is Attachments.Outcome.Rejected -> rejected++
                }
            }
            if (text.isNotEmpty() || attachments.isNotEmpty() || rejected > 0) {
                app.graph.postChatRequest(ChatRequest.Share(text, attachments, rejected))
            }
        }.invokeOnCompletion {
            runOnUiThread {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
    }
}
