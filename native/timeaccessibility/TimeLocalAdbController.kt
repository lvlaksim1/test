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
 * Privileged time access through Android's own ADB daemon.
 * Wireless Debugging is used for initial pairing. Release 29 can experimentally
 * restart adbd in legacy TCP mode and reconnect to 127.0.0.1:5555 so that the
 * existing shell channel can survive after Wi-Fi is switched off.
 */
object TimeLocalAdbController {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastDetail = "Системный доступ не подключён."

    private const val LOCAL_HOST = "127.0.0.1"
    private const val LOCAL_PORT = 5555
    private const val LOCAL_PREFS = "time_machine_local_adb"
    private const val LOCAL_ENABLED = "local_tcp_enabled"

    fun state(context: Context): SystemAccessState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SystemAccessState(false, "Встроенный системный доступ требует Android 11 или новее.")
        }
        val connected = runCatching { TimeLocalAdbConnectionManager.getInstance(context).isConnected }.getOrDefault(false)
        val detail = if (connected) {
            if (isLocalTcpEnabled(context)) "Системный доступ активен через 127.0.0.1:$LOCAL_PORT." else "Системный доступ активен."
        } else lastDetail
        return SystemAccessState(connected, detail)
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

    fun prepareLocalAdb(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val access = ensureConnected(appContext)
            if (!access.isSuccess) {
                val outcome = SystemCommandOutcome(false, "Этап 1/4 — исходное ADB-подключение: ОШИБКА\n${access.detail}")
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            val beforePort = runCommand(appContext, "getprop service.adb.tcp.port")
            val serviceResult = requestTcpMode(appContext, LOCAL_PORT)
            if (!serviceResult.isSuccess) {
                val outcome = SystemCommandOutcome(false, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: ОШИБКА\n")
                    append(serviceResult.detail)
                    append("\nДо эксперимента service.adb.tcp.port=").append(compact(beforePort.stdout).ifEmpty { "<пусто>" })
                })
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            val manager = TimeLocalAdbConnectionManager.getInstance(appContext)
            Thread.sleep(1600)
            runCatching { if (manager.isConnected) manager.disconnect() }
            Thread.sleep(250)

            if (!connectLocal(manager)) {
                setLocalTcpEnabled(appContext, false)
                val outcome = SystemCommandOutcome(false, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: OK — ").append(serviceResult.detail).append("\n")
                    append("Этап 3/4 — $LOCAL_HOST:$LOCAL_PORT: ОШИБКА\n")
                    append("adbd был переведён в TCP-режим, но локальное подключение не установлено.")
                })
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            setLocalTcpEnabled(appContext, true)
            val identity = runCommand(appContext, "id")
            val afterPort = runCommand(appContext, "getprop service.adb.tcp.port")
            val verified = identity.exitCode == 0 && identity.stdout.contains("uid=2000") && afterPort.stdout.trim() == LOCAL_PORT.toString()
            val outcome = if (verified) {
                SystemCommandOutcome(true, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: OK — ").append(serviceResult.detail).append("\n")
                    append("Этап 3/4 — $LOCAL_HOST:$LOCAL_PORT: OK\n")
                    append("Этап 4/4 — shell: OK — ").append(compact(identity.stdout)).append("\n")
                    append("service.adb.tcp.port=").append(compact(afterPort.stdout))
                })
            } else {
                setLocalTcpEnabled(appContext, false)
                SystemCommandOutcome(false, buildString {
                    append("Локальное соединение установлено, но проверка shell не пройдена.\n")
                    append("id=").append(compact(identity.describe())).append("\n")
                    append("service.adb.tcp.port=").append(compact(afterPort.describe()))
                })
            }
            lastDetail = if (outcome.isSuccess) "Системный доступ активен через $LOCAL_HOST:$LOCAL_PORT." else outcome.detail
            deliver(callback, outcome)
        }
    }

    fun testLocalAdbWithoutWifi(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val manager = TimeLocalAdbConnectionManager.getInstance(appContext)
            runCatching { if (manager.isConnected) manager.disconnect() }

            if (!connectLocal(manager)) {
                setLocalTcpEnabled(appContext, false)
                val outcome = SystemCommandOutcome(false, "Проверка без Wi-Fi не запущена: $LOCAL_HOST:$LOCAL_PORT недоступен. Сначала выполните «Подготовить локальный ADB».")
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            setLocalTcpEnabled(appContext, true)
            val before = runCommand(appContext, "id")
            if (before.exitCode != 0 || !before.stdout.contains("uid=2000")) {
                val outcome = SystemCommandOutcome(false, "Локальный ADB подключён, но shell-проверка не пройдена: ${compact(before.describe())}")
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            val wifiOff = runCommand(appContext, "svc wifi disable")
            if (wifiOff.exitCode != 0) {
                val outcome = SystemCommandOutcome(false, "Команда отключения Wi-Fi не выполнена: ${compact(wifiOff.describe())}")
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            Thread.sleep(1800)
            runCatching { if (manager.isConnected) manager.disconnect() }
            Thread.sleep(250)

            if (!connectLocal(manager)) {
                setLocalTcpEnabled(appContext, false)
                val outcome = SystemCommandOutcome(false, buildString {
                    append("Wi-Fi отключён командой shell: OK\n")
                    append("Повторное подключение к $LOCAL_HOST:$LOCAL_PORT после отключения Wi-Fi: ОШИБКА\n")
                    append("Включите Wi-Fi вручную для восстановления беспроводной отладки.")
                })
                lastDetail = outcome.detail
                deliver(callback, outcome)
                return@execute
            }

            setLocalTcpEnabled(appContext, true)
            val identity = runCommand(appContext, "id")
            val epoch = runCommand(appContext, "date +%s")
            val port = runCommand(appContext, "getprop service.adb.tcp.port")
            val verified = identity.exitCode == 0 && identity.stdout.contains("uid=2000") && epoch.exitCode == 0 && epoch.stdout.trim().toLongOrNull() != null && port.stdout.trim() == LOCAL_PORT.toString()
            val outcome = if (verified) {
                SystemCommandOutcome(true, buildString {
                    append("Wi-Fi отключён: OK\n")
                    append("Повторное подключение $LOCAL_HOST:$LOCAL_PORT: OK\n")
                    append("shell после отключения Wi-Fi: OK — ").append(compact(identity.stdout)).append("\n")
                    append("date +%s: ").append(compact(epoch.stdout)).append("\n")
                    append("РЕЗУЛЬТАТ: локальный ADB работает без Wi-Fi.")
                })
            } else {
                SystemCommandOutcome(false, buildString {
                    append("Повторное локальное соединение установлено, но контрольные команды не прошли.\n")
                    append("id=").append(compact(identity.describe())).append("\n")
                    append("date=").append(compact(epoch.describe())).append("\n")
                    append("port=").append(compact(port.describe()))
                })
            }
            lastDetail = if (outcome.isSuccess) "Системный доступ активен через $LOCAL_HOST:$LOCAL_PORT без Wi-Fi." else outcome.detail
            deliver(callback, outcome)
        }
    }

    fun enableWifiViaLocalAdb(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val access = ensureConnected(appContext)
            if (!access.isSuccess) {
                deliver(callback, access)
                return@execute
            }
            val result = runCommand(appContext, "svc wifi enable")
            val outcome = if (result.exitCode == 0) SystemCommandOutcome(true, "Команда включения Wi-Fi выполнена.") else SystemCommandOutcome(false, "Не удалось включить Wi-Fi: ${compact(result.describe())}")
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
            setLocalTcpEnabled(context, false)
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
                SystemCommandOutcome(true, if (isLocalTcpEnabled(context)) "Системный доступ активен через $LOCAL_HOST:$LOCAL_PORT." else "Системный доступ активен.")
            } else {
                if (isLocalTcpEnabled(context)) {
                    if (connectLocal(manager)) {
                        return@runCatching SystemCommandOutcome(true, "Системный доступ активен через $LOCAL_HOST:$LOCAL_PORT.")
                    }
                    setLocalTcpEnabled(context, false)
                }
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

    private fun connectLocal(manager: TimeLocalAdbConnectionManager): Boolean {
        val oldTimeout = manager.timeout
        val oldUnit = manager.timeoutUnit
        manager.setTimeout(4500, TimeUnit.MILLISECONDS)
        return try {
            repeat(3) { attempt ->
                runCatching { if (manager.isConnected) manager.disconnect() }
                val connected = runCatching { manager.connect(LOCAL_HOST, LOCAL_PORT) }.getOrDefault(false) || manager.isConnected
                if (connected) return true
                if (attempt < 2) Thread.sleep(650)
            }
            false
        } finally {
            manager.setTimeout(oldTimeout, oldUnit)
        }
    }

    private fun requestTcpMode(context: Context, port: Int): SystemCommandOutcome {
        return runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            val stream = manager.openStream("tcpip:$port")
            val response = stream.openInputStream().bufferedReader().use { it.readText() }.trim()
            runCatching { stream.close() }
            val detail = response.ifEmpty { "adbd принял tcpip:$port" }
            SystemCommandOutcome(true, compact(detail))
        }.getOrElse { SystemCommandOutcome(false, "Сервис tcpip:$port недоступен: ${it.message.orEmpty()}") }
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

    private fun isLocalTcpEnabled(context: Context): Boolean = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).getBoolean(LOCAL_ENABLED, false)
    private fun setLocalTcpEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(LOCAL_ENABLED, enabled).apply() }
    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(240)

    private fun deliver(callback: (SystemCommandOutcome) -> Unit, outcome: SystemCommandOutcome) {
        mainHandler.post { callback(outcome) }
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
