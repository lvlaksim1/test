// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SystemAccessState(val isReady: Boolean, val detail: String)
data class SystemCommandOutcome(val isSuccess: Boolean, val detail: String)

object TimeLocalAdbController {
    private val executor = Executors.newSingleThreadExecutor()
    private val readerExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastDetail = "Системный доступ не подключён."

    fun state(context: Context): SystemAccessState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return SystemAccessState(false, "Встроенный системный доступ требует Android 11 или новее.")
        if (TimeShellBridge.isLikelyActive(context)) {
            val ping = TimeShellBridge.ping(context)
            if (ping.success && ping.detail.contains("uid=2000")) return SystemAccessState(true, "Системный сервис активен до перезагрузки телефона.")
            TimeShellBridge.markInactive(context)
        }
        return SystemAccessState(false, lastDetail)
    }

    fun connect(context: Context, callback: (SystemCommandOutcome) -> Unit) { executor.execute { deliver(callback, ensureShellServer(context.applicationContext)) } }

    fun pair(context: Context, pairingCode: String, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext
            val code = pairingCode.trim()
            val outcome = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> SystemCommandOutcome(false, "Беспроводная отладка с кодом сопряжения доступна с Android 11.")
                !code.matches(Regex("^\\d{6}$")) -> SystemCommandOutcome(false, "Введите шестизначный код сопряжения.")
                else -> pairInternal(appContext, code)
            }
            lastDetail = outcome.detail; deliver(callback, outcome)
        }
    }

    fun applyTime(context: Context, targetMillis: Long, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext; val access = ensureShellServer(appContext)
            if (!access.isSuccess) { deliver(callback, access); return@execute }
            val reply = TimeShellBridge.call(appContext, "SET_TIME", targetMillis.toString())
            val outcome = if (reply.success) SystemCommandOutcome(true, "OK: автоматическое время выключено, системные часы установлены.") else { TimeShellBridge.markInactive(appContext); SystemCommandOutcome(false, "Системный сервис не выполнил изменение времени: ${compact(reply.detail)}") }
            lastDetail = outcome.detail; deliver(callback, outcome)
        }
    }

    fun setAutomaticTime(context: Context, enabled: Boolean, callback: (SystemCommandOutcome) -> Unit) {
        executor.execute {
            val appContext = context.applicationContext; val access = ensureShellServer(appContext)
            if (!access.isSuccess) { deliver(callback, access); return@execute }
            val reply = TimeShellBridge.call(appContext, "AUTO_TIME", if (enabled) "1" else "0")
            val outcome = if (reply.success) SystemCommandOutcome(true, "OK: автоматическая синхронизация времени ${if (enabled) "включена" else "выключена"}.") else { TimeShellBridge.markInactive(appContext); SystemCommandOutcome(false, "Системный сервис не изменил синхронизацию времени: ${compact(reply.detail)}") }
            lastDetail = outcome.detail; deliver(callback, outcome)
        }
    }

    private fun pairInternal(context: Context, code: String): SystemCommandOutcome {
        val hostReference = AtomicReference<String?>(null); val portReference = AtomicInteger(-1); val resolved = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { hostAddress, port -> hostAddress?.hostAddress?.let(hostReference::set); portReference.set(port); resolved.countDown() }
        mdns.start()
        try { if (!resolved.await(30, TimeUnit.SECONDS)) return SystemCommandOutcome(false, "Не найдено окно сопряжения. Откройте «Сопряжение устройства с помощью кода» и оставьте его открытым.") }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); return SystemCommandOutcome(false, "Поиск окна сопряжения прерван.") } finally { mdns.stop() }
        val host = hostReference.get() ?: return SystemCommandOutcome(false, "Не удалось определить адрес беспроводной отладки."); val port = portReference.get()
        if (port <= 0) return SystemCommandOutcome(false, "Не удалось определить порт сопряжения.")
        return runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context); TimeShellBridge.markInactive(context); if (manager.isConnected) manager.disconnect()
            if (!manager.pair(host, port, code)) SystemCommandOutcome(false, "Android отклонил код сопряжения. Проверьте код и повторите попытку.")
            else { val connected = runCatching { manager.autoConnect(context, 12_000) }.getOrDefault(false) || manager.isConnected; if (!connected) SystemCommandOutcome(false, "Сопряжение выполнено, но ADB-подключение не установлено.") else startShellServer(context, manager) }
        }.getOrElse { SystemCommandOutcome(false, "Сопряжение не выполнено: ${it.message.orEmpty()}") }
    }

    private fun ensureShellServer(context: Context): SystemCommandOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return SystemCommandOutcome(false, "Встроенный системный доступ требует Android 11 или новее.")
        val existing = TimeShellBridge.ping(context)
        if (existing.success && existing.detail.contains("uid=2000")) { TimeShellBridge.markActive(context); return SystemCommandOutcome(true, "Системный сервис активен до перезагрузки телефона.") }
        TimeShellBridge.markInactive(context)
        val outcome = runCatching {
            val manager = TimeLocalAdbConnectionManager.getInstance(context)
            if (!manager.isConnected) { val connected = manager.autoConnect(context, 8_000) || manager.isConnected; if (!connected) return@runCatching SystemCommandOutcome(false, "Не удалось подключиться к беспроводной отладке для запуска системного сервиса.") }
            startShellServer(context, manager)
        }.recover { failure -> if (failure is AdbPairingRequiredException) SystemCommandOutcome(false, "Требуется однократное сопряжение с беспроводной отладкой.") else SystemCommandOutcome(false, "Системный сервис недоступен: ${failure.message.orEmpty()}") }.getOrThrow()
        lastDetail = outcome.detail; return outcome
    }

    private fun startShellServer(context: Context, manager: TimeLocalAdbConnectionManager): SystemCommandOutcome {
        val packageName = context.packageName; val className = "$packageName.timeaccessibility.TimeShellServerEntry"; val token = TimeShellBridge.token(context); val port = TimeShellBridge.PORT; val logPath = "/data/local/tmp/time_machine_shell.log"
        val command = "APK=${'$'}(pm path $packageName | head -n 1 | cut -d: -f2); if [ -z \"${'$'}APK\" ]; then echo APK_NOT_FOUND; exit 21; fi; OLD=${'$'}(pidof time_machine_shell); if [ -n \"${'$'}OLD\" ]; then kill ${'$'}OLD >/dev/null 2>&1; sleep 1; fi; rm -f $logPath; CLASSPATH=\"${'$'}APK\" /system/bin/toybox setsid -d /system/bin/app_process -Djava.class.path=\"${'$'}APK\" /system/bin --nice-name=time_machine_shell $className $token $port </dev/null >$logPath 2>&1 & sleep 1; PID=${'$'}(pidof time_machine_shell); echo STARTED pid=${'$'}PID; if [ -f $logPath ]; then /system/bin/toybox tail -c 1200 $logPath; fi"
        val launch = runAdbShell(manager, command)
        if (launch.exitCode != 0 || !launch.stdout.contains("STARTED")) return SystemCommandOutcome(false, "Не удалось запустить системный shell-сервис: ${compact(launch.describe())}")
        repeat(25) { Thread.sleep(200); val ping = TimeShellBridge.ping(context); if (ping.success && ping.detail.contains("uid=2000")) { TimeShellBridge.markActive(context); runCatching { if (manager.isConnected) manager.disconnect() }; return SystemCommandOutcome(true, "Системный сервис запущен. Повторное сопряжение до перезагрузки телефона не требуется.") } }
        TimeShellBridge.markInactive(context); return SystemCommandOutcome(false, "Системный процесс не открыл локальный канал. Диагностика запуска: ${compact(launch.stdout)}")
    }

    private fun runAdbShell(manager: TimeLocalAdbConnectionManager, command: String): CommandResult = runCatching {
        val marker = "__TM_BOOT_${System.nanoTime()}__"; val request = "$command; code=${'$'}?; echo $marker${'$'}code"; val stream = manager.openStream("shell:$request"); val future = readerExecutor.submit<String> { stream.openInputStream().bufferedReader().use { it.readText() } }
        val raw = try { future.get(9, TimeUnit.SECONDS) } catch (_: Throwable) { runCatching { stream.close() }; future.cancel(true); return CommandResult(-1, "", "тайм-аут запуска shell-сервиса") }
        runCatching { stream.close() }; val markerIndex = raw.lastIndexOf(marker); if (markerIndex < 0) return CommandResult(-1, raw.trim(), "ADB shell не вернул код завершения"); val exitText = raw.substring(markerIndex + marker.length).trim().lineSequence().firstOrNull().orEmpty(); CommandResult(exitText.toIntOrNull() ?: -1, raw.substring(0, markerIndex).trim(), "")
    }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(500)
    private fun deliver(callback: (SystemCommandOutcome) -> Unit, outcome: SystemCommandOutcome) { mainHandler.post { callback(outcome) } }
    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) { fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}" }
}
