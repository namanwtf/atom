package atom.vdisplay.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import atom.core.llm.ContentPart
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.apps.AppResolver
import atom.vdisplay.geom.Coords
import atom.vdisplay.geom.Size
import atom.vdisplay.io.Commands
import atom.vdisplay.present.Presenter
import atom.vdisplay.route.DisplayLease
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.route.DisplayUnavailable
import atom.vdisplay.route.LeaseManager
import atom.vdisplay.route.LeaseState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

/**
 * Agent tools for the hidden background display. They act only on the hidden display; if it disappears they report
 * "display unavailable" and never fall back to the main screen.
 */
internal class BackgroundTools(private val context: Context) {
    fun all(): List<Tool> = listOf(open(), look(), act(), present(), close())

    /** Screenshot pixel space the agent last saw, per display generation (taps are given in it). */
    @Volatile
    private var lastShot: Pair<Long, Size>? = null

    private class T(
        name: String,
        description: String,
        override val risk: Risk,
        parameters: JsonObject,
        override val usesPhoneUi: Boolean = false,
        val body: suspend (JsonObject, ToolContext) -> ToolResult,
    ) : Tool {
        override val spec = ToolSpec(name, description, parameters)
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            body(args, ctx)
        } catch (e: DisplayUnavailable) {
            ToolResult.error(e.message ?: "Display unavailable.")
        }
    }

    private fun lease(): DisplayLease {
        when (val s = DisplayRouter.state.value) {
            is LeaseState.Active -> if (!s.lease.main) return s.lease
            is LeaseState.Lost -> throw DisplayUnavailable(LeaseManager.unavailable(s.why))
            LeaseState.Idle -> Unit
        }
        throw DisplayUnavailable("No background display is open. Call background_open_app first.")
    }

    private fun open() = T(
        "background_open_app",
        "Open an app on a hidden background display so the user's own screen is untouched (preferred over open_app " +
            "for tasks in other apps), then work in it with " +
            "background_look / background_act. Routing is automatic: if the app is already on screen, or is the " +
            "home screen or system UI, the answer says to use the normal screen tools instead. Optional uri opens a " +
            "deep link in that app.",
        Risk.Act,
        schema {
            string("app", "App name or package name.", required = true)
            string("uri", "Optional deep link / URL to open in the app.")
        },
    ) { args, ctx ->
        val resolved = try {
            AppResolver.resolve(context, args.string("app"))
        } catch (t: Throwable) {
            return@T ToolResult.error(t.message ?: "App not found.")
        }
        val pkg = resolved.packageName
        val lease = DisplayRouter.acquire(pkg, ctx.taskId)
        if (lease.main) {
            return@T ToolResult.ok("Use the normal screen tools for ${resolved.label}: ${lease.reason.text}.")
        }
        val uri = args.optString("uri")
        val err = if (uri != null) DisplayRouter.openUri(lease, uri, pkg) else DisplayRouter.launch(lease, pkg)
        if (err != null) {
            DisplayRouter.release()
            return@T ToolResult.error("$err The background display was closed; use the normal screen tools.")
        }
        delay(900) // first frame of a cold start
        lastShot = null
        ToolResult.ok(
            "Opened ${resolved.label} on the background display (${lease.width}x${lease.height}). The user's screen is " +
                "untouched. Next: background_look. When you reach a payment confirmation, QR code or the final " +
                "result, call present_to_main_screen.",
        )
    }

    private fun look() = T(
        "background_look",
        "See the background display: a screenshot (and the element outline when available). Coordinates for " +
            "background_act are pixels of this screenshot.",
        Risk.Read,
        schema {},
    ) { _, _ ->
        val lease = lease()
        val tree = DisplayRouter.readTree(lease)
        val bmp = DisplayRouter.screenshot(lease)
            ?: return@T if (tree != null) ToolResult.ok(tree) else ToolResult.error("Nothing has been drawn on the background display yet; wait a moment and look again.")
        val shot = withContext(Dispatchers.Default) { encode(bmp) }
        lastShot = lease.generation to shot.second
        val note = "Background display screenshot ${shot.second.width}x${shot.second.height}; give background_act " +
            "coordinates in these pixels. Protected screens appear black."
        ToolResult(listOfNotNull(tree, note).joinToString("\n"), images = listOf(shot.first))
    }

    private fun act() = T(
        "background_act",
        "Act on the background display. action: tap, long_press, swipe (x,y → x2,y2), type (text into the focused " +
            "field), key (back, home, enter, delete, recents). Coordinates are pixels of the last background_look " +
            "screenshot. Look again after acting instead of assuming it worked.",
        Risk.Act,
        schema {
            string("action", "What to do.", required = true, enum = listOf("tap", "long_press", "swipe", "type", "key"))
            integer("x", "X in screenshot pixels (tap, long_press, swipe start).")
            integer("y", "Y in screenshot pixels.")
            integer("x2", "Swipe end X.")
            integer("y2", "Swipe end Y.")
            integer("duration_ms", "Swipe duration, default 300.")
            string("text", "Text for type.")
            string("key", "Key for key.", enum = listOf("back", "home", "enter", "delete", "recents"))
        },
    ) { args, _ ->
        val lease = lease()
        val display = Size(lease.width, lease.height)
        val shot = lastShot?.takeIf { it.first == lease.generation }?.second ?: display
        fun pt(xk: String, yk: String) = run {
            val x = args.optInt(xk) ?: throw IllegalArgumentException("Missing $xk")
            val y = args.optInt(yk) ?: throw IllegalArgumentException("Missing $yk")
            Coords.rescale(x, y, shot, display)
        }
        val action = args.string("action")
        val err = try {
            when (action) {
                "tap", "long_press" -> pt("x", "y").let { DisplayRouter.tap(lease, it.x, it.y, long = action == "long_press") }
                "swipe" -> {
                    val a = pt("x", "y")
                    val b = pt("x2", "y2")
                    DisplayRouter.swipe(lease, a.x, a.y, b.x, b.y, (args.optInt("duration_ms") ?: 300).toLong())
                }
                "type" -> DisplayRouter.type(lease, args.string("text"))
                "key" -> DisplayRouter.key(lease, keyCode(args.string("key")) ?: return@T ToolResult.error("Unknown key."))
                else -> return@T ToolResult.error("Unknown action $action.")
            }
        } catch (e: IllegalArgumentException) {
            return@T ToolResult.error(e.message ?: "Bad arguments.")
        }
        if (err != null) ToolResult.error(err) else ToolResult.ok("$action sent to the background display; submitted, unconfirmed. Look again to check.")
    }

    private fun present() = T(
        "present_to_main_screen",
        "Move the app from the background display onto the user's real screen, with the page as it is. Use it when " +
            "the task reaches a payment confirmation, a QR code to show or scan, or the final result the user should " +
            "see or finish themselves. Closes the background display.",
        Risk.Act,
        schema {},
        usesPhoneUi = true,
    ) { _, _ ->
        when (val o = Presenter.present(lease())) {
            is Presenter.Outcome.Moved -> ToolResult.ok("${o.packageName} is now on the main screen (${o.how}), exactly where it was.")
            is Presenter.Outcome.Relaunched -> ToolResult.ok("${o.packageName} was reopened on the main screen; the page may have reset, so check it with read_screen.")
            is Presenter.Outcome.Failed -> ToolResult.error("Could not present: ${o.why}")
        }
    }

    private fun close() = T(
        "background_close",
        "Close the background display when the task is done there (it also closes by itself when the run ends). " +
            "Apps that were open on it go back to the user's recents.",
        Risk.Read,
        schema {},
    ) { _, _ ->
        DisplayRouter.release()
        ToolResult.ok("Background display closed.")
    }

    private fun keyCode(name: String): Int? = when (name) {
        "back" -> Commands.KEY_BACK
        "home" -> Commands.KEY_HOME
        "enter" -> Commands.KEY_ENTER
        "delete" -> Commands.KEY_DEL
        "recents" -> Commands.KEY_APP_SWITCH
        else -> null
    }

    private fun encode(src: Bitmap): Pair<ContentPart.Image, Size> {
        val target = Coords.screenshotSize(Size(src.width, src.height), MAX_SIDE)
        val scaled = if (target.width != src.width) Bitmap.createScaledBitmap(src, target.width, target.height, true).also { src.recycle() } else src
        val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        val size = Size(scaled.width, scaled.height)
        scaled.recycle()
        return ContentPart.Image("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)) to size
    }

    private companion object {
        const val MAX_SIDE = 1080
        const val QUALITY = 60
    }
}

