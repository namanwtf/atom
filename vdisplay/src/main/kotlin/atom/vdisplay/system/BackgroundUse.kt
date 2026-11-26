package atom.vdisplay.system

/**
 * When runs get the background virtual display, and what the agent is told about it. Pure, so it is unit tested;
 * the callers read the setting and the shared root state at the moment they ask (never a value cached at startup),
 * so granting root later is picked up by the next run.
 */
object BackgroundUse {
    /**
     * The setting's value at start-up: the user's own choice when they made one ([chosenByUser]), otherwise on.
     * [stored] is what is saved, null when nothing is.
     */
    fun storedSetting(chosenByUser: Boolean, stored: Boolean?): Boolean = if (chosenByUser) stored ?: true else true

    /**
     * Whether a run is offered the background display tools: the setting is on and root is granted now. The hidden
     * display needs root for input and launching on another display; LSPosed alone cannot do that.
     */
    fun usable(settingOn: Boolean, rootGranted: Boolean): Boolean = settingOn && rootGranted

    /** The agent's instructions while the background display is usable. */
    const val PROMPT_HINT =
        "# Background display\n" +
            "This phone can run apps on a hidden background screen, so the user keeps using their own screen while you work.\n" +
            "- For a task in another app (ordering, booking, checking or changing something in an app), start with " +
            "background_open_app instead of open_app, then work there with background_look and background_act instead " +
            "of read_screen and tap.\n" +
            "- Use the normal screen tools only when background_open_app says so (the app is already on screen, or it is " +
            "the home screen or system UI) or the user asks to watch it on their own screen.\n" +
            "- When the user is needed (a payment or purchase confirmation, a sign-in, a QR code to show or scan, a choice " +
            "only they can make) or to show the final result in the app, call present_to_main_screen to hand the app to " +
            "their screen. Otherwise close it with background_close when done.\n" +
            "- \"Display unavailable\" means start again with background_open_app; never redo those coordinates with the " +
            "normal screen tools.\n" +
            "- Websites are not app tasks: read them with Atom's own web tools or built-in browser, not a browser app."
}
