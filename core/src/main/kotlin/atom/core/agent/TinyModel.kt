package atom.core.agent

/**
 * How a run is set up for a tiny on-device model (under [TINY_BILLIONS] billion parameters, such as Qwen3 0.6B).
 *
 * Such a model answers a short chat prompt well, but the full agent prompt overwhelms it: the operating instructions,
 * the memory and skills notes, the core tool set, the `enable_tools` catalog of every group and a preloaded group
 * came to roughly 2,000 of Qwen3 0.6B's 4,096 tokens, and on a phone it answered "set an alarm for 8:00" with
 * fragments of that prompt ("more tools tools", "(language, tone,, tone,, units, recurring"). So a tiny model gets a
 * short prompt of its own and at most one group of one-step tools: those of the first group the request routes to
 * among [ONE_STEP_GROUPS] (an alarm, a timer, a quick setting). Anything else, and plain chat, gets no tools at all;
 * when such a request asks for work on the phone or the web the user is told that a larger model is needed.
 */
object TinyModel {
    /** Below this many billion parameters a model counts as tiny. */
    const val TINY_BILLIONS = 1.5

    /** Without a size in the name: a 4-bit file of a 1.5B model is about 1.1 GB. */
    private const val TINY_FILE_BYTES = 1_000_000_000L

    /** Groups whose jobs take one tool call with a few plain arguments; the first one routed is offered. */
    val ONE_STEP_GROUPS: List<String> = listOf("clock", "settings")

    /** Model calls per run: the tool call, the answer, and room for one correction. */
    const val MAX_STEPS = 4

    /** True for a model under [TINY_BILLIONS], judged by its name, else by its file size (0 = unknown, not tiny). */
    fun isTiny(name: String, sizeBytes: Long): Boolean {
        SmallModel.billionsFromName(name)?.let { return it < TINY_BILLIONS }
        return sizeBytes in 1 until TINY_FILE_BYTES
    }

    /** The group a tiny model gets tools from: the first of [routed] (best first) that is one-step, or null. */
    fun groupFor(routed: List<String>): String? = routed.firstOrNull { it in ONE_STEP_GROUPS }

    /** The tools of [group] among [tools] (in their order), none when [group] is null. */
    fun <T> toolsFor(tools: List<T>, group: String?, groupsOf: (T) -> Set<String>): List<T> =
        if (group == null) emptyList() else tools.filter { group in groupsOf(it) }

    /**
     * The whole system prompt for a tiny model. [today] is the date line ("Friday 27 November 2026"); the clock time
     * rides on the latest user message as for every run. With [withTools] the model is told to use the tool the
     * prompted tool list below describes; without, that it cannot act on the phone, so it says so instead of
     * pretending.
     */
    fun systemPrompt(withTools: Boolean, today: String): String = buildString {
        append(if (withTools) WITH_TOOLS else CHAT_ONLY)
        append("\nToday is ").append(today).append('.')
    }

    private val CHAT_ONLY = """
        You are Atom, a helpful assistant on the user's Android phone.
        Answer briefly and clearly, in the user's language.
        You cannot open apps, change settings, browse the web or operate the phone. If the user asks for that, say in one sentence that this small on-device model cannot do it and that a larger model can.
    """.trimIndent()

    private val WITH_TOOLS = """
        You are Atom, an assistant on the user's Android phone.
        When the user asks for something one of the tools below does, call that tool once with the right arguments. When its result arrives, tell the user in one short sentence what happened.
        Otherwise answer briefly. Reply in the user's language.
    """.trimIndent()
}
