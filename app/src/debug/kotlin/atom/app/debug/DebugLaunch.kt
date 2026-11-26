package atom.app.debug

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import atom.app.ChatRequest
import atom.app.data.Conversation
import atom.app.data.StoredMessage
import atom.app.data.TitleSource
import atom.app.graph
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ToolCall
import kotlinx.coroutines.launch

/**
 * Debug builds only (this file lives in src/debug; release builds compile the no-op in src/release).
 *
 * Lets tools/screenshots/capture.py show a long conversation on a CI emulator, which has no model key:
 *
 *     am start -n <pkg>/atom.app.ui.MainActivity --es atom.app.debug.EXTRA_DEMO_TRANSCRIPT open
 *
 * "open" saves a clearly fake sample conversation under a fixed id and opens it; "failed" does the same with a short
 * sample whose run failed (for the "Report a problem" action under it); "clear" deletes both again.
 * "site" saves and opens a sample run that made the website "atom-demo" (its tool calls end in the site's preview
 * card; tools/screenshots/capture.py creates the site itself first); "site_clear" deletes that conversation.
 */
internal object DebugLaunch {
    private const val EXTRA_DEMO = "atom.app.debug.EXTRA_DEMO_TRANSCRIPT"
    private const val DEMO_ID = "debug-demo-transcript"
    private const val FAILED_ID = "debug-demo-failed"
    private const val SITE_DEMO_ID = "debug-demo-site"

