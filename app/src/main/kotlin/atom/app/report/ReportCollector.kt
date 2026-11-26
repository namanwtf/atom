package atom.app.report

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import atom.app.AppGraph
import atom.app.data.ConversationExport
import atom.app.graph
import atom.web.files.Share
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What was collected for a report; [text] formats it with what the person chose on the report screen. */
class ReportDraft internal constructor(
    private val input: ReportInput,
    /** The conversation export, offered only when the person turns it on. */
    private val conversation: String?,
    private val known: List<String>,
) {
    val hasConversation: Boolean get() = conversation != null

    fun text(note: String, includeConversation: Boolean, screenshot: Boolean): String = ProblemReport.text(
        input.copy(note = note, conversation = conversation?.takeIf { includeConversation }, screenshot = screenshot),
        known,
    )
}

/**
 * Gathers a problem report on the phone and hands it to the share sheet. Nothing here touches the network: the
 * person previews the text and picks where it goes.
 */
object ReportCollector {
    /** The screen the report was started from, captured before the report screen opened. */
    fun screenshotFile(context: Context) = File(dir(context), "screenshot.png")

    private fun dir(context: Context) = File(context.cacheDir, "reports").apply { mkdirs() }

    /** Saves what [activity]'s window shows now, for the report's optional screenshot. */
    suspend fun captureScreen(activity: Activity): Boolean {
        val file = screenshotFile(activity)
        file.delete()
        val window = activity.window ?: return false
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val ok = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(window, bitmap, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }.onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false
        if (!ok) {
            bitmap.recycle()
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess.also { bitmap.recycle() }
        }
    }

    fun clearScreenshot(context: Context) {
        screenshotFile(context).delete()
    }

