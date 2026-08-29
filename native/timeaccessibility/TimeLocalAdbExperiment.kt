// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.muntashirakon.adb.LocalServices
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Isolated diagnostic for switching Android's adbd from Wireless Debugging to
 * legacy TCP mode and reconnecting to the daemon through loopback.
 *
 * The tcpip service intentionally is NOT read to EOF. Restarting adbd tears down
 * the transport and some Android/libadb combinations leave that read blocked
 * indefinitely. Successful opening of the service is treated only as a request;
 * the real success criterion is a subsequent authenticated connection to
 * 127.0.0.1:5555 plus shell verification.
 */
object TimeLocalAdbExperiment {
    private const val LOCAL_HOST = "127.0.0.1"
    private const val LOCAL_PORT = 5555
    private const val LOCAL_PREFS = "time_machine_local_adb"
    private const val LOCAL_ENABLED = "local_tcp_enabled"

    private val executor = Executors.newSingleThreadExecutor()
    private val readerExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun prepare(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val manager = TimeLocalAdbConnectionManager.getInstance(appContext)

            if (!ensureCurrentConnection(appContext, manager)) {
                deliver(callback, SystemCommandOutcome(false, "Этап 1/4 — исходное ADB-подключение: ОШИБКА\nСначала подключите системный доступ через беспроводную отладку."))
                return@execute
            }

            val beforePort = runShell(appContext, manager, "getprop service.adb.tcp.port")
            val tcpRequest = requestTcpMode(manager)
            if (!tcpRequest.isSuccess) {
                deliver(callback, SystemCommandOutcome(false, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: ОШИБКА\n")
                    append(tcpRequest.detail)
                    append("\nДо эксперимента service.adb.tcp.port=")
                    append(compact(beforePort.stdout).ifEmpty { "<пусто>" })
                }))
                return@execute
            }

            // adbd restarts after tcpip:PORT. The old TLS transport is expected to die.
            Thread.sleep(1800)
            runCatching { if (manager.isConnected) manager.disconnect() }
            Thread.sleep(250)

