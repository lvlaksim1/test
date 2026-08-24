package __PACKAGE__.timeaccessibility

import android.util.Xml
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
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class TimeControlModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName(): String = "TimeControl"

    @ReactMethod
    fun getStatus(promise: Promise) {
        val status = jsonToWritableMap(TimeCycleStore.status(context))
        val shizuku = TimeShizukuController.state()
        status.putBoolean("isShizukuRunning", shizuku.isRunning)
        status.putBoolean("isShizukuPermissionGranted", shizuku.isPermissionGranted)
        promise.resolve(status)
    }

    @ReactMethod
    fun requestShizukuPermission(promise: Promise) {
        val current = TimeShizukuController.state()
        if (!current.isRunning) {
            promise.reject("SHIZUKU_NOT_RUNNING", "Сначала установите и запустите Shizuku через беспроводную отладку.")
            return
        }
        if (current.isPermissionGranted || TimeShizukuController.requestPermission()) promise.resolve(true) else promise.resolve(false)
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
                promise.resolve(jsonToWritableMap(TimeCycleStore.status(context)))
            } else {
                promise.reject("AUTOMATIC_TIME_FAILED", outcome.detail)
            }
        }
    }

    @ReactMethod
    fun applyTime(targetMillis: Double, promise: Promise) {
        if (TimeCycleStore.isRunning(context)) {
            promise.reject("CYCLE_RUNNING", "Нельзя вручную менять время во время выполнения цикла.")
            return
        }
        val shizuku = TimeShizukuController.state()
        if (!shizuku.isPermissionGranted) {
            promise.reject("SHIZUKU_PERMISSION_REQUIRED", "Сначала запустите Shizuku и выдайте доступ приложению.")
            return
        }
        TimeShizukuController.applyTime(context, targetMillis.toLong()) { outcome ->
            if (outcome.isSuccess) {
                TimeCycleStore.setAutomaticTimeEnabled(context, false)
                TimeCycleStore.addEvent(context, outcome.detail)
                promise.resolve(jsonToWritableMap(TimeCycleStore.status(context)))
            } else {
                promise.reject("APPLY_TIME_FAILED", outcome.detail)
            }
        }
    }

    @ReactMethod
    fun getOpenApps(promise: Promise) {
        val shizuku = TimeShizukuController.state()
        if (!shizuku.isPermissionGranted) {
            promise.reject("SHIZUKU_PERMISSION_REQUIRED", "Сначала запустите Shizuku и выдайте доступ приложению.")
            return
        }
        TimeShizukuController.listOpenApps(context) { outcome ->
            if (!outcome.isSuccess) {
                promise.reject("OPEN_APPS_FAILED", outcome.detail)
                return@listOpenApps
            }
            val result = Arguments.createArray()
            outcome.detail.removePrefix("OK:").lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    val packageName = parts[0].trim()
                    if (packageName.isEmpty()) return@forEach
                    val item = Arguments.createMap()
                    item.putString("packageName", packageName)
                    item.putString("label", applicationLabel(packageName))
                    val processArray = Arguments.createArray()
                    val processNames = parts.getOrNull(1).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    if (processNames.isEmpty()) processArray.pushString(packageName) else processNames.forEach { processArray.pushString(it) }
                    item.putArray("processNames", processArray)
                    result.pushMap(item)
                }
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun inspectApp(packageName: String, promise: Promise) {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            promise.reject("INVALID_PACKAGE", "Некорректное имя пакета приложения.")
            return
        }
        val shizuku = TimeShizukuController.state()
        if (!shizuku.isPermissionGranted) {
            promise.reject("SHIZUKU_PERMISSION_REQUIRED", "Сначала запустите Shizuku и выдайте доступ приложению.")
            return
        }
        TimeShizukuController.inspectApp(context, packageName) { outcome ->
            if (!outcome.isSuccess) {
                promise.reject("UI_INSPECTION_FAILED", outcome.detail)
                return@inspectApp
            }
            runCatching { parseUiHierarchy(outcome.detail.removePrefix("OK:").trimStart()) }
                .onSuccess { promise.resolve(it) }
                .onFailure { promise.reject("UI_PARSE_FAILED", it.message ?: "Не удалось разобрать UI hierarchy.", it) }
        }
    }

    @ReactMethod
    fun startCycle(settings: ReadableMap, promise: Promise) {
        val shizuku = TimeShizukuController.state()
        if (!shizuku.isPermissionGranted) {
            promise.reject("SHIZUKU_PERMISSION_REQUIRED", "Сначала запустите Shizuku и нажмите «Разрешить Shizuku» в приложении.")
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
            require(total in 1..99999) { "Количество циклов должно быть от 1 до 99999." }
            require(days in -999..999 && hours in -999..999 && minutes in -999..999) { "Шаг задан вне допустимого диапазона." }
            TimeCycleStore.saveAndStart(context, startAt, days, hours, minutes, pause, total)
            TimeCycleForegroundService.start(context)
        }.onSuccess { promise.resolve(jsonToWritableMap(TimeCycleStore.status(context))) }
            .onFailure { promise.reject("START_FAILED", it.message ?: "Не удалось запустить цикл.", it) }
    }

    @ReactMethod
    fun stopCycle(promise: Promise) {
        TimeShizukuCycleRunner.stop()
        TimeCycleStore.stop(context)
        TimeCycleForegroundService.stop(context)
        promise.resolve(jsonToWritableMap(TimeCycleStore.status(context)))
    }

    @ReactMethod
    fun clearEvents(promise: Promise) {
        TimeCycleStore.clearEvents(context)
        promise.resolve(true)
    }

    @Suppress("DEPRECATION")
    private fun applicationLabel(packageName: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    private fun parseUiHierarchy(xml: String): WritableArray {
        val result = Arguments.createArray()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var sequence = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val item = Arguments.createMap()
                val attributes = Arguments.createMap()
                item.putInt("sequence", sequence++)
                item.putInt("depth", (parser.depth - 2).coerceAtLeast(0))
                for (index in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(index)
                    val value = parser.getAttributeValue(index) ?: ""
                    attributes.putString(name, value)
                    item.putString(name, value)
                }
                val text = parser.getAttributeValue(null, "text").orEmpty()
                val description = parser.getAttributeValue(null, "content-desc").orEmpty()
                val resourceId = parser.getAttributeValue(null, "resource-id").orEmpty()
                val className = parser.getAttributeValue(null, "class").orEmpty()
                val displayName = sequenceOf(text, description, resourceId.substringAfterLast('/'), className.substringAfterLast('.'))
                    .firstOrNull { it.isNotBlank() } ?: "Элемент ${sequence}"
                item.putString("name", displayName)
                item.putMap("attributes", attributes)
                result.pushMap(item)
            }
            event = parser.next()
        }
        return result
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

    companion object {
        private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9._]+$")
    }
}