    fun handle(activity: ComponentActivity, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_DEMO) ?: return
        intent.removeExtra(EXTRA_DEMO)
        val graph = activity.graph
        activity.lifecycleScope.launch {
            when (action) {
                "open" -> {
                    graph.conversations.save(demo())
                    graph.runtimePrefs.setReadingPosition(DEMO_ID, null)
                    graph.postChatRequest(ChatRequest.Open(DEMO_ID))
                }
                "failed" -> {
                    graph.conversations.save(failed())
                    graph.runtimePrefs.setReadingPosition(FAILED_ID, null)
                    graph.postChatRequest(ChatRequest.Open(FAILED_ID))
                }
                "clear" -> {
                    graph.conversations.delete(DEMO_ID)
                    graph.runtimePrefs.setReadingPosition(DEMO_ID, null)
                    graph.conversations.delete(FAILED_ID)
                    graph.runtimePrefs.setReadingPosition(FAILED_ID, null)
                }
                "site" -> {
                    graph.conversations.save(siteDemo())
                    graph.runtimePrefs.setReadingPosition(SITE_DEMO_ID, null)
                    graph.postChatRequest(ChatRequest.Open(SITE_DEMO_ID))
                }
                "site_clear" -> {
                    graph.conversations.delete(SITE_DEMO_ID)
                    graph.runtimePrefs.setReadingPosition(SITE_DEMO_ID, null)
                }
            }
        }
    }

    private fun demo(): Conversation {
        val start = System.currentTimeMillis() - 60 * 60 * 1000L
        val messages = SAMPLE.flatMapIndexed { i, (question, answer) ->
            val at = start + i * 120_000L
            listOf(
                StoredMessage(ChatMessage.user(question), createdAt = at),
                StoredMessage(ChatMessage.assistant(answer), createdAt = at + 30_000L, durationMs = 2_400L),
            )
        }
        return Conversation(
            id = DEMO_ID,
            title = "Demo transcript (debug sample)",
            createdAt = start,
            updatedAt = start + SAMPLE.size * 120_000L,
            messages = messages,
            titleSource = TitleSource.User,
        )
    }

    /** A made-up run that failed: one failed tool step, then the run's error notice. */
    private fun failed(): Conversation {
        val at = System.currentTimeMillis() - 5 * 60 * 1000L
        val call = ToolCall("dbgFail01", "set_do_not_disturb", """{"enabled":true}""")
        return Conversation(
            id = FAILED_ID,
            title = "Failed run (debug sample)",
            createdAt = at,
            updatedAt = at + 20_000L,
            messages = listOf(
                StoredMessage(ChatMessage.user("Sample request: turn on Do Not Disturb."), createdAt = at),
                StoredMessage(ChatMessage.assistant("", listOf(call)), createdAt = at + 8_000L),
                StoredMessage(
                    ChatMessage.toolResult(call, listOf(ContentPart.Text("Sample error: Do Not Disturb access is not allowed for this debug sample."))),
                    isError = true,
                    durationMs = 320L,
                    createdAt = at + 9_000L,
                ),
                StoredMessage.notice("Sample failure for screenshots: the provider stopped answering.", at + 20_000L).copy(isError = true),
            ),
            titleSource = TitleSource.User,
        )
    }

    /** A hand-written run that made the debug site "atom-demo": three tool calls, then a short answer. */
    private fun siteDemo(): Conversation {
        val start = System.currentTimeMillis() - 10 * 60 * 1000L
        val calls = listOf(
            ToolCall("demoSite1", "site_create", "{\"name\":\"Atom demo\"}") to
                "Created site atom-demo with a placeholder index.html. Write its files with site_write, then show it with site_preview.",
            ToolCall("demoSite2", "site_write", "{\"path\":\"index.html\",\"content\":\"(debug sample)\"}") to
                "Wrote index.html (1.2 KB) in site atom-demo. Show it with site_preview.",
            ToolCall("demoSite3", "site_preview", "{\"name\":\"atom-demo\"}") to
                "The live preview of \"Water tracker\" is open on the phone.\nURL: http://127.0.0.1:0/ (debug sample)\n" +
                "Site preview: atom-demo",
        )
        val messages = buildList {
            add(StoredMessage(ChatMessage.user("Sample request: make me a small website that tracks my water intake."), createdAt = start))
            calls.forEachIndexed { i, (call, result) ->
                val at = start + 10_000L * (i + 1)
                add(StoredMessage(ChatMessage.assistant("", listOf(call)), createdAt = at))
                add(StoredMessage(ChatMessage.toolResult(call, listOf(ContentPart.Text(result))), durationMs = 400L, createdAt = at + 400L))
            }
            add(
                StoredMessage(
                    ChatMessage.assistant("This is a **debug sample**: the water tracker site is ready. Tap Open preview to try it."),
                    createdAt = start + 60_000L,
                    durationMs = 1_800L,
                ),
            )
        }
        return Conversation(
            id = SITE_DEMO_ID,
            title = "Site demo (debug sample)",
            createdAt = start,
            updatedAt = start + 60_000L,
            messages = messages,
            titleSource = TitleSource.User,
        )
    }

    /** Made-up sample text for screenshots. Nothing here came from a model. */
    private val SAMPLE = listOf(
        "Sample question one: how should I lay out a small reading nook?" to """
            This is a **debug sample answer**, written by hand for screenshots.

            1. **Pick the corner.** A spot next to a window gives daylight for most of the afternoon.
            2. **Choose one good chair.** Comfort matters more than style; try it for ten minutes before buying.
            3. **Add a lamp** at shoulder height behind the chair, so the page is lit without glare.
               - A warm bulb (around 2700K) is easier on the eyes in the evening.
               - A dimmer helps when the nook doubles as a place to rest.
            4. **Keep a small shelf** within reach for the current pile of books.

            Which part would you like to start with?
        """.trimIndent(),
        "Sample question two: the window faces west. Does that change anything?" to """
            Another hand-written sample. A west window means strong, low sun late in the day:

            - A sheer curtain softens the glare without darkening the room.
            - Turn the chair so the light comes over your shoulder, not into your eyes.
            - Plants on the sill will like the warmth, but water them a little more often.

            In the morning the corner stays cooler and dimmer, so the lamp will do more of the work then.
        """.trimIndent(),
        "Sample question three: list a few colour schemes that would work." to """
            Sample answer three. A few calm combinations:

            1. **Sand and olive**: warm beige walls, an olive armchair, oak shelf.
            2. **Slate and brass**: blue-grey walls, a brass lamp, cream cushions.
            3. **White and terracotta**: bright walls with a terracotta throw and clay pots.
            4. **Forest and walnut**: deep green paint behind a walnut bookcase.

            Each of these keeps the corner restful, which suits long reading sessions.
        """.trimIndent(),
        "Sample question four: what about a rug?" to """
            Sample answer four. A rug anchors the nook and makes it feel like its own place:

            - Size it so at least the chair's front legs sit on it.
            - A low pile is easier to clean and keeps a footstool steady.
            - A simple pattern hides wear better than a plain colour.

            Round rugs work well in corners because they soften the right angle of the walls.
        """.trimIndent(),
        "Sample question five: summarise the plan in a short checklist." to """
            Sample answer five, the checklist:

            - Corner by the west window
            - Comfortable chair, tried in person
            - Lamp behind the chair, warm dimmable bulb
            - Sheer curtain for the late sun
            - Low-pile rug under the chair's front legs
            - Small shelf for the current books

            That is the whole plan. Scroll up to see each step in detail.
        """.trimIndent(),
    )
}
