package __PACKAGE__.timeaccessibility

import android.content.Intent
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.json.JSONArray
import org.json.JSONObject

class TimeAccessibilityModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName(): String = "TimeAccessibility"

    @ReactMethod
    fun getStatus(promise: Promise) {
        val status = jsonToWritableMap(TimeCycleStore.status(context))
        status.putBoolean("isAccessibilityEnabled", TimeAccessibilityService.isServiceActive())
        promise.resolve(status)
    }

    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        runCatching {
            TimeCycleStore.requestReturnToAppAfterEnable(context)
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onSuccess {
            promise.resolve(true)
        }.onFailure {
            promise.reject("OPEN_SETTINGS_FAILED", "Не удалось открыть настройки специальных возможностей.", it)
        }
    }

    @ReactMethod
    fun startCycle(settings: ReadableMap, promise: Promise) {
        if (!TimeAccessibilityService.isServiceActive()) {
            promise.reject("ACCESSIBILITY_DISABLED", "Сначала вручную включите службу в специальных возможностях Android.")
            return
        }

        runCatching {
            val startAt = settings.getDouble("startAtMillis").toLong()
            val days = settings.getInt("stepDays")
            val hours = settings.getInt("stepHours")
            val minutes = settings.getInt("stepMinutes")
            val pause = settings.getInt("pauseSeconds")
            val total = settings.getInt("totalCycles")
            require(pause in 1..86400) { "Пауза должна быть от 1 секунды до 24 часов." }
            require(total in 1..1000) { "Количество циклов должно быть от 1 до 1000." }
            require(days in 0..999 && hours in 0..999 && minutes in 0..999) { "Шаг задан вне допустимого диапазона." }

            TimeCycleStore.saveAndStart(context, startAt, days, hours, minutes, pause, total)
            TimeAccessibilityService.requestStart(context)
        }.onSuccess {
            promise.resolve(jsonToWritableMap(TimeCycleStore.status(context)))
        }.onFailure {
            promise.reject("START_FAILED", it.message ?: "Не удалось запустить цикл.", it)
        }
    }

    @ReactMethod
    fun stopCycle(promise: Promise) {
        TimeCycleStore.stop(context)
        TimeAccessibilityService.requestStop()
        promise.resolve(jsonToWritableMap(TimeCycleStore.status(context)))
    }

    @ReactMethod
    fun clearEvents(promise: Promise) {
        TimeCycleStore.clearEvents(context)
        promise.resolve(true)
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
