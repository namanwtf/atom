package atom.automation.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import atom.automation.Automations
import atom.automation.rule.WaitFor
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Continues an automation once the phone is charging or idle; the system batches it with other deferred work. */
class DeferredActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ruleId = inputData.getString(KEY_RULE) ?: return Result.failure()
        val index = inputData.getInt(KEY_INDEX, 0)
        val vars = inputData.getString(KEY_VARS)?.let { runCatching { Json.decodeFromString(VARS, it) }.getOrNull() }.orEmpty()
        Automations.attachIfNeeded(applicationContext)
        val rt = AutomationRuntime.get(applicationContext)
        rt.start()
        rt.rules.resume(ruleId, index, vars)
        return Result.success()
    }

    companion object {
        private const val KEY_RULE = "rule"
        private const val KEY_INDEX = "index"
        private const val KEY_VARS = "vars"
        private val VARS = MapSerializer(String.serializer(), String.serializer())

        fun enqueue(context: Context, ruleId: String, index: Int, vars: Map<String, String>, waitFor: WaitFor) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(waitFor == WaitFor.Charging)
                .setRequiresDeviceIdle(waitFor == WaitFor.Idle)
                .build()
            val trimmed = vars.mapValues { it.value.take(2_000) }
            val request = OneTimeWorkRequestBuilder<DeferredActionWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_RULE to ruleId, KEY_INDEX to index, KEY_VARS to Json.encodeToString(VARS, trimmed)))
                .addTag("atom-automation")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("atom-automation-$ruleId-$index", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
