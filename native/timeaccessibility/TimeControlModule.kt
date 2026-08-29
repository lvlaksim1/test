package __PACKAGE__.timeaccessibility

import android.content.Intent
import android.provider.Settings
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

    @ReactMethod fun getStatus(promise: Promise) { promise.resolve(statusWithAccess()) }

    @ReactMethod
    fun connectSystemAccess(promise: Promise) {
        TimeLocalAdbController.connect(context) { outcome ->
            if (outcome.isSuccess) {
                TimePairingService.stop(context)
                promise.resolve(statusWithAccess())
            } else {
                runCatching { context.startActivity(Intent(context, TimePairingLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onSuccess { promise.resolve(statusWithAccess()) }
                    .onFailure { promise.reject("SYSTEM_ACCESS_CONNECT_FAILED", outcome.detail, it) }
            }
        }
    }

    @ReactMethod fun pairSystemAccess(pairingCode: String, promise: Promise) { TimeLocalAdbController.pair(context, pairingCode) { outcome -> if (outcome.isSuccess) { TimePairingService.stop(context); promise.resolve(statusWithAccess()) } else promise.reject("SYSTEM_ACCESS_PAIR_FAILED", outcome.detail) } }

    @ReactMethod
    fun openDeveloperSettings(promise: Promise) {
        runCatching { context.startActivity(Intent(context, TimePairingLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { promise.resolve(true) }.onFailure { promise.reject("DEVELOPER_SETTINGS_FAILED", "Не удалось открыть настройки разработчика.", it) }
    }

    @ReactMethod
    fun openDateTimeSettings(promise: Promise) {
        runCatching { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { promise.resolve(true) }.onFailure { promise.reject("DATE_SETTINGS_FAILED", "Не удалось открыть настройки даты и времени.", it) }
    }

    @ReactMethod
    fun setAutomaticTime(enabled: Boolean, promise: Promise) {
        if (TimeCycleStore.isRunning(context)) { promise.reject("CYCLE_RUNNING", "Нельзя переключать синхронизацию во время выполнения цикла."); return }
        TimeLocalAdbController.setAutomaticTime(context, enabled) { outcome ->
            if (outcome.isSuccess) { TimeCycleStore.setAutomaticTimeEnabled(context, enabled); TimeCycleStore.addEvent(context, outcome.detail); promise.resolve(statusWithAccess()) }
            else promise.reject("AUTOMATIC_TIME_FAILED", outcome.detail)
        }
    }

    @ReactMethod
    fun startCycle(settings: ReadableMap, promise: Promise) {
        if (!TimeLocalAdbController.state(context).isReady) { promise.reject("SYSTEM_ACCESS_REQUIRED", "Сначала подключите системный доступ через беспроводную отладку."); return }
        runCatching {
            val startAt = settings.getDouble("startAtMillis").toLong(); val days = settings.getInt("stepDays"); val hours = settings.getInt("stepHours"); val minutes = settings.getInt("stepMinutes"); val pause = settings.getInt("pauseSeconds"); val repeatsPerSeries = settings.getInt("repeatsPerSeries"); val seriesPause = settings.getInt("seriesPauseSeconds"); val totalSeries = settings.getInt("totalSeries"); val total = settings.getInt("totalCycles")
            require(pause in 1..86400); require(seriesPause in 0..86400); require(repeatsPerSeries in 1..99999); require(totalSeries in 1..99999)
            val calculatedTotal = repeatsPerSeries.toLong() * totalSeries.toLong(); require(calculatedTotal in 1L..99999L && total == calculatedTotal.toInt()); require(days in -999..999 && hours in -999..999 && minutes in -999..999)
            TimeCycleStore.saveAndStart(context, startAt, days, hours, minutes, pause, repeatsPerSeries, seriesPause, totalSeries, total); TimeCycleForegroundService.start(context)
        }.onSuccess { promise.resolve(statusWithAccess()) }.onFailure { promise.reject("START_FAILED", it.message ?: "Не удалось запустить цикл.", it) }
    }

    @ReactMethod fun stopCycle(promise: Promise) { TimeCycleRunner.stop(); TimeCycleStore.stop(context); TimeCycleForegroundService.stop(context); promise.resolve(statusWithAccess()) }
    @ReactMethod fun clearEvents(promise: Promise) { TimeCycleStore.clearEvents(context); promise.resolve(true) }

    private fun statusWithAccess(): WritableMap { val status = jsonToWritableMap(TimeCycleStore.status(context)); val access = TimeLocalAdbController.state(context); status.putBoolean("isSystemAccessReady", access.isReady); status.putString("systemAccessDetail", access.detail); return status }
    private fun jsonToWritableMap(value: JSONObject): WritableMap { val result = Arguments.createMap(); val iterator = value.keys(); while (iterator.hasNext()) { val key = iterator.next(); when (val item = value.opt(key)) { null, JSONObject.NULL -> result.putNull(key); is Boolean -> result.putBoolean(key,item); is Int -> result.putInt(key,item); is Long -> result.putDouble(key,item.toDouble()); is Double -> result.putDouble(key,item); is String -> result.putString(key,item); is JSONObject -> result.putMap(key,jsonToWritableMap(item)); is JSONArray -> result.putArray(key,jsonToWritableArray(item)); else -> result.putString(key,item.toString()) } }; return result }
    private fun jsonToWritableArray(value: JSONArray): WritableArray { val result=Arguments.createArray(); for(index in 0 until value.length()){ when(val item=value.opt(index)){ null,JSONObject.NULL->result.pushNull(); is Boolean->result.pushBoolean(item); is Int->result.pushInt(item); is Long->result.pushDouble(item.toDouble()); is Double->result.pushDouble(item); is String->result.pushString(item); is JSONObject->result.pushMap(jsonToWritableMap(item)); is JSONArray->result.pushArray(jsonToWritableArray(item)); else->result.pushString(item.toString()) } }; return result }
}