    /** Copies an image the person picked into the report folder, replacing any captured screen. */
    suspend fun useImage(context: Context, uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input -> screenshotFile(context).outputStream().use { input.copyTo(it) } }
        }.isSuccess
    }

    /** Collects everything once; [ReportDraft.text] then formats it for the person's current choices. */
    suspend fun collect(context: Context, conversationId: String?): ReportDraft = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val graph = app.graph
        android.util.Log.i("AtomReport", "collecting a problem report")
        val known = runCatching { knownSecrets(graph) }.getOrDefault(emptyList())
        val (run, conversation) = runCatching { lastRun(graph, conversationId) }.getOrDefault(null to null)
        val log = runCatching { atomLog() }.getOrElse { null to "logcat could not run: ${it.message ?: it::class.java.simpleName}" }
        val input = ReportInput(
            createdAt = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            app = appFacts(app),
            device = deviceFacts(),
            run = run,
            exits = runCatching { exits(app) }.getOrDefault(emptyList()),
            log = log.first,
            logNote = log.second,
        )
        ReportDraft(input, conversation?.takeIf { it.messages.isNotEmpty() }?.let { ConversationExport.markdown(it) }, known)
    }

    /** Writes [text] (and the screenshot when chosen) to files and opens the share sheet. */
    fun share(context: Context, text: String, withScreenshot: Boolean) {
        val app = context.applicationContext
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        // Older reports are replaced: only the one being shared stays in the cache.
        dir(app).listFiles { f -> f.name.startsWith("atom-report-") }?.forEach { it.delete() }
        val report = File(dir(app), "atom-report-$stamp.txt").apply { writeText(text) }
        val files = listOfNotNull(report, screenshotFile(app).takeIf { withScreenshot && it.isFile })
        val uris = files.map { Share.uriFor(app, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType("text/plain")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
        }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        send.putExtra(Intent.EXTRA_SUBJECT, app.getString(atom.app.R.string.report_share_subject, version))
        send.clipData = ClipData.newUri(app.contentResolver, report.name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, app.getString(atom.app.R.string.report_share_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val activity = context.findActivity()
        if (activity != null) activity.startActivity(chooser) else app.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun appFacts(context: Context): AppFacts {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return AppFacts(
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            packageName = context.packageName,
            debugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    private fun deviceFacts() = DeviceFacts(
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdk = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
        model = Build.MODEL.orEmpty(),
        rom = runCatching { RomNames.detect(::prop) }.getOrNull(),
        build = Build.DISPLAY,
    )

    /** One system property, read with the `getprop` tool every app may run (no permission involved). */
    private fun prop(name: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** The run asked about (its conversation), else the newest run in memory, else the newest saved conversation. */
    private suspend fun lastRun(graph: AppGraph, conversationId: String?): Pair<RunFacts?, atom.app.data.Conversation?> {
        val live = graph.tasks.liveRuns.value
        val run = conversationId?.let { live[it] } ?: if (conversationId == null) live.values.maxByOrNull { it.startedAt } else null
        val id = conversationId ?: run?.conversation?.id ?: graph.conversations.summaries.first().maxByOrNull { it.updatedAt }?.id
        val saved = id?.let { graph.conversations.load(it) }
        if (run != null) {
            val messages = run.conversation.messages
            val last = messages.lastOrNull()?.createdAt?.takeIf { it >= run.startedAt }
            val facts = RunFacts(
                outcome = when {
                    run.active -> "Still running (${run.phase.name})"
                    else -> when (run.outcome) {
                        atom.app.agent.RunOutcome.Completed -> "Completed"
                        atom.app.agent.RunOutcome.StepLimit -> "Stopped at the step limit"
                        atom.app.agent.RunOutcome.OutputLimit -> "Stopped at the output limit"
                        atom.app.agent.RunOutcome.Failed -> "Failed"
                        atom.app.agent.RunOutcome.Cancelled -> "Stopped"
                        null -> run.phase.name
                    }
                },
                error = run.error,
                source = run.source.name,
                model = run.engineLabel,
                startedAt = run.startedAt,
                durationMs = last?.let { it - run.startedAt },
                steps = ProblemReport.steps(messages, run.turnStart),
            )
            return facts to (saved ?: run.conversation)
        }
        if (saved == null) return null to null
        val start = ProblemReport.lastTurnStart(saved.messages)
        return ProblemReport.storedOutcome(saved.messages, start) to saved
    }

    /** Crashes, freezes and similar ends of Atom's own process from the last week, as Android recorded them. */
    private fun exits(context: Context): List<ExitFacts> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp >= since && it.reason in REASONS }
            .take(5)
            .map { e ->
                ExitFacts(
                    at = e.timestamp,
                    reason = REASONS[e.reason] ?: "Other",
                    description = e.description,
                    trace = if (e.reason == ApplicationExitInfo.REASON_ANR) runCatching { mainThread(e) }.getOrDefault(emptyList()) else emptyList(),
                )
            }
    }

    private val REASONS = mapOf(
        ApplicationExitInfo.REASON_CRASH to "Crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "Native crash",
        ApplicationExitInfo.REASON_ANR to "Not responding (ANR)",
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "Failed to start",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "Stopped for using too many resources",
        ApplicationExitInfo.REASON_LOW_MEMORY to "Closed for low memory",
    )

    /** The main thread's stack from an ANR trace (up to 25 lines). */
    private fun mainThread(e: ApplicationExitInfo): List<String> {
        val lines = e.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(4000).toList() } ?: return emptyList()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyList()
        return lines.drop(start).takeWhile { it.isNotBlank() }.take(25)
    }

    /**
     * Atom's own log lines (tags starting with "Atom", plus Android's crash lines for this app). An app reads only
     * its own log this way; no permission is involved. Returns the lines and a note when nothing could be read.
     */
    private fun atomLog(): Pair<List<String>?, String?> {
        val p = ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime", "-t", "5000").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { r -> r.readLines() }
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
        val tag = Regex("^\\S+ \\S+\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+([^:]*?)\\s*:")
        val entries = lines.filter { tag.containsMatchIn(it) }
        if (entries.isEmpty()) {
            val why = lines.firstOrNull { it.isNotBlank() && !it.startsWith("---------") }?.take(160)
            return null to (why?.let { "logcat gave no entries: $it" } ?: "logcat gave no entries")
        }
        val atom = entries.filter { line ->
            val t = tag.find(line)?.groupValues?.get(1).orEmpty()
            t.startsWith("atom", ignoreCase = true) || t == "AndroidRuntime"
        }.takeLast(ProblemReport.MAX_LOG_LINES)
        return atom to if (atom.isEmpty()) "${entries.size} entries readable, none from Atom's tags" else null
    }

    /** The person's saved keys, sign-in tokens and header values: removed by exact match wherever they appear. */
    private suspend fun knownSecrets(graph: AppGraph): List<String> {
        val out = ArrayList<String>()
        for (p in graph.providers.all()) {
            graph.vault.apiKey(p.id)?.let { out += it }
            graph.vault.oauthTokens(p.id)?.let { out += it; out += jsonStrings(it) }
            p.headers.forEach { out += it.value }
        }
        graph.vault.hfToken()?.let { out += it }
        return out.filter { it.length >= Redactor.MIN_KNOWN }
    }

    private fun jsonStrings(text: String): List<String> {
        fun walk(e: JsonElement): List<String> = when (e) {
            is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
            is JsonObject -> e.values.flatMap(::walk)
            is JsonArray -> e.flatMap(::walk)
        }
        return runCatching { walk(Json.parseToJsonElement(text)) }.getOrDefault(emptyList())
    }
}

/** The ROM name and version from its system properties (pure, unit-tested); null on plain Android. */
object RomNames {
    fun detect(prop: (String) -> String?): String? {
        fun p(name: String) = prop(name)?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }
        p("ro.mi.os.version.name")?.let { return "HyperOS $it" }
        p("ro.miui.ui.version.name")?.let { return "MIUI $it" }
        p("ro.build.version.oplusrom")?.let { return "ColorOS / OxygenOS $it" }
        p("ro.build.version.opporom")?.let { return "ColorOS $it" }
        p("ro.build.version.magic")?.let { return "MagicOS $it" }
        p("ro.build.version.emui")?.let { return it }
        p("ro.vivo.os.version")?.let { return (p("ro.vivo.os.name") ?: "OriginOS") + " " + it }
        p("ro.build.version.oneui")?.toIntOrNull()?.let { return "One UI ${it / 10000}.${it / 100 % 100}" }
        p("ro.lineage.build.version")?.let { return "LineageOS $it" }
        p("ro.build.nothing.version")?.let { return "Nothing OS $it" }
        return null
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
