package __PACKAGE__.timeaccessibility

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject

class TimeControlModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName(): String = "TimeControl"

    @ReactMethod
    fun getStatus(promise: Promise) {
        TimeCycleStore.reconcileRuntimeState(context)
        val shizuku = TimeShizukuController.state()
        if (shizuku.isPermissionGranted && !TimeCycleStore.isRunning(context)) {
            TimeShizukuController.getAutomaticTime(context) { outcome ->
                if (outcome.isSuccess) {
                    when (outcome.detail.removePrefix("OK:").trim()) {
                        "1" -> TimeCycleStore.setAutomaticTimeEnabled(context, true)
                        "0" -> TimeCycleStore.setAutomaticTimeEnabled(context, false)
                    }
                }
                resolveStatus(promise)
            }
        } else {
            resolveStatus(promise)
        }
    }

    @ReactMethod
    fun requestShizukuPermission(promise: Promise) {
        val current = TimeShizukuController.state()
        if (!current.isRunning) {
            promise.reject("SHIZUKU_NOT_RUNNING", "Сначала установите и запустите Shizuku через беспроводную отладку.")
            return
        }
        TimeShizukuController.requestPermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun setAutomaticTime(enabled: Boolean, promise: Promise) {
        if (TimeCycleStore.isRunning(context)) {
            promise.reject("CYCLE_RUNNING", "Нельзя переключать синхронизацию во время выполнения цикла.")
            return
        }
        TimeShizukuController.setAutomaticTime(context, enabled) { outcome ->
            if (outcome.isSuccess) {
                TimeCycleStore.setAutomaticTimeEnabled(context, enabled)
                TimeCycleStore.addEvent(context, outcome.detail)
                resolveStatus(promise)
            } else {
                promise.reject("AUTOMATIC_TIME_FAILED", outcome.detail)
            }
        }
    }

    @ReactMethod
    fun startCycle(settings: ReadableMap, promise: Promise) {
        val shizuku = TimeShizukuController.state()
        if (!shizuku.isPermissionGranted) {
            promise.reject("SHIZUKU_PERMISSION_REQUIRED", "Сначала запустите Shizuku и нажмите «Разрешить Shizuku» в приложении.")
            return
        }
        if (TimeCycleStore.isRunning(context)) {
            promise.reject("CYCLE_RUNNING", "Цикл уже выполняется.")
            return
        }
        runCatching {
            val plan = CyclePlan(
                startAtMillis = settings.getDouble("startAtMillis").toLong(),
                stepDays = settings.getInt("stepDays"),
                stepHours = settings.getInt("stepHours"),
                stepMinutes = settings.getInt("stepMinutes"),
                pauseMillis = settings.getInt("pauseSeconds") * 1000L,
                repeatsPerSeries = settings.getInt("repeatsPerSeries"),
                seriesPauseMillis = settings.getInt("seriesPauseSeconds") * 1000L,
                totalSeries = settings.getInt("totalSeries"),
            )
            CycleEngine.validatePlan(plan)
            val requestedTotal = settings.getInt("totalCycles")
            require(requestedTotal == plan.totalCycles) {
                "Общее количество изменений не соответствует параметрам цикла."
            }
            TimeCycleStore.saveAndStart(context, plan)
            TimeCycleForegroundService.start(context)
        }.onSuccess { resolveStatus(promise) }
            .onFailure { promise.reject("START_FAILED", it.message ?: "Не удалось запустить цикл.", it) }
    }

    @ReactMethod
    fun stopCycle(promise: Promise) {
        TimeShizukuCycleRunner.stop(context)
        TimeCycleStore.stop(context)
        TimeCycleForegroundService.stop(context)
        resolveStatus(promise)
    }

    @ReactMethod
    fun clearEvents(promise: Promise) {
        TimeCycleStore.clearEvents(context)
        promise.resolve(true)
    }

    private fun resolveStatus(promise: Promise) {
        val status = jsonToWritableMap(TimeCycleStore.status(context))
        val shizuku = TimeShizukuController.state()
        status.putBoolean("isShizukuRunning", shizuku.isRunning)
        status.putBoolean("isShizukuPermissionGranted", shizuku.isPermissionGranted)
        promise.resolve(status)
    }

    private fun jsonToWritableMap(value: JSONObject): WritableMap {
        val result = Arguments.createMap()
        val iterator = value.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            when (val item = value.opt(key)) {
                null, JSONObject.NULL -> result.putNull(key)
                is Boolean -> result.putBoolean(key, item)
                is Int -> result.putInt(key, item)
                is Long -> result.putDouble(key, item.toDouble())
                is Double -> result.putDouble(key, item)
                is String -> result.putString(key, item)
                is JSONObject -> result.putMap(key, jsonToWritableMap(item))
                is JSONArray -> result.putArray(key, jsonToWritableArray(item))
                else -> result.putString(key, item.toString())
            }
        }
        return result
    }

    private fun jsonToWritableArray(value: JSONArray): WritableArray {
        val result = Arguments.createArray()
        for (index in 0 until value.length()) {
            when (val item = value.opt(index)) {
                null, JSONObject.NULL -> result.pushNull()
                is Boolean -> result.pushBoolean(item)
                is Int -> result.pushInt(item)
                is Long -> result.pushDouble(item.toDouble())
                is Double -> result.pushDouble(item)
                is String -> result.pushString(item)
                is JSONObject -> result.pushMap(jsonToWritableMap(item))
                is JSONArray -> result.pushArray(jsonToWritableArray(item))
                else -> result.pushString(item.toString())
            }
        }
        return result
    }
}
