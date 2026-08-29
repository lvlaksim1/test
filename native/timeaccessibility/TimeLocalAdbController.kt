// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SystemAccessState(val isReady: Boolean, val detail: String)
data class SystemCommandOutcome(val isSuccess: Boolean, val detail: String)

/**
 * Wireless ADB is only the bootstrap transport. It launches a Shizuku-style
 * detached uid=2000 process through a native fork/setsid starter. The normal
 * application talks to that process through Binder, and the server re-delivers
 * its Binder whenever the application process is created again.
 */
object TimeLocalAdbController {
    private val executor = Executors.newSingleThreadExecutor()
    private val readerExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastDetail = "Системный доступ не подключён."

    fun state(context: Context): SystemAccessState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SystemAccessState(false, "Встроенный системный доступ требует Android 11 или новее.")
        }

        val ping = TimePrivilegedBridge.ping()
        return if (ping.success && ping.detail.contains("uid=2000")) {
            lastDetail = "Системный сервис активен до перезагрузки телефона."
            SystemAccessState(true, lastDetail)
        } else {
            SystemAccessState(false, lastDetail)
        }
    }

    fun connect(context: Context, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute { deliver(callback, ensurePrivilegedServer(context.applicationContext)) }
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
            val appContext = context.applicationContext
            val access = ensurePrivilegedServer(appContext)
            if (!access.isSuccess) {
                deliver(callback, access)
                return@execute
            }

            val reply = TimePrivilegedBridge.setTime(targetMillis)
            val outcome = if (reply.success) {
                SystemCommandOutcome(true, "OK: автоматическое время выключено, системные часы установлены.")
            } else {
                SystemCommandOutcome(false, "Системный сервис не выполнил изменение времени: ${compact(reply.detail)}")
            }
            lastDetail = outcome.detail
            deliver(callback, outcome)
        }
    }

    fun setAutomaticTime(context: Context, enabled: Boolean, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val access = ensurePrivilegedServer(appContext)
            if (!access.isSuccess) {
                deliver(callback, access)
                return@execute
            }

            val reply = TimePrivilegedBridge.setAutomaticTime(enabled)
            val outcome = if (reply.success) {
                SystemCommandOutcome(true, "OK: автоматическая синхронизация времени ${if (enabled) "включена" else "выключена"}.")
            } else {
                SystemCommandOutcome(false, "Системный сервис не изменил синхронизацию времени: ${compact(reply.detail)}")
            }
            lastDetail = outcome.detail
            deliver(callback, outcome)
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
            TimePrivilegedBridge.clear()
            if (manager.isConnected) manager.disconnect()
            if (!manager.pair(host, port, code)) {
                SystemCommandOutcome(false, "Android отклонил код сопряжения. Проверьте код и повторите попытку.")
            } else {
                val connected = runCatching { manager.autoConnect(context, 12_000) }.getOrDefault(false) || manager.isConnected
                if (!connected) {
                    SystemCommandOutcome(false, "Сопряжение выполнено, но ADB-подключение не установлено.")
                } else {
                    startPrivilegedServer(context, manager)
                }
            }
        }.getOrElse { SystemCommandOutcome(false, "Сопряжение не выполнено: ${it.message.orEmpty()}") }
    }

    private fun ensurePrivilegedServer(context: Context): SystemCommandOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val outcome = SystemCommandOutcome(false, "Встроенный системный доступ требует Android 11 или новее.")
            lastDetail = outcome.detail
            return outcome
        }

        // On a fresh app process the existing server needs a moment to send its
        // Binder through TimeBinderProvider. Do not touch ADB during this wait.
        val existing = awaitBinder(3_500)
        if (existing.isSuccess) {
            lastDetail = existing.detail
            return existing
        }

        val outcome = runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            if (!manager.isConnected) {
                val connected = manager.autoConnect(context, 8_000) || manager.isConnected
                if (!connected) {
                    return@runCatching SystemCommandOutcome(false, "Системный сервер не найден. Для его запуска после перезагрузки телефона требуется беспроводная отладка.")
                }
            }
            startPrivilegedServer(context, manager)
        }.recover { failure ->
            when (failure) {
                is AdbPairingRequiredException -> SystemCommandOutcome(false, "Требуется однократное сопряжение с беспроводной отладкой.")
                else -> SystemCommandOutcome(false, "Системный сервис недоступен: ${failure.message.orEmpty()}")
            }
        }.getOrThrow()
        lastDetail = outcome.detail
        return outcome
    }

    private fun startPrivilegedServer(context: Context, manager: TimeLocalAdbConnectionManager): SystemCommandOutcome {
        val packageName = context.packageName
        val authority = "$packageName.timebridge"
        val className = "$packageName.timeaccessibility.TimePrivilegedServer"
        val appUid = context.applicationInfo.uid
        val apkPath = context.applicationInfo.sourceDir
        val starterPath = "${context.applicationInfo.nativeLibraryDir}/libtime_machine_starter.so"

        TimePrivilegedBridge.clear()

        val command = buildString {
            append("OLD=${'$'}(pidof time_machine_server 2>/dev/null); ")
            append("if [ -n \"${'$'}OLD\" ]; then kill ${'$'}OLD >/dev/null 2>&1; sleep 1; fi; ")
            append("STARTER=").append(shellQuote(starterPath)).append("; ")
            append("if [ ! -x \"${'$'}STARTER\" ]; then echo STARTER_NOT_EXECUTABLE; ls -l \"${'$'}STARTER\" 2>&1; exit 22; fi; ")
            append("\"${'$'}STARTER\" ")
            append("--apk=").append(shellQuote(apkPath)).append(' ')
            append("--class=").append(shellQuote(className)).append(' ')
            append("--name=time_machine_server ")
            append("--package=").append(shellQuote(packageName)).append(' ')
            append("--authority=").append(shellQuote(authority)).append(' ')
            append("--app-uid=").append(appUid)
        }

        val launch = runAdbShell(manager, command)
        if (launch.exitCode != 0 || !launch.stdout.contains("time_machine_starter exit with 0")) {
            return SystemCommandOutcome(false, "Не удалось запустить системный сервер: ${compact(launch.describe())}")
        }

        val ready = awaitBinder(8_000)
        if (ready.isSuccess) {
            runCatching { if (manager.isConnected) manager.disconnect() }
            return SystemCommandOutcome(true, "Системный сервис запущен. До перезагрузки телефона ADB больше не требуется.")
        }

        return SystemCommandOutcome(false, "Native starter запустился, но Binder от системного сервера не получен. ${compact(launch.stdout)}")
    }

    private fun awaitBinder(timeoutMillis: Long): SystemCommandOutcome {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val ping = TimePrivilegedBridge.ping()
            if (ping.success && ping.detail.contains("uid=2000")) {
                return SystemCommandOutcome(true, "Системный сервис активен до перезагрузки телефона.")
            }
            if (timeoutMillis <= 0) break
            Thread.sleep(150)
        } while (SystemClock.elapsedRealtime() < deadline)
        return SystemCommandOutcome(false, "Системный Binder не получен.")
    }

    private fun runAdbShell(manager: TimeLocalAdbConnectionManager, command: String): CommandResult {
        return runCatching {
            val marker = "__TM_BOOT_${System.nanoTime()}__"
            val request = "$command; code=${'$'}?; echo $marker${'$'}code"
            val stream = manager.openStream("shell:$request")
            val future = readerExecutor.submit<String> { stream.openInputStream().bufferedReader().use { it.readText() } }
            val raw = try {
                future.get(10, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                runCatching { stream.close() }
                future.cancel(true)
                return CommandResult(-1, "", "тайм-аут запуска native starter")
            }
            runCatching { stream.close() }
            val markerIndex = raw.lastIndexOf(marker)
            if (markerIndex < 0) return CommandResult(-1, raw.trim(), "ADB shell не вернул код завершения")
            val exitText = raw.substring(markerIndex + marker.length).trim().lineSequence().firstOrNull().orEmpty()
            CommandResult(exitText.toIntOrNull() ?: -1, raw.substring(0, markerIndex).trim(), "")
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(700)
    private fun deliver(callback: (SystemCommandOutcome) -> Unit, outcome: SystemCommandOutcome) { mainHandler.post { callback(outcome) } }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
