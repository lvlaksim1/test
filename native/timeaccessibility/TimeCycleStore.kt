package __PACKAGE__.timeaccessibility

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
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
    private const val KEY_NEXT_DUE_ELAPSED = "next_due_elapsed"
    private const val KEY_EVENTS = "events"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveAndStart(
        context: Context,
        startAtMillis: Long,
        stepDays: Int,
        stepHours: Int,
        stepMinutes: Int,
        pauseSeconds: Int,
        repeatsPerSeries: Int,
        seriesPauseSeconds: Int,
        totalSeries: Int,
        totalCycles: Int,
    ) {
        prefs(context).edit()
            .putLong(KEY_START, startAtMillis)
            .putInt(KEY_DAYS, stepDays)
            .putInt(KEY_HOURS, stepHours)
            .putInt(KEY_MINUTES, stepMinutes)
            .putInt(KEY_PAUSE, pauseSeconds)
            .putInt(KEY_REPEATS_PER_SERIES, repeatsPerSeries)
            .putInt(KEY_SERIES_PAUSE, seriesPauseSeconds)
            .putInt(KEY_TOTAL_SERIES, totalSeries)
            .putInt(KEY_TOTAL, totalCycles)
            .putInt(KEY_COMPLETED, 0)
            .putBoolean(KEY_RUNNING, true)
            .remove(KEY_LAST_APPLIED)
            .remove(KEY_NEXT_DUE_ELAPSED)
            .apply()
        addEvent(context, "Цикл запущен: главных циклов — $totalSeries, повторов в каждом — $repeatsPerSeries, всего изменений — $totalCycles.")
    }

    fun stop(context: Context, note: String = "Цикл остановлен пользователем.") {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .remove(KEY_NEXT_DUE_ELAPSED)
            .apply()
        addEvent(context, note)
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)
    fun completedCycles(context: Context): Int = prefs(context).getInt(KEY_COMPLETED, 0)
    fun totalCycles(context: Context): Int = prefs(context).getInt(KEY_TOTAL, 0)
    fun repeatsPerSeries(context: Context): Int {
        val storage = prefs(context)
        return storage.getInt(KEY_REPEATS_PER_SERIES, storage.getInt(KEY_TOTAL, 1)).coerceAtLeast(1)
    }
    fun totalSeries(context: Context): Int = prefs(context).getInt(KEY_TOTAL_SERIES, 1).coerceAtLeast(1)

    fun isBetweenSeriesPause(context: Context): Boolean {
        val completed = completedCycles(context)
        val total = totalCycles(context)
        val repeats = repeatsPerSeries(context)
        return completed > 0 && completed < total && completed % repeats == 0
    }

    fun pauseBeforeNextMillis(context: Context): Long {
        val storage = prefs(context)
        val seconds = if (isBetweenSeriesPause(context)) {
            storage.getInt(KEY_SERIES_PAUSE, 0).coerceAtLeast(0)
        } else {
            storage.getInt(KEY_PAUSE, 1).coerceAtLeast(1)
        }
        return seconds * 1000L
    }

    fun nextDueElapsed(context: Context): Long? {
        val storage = prefs(context)
        return if (storage.contains(KEY_NEXT_DUE_ELAPSED)) storage.getLong(KEY_NEXT_DUE_ELAPSED, 0L) else null
    }

    fun setNextDueElapsed(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_NEXT_DUE_ELAPSED, value).apply()
    }

    fun clearNextDueElapsed(context: Context) {
        prefs(context).edit().remove(KEY_NEXT_DUE_ELAPSED).apply()
    }

    fun setAutomaticTimeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOMATIC_TIME, enabled).apply()
    }

    fun targetForCurrentCycle(context: Context): Long {
        val storage = prefs(context)
        val calendar = Calendar.getInstance().apply { timeInMillis = storage.getLong(KEY_START, System.currentTimeMillis()) }
        val index = storage.getInt(KEY_COMPLETED, 0)
        calendar.add(Calendar.DAY_OF_YEAR, storage.getInt(KEY_DAYS, 0) * index)
        calendar.add(Calendar.HOUR_OF_DAY, storage.getInt(KEY_HOURS, 0) * index)
        calendar.add(Calendar.MINUTE, storage.getInt(KEY_MINUTES, 0) * index)
        return calendar.timeInMillis
    }

    fun markAttemptSucceeded(context: Context, targetMillis: Long, detail: String): Boolean {
        val storage = prefs(context)
        val completed = storage.getInt(KEY_COMPLETED, 0) + 1
        val total = storage.getInt(KEY_TOTAL, 0)
        val running = completed < total
        val editor = storage.edit()
            .putInt(KEY_COMPLETED, completed)
            .putBoolean(KEY_RUNNING, running)
            .putLong(KEY_LAST_APPLIED, targetMillis)
        if (!running) editor.remove(KEY_NEXT_DUE_ELAPSED)
        editor.apply()
        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(targetMillis))
        addEvent(context, "Применено значение $formatted. $detail")
        if (!running) addEvent(context, "Все ${totalSeries(context)} главных циклов завершены. Выполнено изменений: $total.")
        return running
    }

    fun markAttemptFailed(context: Context, targetMillis: Long, detail: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .remove(KEY_NEXT_DUE_ELAPSED)
            .apply()
        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(targetMillis))
        addEvent(context, "Не подтверждено значение $formatted. $detail")
        addEvent(context, "Цикл остановлен из-за ошибки Shizuku.")
    }

    fun finishIfComplete(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .remove(KEY_NEXT_DUE_ELAPSED)
            .apply()
    }

    fun status(context: Context): JSONObject {
        val storage = prefs(context)
        val running = storage.getBoolean(KEY_RUNNING, false)
        return JSONObject().apply {
            put("isRunning", running)
            put("completedCycles", storage.getInt(KEY_COMPLETED, 0))
            put("totalCycles", storage.getInt(KEY_TOTAL, 0))
            put("nextTargetMillis", if (running && storage.contains(KEY_START)) targetForCurrentCycle(context) else JSONObject.NULL)
            put("lastAppliedMillis", if (storage.contains(KEY_LAST_APPLIED)) storage.getLong(KEY_LAST_APPLIED, 0L) else JSONObject.NULL)
            put("isAutomaticTimeEnabled", storage.getBoolean(KEY_AUTOMATIC_TIME, true))
            put("events", events(context))
        }
    }

    fun events(context: Context): JSONArray {
        val stored = prefs(context).getString(KEY_EVENTS, "[]") ?: "[]"
        return runCatching { JSONArray(stored) }.getOrDefault(JSONArray())
    }

    fun clearEvents(context: Context) { prefs(context).edit().putString(KEY_EVENTS, "[]").apply() }

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
