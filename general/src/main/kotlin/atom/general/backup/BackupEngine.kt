package atom.general.backup

import atom.ui.feature.BackupContributor
import atom.ui.feature.Backups
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** What the file says about itself (read before anything is imported). */
data class BackupHeader(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val keys: List<String>,
    val containsSecrets: Boolean,
)

/** A parsed, structurally valid backup. Sections are not yet validated by their contributors. */
data class BackupFile(val header: BackupHeader, val sections: Map<String, JsonElement>)

/** Why a file was rejected. The screen maps each to a message. */
sealed interface BackupProblem {
    data object Empty : BackupProblem
    data object TooLarge : BackupProblem
    data object NotJson : BackupProblem
    /** Valid JSON, but not an Atom backup. */
    data object Foreign : BackupProblem
    data class UnsupportedVersion(val found: Int) : BackupProblem
    data class Corrupt(val detail: String) : BackupProblem
    /** A contributor rejected its section. */
    data class SectionInvalid(val key: String, val reason: String) : BackupProblem
    /** Nothing in the file matches a section this build knows. */
    data object NothingToImport : BackupProblem
}

sealed interface ParseResult {
    data class Ok(val file: BackupFile) : ParseResult
    data class Invalid(val problem: BackupProblem) : ParseResult
}

/** Import plan after validation: which sections will be replaced, which are left alone. */
data class ImportPlan(
    val file: BackupFile,
    val toImport: List<BackupContributor>,
    /** Sections in the file that no module in this build understands (skipped). */
    val unknownKeys: List<String>,
    /** Registered sections absent from the file (their current data stays as is). */
    val untouchedKeys: List<String>,
)

sealed interface ValidationResult {
    data class Ok(val plan: ImportPlan) : ValidationResult
    data class Invalid(val problems: List<BackupProblem>) : ValidationResult
}

sealed interface ImportResult {
    data class Success(val imported: List<String>, val skipped: List<String>) : ImportResult
    /** Agent tasks are running; nothing was touched. */
    data object Busy : ImportResult
    /** Conversations are still being saved; nothing was touched. */
    data object Saving : ImportResult
    data class Invalid(val problems: List<BackupProblem>) : ImportResult
    /**
     * [failedKey] threw. Every section imported before it (and itself) was restored from its in-memory snapshot;
     * [rollbackFailures] lists any section whose restore also failed (empty = fully rolled back).
     */
    data class Failed(val failedKey: String, val reason: String, val rollbackFailures: List<String>) : ImportResult
}

/**
 * Builds and restores the single-file backup. Pure logic over [BackupContributor]s; file picking lives in the
 * screen. Import is transactional: validate everything, snapshot everything, then import; any failure rolls all
 * already-imported sections back.
 */
