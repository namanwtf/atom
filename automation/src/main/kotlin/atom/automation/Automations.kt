package atom.automation

import android.content.Context
import atom.automation.rule.LlmRuleCompiler
import atom.automation.rule.RuleCompiler
import atom.automation.rule.Trigger
import atom.automation.rule.TriggerEvent
import atom.automation.system.AutomationRuntime

/**
 * What the app plugs in. Call [bind] once from Application.onCreate (before or after the feature's onAppCreate);
 * everything has a working default except tools and the compiler's model.
 */
object Automations {
    @Volatile var launcher: RunLauncher = HostRunLauncher
        private set
    @Volatile var toolInvoker: ToolInvoker? = null
        private set
    @Volatile var engineProvider: EngineProvider? = null
        private set
    @Volatile var models: ModelChoiceProvider = FollowGlobalModels
        private set
    @Volatile var backgroundDisplay: BackgroundDisplay = BackgroundDisplay { false }
        private set
    @Volatile private var compilerOverride: RuleCompiler? = null

    @Volatile private var appContext: Context? = null

    fun bind(
        toolInvoker: ToolInvoker? = this.toolInvoker,
        engineProvider: EngineProvider? = this.engineProvider,
        models: ModelChoiceProvider = this.models,
        launcher: RunLauncher = this.launcher,
        backgroundDisplay: BackgroundDisplay = this.backgroundDisplay,
        compiler: RuleCompiler? = this.compilerOverride,
    ) {
        this.toolInvoker = toolInvoker
        this.engineProvider = engineProvider
        this.models = models
        this.launcher = launcher
        this.backgroundDisplay = backgroundDisplay
        this.compilerOverride = compiler
    }

    val compiler: RuleCompiler
        get() = compilerOverride ?: LlmRuleCompiler(
            engines = { engineProvider?.current() },
            tools = { toolInvoker?.available().orEmpty() },
        )

    internal fun attach(context: Context) {
        appContext = context.applicationContext
    }

    internal fun attachIfNeeded(context: Context) {
        if (appContext == null) attach(context)
    }

    private fun runtimeOrNull(): AutomationRuntime? = appContext?.let { AutomationRuntime.get(it) }

    /** For the notification listener. */
    val notificationEvents: NotificationEvents = object : NotificationEvents {
        override val wanted: Boolean
            get() = runtimeOrNull()?.state?.value?.rules?.any { it.enabled && it.trigger is Trigger.Notification } == true

        override fun onNotificationPosted(packageName: String, appLabel: String?, title: String?, text: String?) {
            val rt = runtimeOrNull() ?: return
            if (packageName == rt.context.packageName || !wanted) return
            rt.rules.onEvent(TriggerEvent.Notification(packageName, appLabel, title, text))
        }
    }

    /** For the accessibility service. */
    val appOpenEvents: AppOpenEvents = object : AppOpenEvents {
        @Volatile private var last: String? = null

        @Volatile private var cachedFor: Any? = null
        @Volatile private var cached: Set<String> = emptySet()

        override val watchedPackages: Set<String>
            get() {
                val rt = runtimeOrNull() ?: return emptySet()
                val rules = rt.state.value.rules
                if (cachedFor !== rules) {
                    val apps = rules.filter { it.enabled }.mapNotNull { (it.trigger as? Trigger.AppOpened)?.app }
                    cached = apps.mapNotNull { AppNames.resolve(rt.context, it) }.toSet()
                    cachedFor = rules
                }
                return cached
            }

        override fun onAppOpened(packageName: String) {
            if (packageName == last) return
            last = packageName
            val rt = runtimeOrNull() ?: return
            if (packageName == rt.context.packageName) return
            rt.rules.onEvent(TriggerEvent.AppOpened(packageName, AppNames.label(rt.context, packageName)))
        }
    }
}

internal object AppNames {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun label(context: Context, pkg: String): String? = cache[pkg] ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.also { cache[pkg] = it }

    /** A package name for "WhatsApp" or "com.whatsapp"; null when nothing installed matches. */
    fun resolve(context: Context, app: String): String? {
        if ('.' in app && runCatching { context.packageManager.getApplicationInfo(app, 0) }.isSuccess) return app
        cache.entries.firstOrNull { it.value.equals(app, ignoreCase = true) }?.let { return it.key }
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
        )
        for (info in launchable) {
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm).toString()
            cache[pkg] = label
            if (label.equals(app, ignoreCase = true)) return pkg
        }
        return null
    }
}
