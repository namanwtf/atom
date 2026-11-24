package atom.device.tools

import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.int
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.screen.Screenshots
import atom.device.screen.UiController
import atom.device.screen.UiError

internal object UiTools {
    private const val SCREEN_CHARS = 6_000

    fun all(): List<Tool> = listOf(readScreen(), tap(long = false), tap(long = true), typeText(), scroll(), swipe(), globalAction())

    private fun readScreen() = SimpleTool(
        name = "read_screen",
        description = "Read the screen: foreground app plus an outline of visible elements, each with an id " +
            "(n1, n2…) for tap/type_text/scroll, valid until the next read_screen. Flags: click, long, edit, " +
            "scroll, checked. Set screenshot=true only when the outline is not enough; images are expensive.",
        risk = Risk.Read,
        parameters = schema {
            boolean("screenshot", "Also attach a downscaled screenshot. Default false.")
        },
    ) { args, _ ->
        UiController.tool { svc ->
            val text = UiController.read(svc, SCREEN_CHARS)
            if (args.optBoolean("screenshot") == true) {
                try {
                    val shot = Screenshots.capture(svc)
                    ToolResult(text + "\n" + shot.note, images = listOf(shot.image))
                } catch (e: UiError) {
                    ToolResult.ok(text + "\n" + e.message)
                }
            } else {
                ToolResult.ok(text)
            }
        }
    }

    private fun tap(long: Boolean) = SimpleTool(
        name = if (long) "long_press" else "tap",
        description = (if (long) "Long-press (press and hold)" else "Tap") +
            " an element by id from read_screen, or a screen point x,y in pixels. Waits for the screen to settle.",
        risk = Risk.Act,
        parameters = schema {
            string("id", "Element id from the latest read_screen, e.g. n12.")
            integer("x", "Screen x in pixels (when no id).")
            integer("y", "Screen y in pixels (when no id).")
        },
    ) { args, _ ->
        UiController.tool { svc ->
            val done = UiController.tap(svc, args.optString("id"), args.optInt("x"), args.optInt("y"), long)
            UiController.outcome(svc, done)
        }
    }

    private fun typeText() = SimpleTool(
        name = "type_text",
        description = "Type into a text field: id from read_screen (a line marked edit), or the focused field " +
            "if id is omitted. Replaces the field's text unless append=true. submit=true then presses Enter/Search.",
        risk = Risk.Act,
        parameters = schema {
            string("text", "Text to enter.", required = true)
            string("id", "Field id from the latest read_screen.")
            boolean("append", "Add to the existing text instead of replacing it. Default false.")
            boolean("submit", "Press the keyboard's Enter/Search/Send key afterwards. Default false.")
        },
    ) { args, _ ->
        val text = args.string("text")
        UiController.tool { svc ->
            val done = UiController.typeText(
                svc,
                args.optString("id"),
                text,
                append = args.optBoolean("append") == true,
                submit = args.optBoolean("submit") == true,
            )
            UiController.outcome(svc, done)
        }
    }

    private val directions = listOf("up", "down", "left", "right")

    private fun scroll() = SimpleTool(
        name = "scroll",
        description = "Scroll a list or page. direction names the content you want to see: down reveals what " +
            "is further down. Without id, scrolls the largest scrollable area on screen.",
        risk = Risk.Act,
        parameters = schema {
            string("direction", "Which way to scroll.", required = true, enum = directions)
            string("id", "Id of the list (or any element inside it) from read_screen.")
        },
    ) { args, _ ->
        val direction = args.string("direction").lowercase()
        if (direction !in directions) throw ToolArgumentException("direction must be one of ${directions.joinToString()}")
        UiController.tool { svc ->
            UiController.outcome(svc, UiController.scroll(svc, args.optString("id"), direction))
        }
    }

    private fun swipe() = SimpleTool(
        name = "swipe",
        description = "Drag a finger between two screen points in pixels, over duration_ms (default 300). " +
            "For gestures scroll cannot express: pagers, sliders, pull-to-refresh, dismissing cards.",
        risk = Risk.Act,
        parameters = schema {
            integer("x1", "Start x.", required = true)
            integer("y1", "Start y.", required = true)
            integer("x2", "End x.", required = true)
            integer("y2", "End y.", required = true)
            integer("duration_ms", "Gesture length, 50-5000. Default 300; longer is slower and more precise.")
        },
    ) { args, _ ->
        UiController.tool { svc ->
            val done = UiController.swipe(
                svc, args.int("x1"), args.int("y1"), args.int("x2"), args.int("y2"),
                (args.optInt("duration_ms") ?: 300).toLong(),
            )
            UiController.outcome(svc, done)
        }
    }

    private val globals = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")

    private fun globalAction() = SimpleTool(
        name = "global_action",
        description = "System navigation: back, home, recents (app switcher), notifications (open the shade), " +
            "quick_settings, lock_screen.",
        risk = Risk.Act,
        parameters = schema {
            string("action", "The system action.", required = true, enum = globals)
        },
    ) { args, _ ->
        val action = args.string("action").lowercase()
        UiController.tool { svc -> UiController.outcome(svc, UiController.globalAction(svc, action)) }
    }
}
