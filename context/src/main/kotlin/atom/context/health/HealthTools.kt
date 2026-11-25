package atom.context.health

import android.content.Context
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.AggregationType
import android.health.connect.datatypes.BodyFatRecord
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.HeightRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RestingHeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.TotalCaloriesBurnedRecord
import android.health.connect.datatypes.WeightRecord
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import atom.context.sources.CtxTool
import atom.context.sources.Missing
import atom.context.sources.When
import atom.context.sources.guarded
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.schema
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Health Connect read permissions Atom asks for; all read-only. */
object HealthPerms {
    val all = listOf(
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_DISTANCE",
        "android.permission.health.READ_TOTAL_CALORIES_BURNED",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_RESTING_HEART_RATE",
        "android.permission.health.READ_WEIGHT",
        "android.permission.health.READ_HEIGHT",
        "android.permission.health.READ_BODY_FAT",
    )

    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && context.getSystemService(HealthConnectManager::class.java) != null

    fun granted(context: Context, p: String) = context.checkSelfPermission(p) == 0
}

/** Health summary from Health Connect (built into Android 14+). Read on demand; nothing is cached or synced. */
internal class HealthTools(context: Context) {
    private val app = context.applicationContext

    fun all(): List<Tool> = listOf(summary())

    private fun summary() = CtxTool(
        name = "health_summary",
        description = "Health Connect summary for the last N days (default 1 = today so far): steps, distance, calories, " +
            "sleep, exercise sessions, heart rate, resting heart rate, weight, height, body fat. Only the types the user allowed.",
        risk = Risk.Read,
        parameters = schema { integer("days", "Window in days, 1-30. Default 1 (since midnight).") },
    ) { args, _ ->
        guarded {
            if (!HealthPerms.available(app)) throw Missing("Health Connect needs Android 14 or newer; it is not available on this phone.")
            if (HealthPerms.all.none { HealthPerms.granted(app, it) }) {
                throw Missing("Atom has no Health Connect access. Ask the user to allow it on Atom's Permissions page (Health Connect row).")
            }
            val days = (args.optInt("days") ?: 1).coerceIn(1, 30)
            ToolResult.sensitive(HealthReader(app).summary(days))
        }
    }
}

@RequiresApi(34)
private class HealthReader(private val app: Context) {
    private val hc = app.getSystemService(HealthConnectManager::class.java)!!
    private val exec = Executors.newSingleThreadExecutor()

    private fun has(p: String) = HealthPerms.granted(app, "android.permission.health.$p")

