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
    private const val PREFS = "time_cycle_accessibility"
    private const val KEY_START = "start_at"
    private const val KEY_DAYS = "step_days"
    private const val KEY_HOURS = "step_hours"
    private const val KEY_MINUTES = "step_minutes"
    private const val KEY_PAUSE = "pause_seconds"
    private const val KEY_TOTAL = "total_cycles"
    private const val KEY_COMPLETED = "completed_cycles"
    private const val KEY_RUNNING = "is_running"
    private const val KEY_EVENTS = "events"
    private const val KEY_RETURN_TO_APP = "return_to_app_after_enable"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveAndStart(
        context: Context,
        startAtMillis: Long,
        stepDays: Int,
        stepHours: Int,
        stepMinutes: Int,
        pauseSeconds: Int,
        totalCycles: Int,
    ) {
        prefs(context).edit()
            .putLong(KEY_START, startAtMillis)
            .putInt(KEY_DAYS, stepDays)
            .putInt(KEY_HOURS, stepHours)
            .putInt(KEY_MINUTES, stepMinutes)
            .putInt(KEY_PAUSE, pauseSeconds)
            .putInt(KEY_TOTAL, totalCycles)
            .putInt(KEY_COMPLETED, 0)
            .putBoolean(KEY_RUNNING, true)
            .apply()
        addEvent(context, "Цикл запущен: задано попыток — $totalCycles.")
    }

    fun stop(context: Context, note: String = "Цикл остановлен пользователем.") {
        prefs(context).edit().putBoolean(KEY_RUNNING, false).apply()
        addEvent(context, note)
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)

    fun completedCycles(context: Context): Int = prefs(context).getInt(KEY_COMPLETED, 0)

    fun totalCycles(context: Context): Int = prefs(context).getInt(KEY_TOTAL, 0)

    fun pauseMillis(context: Context): Long = prefs(context).getInt(KEY_PAUSE, 2).coerceAtLeast(1) * 1000L

    fun requestReturnToAppAfterEnable(context: Context) {
        prefs(context).edit().putBoolean(KEY_RETURN_TO_APP, true).apply()
    }

    fun consumeReturnToAppAfterEnable(context: Context): Boolean {
        val storage = prefs(context)
        val requested = storage.getBoolean(KEY_RETURN_TO_APP, false)
        if (requested) storage.edit().putBoolean(KEY_RETURN_TO_APP, false).apply()
        return requested
    }

    fun targetForCurrentCycle(context: Context): Long {
        val storage = prefs(context)
        val calendar = Calendar.getInstance().apply {
            timeInMillis = storage.getLong(KEY_START, System.currentTimeMillis())
        }
        val index = storage.getInt(KEY_COMPLETED, 0)
        calendar.add(Calendar.DAY_OF_YEAR, storage.getInt(KEY_DAYS, 0) * index)
        calendar.add(Calendar.HOUR_OF_DAY, storage.getInt(KEY_HOURS, 0) * index)
        calendar.add(Calendar.MINUTE, storage.getInt(KEY_MINUTES, 0) * index)
        return calendar.timeInMillis
    }

    fun markAttemptFinished(context: Context, targetMillis: Long, success: Boolean, detail: String) {
        val storage = prefs(context)
        val completed = storage.getInt(KEY_COMPLETED, 0) + 1
        val total = storage.getInt(KEY_TOTAL, 0)
        val running = completed < total
        storage.edit()
            .putInt(KEY_COMPLETED, completed)
            .putBoolean(KEY_RUNNING, running)
            .apply()

        val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(targetMillis))
        val prefix = if (success) "Применено" else "Не подтверждено"
        addEvent(context, "$prefix значение $formatted. $detail")
        if (!running) {
            addEvent(context, "Все $total циклов завершены.")
        }
    }

    fun status(context: Context): JSONObject {
        val storage = prefs(context)
        return JSONObject().apply {
            put("isRunning", storage.getBoolean(KEY_RUNNING, false))
            put("completedCycles", storage.getInt(KEY_COMPLETED, 0))
            put("totalCycles", storage.getInt(KEY_TOTAL, 0))
            put("nextTargetMillis", if (storage.contains(KEY_START)) targetForCurrentCycle(context) else JSONObject.NULL)
            put("events", events(context))
        }
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
        val entry = JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("message", message)
        }
        items.put(entry)
        while (items.length() > 30) {
            items.remove(0)
        }
        storage.edit().putString(KEY_EVENTS, items.toString()).apply()
    }
}
