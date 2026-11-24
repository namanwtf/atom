package atom.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ComponentName
import atom.app.feature.Features
import atom.app.ui.MainActivity
import atom.voice.Wake
import kotlinx.coroutines.launch

class AtomApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifications.createChannels()
        Features.all.forEach { it.onAppCreate(this) }

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
}
