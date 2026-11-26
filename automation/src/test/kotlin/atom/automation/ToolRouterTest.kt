package atom.automation

import atom.core.agent.AgentConfig
import atom.core.agent.ContextPolicy
import atom.core.agent.ToolPreload
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolLoadout
import atom.core.tool.ToolResult
import atom.core.tool.schema
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRouterTest {
    private fun top(request: String, minScore: Int = 1) = ToolRouter.route(request, minScore = minScore).firstOrNull()

    private fun groups(request: String, minScore: Int = 1) = ToolRouter.route(request, minScore = minScore)

    @Test
    fun theFailingEndToEndTasks() {
        assertEquals("settings", top("Turn on Do Not Disturb."))
        assertEquals("messages", top("What was my last notification about?"))
        assertEquals("automation", top("Every day at 9am remind me to drink water."))
        assertEquals("browser", top("Open the agent browser and summarize the top story on https://example.com."))
        assertEquals("terminal", top("In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output."))
    }

    @Test
    fun theOwnersBrowserTask() {
        val request = "In Atom's built-in agent browser, search for today's top technology news and summarize it"
        assertEquals("browser", top(request))
        // Small models get it too: "browser" is a strong match on its own.
        assertEquals(listOf("browser"), groups(request, minScore = ToolRouter.STRONG - 1))
    }

    @Test
    fun deviceControls() {
        for (r in listOf(
            "set the volume to 50%", "turn the brightness down", "turn on the flashlight", "switch off wifi",
            "enable bluetooth", "turn on dark mode", "put the phone on silent mode", "turn on airplane mode",
            "Bitte nicht stören einschalten", "activa no molestar", "active le mode ne pas déranger", "Taschenlampe an",
        )) assertEquals("settings", top(r), r)
        for (r in listOf("set an alarm for 6:30", "start a 10 minute timer", "wake me at 7", "stell einen Wecker", "pon una alarma")) {
            assertEquals("clock", top(r), r)
        }
    }

    @Test
    fun notificationsCodesAndMessages() {
        for (r in listOf(
            "what's my OTP?", "read me the verification code I just got", "any new messages from Sam?",
            "show my notifications", "who called me today?", "find Anna in my contacts", "Was steht in meinen Benachrichtigungen?",
        )) assertEquals("messages", top(r), r)
    }

    @Test
    fun remindersAndSchedules() {
        for (r in listOf(
            "remind me tomorrow to call mom", "every morning tell me the weather", "schedule a daily summary at 8",
            "erinnere mich morgen an den Termin", "recuérdame comprar leche", "rappelle-moi demain",
        )) assertTrue("automation" in groups(r), r)
        assertEquals("automation", top("remind me at 7 to stretch"))
    }

    @Test
    fun webAndTerminal() {
        assertEquals("browser", top("log in to my bank website and check the balance"))
        assertEquals("browser", top("open the link www.example.org"))
        for (r in listOf("run ls -la in the shell", "install numpy with pip", "git clone the repo", "write a python script that sorts a list")) {
            assertEquals("terminal", top(r), r)
        }
    }

    @Test
    fun otherGroups() {
        assertEquals("calendar", top("what's on my calendar tomorrow?"))
        assertEquals("calendar", top("move my meeting with Tom to 3pm"))
        assertEquals("personal", top("where am I right now?"))
        assertEquals("personal", top("how much screen time did I have?"))
        assertEquals("phone", top("how much battery is left?"))
        assertEquals("images", top("generate an image of a red fox"))
        assertTrue("files" in groups("summarize the pdf in my downloads"))
    }

    @Test
    fun makingAWebsiteLoadsTheSiteTools() {
        for (r in listOf(
            "make me a small website that tracks my water intake", "Make a one-page site that says hello",
            "build me a snake game", "create a todo app", "make a landing page for my bakery", "write an html page with a clock",
            "erstelle mir eine Webseite für meinen Verein", "hazme una página web para mi tienda", "fais-moi un site pour mon club",
        )) {
            assertEquals("sites", top(r), r)
            // Small models get it too: one strong match is enough.
            assertTrue("sites" in groups(r, minScore = ToolRouter.STRONG - 1), r)
        }
        // The agent browser comes along to check the page.
        assertEquals(listOf("sites", "browser"), groups("make me a small website that tracks my water intake").take(2))
        assertEquals(setOf("sites"), ToolGroups.groupsOf("site_preview"))
    }

    @Test
    fun otherThingsMadeAreNotWebsites() {
        for (r in listOf(
            "write a message in the app to Sam", "make the app louder", "generate an image of a red fox", "create a calendar event",
            "write a python script that sorts a list", "make a call to mom", "set an alarm for 7",
        )) assertTrue("sites" !in groups(r), r)
    }

    @Test
    fun plainChatAndScreenWorkLoadNothing() {
        // Chat needs no tools; screen work, opening apps and web search are in the core set that is always sent.
        for (r in listOf(
            "hi", "What is the capital of France?", "Write a haiku about autumn", "tell me a joke",
            "open Spotify", "tap the blue button", "what's on my screen?", "translate this into German: good morning",
        )) assertEquals(emptyList(), groups(r, minScore = ToolRouter.STRONG - 1), r)
    }

    @Test
    fun weakWordsAloneLoadNothing() {
        // "news" alone is answered with the core web_search; a lone "run", "copy" or "code" is no reason to load a
        // group either, for cloud models too (the default threshold).
        assertEquals(listOf("browser"), groups("what's in the news today?", minScore = 1))
        for (r in listOf("what's in the news today?", "run the numbers again and copy them here", "what does this code do?", "pause for a second and think")) {
            assertEquals(emptyList(), ToolRouter.route(r), r)
        }
    }

    @Test
    fun recentToolUseCounts() {
        assertEquals(listOf("terminal"), ToolRouter.route("now do it again", recentTools = listOf("terminal_run", "read_screen")))
        assertEquals("settings", ToolRouter.route("and a bit lower", recentTools = listOf("set_volume")).first())
    }

    // ---- budget ----------------------------------------------------------------------------------------

    private class Fake(name: String, description: String) : Tool {
        override val spec = ToolSpec(
            name,
            description,
            schema {
                string("target", "What to act on; see the tool description for the accepted values.", required = true)
                integer("count", "How many; the default when left out is one.")
            },
        )
        override val risk = Risk.Read
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("ok")
    }

    /** The core set and the groups the E2E tasks need, with descriptions of the real tools' typical length. */
    private val tools = (ToolGroups.CORE.toList() + listOf(
        "set_do_not_disturb", "set_toggle", "set_volume", "set_brightness", "set_dark_mode", "get_device_controls", "set_sound_mode",
        "set_screen_timeout", "list_changes", "undo_last_change", "undo_change", "undo_changes_today",
        "notifications_current", "notification_history", "sms_search", "sms_verification_code", "call_log_search", "contacts_search",
        "messenger_triage", "read_item", "browser_open", "browser_extract", "browser_find", "browser_click", "browser_type",
        "browser_submit", "browser_scroll", "browser_screenshot", "browser_navigate", "browser_wait", "browser_close",
        "scheduled_task_create", "scheduled_task_list", "scheduled_task_manage", "scheduled_task_delete", "automation_create",
        "terminal_run", "terminal_session", "terminal_job_start", "terminal_job_info", "terminal_job_stop", "shell",
        "set_alarm", "set_timer", "alarms_and_timers", "change_alarm",
    )).map { Fake(it, "Does $it on the phone for the owner and reports the result in a short line, or why it could not.") }

    private fun loadout() = ToolLoadout(tools, { ToolGroups.groupsOf(it.spec.name) }, ToolGroups.SUMMARIES)

    private val small = AgentConfig("m", "s", maxOutputTokens = 512, contextWindow = 4_096, context = ContextPolicy(budgetTokens = 3_200, sharedWindow = 4_096))

    @Test
    fun preloadedToolsFitA4096Window() {
        for (request in listOf(
            "In Atom's built-in agent browser, search for today's top technology news and summarize it",
            "Turn on Do Not Disturb and set the volume to 20%, then remind me every day at 9 to check my messages",
            "In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output.",
        )) {
            val loadout = loadout()
            val routed = ToolRouter.route(request, minScore = ToolRouter.STRONG - 1)
            val pre = ToolPreload.apply(loadout, routed, small)
            assertTrue(pre.groups.isNotEmpty() && pre.groups.size <= ToolPreload.SMALL_MAX_GROUPS, "$request -> ${pre.groups}")
            assertEquals(routed.first(), pre.groups.first(), request)
            val tokens = small.toolTokens(loadout.specs())
            assertTrue(
                tokens + small.maxOutputTokens + ContextPolicy.SHARED_MARGIN + ToolPreload.MESSAGE_ROOM <= 4_096,
                "$request: tools take $tokens tokens",
            )
            assertTrue(small.packingFor(loadout.specs()).budgetTokens!! >= ToolPreload.MESSAGE_ROOM)
        }
    }

    @Test
    fun aGroupThatDoesNotFitIsSkipped() {
        val loadout = loadout()
        val core = loadout.specs()
        // A window this small has no room for any group next to the core set and the messages.
        val tiny = small.copy(contextWindow = 2_600, context = small.context.copy(sharedWindow = 2_600))
        val pre = ToolPreload.apply(loadout, listOf("settings", "browser"), tiny)
        assertEquals(emptyList(), pre.groups)
        assertEquals(core, loadout.specs())
    }

    @Test
    fun cloudPreloadsAtMostFourGroups() {
        val loadout = loadout()
        val cloud = AgentConfig("m", "s", contextWindow = 200_000)
        val routed = ToolRouter.route(
            "Turn on Do Not Disturb, set an alarm, check my notifications, remind me every day, open the website and run the script in the terminal",
        )
        assertTrue(routed.size > ToolPreload.MAX_GROUPS, routed.toString())
        assertEquals(ToolPreload.MAX_GROUPS, ToolPreload.apply(loadout, routed, cloud).groups.size)
    }

    @Test
    fun cloudPreloadsEveryMatchedGroup() {
        val loadout = loadout()
        val cloud = AgentConfig("m", "s", contextWindow = 200_000)
        val routed = ToolRouter.route("Turn on Do Not Disturb, set an alarm for 7, check my notifications and remind me every day at 9")
        val pre = ToolPreload.apply(loadout, routed, cloud)
        assertEquals(setOf("settings", "clock", "messages", "automation"), pre.groups.toSet())
        assertTrue(loadout.specs().any { it.name == "set_do_not_disturb" } && loadout.specs().any { it.name == "scheduled_task_create" })
    }

    @Test
    fun preloadedGroupsBringTheirNotesOnce() {
        val loadout = ToolLoadout(tools, { ToolGroups.groupsOf(it.spec.name) }, ToolGroups.SUMMARIES, mapOf("browser" to listOf("Use the agent browser.")))
        assertEquals(listOf("Use the agent browser."), loadout.preloadGroups(listOf("browser")).notes)
        val later = loadout.load(listOf("browser"))
        assertTrue("Use the agent browser." !in later.text, later.text)
    }
}
