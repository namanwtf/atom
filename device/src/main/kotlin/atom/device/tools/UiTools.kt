package atom.device.tools

import android.content.Context
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
import atom.device.Accessibility
import atom.device.screen.Screenshots
import atom.device.screen.ShellUi
import atom.device.screen.UiController
import atom.device.screen.UiError

internal class UiTools(private val context: Context) {
    private val screenChars = 6_000

    fun all(): List<Tool> = listOf(readScreen(), tap(long = false), tap(long = true), typeText(), paste(), scroll(), swipe(), globalAction())

    private fun readScreen() = SimpleTool(
        name = "read_screen",
        description = "Read the screen: foreground app plus an outline of visible elements, each with an id " +
            "(n1, n2…) for tap/type_text/scroll, valid until the next read_screen. Flags: click, long, edit, " +
            "scroll, checked. Password fields are never shown. Set screenshot=true only when the outline is not " +
            "enough; images are expensive.",
        risk = Risk.Read,
        parameters = schema {
            boolean("screenshot", "Also attach a downscaled screenshot. Default false.")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val wantShot = args.optBoolean("screenshot") == true
        val sh = UiController.shell
        if (Accessibility.service == null && ShellUi.usable(sh)) {
            return@SimpleTool try {
                val text = ShellUi.read(context, sh!!, screenChars)
                if (wantShot) {
                    val shot = ShellUi.screenshot(context, sh)
                    ToolResult(text + "\n" + shot.note, images = listOf(shot.image))
                } else {
                    ToolResult.ok(text)
                }
            } catch (e: UiError) {
                ToolResult.error(e.message ?: "UI dump failed. " + UiController.NOT_CONNECTED)
            }
        }
        UiController.tool { svc ->
            val text = UiController.read(svc, screenChars)
            if (!wantShot) return@tool ToolResult.ok(text)
            try {
                val shot = Screenshots.capture(svc)
                ToolResult(text + "\n" + shot.note, images = listOf(shot.image))
            } catch (e: UiError) {
                if (ShellUi.usable(sh)) {
                    try {
                        val shot = ShellUi.screenshot(context, sh!!)
                        return@tool ToolResult(text + "\n" + shot.note, images = listOf(shot.image))
                    } catch (_: UiError) {
                    }
                }
                ToolResult.ok(text + "\n" + e.message)
            }
        }
    }

    private fun tap(long: Boolean) = SimpleTool(
        name = if (long) "long_press" else "tap",
        description = (if (long) "Long-press (press and hold)" else "Tap") +
            " an element by id from read_screen (preferred), or a screen point x,y in pixels only when no element fits. " +
            "Waits for the screen to settle and reports done or \"submitted, unconfirmed\".",
        risk = Risk.Act,
        parameters = schema {
            string("id", "Element id from the latest read_screen, e.g. n12.")
            integer("x", "Screen x in pixels (when no id).")
            integer("y", "Screen y in pixels (when no id).")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val id = args.optString("id")
        val sh = UiController.shell
        if (Accessibility.service == null && ShellUi.usable(sh)) {
            val (x, y) = if (id != null) {
                val b = ShellUi.boxFor(id) ?: return@SimpleTool ToolResult.error("Unknown element id '$id'. Call read_screen again.")
                b.centerX to b.centerY
            } else {
                (args.optInt("x") ?: throw ToolArgumentException("Give id, or x and y.")) to
                    (args.optInt("y") ?: throw ToolArgumentException("Give id, or x and y."))
            }
            return@SimpleTool if (ShellUi.tap(sh!!, x, y, long)) {
                ToolResult.ok("Outcome: submitted, unconfirmed. ${if (long) "Long-pressed" else "Tapped"} ($x,$y) via shell input; accessibility is off, so call read_screen to see the result.")
            } else {
                ToolResult.error("The shell input command failed.")
            }
        }
        UiController.tool { svc ->
            val done = UiController.tap(svc, id, args.optInt("x"), args.optInt("y"), long)
            UiController.outcome(svc, done)
        }
    }

    private fun typeText() = SimpleTool(
        name = "type_text",
        description = "Type into a text field: id from read_screen (a line marked edit), or the focused field " +
            "if id is omitted. Replaces the field's text unless append=true; clear with text=\"\". submit=true then " +
            "presses Enter/Search. The field is read back to confirm.",
        risk = Risk.Act,
        parameters = schema {
            string("text", "Text to enter.", required = true)
            string("id", "Field id from the latest read_screen.")
            boolean("append", "Add to the existing text instead of replacing it. Default false.")
            boolean("submit", "Press the keyboard's Enter/Search/Send key afterwards. Default false.")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val text = args.string("text")
        UiController.tool { svc ->
            val typed = UiController.typeText(
                svc,
                args.optString("id"),
                text,
                append = args.optBoolean("append") == true,
                submit = args.optBoolean("submit") == true,
            )
            UiController.outcome(svc, typed.summary, verified = typed.verified)
        }
    }

    private fun paste() = SimpleTool(
        name = "paste_text",
        description = "Paste into a text field (id from read_screen, or the focused field). With text, that text is " +
            "put on the clipboard first. Use when type_text is refused or for long text.",
        risk = Risk.Act,
        parameters = schema {
            string("id", "Field id from the latest read_screen.")
            string("text", "Text to place on the clipboard and paste. Omit to paste what is already there.")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        UiController.tool { svc ->
            args.optString("text")?.let { t ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    svc.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Atom", t))
                }
            }
            val typed = UiController.paste(svc, args.optString("id"))
            UiController.outcome(svc, typed.summary, verified = typed.verified)
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
        usesPhoneUi = true,
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
        usesPhoneUi = true,
    ) { args, _ ->
        val ms = (args.optInt("duration_ms") ?: 300).toLong().coerceIn(50, 5_000)
        val sh = UiController.shell
        if (Accessibility.service == null && ShellUi.usable(sh)) {
            val ok = ShellUi.swipe(sh!!, args.int("x1"), args.int("y1"), args.int("x2"), args.int("y2"), ms)
            return@SimpleTool if (ok) ToolResult.ok("Outcome: submitted, unconfirmed. Swiped via shell input; call read_screen.")
            else ToolResult.error("The shell input command failed.")
        }
        UiController.tool { svc ->
            val done = UiController.swipe(svc, args.int("x1"), args.int("y1"), args.int("x2"), args.int("y2"), ms)
            UiController.outcome(svc, done)
        }
    }

    private val globals = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen")

    private fun globalAction() = SimpleTool(
        name = "global_action",
        description = "System navigation: back, home, recents (app switcher), notifications (open the shade), " +
            "quick_settings, lock_screen. For power, volume and mute keys use press_key.",
        risk = Risk.Act,
        parameters = schema {
            string("action", "The system action.", required = true, enum = globals)
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val action = args.string("action").lowercase()
        val sh = UiController.shell
        if (Accessibility.service == null && ShellUi.usable(sh)) {
            val key = when (action) {
                "back" -> "KEYCODE_BACK"; "home" -> "KEYCODE_HOME"; "recents" -> "KEYCODE_APP_SWITCH"
                "notifications" -> null; "quick_settings" -> null; else -> "KEYCODE_SLEEP"
            }
            val cmd = when (action) {
                "notifications" -> "cmd statusbar expand-notifications"
                "quick_settings" -> "cmd statusbar expand-settings"
                else -> "input keyevent $key"
            }
            return@SimpleTool if (sh!!.run(cmd, 10_000).ok) ToolResult.ok("Outcome: submitted, unconfirmed. Performed $action via shell; call read_screen.")
            else ToolResult.error("The shell could not perform $action.")
        }
        UiController.tool { svc -> UiController.outcome(svc, UiController.globalAction(svc, action)) }
    }
}