            if (!connectLocal(manager)) {
                setLocalMode(appContext, false)
                deliver(callback, SystemCommandOutcome(false, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: запрос отправлен\n")
                    append("Этап 3/4 — $LOCAL_HOST:$LOCAL_PORT: ОШИБКА\n")
                    append("adbd не принял локальное подключение. Зависания больше нет; это отрицательный результат эксперимента.")
                }))
                return@execute
            }

            setLocalMode(appContext, true)
            val identity = runShell(appContext, manager, "id")
            val afterPort = runShell(appContext, manager, "getprop service.adb.tcp.port")
            val verified = identity.exitCode == 0 && identity.stdout.contains("uid=2000") && afterPort.stdout.trim() == LOCAL_PORT.toString()

            val outcome = if (verified) {
                SystemCommandOutcome(true, buildString {
                    append("Этап 1/4 — исходное ADB-подключение: OK\n")
                    append("Этап 2/4 — tcpip:$LOCAL_PORT: запрос отправлен\n")
                    append("Этап 3/4 — $LOCAL_HOST:$LOCAL_PORT: OK\n")
                    append("Этап 4/4 — shell: OK — ").append(compact(identity.stdout)).append("\n")
                    append("service.adb.tcp.port=").append(compact(afterPort.stdout)).append("\n")
                    append("Можно запускать второй тест с отключением Wi‑Fi.")
                })
            } else {
                setLocalMode(appContext, false)
                SystemCommandOutcome(false, buildString {
                    append("Локальное соединение установлено, но контрольные команды не прошли.\n")
                    append("id=").append(compact(identity.describe())).append("\n")
                    append("service.adb.tcp.port=").append(compact(afterPort.describe()))
                })
            }
            deliver(callback, outcome)
        }
    }

    fun testWithoutWifi(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val manager = TimeLocalAdbConnectionManager.getInstance(appContext)
            runCatching { if (manager.isConnected) manager.disconnect() }

            if (!connectLocal(manager)) {
                setLocalMode(appContext, false)
                deliver(callback, SystemCommandOutcome(false, "Проверка без Wi‑Fi не запущена: $LOCAL_HOST:$LOCAL_PORT недоступен. Сначала выполните первый тест."))
                return@execute
            }

            setLocalMode(appContext, true)
            val before = runShell(appContext, manager, "id")
            if (before.exitCode != 0 || !before.stdout.contains("uid=2000")) {
                deliver(callback, SystemCommandOutcome(false, "Локальный ADB подключён, но shell-проверка не пройдена: ${compact(before.describe())}"))
                return@execute
            }

            val wifiOff = runShell(appContext, manager, "svc wifi disable")
            if (wifiOff.exitCode != 0) {
                deliver(callback, SystemCommandOutcome(false, "Команда отключения Wi‑Fi не выполнена: ${compact(wifiOff.describe())}"))
                return@execute
            }

            Thread.sleep(2000)
            runCatching { if (manager.isConnected) manager.disconnect() }
            Thread.sleep(300)

            if (!connectLocal(manager)) {
                setLocalMode(appContext, false)
                deliver(callback, SystemCommandOutcome(false, buildString {
                    append("Wi‑Fi отключён командой shell: OK\n")
                    append("Повторное подключение к $LOCAL_HOST:$LOCAL_PORT: ОШИБКА\n")
                    append("Включите Wi‑Fi вручную. Локальный ADB без Wi‑Fi на этом устройстве не удержался.")
                }))
                return@execute
            }

            setLocalMode(appContext, true)
            val identity = runShell(appContext, manager, "id")
            val epoch = runShell(appContext, manager, "date +%s")
            val port = runShell(appContext, manager, "getprop service.adb.tcp.port")
            val verified = identity.exitCode == 0 && identity.stdout.contains("uid=2000") && epoch.exitCode == 0 && epoch.stdout.trim().toLongOrNull() != null && port.stdout.trim() == LOCAL_PORT.toString()

            val outcome = if (verified) {
                SystemCommandOutcome(true, buildString {
                    append("Wi‑Fi отключён: OK\n")
                    append("Повторное подключение $LOCAL_HOST:$LOCAL_PORT: OK\n")
                    append("shell после отключения Wi‑Fi: OK — ").append(compact(identity.stdout)).append("\n")
                    append("date +%s: ").append(compact(epoch.stdout)).append("\n")
                    append("РЕЗУЛЬТАТ: локальный ADB работает без Wi‑Fi.")
                })
            } else {
                SystemCommandOutcome(false, buildString {
                    append("Повторное локальное соединение установлено, но контрольные команды не прошли.\n")
                    append("id=").append(compact(identity.describe())).append("\n")
                    append("date=").append(compact(epoch.describe())).append("\n")
                    append("port=").append(compact(port.describe()))
                })
            }
            deliver(callback, outcome)
        }
    }

    fun enableWifi(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val manager = TimeLocalAdbConnectionManager.getInstance(appContext)
            val connected = manager.isConnected || connectLocal(manager) || ensureCurrentConnection(appContext, manager)
            if (!connected) {
                deliver(callback, SystemCommandOutcome(false, "ADB-подключение недоступно. Включите Wi‑Fi вручную."))
                return@execute
            }
            val result = runShell(appContext, manager, "svc wifi enable")
            deliver(callback, if (result.exitCode == 0) SystemCommandOutcome(true, "Команда включения Wi‑Fi выполнена.") else SystemCommandOutcome(false, "Не удалось включить Wi‑Fi: ${compact(result.describe())}"))
        }
    }

    private fun ensureCurrentConnection(context: Context, manager: TimeLocalAdbConnectionManager): Boolean {
        if (manager.isConnected) return true
        if (isLocalMode(context) && connectLocal(manager)) return true
        return runCatching { manager.autoConnect(context, 8_000) }.getOrDefault(false) || manager.isConnected
    }

    private fun requestTcpMode(manager: TimeLocalAdbConnectionManager): SystemCommandOutcome {
        val oldTimeout = manager.timeout
        val oldUnit = manager.timeoutUnit
        manager.setTimeout(4_000, TimeUnit.MILLISECONDS)
        return try {
            val stream = manager.openStream("tcpip:$LOCAL_PORT")
            // Never read this stream to EOF: adbd restarts and can leave the read blocked.
            Thread.sleep(250)
            runCatching { stream.close() }
            SystemCommandOutcome(true, "Запрос tcpip:$LOCAL_PORT отправлен; подтверждаем результат только повторным подключением.")
        } catch (failure: Throwable) {
            SystemCommandOutcome(false, "Не удалось отправить tcpip:$LOCAL_PORT: ${failure.message.orEmpty()}")
        } finally {
            manager.setTimeout(oldTimeout, oldUnit)
        }
    }

    private fun connectLocal(manager: TimeLocalAdbConnectionManager): Boolean {
        val oldTimeout = manager.timeout
        val oldUnit = manager.timeoutUnit
        manager.setTimeout(4_000, TimeUnit.MILLISECONDS)
        return try {
            repeat(3) { attempt ->
                runCatching { if (manager.isConnected) manager.disconnect() }
                val connected = runCatching { manager.connect(LOCAL_HOST, LOCAL_PORT) }.getOrDefault(false) || manager.isConnected
                if (connected) return true
                if (attempt < 2) Thread.sleep(600)
            }
            false
        } finally {
            manager.setTimeout(oldTimeout, oldUnit)
        }
    }

    private fun runShell(context: Context, manager: TimeLocalAdbConnectionManager, command: String): CommandResult {
        if (!manager.isConnected && !ensureCurrentConnection(context, manager)) return CommandResult(-1, "", "ADB не подключён")
        return runCatching {
            val stream = manager.openStream(LocalServices.SHELL)
            val marker = "__TM_DIAG_${System.nanoTime()}__"
            val request = "$command; code=${'$'}?; echo $marker${'$'}code; exit\n"
            stream.openOutputStream().use { output ->
                output.write(request.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val future = readerExecutor.submit<String> { stream.openInputStream().bufferedReader().use { it.readText() } }
            val raw = try {
                future.get(6, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                runCatching { stream.close() }
                future.cancel(true)
                return CommandResult(-1, "", "тайм-аут ADB shell")
            }
            runCatching { stream.close() }

            val markerIndex = raw.lastIndexOf(marker)
            if (markerIndex < 0) return CommandResult(-1, raw.trim(), "нет кода завершения")
            val exitText = raw.substring(markerIndex + marker.length).trim().lineSequence().firstOrNull().orEmpty()
            CommandResult(exitText.toIntOrNull() ?: -1, raw.substring(0, markerIndex).trim(), "")
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun isLocalMode(context: Context): Boolean = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).getBoolean(LOCAL_ENABLED, false)
    private fun setLocalMode(context: Context, enabled: Boolean) { context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(LOCAL_ENABLED, enabled).apply() }
    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(240)
    private fun deliver(callback: (SystemCommandOutcome) -> Unit, outcome: SystemCommandOutcome) { mainHandler.post { callback(outcome) } }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
