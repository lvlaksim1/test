package __PACKAGE__.timeaccessibility

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeCycleStore {
    private const val PREFS = "time_cycle_shizuku"
    private const val KEY_START = "start_at"
    private const val KEY_DAYS = "step_days"
    private const val KEY_HOURS = "step_hours"
    private const val KEY_MINUTES = "step_minutes"
    private const val KEY_PAUSE = "pause_seconds"
    private const val KEY_REPEATS_PER_SERIES = "repeats_per_series"
    private const val KEY_SERIES_PAUSE = "series_pause_seconds"
    private const val KEY_TOTAL_SERIES = "total_series"
    private const val KEY_TOTAL = "total_cycles"
    private const val KEY_COMPLETED = "completed_cycles"
    private const val KEY_RUNNING = "is_running"
    private const val KEY_LAST_APPLIED = "last_applied_at"
    private const val KEY_AUTOMATIC_TIME = "automatic_time_enabled"
    private const val KEY_EVENTS = "events"
    private const val KEY_PHASE = "cycle_phase"
    private const val KEY_NEXT_DUE_ELAPSED = "next_due_elapsed"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_SERVICE_HEARTBEAT = "service_heartbeat_elapsed"
    private const val KEY_RUNTIME_SCHEMA = "runtime_schema"
    private const val RUNTIME_SCHEMA = 2
    private const val HEARTBEAT_STALE_MILLIS = 20_000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    fun saveAndStart(context: Context, plan: CyclePlan) {
        CycleEngine.validatePlan(plan)
        val nowElapsed = SystemClock.elapsedRealtime()
        val state = CycleEngine.initialState(nowElapsed)
        prefs(context).edit()
            .putLong(KEY_START, plan.startAtMillis)
            .putInt(KEY_DAYS, plan.stepDays)
            .putInt(KEY_HOURS, plan.stepHours)
            .putInt(KEY_MINUTES, plan.stepMinutes)
            .putInt(KEY_PAUSE, (plan.pauseMillis / 1000L).toInt())
            .putInt(KEY_REPEATS_PER_SERIES, plan.repeatsPerSeries)
            .putInt(KEY_SERIES_PAUSE, (plan.seriesPauseMillis / 1000L).toInt())
            .putInt(KEY_TOTAL_SERIES, plan.totalSeries)
            .putInt(KEY_TOTAL, plan.totalCycles)
            .putInt(KEY_BOOT_COUNT, bootCount(context))
            .putLong(KEY_SERVICE_HEARTBEAT, nowElapsed)
            .putInt(KEY_RUNTIME_SCHEMA, RUNTIME_SCHEMA)
            .remove(KEY_LAST_APPLIED)
            .apply()
        saveState(context, state)
        addEvent(
            context,
            "Цикл запущен: главных циклов — ${plan.totalSeries}, повторов в каждом — ${plan.repeatsPerSeries}, всего изменений — ${plan.totalCycles}.",
        )
    }

    fun loadPlan(context: Context): CyclePlan? {
        val storage = prefs(context)
        if (!storage.contains(KEY_START)) return null
        return CyclePlan(
            startAtMillis = storage.getLong(KEY_START, 0L),
            stepDays = storage.getInt(KEY_DAYS, 0),
            stepHours = storage.getInt(KEY_HOURS, 0),
            stepMinutes = storage.getInt(KEY_MINUTES, 0),
            pauseMillis = storage.getInt(KEY_PAUSE, 1).coerceAtLeast(1) * 1000L,
            repeatsPerSeries = storage.getInt(KEY_REPEATS_PER_SERIES, 1).coerceAtLeast(1),
            seriesPauseMillis = storage.getInt(KEY_SERIES_PAUSE, 0).coerceAtLeast(0) * 1000L,
            totalSeries = storage.getInt(KEY_TOTAL_SERIES, 1).coerceAtLeast(1),
        )
    }

    fun loadState(context: Context): CycleRuntimeState {
        val storage = prefs(context)
        val rawPhase = storage.getString(KEY_PHASE, CyclePhase.IDLE.name)
        val phase = runCatching { CyclePhase.valueOf(rawPhase ?: CyclePhase.IDLE.name) }
            .getOrDefault(CyclePhase.IDLE)
        return CycleRuntimeState(
            phase = phase,
            completedCycles = storage.getInt(KEY_COMPLETED, 0).coerceAtLeast(0),
            nextDueElapsed = if (storage.contains(KEY_NEXT_DUE_ELAPSED)) storage.getLong(KEY_NEXT_DUE_ELAPSED, 0L) else null,
            lastAppliedMillis = if (storage.contains(KEY_LAST_APPLIED)) storage.getLong(KEY_LAST_APPLIED, 0L) else null,
        )
    }

    fun saveState(context: Context, state: CycleRuntimeState) {
        val active = state.phase == CyclePhase.WAITING || state.phase == CyclePhase.APPLYING || state.phase == CyclePhase.STOPPING
        val editor = prefs(context).edit()
            .putInt(KEY_COMPLETED, state.completedCycles)
            .putBoolean(KEY_RUNNING, active)
            .putString(KEY_PHASE, state.phase.name)
        if (state.nextDueElapsed == null) editor.remove(KEY_NEXT_DUE_ELAPSED) else editor.putLong(KEY_NEXT_DUE_ELAPSED, state.nextDueElapsed)
        if (state.lastAppliedMillis == null) editor.remove(KEY_LAST_APPLIED) else editor.putLong(KEY_LAST_APPLIED, state.lastAppliedMillis)
        editor.apply()
    }

    fun stop(context: Context, note: String = "Цикл остановлен пользователем.") {
        val state = loadState(context)
        if (!isActive(state)) return
        saveState(context, CycleEngine.markStopped(state))
        addEvent(context, note)
    }

    fun markStopping(context: Context) {
        val state = loadState(context)
        if (isActive(state)) saveState(context, CycleEngine.markStopping(state))
    }

    fun stopInterrupted(context: Context, note: String) {
        val state = loadState(context)
        if (!isActive(state)) return
        saveState(context, CycleEngine.markInterrupted(state))
        addEvent(context, note)
    }

    fun isRunning(context: Context): Boolean = isActive(loadState(context))

    private fun isActive(state: CycleRuntimeState): Boolean =
        state.phase == CyclePhase.WAITING || state.phase == CyclePhase.APPLYING || state.phase == CyclePhase.STOPPING

    fun markServiceHeartbeat(context: Context) {
        if (!isRunning(context)) return
        prefs(context).edit().putLong(KEY_SERVICE_HEARTBEAT, SystemClock.elapsedRealtime()).apply()
    }

    fun validateRuntimeForService(context: Context): Boolean {
        if (!isRunning(context)) return false
        val storage = prefs(context)
        if (!storage.contains(KEY_PHASE) || !storage.contains(KEY_BOOT_COUNT)) {
            stopInterrupted(context, "Цикл остановлен: сохранённое состояние относится к предыдущей версии приложения.")
            return false
        }
        val storedBoot = storage.getInt(KEY_BOOT_COUNT, -1)
        val currentBoot = bootCount(context)
        if (storedBoot >= 0 && currentBoot >= 0 && storedBoot != currentBoot) {
            stopInterrupted(context, "Цикл остановлен после перезагрузки устройства. Запустите его заново.")
            return false
        }
        val plan = loadPlan(context)
        if (plan == null || runCatching { CycleEngine.validatePlan(plan) }.isFailure) {
            stopInterrupted(context, "Цикл остановлен: сохранённый план повреждён. Запустите цикл заново.")
            return false
        }
        return true
    }

    fun reconcileRuntimeState(context: Context) {
        if (!validateRuntimeForService(context)) return
        val storage = prefs(context)
        val heartbeat = storage.getLong(KEY_SERVICE_HEARTBEAT, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (heartbeat <= 0L || heartbeat > nowElapsed || nowElapsed - heartbeat > HEARTBEAT_STALE_MILLIS) {
            stopInterrupted(context, "Цикл остановлен: фоновая служба больше не выполняется. Запустите цикл заново.")
        }
    }

    fun setAutomaticTimeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC_TIME, enabled).apply()
    }

    fun status(context: Context): JSONObject {
        reconcileRuntimeState(context)
        val storage = prefs(context)
        val state = loadState(context)
        val plan = loadPlan(context)
        val running = isActive(state)
        val nextTarget = if (running && plan != null) CycleEngine.currentStep(plan, state)?.targetMillis else null
        return JSONObject().apply {
            put("isRunning", running)
            put("completedCycles", state.completedCycles)
            put("totalCycles", plan?.totalCycles ?: storage.getInt(KEY_TOTAL, 0))
            put("nextTargetMillis", nextTarget ?: JSONObject.NULL)
            put("lastAppliedMillis", state.lastAppliedMillis ?: JSONObject.NULL)
            put("isAutomaticTimeEnabled", storage.getBoolean(KEY_AUTOMATIC_TIME, true))
            put("phase", state.phase.name)
            put("events", events(context))
        }
    }

    fun logAttemptSucceeded(context: Context, targetMillis: Long, detail: String) {
        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(targetMillis))
        addEvent(context, "Применено значение $formatted. $detail")
    }

    fun logAttemptFailed(context: Context, targetMillis: Long, detail: String) {
        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(targetMillis))
        addEvent(context, "Не подтверждено значение $formatted. $detail")
        addEvent(context, "Цикл остановлен из-за ошибки Shizuku.")
    }

    fun logCompleted(context: Context, plan: CyclePlan) {
        addEvent(context, "Все ${plan.totalSeries} главных циклов завершены. Выполнено изменений: ${plan.totalCycles}.")
    }

    fun events(context: Context): JSONArray {
        val stored = prefs(context).getString(KEY_EVENTS, "[]") ?: "[]"
        return runCatching { JSONArray(stored) }.getOrDefault(JSONArray())
    }

    fun clearEvents(context: Context) {
        prefs(context).edit().putString(KEY_EVENTS, "[]").apply()
    }

    fun addEvent(context: Context, message: String) {
        val storage = prefs(context)
        val items = events(context)
        items.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        })
        while (items.length() > 30) items.remove(0)
        storage.edit().putString(KEY_EVENTS, items.toString()).apply()
    }
}