    suspend fun summary(days: Int): String = try {
        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val start = java.time.LocalDate.now(zone).minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val range = TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()
        val lines = mutableListOf("Health ${if (days == 1) "today so far" else "over the last $days days"} (Health Connect):")

        if (has("READ_STEPS")) aggregate(range, StepsRecord.STEPS_COUNT_TOTAL)?.let {
            lines += "steps: $it" + if (days > 1) " (${it / days}/day)" else ""
        }
        if (has("READ_DISTANCE")) aggregate(range, DistanceRecord.DISTANCE_TOTAL)?.let { lines += String.format(Locale.US, "distance: %.1f km", it.inMeters / 1000) }
        if (has("READ_TOTAL_CALORIES_BURNED")) aggregate(range, TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { lines += String.format(Locale.US, "calories burned: %.0f kcal", it.inCalories / 1000) }
        if (has("READ_SLEEP")) {
            val sleepRange = TimeInstantRangeFilter.Builder().setStartTime(start.minus(Duration.ofHours(12))).setEndTime(end).build()
            val sessions = read(SleepSessionRecord::class.java, sleepRange)
            lines += if (sessions.isNotEmpty()) {
                val total = sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val last = sessions.maxBy { it.endTime }
                "sleep: ${sessions.size} session${if (sessions.size == 1) "" else "s"}, ${total / 60} h ${total % 60} min total; last " +
                    "${When.at(last.startTime.toEpochMilli())} → ${last.endTime.atZone(zone).toLocalTime().toString().take(5)}"
            } else {
                "sleep: nothing recorded"
            }
        }
        if (has("READ_EXERCISE")) {
            val ex = read(ExerciseSessionRecord::class.java, range)
            lines += if (ex.isEmpty()) "exercise: none recorded" else "exercise: " + ex.sortedBy { it.startTime }.takeLast(10).joinToString("; ") {
                "${exerciseName(it.exerciseType)} ${Duration.between(it.startTime, it.endTime).toMinutes()} min (${When.at(it.startTime.toEpochMilli())})"
            }
        }
        if (has("READ_HEART_RATE")) {
            val avg = aggregate(range, HeartRateRecord.BPM_AVG)
            if (avg != null) lines += "heart rate: avg $avg bpm (min ${aggregate(range, HeartRateRecord.BPM_MIN)}, max ${aggregate(range, HeartRateRecord.BPM_MAX)})"
        }
        if (has("READ_RESTING_HEART_RATE")) read(RestingHeartRateRecord::class.java, range).maxByOrNull { it.time }?.let { lines += "resting heart rate: ${it.beatsPerMinute} bpm" }
        val bodyRange = TimeInstantRangeFilter.Builder().setStartTime(end.minus(Duration.ofDays(365))).setEndTime(end).build()
        if (has("READ_WEIGHT")) read(WeightRecord::class.java, bodyRange).maxByOrNull { it.time }?.let {
            lines += String.format(Locale.US, "weight: %.1f kg (%s)", it.weight.inGrams / 1000, When.ago(it.time.toEpochMilli()))
        }
        if (has("READ_HEIGHT")) read(HeightRecord::class.java, bodyRange).maxByOrNull { it.time }?.let { lines += String.format(Locale.US, "height: %.0f cm", it.height.inMeters * 100) }
        if (has("READ_BODY_FAT")) read(BodyFatRecord::class.java, bodyRange).maxByOrNull { it.time }?.let { lines += String.format(Locale.US, "body fat: %.1f%%", it.percentage.value) }
        if (lines.size == 1) lines += "No data for the allowed types in this window."
        val missing = HealthPerms.all.count { !HealthPerms.granted(app, it) }
        if (missing > 0) lines += "($missing data types are not allowed; the user can allow them on the Permissions page.)"
        lines.joinToString("\n")
    } finally {
        exec.shutdown()
    }

    private suspend fun <T : Any> aggregate(range: TimeInstantRangeFilter, type: AggregationType<T>): T? = suspendCancellableCoroutine { cont ->
        try {
            val req = AggregateRecordsRequest.Builder<T>(range).addAggregationType(type).build()
            hc.aggregate(req, exec, object : OutcomeReceiver<AggregateRecordsResponse<T>, HealthConnectException> {
                override fun onResult(result: AggregateRecordsResponse<T>) {
                    if (cont.isActive) cont.resume(result.get(type))
                }

                override fun onError(error: HealthConnectException) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun <T : Record> read(cls: Class<T>, range: TimeInstantRangeFilter): List<T> = suspendCancellableCoroutine { cont ->
        try {
            val req = ReadRecordsRequestUsingFilters.Builder(cls).setTimeRangeFilter(range).setPageSize(500).build()
            hc.readRecords(req, exec, object : OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException> {
                override fun onResult(result: ReadRecordsResponse<T>) {
                    if (cont.isActive) cont.resume(result.records)
                }

                override fun onError(error: HealthConnectException) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private fun exerciseName(type: Int): String = ExerciseSessionType::class.java.fields
        .firstOrNull { it.name.startsWith("EXERCISE_SESSION_TYPE_") && runCatching { it.getInt(null) }.getOrNull() == type }
        ?.name?.removePrefix("EXERCISE_SESSION_TYPE_")?.lowercase()?.replace('_', ' ') ?: "exercise"
}
