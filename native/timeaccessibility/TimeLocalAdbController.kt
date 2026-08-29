// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

data class SystemAccessState(val isReady: Boolean, val detail: String)
data class SystemCommandOutcome(val isSuccess: Boolean, val detail: String)

/**
 * Privileged time access through Android's own Wireless Debugging daemon.
 * Only the small, fixed set of commands needed by Машина времени is exposed.
 */
object TimeLocalAdbController {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastDetail = "Системный доступ не подключён."

    fun state(context: Context): SystemAccessState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SystemAccessState(false, "Встроенный системный доступ требует Android 11 или новее.")
        }
        val connected = runCatching { TimeLocalAdbConnectionManager.getInstance(context).isConnected }.getOrDefault(false)
        return SystemAccessState(connected, if (connected) "Системный доступ активен." else lastDetail)
    }

    fun connect(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val outcome = ensureConnected(context.applicationContext)
            deliver(callback, outcome)
        }
    }

    fun pair(context: Context, pairingCode: String, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val code = pairingCode.trim()
            val outcome = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> SystemCommandOutcome(false, "Беспроводная отладка с кодом сопряжения доступна с Android 11.")
                !code.matches(Regex("^\\d{6}$")) -> SystemCommandOutcome(false, "Введите шестизначный код сопряжения.")
                else -> pairInternal(appContext, code)
            }
            lastDetail = outcome.detail
            deliver(callback, outcome)
        }
    }

    fun applyTime(context: Context, targetMillis: Long, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val access = ensureConnected(context.applicationContext)
            if (!access.isSuccess) {
                deliver(callback, access)
                return@execute
            }

            val disableAutomatic = runCommand(context, "settings put global auto_time 0")
            val setByAlarm = runCommand(context, "cmd alarm set-time $targetMillis")
            val setResult = if (setByAlarm.exitCode == 0) setByAlarm else runCommand(context, "date -s @${targetMillis / 1000L}")
            val automaticValue = runCommand(context, "settings get global auto_time")
            val currentTime = runCommand(context, "date +%s")
            val currentMillis = currentTime.stdout.trim().toLongOrNull()?.times(1000L)
            val verified = disableAutomatic.exitCode == 0 && setResult.exitCode == 0 &&
                automaticValue.stdout.trim() == "0" && currentMillis != null &&
                abs(currentMillis - targetMillis) <= 90_000L

            val detail = if (verified) {
                "OK: автоматическое время выключено, системные часы установлены."
            } else {
                buildString {
                    append("ОШИБКА: auto=").append(compact(automaticValue.stdout))
                    append("; cmd=").append(compact(setByAlarm.describe()))
                    append("; fallback=").append(compact(setResult.describe()))
                    append("; now=").append(currentMillis ?: "?")
                }
            }
            deliver(callback, SystemCommandOutcome(verified, detail))
        }
    }

    fun setAutomaticTime(context: Context, enabled: Boolean, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val access = ensureConnected(context.applicationContext)
            if (!access.isSuccess) {
                deliver(callback, access)
                return@execute
            }
            val expected = if (enabled) "1" else "0"
            val change = runCommand(context, "settings put global auto_time $expected")
            val actual = runCommand(context, "settings get global auto_time")
            val verified = change.exitCode == 0 && actual.stdout.trim() == expected
            val detail = if (verified) {
                "OK: автоматическая синхронизация времени ${if (enabled) "включена" else "выключена"}."
            } else {
                "ОШИБКА: auto=${compact(actual.stdout)}; change=${compact(change.describe())}"
            }
            deliver(callback, SystemCommandOutcome(verified, detail))
        }
    }

    private fun pairInternal(context: Context, code: String): SystemCommandOutcome {
        val hostReference = AtomicReference<String?>(null)
        val portReference = AtomicInteger(-1)
        val resolved = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { hostAddress, port ->
            hostAddress?.hostAddress?.let(hostReference::set)
            portReference.set(port)
            resolved.countDown()
        }
        mdns.start()
        try {
            if (!resolved.await(30, TimeUnit.SECONDS)) {
                return SystemCommandOutcome(false, "Не найдено окно сопряжения. Откройте «Сопряжение устройства с помощью кода» и оставьте его открытым.")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SystemCommandOutcome(false, "Поиск окна сопряжения прерван.")
        } finally {
            mdns.stop()
        }

        val host = hostReference.get() ?: return SystemCommandOutcome(false, "Не удалось определить адрес беспроводной отладки.")
        val port = portReference.get()
        if (port <= 0) return SystemCommandOutcome(false, "Не удалось определить порт сопряжения.")

        return runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            if (manager.isConnected) manager.disconnect()
            if (!manager.pair(host, port, code)) {
                SystemCommandOutcome(false, "Android отклонил код сопряжения. Проверьте код и повторите попытку.")
            } else {
                val connected = runCatching { manager.autoConnect(context, 12_000) }.getOrDefault(false) || manager.isConnected
                if (connected) SystemCommandOutcome(true, "Системный доступ активен.")
                else SystemCommandOutcome(false, "Сопряжение выполнено, но подключение не установлено. Нажмите «Системный доступ» ещё раз.")
            }
        }.getOrElse { SystemCommandOutcome(false, "Сопряжение не выполнено: ${it.message.orEmpty()}") }
    }

    private fun ensureConnected(context: Context): SystemCommandOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val outcome = SystemCommandOutcome(false, "Встроенный системный доступ требует Android 11 или новее.")
            lastDetail = outcome.detail
            return outcome
        }
        return runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            if (manager.isConnected) {
                SystemCommandOutcome(true, "Системный доступ активен.")
            } else {
                val connected = manager.autoConnect(context, 8_000) || manager.isConnected
                if (connected) SystemCommandOutcome(true, "Системный доступ активен.")
                else SystemCommandOutcome(false, "Не удалось подключиться к беспроводной отладке.")
            }
        }.recover { failure ->
            when (failure) {
                is AdbPairingRequiredException -> SystemCommandOutcome(false, "Требуется сопряжение с беспроводной отладкой.")
                else -> SystemCommandOutcome(false, "Системный доступ недоступен: ${failure.message.orEmpty()}")
            }
        }.getOrThrow().also { lastDetail = it.detail }
    }

    private fun runCommand(context: Context, command: String): CommandResult {
        return runCatching {
            val access = ensureConnected(context.applicationContext)
            if (!access.isSuccess) return CommandResult(-1, "", access.detail)
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            val stream = manager.openStream(LocalServices.SHELL)
            val marker = "__TIME_MACHINE_EXIT_${System.nanoTime()}__"
            val request = "$command; code=${'$'}?; echo $marker${'$'}code; exit\n"
            stream.openOutputStream().use { output ->
                output.write(request.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val raw = stream.openInputStream().bufferedReader().use { it.readText() }
            runCatching { stream.close() }
            val markerIndex = raw.lastIndexOf(marker)
            if (markerIndex < 0) {
                CommandResult(-1, raw.trim(), "ADB shell не вернул код завершения")
            } else {
                val exitText = raw.substring(markerIndex + marker.length).trim().lineSequence().firstOrNull().orEmpty()
                val exitCode = exitText.toIntOrNull() ?: -1
                CommandResult(exitCode, raw.substring(0, markerIndex).trim(), "")
            }
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(180)

    private fun deliver(callback: (SystemCommandOutcome) -> Unit, outcome: SystemCommandOutcome) {
        mainHandler.post { callback(outcome) }
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
