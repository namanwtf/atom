package atom.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.os.Bundle
import android.view.WindowManager
import atom.app.feature.Features
import atom.app.integration.Integration
import atom.app.service.RunPowerPolicy
import atom.app.ui.MainActivity
import atom.ui.feature.Atom
import atom.voice.Wake
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AtomApp : Application() {
    lateinit var graph: AppGraph
        private set

    private var resumed: WeakReference<Activity>? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifications.createChannels()
        Atom.install(graph.tasks)
        // The optional on-device AI pack: one file check on an I/O thread; nothing is loaded until it is used.
        atom.app.ondevice.OnDevicePack.init(this, graph.appScope)
        // Every module's install / bind call (extend, voice, overlay, automation, web, vdisplay, backup) first,
        // so each feature's onAppCreate already sees the app's adapters.
        Integration.install(this)
        Features.all.forEach { runCatching { it.onAppCreate(this) } }
        // After the characters feature installed its store: renamed characters rename their chats.
        atom.app.integration.CharacterChatTitles.start(graph)
        // Once, after the update that brought per-tool switches: build the tools (that records each one's real
        // gate), then turn any old master switch that was off into per-tool switches. Nothing runs afterwards.
        atom.device.switches.ToolSwitches.init(this)
        if (atom.device.switches.ToolSwitches.needsFold()) {
            graph.appScope.launch(Dispatchers.Default) {
                runCatching {
                    atom.device.DeviceTools.listing(this@AtomApp)
                    Features.all.forEach { f -> runCatching { f.tools(this@AtomApp) } }
                    val gates = atom.device.switches.ToolGates.snapshot()
                    val cards = atom.device.caps.ToolCatalog.all
                    atom.device.caps.ToolCatalog.mismatches(cards, gates).forEach { android.util.Log.w("AtomTools", it) }
                    atom.device.switches.ToolSwitches.foldMasters(cards, gates)
                }
            }
        }

        graph.appScope.launch { runCatching { graph.settings.applyNewInstallDefaults() } }

        // Work a crash or kill cut short is restored (and marked interrupted) before anything else touches it.
        graph.appScope.launch { graph.tasks.recover() }

        // Once, when the previous Atom's data is on this phone: keys, sign-ins and conversations (docs/IMPORT.md).
        // Off the main thread, after every module is installed. Old files are only read, and removed a week after a
        // clean import.
        graph.appScope.launch(Dispatchers.IO) { runCatching { atom.app.data.legacy.LegacyImport.onAppStart(this@AtomApp, graph) } }

        registerActivityLifecycleCallbacks(KeepScreenOn())
        graph.appScope.launch(Dispatchers.Main) {
            // Only the activity in front can carry the flag: a paused one (background, screen off) never does.
            graph.runPower.keepScreenOn.collect { keep -> resumed?.get()?.let { applyKeepScreenOn(it, RunPowerPolicy.windowKeepsScreenOn(keep, windowInFront = true)) } }
        }

        Wake.setAssistLaunchComponent(ComponentName(this, MainActivity::class.java))
        // Commands are not replayed, so this collector lives for the whole process. A spoken command opens the
        // chat and runs there: the user sees what Atom does, and a visible activity may start the task service.
        graph.appScope.launch {
            Wake.commands.collect { command -> startActivity(MainActivity.promptIntent(this@AtomApp, command.text)) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // An idle on-device model can hold gigabytes; give it back as soon as we are in the background LRU.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) graph.router.onTrimMemory()
    }

    private fun applyKeepScreenOn(activity: Activity, keep: Boolean) {
        if (keep) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Applies "keep screen on while running" to whichever Atom activity is in front. */
    private inner class KeepScreenOn : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            applyKeepScreenOn(activity, RunPowerPolicy.windowKeepsScreenOn(graph.runPower.keepScreenOn.value, windowInFront = true))
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
            // Backgrounded or screen off: never keep the screen on for a window the user cannot see.
            applyKeepScreenOn(activity, RunPowerPolicy.windowKeepsScreenOn(graph.runPower.keepScreenOn.value, windowInFront = false))
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
