package __PACKAGE__.timeaccessibility

import java.util.concurrent.TimeUnit
import kotlin.math.abs

class TimeShizukuUserService : ITimeShizukuService.Stub() {
    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 8L
        private const val VERIFY_TOLERANCE_MILLIS = 10_000L
    }

    @Volatile private var currentProcess: Process? = null
    @Volatile private var cancelRequested = false

    override fun applyTime(targetMillis: Long): String {
        cancelRequested = false
        val disableAutomatic = runCommand("settings put global auto_time 0")
        if (cancelRequested) return "ОШИБКА: операция отменена пользователем."

        val setByAlarm = runCommand("cmd alarm set-time $targetMillis")
        if (cancelRequested) return "ОШИБКА: операция отменена пользователем."
        val fallback = if (setByAlarm.exitCode == 0) null else runCommand("date -s @${targetMillis / 1000L}")
        if (cancelRequested) return "ОШИБКА: операция отменена пользователем."
        val setResult = fallback ?: setByAlarm

        val automaticValue = runCommand("settings get global auto_time")
        val currentTime = runCommand("date +%s")
        val currentMillis = currentTime.stdout.trim().toLongOrNull()?.times(1000L)
        val verified = disableAutomatic.exitCode == 0 && setResult.exitCode == 0 &&
            automaticValue.stdout.trim() == "0" && currentMillis != null &&
            abs(currentMillis - targetMillis) <= VERIFY_TOLERANCE_MILLIS

        return if (verified) {
            "OK: автоматическое время выключено, системные часы установлены."
        } else {
            buildString {
                append("ОШИБКА: auto=").append(compact(automaticValue.stdout))
                append("; cmd=").append(compact(setByAlarm.describe()))
                if (fallback != null) append("; fallback=").append(compact(fallback.describe()))
                append("; now=").append(currentMillis ?: "?")
            }
        }
    }

    override fun setAutomaticTime(enabled: Boolean): String {
        cancelRequested = false
        val expected = if (enabled) "1" else "0"
        val change = runCommand("settings put global auto_time $expected")
        if (cancelRequested) return "ОШИБКА: операция отменена пользователем."
        val actual = runCommand("settings get global auto_time")
        val verified = change.exitCode == 0 && actual.stdout.trim() == expected
        return if (verified) {
            "OK: автоматическая синхронизация времени ${if (enabled) "включена" else "выключена"}."
        } else {
            "ОШИБКА: auto=${compact(actual.stdout)}; change=${compact(change.describe())}"
        }
    }

    override fun getAutomaticTime(): String {
        cancelRequested = false
        val actual = runCommand("settings get global auto_time")
        val value = actual.stdout.trim()
        return if (actual.exitCode == 0 && (value == "0" || value == "1")) {
            "OK:$value"
        } else {
            "ОШИБКА: auto=${compact(actual.describe())}"
        }
    }

    override fun cancelCurrentCommand() {
        cancelRequested = true
        runCatching { currentProcess?.destroyForcibly() }
    }

    override fun destroy() {
        cancelCurrentCommand()
    }

    private fun runCommand(command: String): CommandResult {
        if (cancelRequested) return CommandResult(-2, "", "операция отменена")
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command).start()
            currentProcess = process
            try {
                val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                    return@runCatching CommandResult(-3, "", "тайм-аут команды ${COMMAND_TIMEOUT_SECONDS} сек.")
                }
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                CommandResult(process.exitValue(), stdout, stderr)
            } finally {
                if (currentProcess === process) currentProcess = null
            }
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(180)

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
