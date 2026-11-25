package atom.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.os.Bundle
import android.view.WindowManager
import atom.app.agent.SubagentBridge
import atom.app.feature.Features
import atom.extend.Extend
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
        SubagentBridge(graph).let { Extend.install(contextWindow = it, engines = it, toolScope = it) }
        Features.all.forEach { runCatching { it.onAppCreate(this) } }

        // Work a crash or kill cut short is restored (and marked interrupted) before anything else touches it.
        graph.appScope.launch { graph.tasks.recover() }

        registerActivityLifecycleCallbacks(KeepScreenOn())
        graph.appScope.launch(Dispatchers.Main) {
            graph.runPower.keepScreenOn.collect { keep -> resumed?.get()?.let { applyKeepScreenOn(it, keep) } }
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
            applyKeepScreenOn(activity, graph.runPower.keepScreenOn.value)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
