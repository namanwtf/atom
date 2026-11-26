package atom.automation

import java.util.Locale

/**
 * Guesses which on-demand tool groups ([ToolGroups]) a request needs, so the run can load them before the first
 * model call. Small models rarely call `enable_tools` on their own and then never see the tool the task needs; big
 * ones spend a whole step on it. Deterministic and cheap: phrase rules over the request text (English plus the
 * most common German, Spanish and French wording) and the tools the conversation used recently. No model call.
 *
 * The core set (screen, taps, opening apps, web search, reading pages) is always sent, so it has no rules here.
 * `enable_tools` stays available for anything the rules miss.
 */
object ToolRouter {
    /** A rule match this strong is enough on its own, also for small models. */
    const val STRONG = 3

    /** Weight of a group the conversation's recent tool calls used: enough on its own. */
    const val RECENT = STRONG

    private class Rule(val group: String, val weight: Int, val pattern: Regex)

    private fun rules(group: String, weight: Int, vararg phrases: String): List<Rule> =
        phrases.map { Rule(group, weight, Regex("(?<![\\p{L}\\p{N}])(?:$it)(?![\\p{L}\\p{N}])")) }

    /** Verbs of making something, and up to three describing words that are not a preposition ("in the app"). */
    private const val MAKE = "(?:make|build|create|code|design|write|generate)"
    private const val WORDS = "(?: (?!(?:in|on|at|to|for|from|with|of|into|about)(?![\\w'-]))[\\w'-]+){0,3}"

