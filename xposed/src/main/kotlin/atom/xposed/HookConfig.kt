package atom.xposed

/** What a long press on the power key opens while the module is active. */
enum class PowerKeyTarget(val key: String) {
    /** Leave the ROM's own behaviour alone (power menu, the ROM's assistant, ...). */
    SystemDefault("system"),
    Gemini("gemini"),
    Atom("atom"),
    ;

    companion object {
        fun of(key: String?): PowerKeyTarget = entries.firstOrNull { it.key == key } ?: SystemDefault
    }
}

/**
 * Settings the hooked processes read. The app owns them; they reach system_server, SystemUI and the Google app
 * through the framework's remote preferences (group [GROUP]), which hooked processes can only read.
 *
 * Every field defaults to off, so a missing or unreadable group means "do nothing" in every hook.
 */
data class HookConfig(
    val powerKeyTarget: PowerKeyTarget = PowerKeyTarget.SystemDefault,
    val autoDefaultAssistant: Boolean = false,
    val gestureBarCircleToSearch: Boolean = false,
    val twoFingerCircleToSearch: Boolean = false,
    val enforceAccessibility: Boolean = false,
) {
    /** The package the assistant role is kept on when [autoDefaultAssistant] is on. */
    fun assistantPackage(atomPackage: String): String =
        if (powerKeyTarget == PowerKeyTarget.Gemini) GOOGLE_APP else atomPackage

    val anyCircleToSearch: Boolean get() = gestureBarCircleToSearch || twoFingerCircleToSearch

    /** Flat map for SharedPreferences-style storage. Only primitives, so any process can read it without our classes. */
    fun toMap(): Map<String, Any> = mapOf(
        KEY_VERSION to VERSION,
        KEY_POWER_TARGET to powerKeyTarget.key,
        KEY_AUTO_ASSISTANT to autoDefaultAssistant,
        KEY_CTS_BAR to gestureBarCircleToSearch,
        KEY_CTS_TWO_FINGER to twoFingerCircleToSearch,
        KEY_ENFORCE_A11Y to enforceAccessibility,
    )

    companion object {
        const val GROUP = "atom_system_hooks"
        const val VERSION = 1
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

        const val KEY_VERSION = "version"
        const val KEY_POWER_TARGET = "power_key_target"
        const val KEY_AUTO_ASSISTANT = "auto_default_assistant"
        const val KEY_CTS_BAR = "cts_gesture_bar"
        const val KEY_CTS_TWO_FINGER = "cts_two_finger"
        const val KEY_ENFORCE_A11Y = "enforce_accessibility"

        /** Tolerant reader: wrong types and unknown values fall back to the safe default instead of throwing. */
        fun fromMap(map: Map<String, *>?): HookConfig {
            if (map == null) return HookConfig()
            fun bool(key: String) = map[key] as? Boolean ?: (map[key] as? String)?.toBooleanStrictOrNull() ?: false
            return HookConfig(
                powerKeyTarget = PowerKeyTarget.of(map[KEY_POWER_TARGET] as? String),
                autoDefaultAssistant = bool(KEY_AUTO_ASSISTANT),
                gestureBarCircleToSearch = bool(KEY_CTS_BAR),
                twoFingerCircleToSearch = bool(KEY_CTS_TWO_FINGER),
                enforceAccessibility = bool(KEY_ENFORCE_A11Y),
            )
        }
    }
}