class BackupEngine(
    private val appVersion: String,
    private val contributors: () -> List<BackupContributor> = { Backups.all },
    private val clock: () -> Long = System::currentTimeMillis,
    /** True while conversations are still being written; imports wait until they are done. */
    private val saving: () -> Boolean = { false },
) {
    suspend fun export(): String {
        val list = contributors()
        val sections = LinkedHashMap<String, JsonElement>()
        for (c in list) sections[c.key] = c.export()
        val now = clock()
        val root = JsonObject(
            linkedMapOf(
                F_FORMAT to JsonPrimitive(FORMAT),
                F_VERSION to JsonPrimitive(FORMAT_VERSION),
                F_APP_VERSION to JsonPrimitive(appVersion),
                F_CREATED to JsonPrimitive(Instant.ofEpochMilli(now).toString()),
                F_CREATED_MS to JsonPrimitive(now),
                F_KEYS to JsonArray(sections.keys.map { JsonPrimitive(it) }),
                F_SECRETS to JsonPrimitive(list.any { it.containsSecrets }),
                F_DATA to JsonObject(sections),
            ),
        )
        return json.encodeToString(JsonElement.serializer(), root)
    }

    /** True when an export would include secrets (the screen warns first). */
    fun exportContainsSecrets(): Boolean = contributors().any { it.containsSecrets }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Invalid(BackupProblem.Empty)
        if (text.length > MAX_CHARS) return ParseResult.Invalid(BackupProblem.TooLarge)
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ParseResult.Invalid(BackupProblem.NotJson)
        }
        if (root !is JsonObject) return ParseResult.Invalid(BackupProblem.Foreign)
        val format = (root[F_FORMAT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) return ParseResult.Invalid(BackupProblem.Foreign)
        val version = (root[F_VERSION] as? JsonPrimitive)?.intOrNull
            ?: return ParseResult.Invalid(BackupProblem.Corrupt("missing format version"))
        if (version !in SUPPORTED_VERSIONS) return ParseResult.Invalid(BackupProblem.UnsupportedVersion(version))
        val data = root[F_DATA] as? JsonObject ?: return ParseResult.Invalid(BackupProblem.Corrupt("missing data"))
        val keys = (root[F_KEYS] as? JsonArray)?.map { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return ParseResult.Invalid(BackupProblem.Corrupt("bad section list"))
        } ?: return ParseResult.Invalid(BackupProblem.Corrupt("missing section list"))
        if (keys.toSet() != data.keys || keys.size != keys.toSet().size) {
            return ParseResult.Invalid(BackupProblem.Corrupt("section list does not match data"))
        }
        val header = BackupHeader(
            formatVersion = version,
            appVersion = (root[F_APP_VERSION] as? JsonPrimitive)?.contentOrNull ?: "?",
            createdAtMillis = (root[F_CREATED_MS] as? JsonPrimitive)?.longOrNull ?: 0L,
            keys = keys,
            containsSecrets = (root[F_SECRETS] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        return ParseResult.Ok(BackupFile(header, data))
    }

    /** Asks every matching contributor to validate its section. Writes nothing. */
    suspend fun validate(file: BackupFile): ValidationResult {
        val registered = contributors()
        val byKey = registered.associateBy { it.key }
        val targets = file.header.keys.mapNotNull { byKey[it] }
        val unknown = file.header.keys.filter { it !in byKey }
        if (targets.isEmpty()) return ValidationResult.Invalid(listOf(BackupProblem.NothingToImport))
        val problems = targets.mapNotNull { c ->
            val reason = try {
                c.validate(file.sections.getValue(c.key))
            } catch (e: Exception) {
                e.message ?: e::class.simpleName ?: "invalid"
            }
            reason?.let { BackupProblem.SectionInvalid(c.key, it) }
        }
        if (problems.isNotEmpty()) return ValidationResult.Invalid(problems)
        val untouched = registered.map { it.key }.filter { it !in file.sections }
        return ValidationResult.Ok(ImportPlan(file, targets, unknown, untouched))
    }

    /**
     * Replaces current data with the backup. Refuses while [tasksRunning]. Runs to completion (or full rollback)
     * even if the caller is cancelled, so data is never left half-imported.
     */
    suspend fun import(file: BackupFile, tasksRunning: () -> Boolean): ImportResult {
        if (tasksRunning()) return ImportResult.Busy
        if (saving()) return ImportResult.Saving
        val plan = when (val v = validate(file)) {
            is ValidationResult.Invalid -> return ImportResult.Invalid(v.problems)
            is ValidationResult.Ok -> v.plan
        }
        return withContext(NonCancellable) {
            // 1. Snapshot every section we are about to replace. Nothing has been written yet.
            val snapshots = LinkedHashMap<String, JsonElement>()
            for (c in plan.toImport) {
                try {
                    snapshots[c.key] = c.export()
                } catch (e: Exception) {
                    return@withContext ImportResult.Failed(c.key, "could not snapshot current data: ${reason(e)}", emptyList())
                }
            }
            // 2. Import in order; on failure, roll back the failed section and every one before it.
            val done = ArrayList<BackupContributor>()
            for (c in plan.toImport) {
                try {
                    c.import(file.sections.getValue(c.key))
                    done += c
                } catch (e: Exception) {
                    val failures = (done + c).asReversed().mapNotNull { r ->
                        try {
                            r.import(snapshots.getValue(r.key))
                            null
                        } catch (re: Exception) {
                            r.key
                        }
                    }
                    return@withContext ImportResult.Failed(c.key, reason(e), failures)
                }
            }
            ImportResult.Success(done.map { it.key }, plan.unknownKeys)
        }
    }

    private fun reason(e: Exception) = e.message?.take(200) ?: e::class.simpleName ?: "error"

    companion object {
        const val FORMAT = "atom-backup"
        const val FORMAT_VERSION = 1
        val SUPPORTED_VERSIONS = 1..1
        /** Hard cap on what is read from a picked file. */
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_CHARS = MAX_BYTES.toInt()
        const val MIME = "application/json"

        private const val F_FORMAT = "format"
        private const val F_VERSION = "formatVersion"
        private const val F_APP_VERSION = "appVersion"
        private const val F_CREATED = "createdAt"
        private const val F_CREATED_MS = "createdAtMillis"
        private const val F_KEYS = "contributors"
        private const val F_SECRETS = "containsSecrets"
        private const val F_DATA = "data"

        private val json = Json { prettyPrint = false }

        /** Suggested file name, e.g. atom-backup-20261125-0310.json. */
        fun fileName(millis: Long): String {
            val t = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(millis))
            return "atom-backup-$t.json"
        }
    }
}