    private val RULES: List<Rule> = buildList {
        addAll(
            rules(
                "settings", STRONG,
                "do not disturb", "don'?t disturb", "dnd", "nicht stören", "no molestar", "ne pas déranger",
                "volume", "lautstärke", "brightness", "helligkeit", "brillo", "luminosité",
                "flash ?light", "torch", "taschenlampe", "linterna", "lampe torche",
                "wi-?fi", "wlan", "bluetooth", "airplane mode", "flight mode", "flugmodus", "modo avión", "mode avion",
                "mobile data", "hotspot", "dark mode", "dunkelmodus", "modo oscuro", "mode sombre",
                "ringer", "silent mode", "mute", "vibrate", "vibration", "screen timeout", "auto-?rotate",
                "undo (?:the |my |that )?(?:last )?change",
            ),
        )
        addAll(
            rules(
                "clock", STRONG,
                "alarms?", "wecker", "alarma", "réveil", "timers?", "stopwatch", "countdown", "wake me",
            ),
        )
        addAll(
            rules(
                "automation", STRONG,
                "remind(?: me)?", "reminders?", "erinnere", "erinnerung", "recuérdame", "recordatorio", "rappelle(?:-moi)?",
                "every (?:day|morning|evening|night|week|month|hour|monday|tuesday|wednesday|thursday|friday|saturday|sunday)",
                "each (?:day|morning|week)", "daily", "weekly", "jeden tag", "cada día", "tous les jours",
                "schedule(?:d)?", "automations?", "automate", "whenever",
            ),
        )
        addAll(rules("automation", 2, "at \\d{1,2}(?::\\d{2})? ?(?:am|pm|uhr|h)?", "tomorrow", "morgen", "mañana", "demain"))
        addAll(
            rules(
                "messages", STRONG,
                "notifications?", "benachrichtigung(?:en)?", "notificaci(?:ón|ones)", "notif",
                "otp", "one-?time (?:code|password)", "verification code", "(?:login|2fa|security|sms) code",
                "sms", "text messages?", "messages?", "nachrichten?", "mensajes?", "whatsapp", "telegram",
                "missed calls?", "call log", "who called", "contacts?", "kontakte?", "contactos?", "phone number",
            ),
        )
        addAll(rules("messages", 1, "codes?"))
        addAll(
            rules(
                "browser", STRONG,
                "browser", "website", "web ?page", "webseite", "página web", "site web", "web site",
                "open (?:the |this )?(?:url|link|page|site)", "https?://\\S+", "www\\.\\S+",
                "log ?in to", "sign ?in to", "fill (?:in|out) (?:the |a )?form", "click (?:on )?(?:the )?(?:link|button)",
            ),
        )
        addAll(rules("browser", 1, "search", "news", "look up", "google", "noticias", "actualités", "suche"))
        // Making a website, web app or game: the site tools (they come before the browser, which checks the page).
        addAll(
            rules(
                "sites", STRONG,
                "$MAKE(?: me| us)?(?: an?| my| the| one| another| this)?$WORDS (?:web ?sites?|web ?pages?|web ?apps?|landing pages?|home ?pages?|html pages?)",
                "$MAKE(?: me| us)? an?$WORDS (?:sites?|pages?|apps?|games?)",
                "html(?: page| file| site)?", "css", "live preview",
                "(?:erstell|bau|mach)\\w*(?: mir)? eine? (?:webseite|website|seite|web-?app|spiel)",
                "(?:crea|haz|hazme|créame)\\w*(?: me)? una? (?:página|web|sitio|aplicación|juego)",
                "(?:crée|créer|fais|fais-moi)(?: moi)? une? (?:site|page|application|jeu)",
            ),
        )
        addAll(
            rules(
                "terminal", STRONG,
                "terminal", "shell", "command line", "bash", "run (?:the |this |a )?(?:command|script)", "scripts?",
                "install", "apt", "pip", "npm", "git", "python3?", "[\\w-]+\\.(?:py|sh|js)", "node(?:js)?", "compile",
                "linux", "ssh", "kommandozeile",
            ),
        )
        addAll(rules("terminal", 1, "run it", "run"))
        addAll(
            rules(
                "files", 2,
                "files?", "folders?", "directory", "downloads?", "documents?", "pdf", "[\\w-]+\\.(?:txt|md|csv|json)",
                "datei(?:en)?", "archivos?", "fichiers?",
            ),
        )
        addAll(
            rules(
                "personal", STRONG,
                "where am i", "my location", "current location", "gps", "app usage", "screen time", "health",
                "steps", "storage", "speicherplatz",
            ),
        )
        addAll(rules("personal", 2, "photos?", "pictures?", "gallery", "fotos?", "location"))
        addAll(
            rules(
                "phone", STRONG,
                "battery", "akku", "batería", "batterie", "charging", "network status", "clipboard",
                "long ?press", "press (?:the )?(?:back|home|enter) (?:key|button)", "play (?:the )?music",
                "next song", "skip (?:the )?song",
            ),
        )
        addAll(rules("phone", 1, "copy", "paste", "pause"))
        addAll(
            rules(
                "calendar", STRONG,
                "calendar", "kalender", "calendario", "calendrier", "meetings?", "appointments?", "events?",
                "termin(?:e)?", "agenda",
            ),
        )
        addAll(
            rules(
                "system", 2,
                "freeze (?:the )?app", "force ?stop", "kill (?:the )?app", "logcat", "system logs?", "firewall",
                "refresh rate", "charge limit", "battery drain", "spoof", "protected setting",
            ),
        )
        addAll(rules("images", STRONG, "generate (?:an? )?(?:image|picture|photo)", "draw(?: me)?", "edit (?:this|the) (?:image|photo|picture)"))
        addAll(rules("background", 2, "in the background", "hidden display", "while i (?:use|keep)"))
        addAll(rules("memory", 2, "forget", "clear (?:my )?memory", "update (?:my )?memory"))
        addAll(rules("skills", STRONG, "skills?"))
        addAll(rules("subagents", 2, "subagents?", "in parallel", "helpers"))
        addAll(rules("characters", STRONG, "characters?"))
    }

    /**
     * Groups the request likely needs, best first: each rule that matches adds its weight once, and every group the
     * [recentTools] (tool names the conversation called lately) belong to adds [RECENT]. Only groups with at least
     * [minScore] are returned (by default one strong match, or weaker ones adding up to it: a lone "run", "copy" or
     * "news" loads nothing); ties keep the order of the first match in the text.
     */
    fun route(request: String, recentTools: List<String> = emptyList(), minScore: Int = STRONG): List<String> {
        val text = request.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val score = LinkedHashMap<String, Int>()
        val first = HashMap<String, Int>()
        for (rule in RULES) {
            val m = rule.pattern.find(text) ?: continue
            score[rule.group] = (score[rule.group] ?: 0) + rule.weight
            first[rule.group] = minOf(first[rule.group] ?: Int.MAX_VALUE, m.range.first)
        }
        for (name in recentTools) for (g in ToolGroups.groupsOf(name)) {
            score[g] = (score[g] ?: 0) + RECENT
            first.putIfAbsent(g, Int.MAX_VALUE)
        }
        return score.entries
            .filter { it.value >= minScore }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { first[it.key] ?: Int.MAX_VALUE })
            .map { it.key }
    }
}
