package __PACKAGE__.timeaccessibility

import kotlin.math.abs

class TimeShizukuUserService : ITimeShizukuService.Stub() {
    override fun applyTime(targetMillis: Long): String {
        val disableAutomatic = runCommand("settings put global auto_time 0")
        val setByAlarm = runCommand("cmd alarm set-time $targetMillis")
        val setResult = if (setByAlarm.exitCode == 0) {
            setByAlarm
        } else {
            runCommand("date -s @${targetMillis / 1000L}")
        }
        val automaticValue = runCommand("settings get global auto_time")
        val currentTime = runCommand("date +%s")
        val currentMillis = currentTime.stdout.trim().toLongOrNull()?.times(1000L)
        val verified = disableAutomatic.exitCode == 0 && setResult.exitCode == 0 &&
            automaticValue.stdout.trim() == "0" && currentMillis != null &&
            abs(currentMillis - targetMillis) <= 90_000L

        return if (verified) {
            "OK: автоматическое время выключено, системные часы установлены."
        } else {
            buildString {
                append("ОШИБКА: auto=").append(compact(automaticValue.stdout))
                append("; cmd=").append(compact(setByAlarm.describe()))
                append("; fallback=").append(compact(setResult.describe()))
                append("; now=").append(currentMillis ?: "?")
            }
        }
    }

    override fun setAutomaticTime(enabled: Boolean): String {
        val expected = if (enabled) "1" else "0"
        val change = runCommand("settings put global auto_time $expected")
        val actual = runCommand("settings get global auto_time")
        val verified = change.exitCode == 0 && actual.stdout.trim() == expected
        return if (verified) {
            "OK: автоматическая синхронизация времени ${if (enabled) "включена" else "выключена"}."
        } else {
            "ОШИБКА: auto=${compact(actual.stdout)}; change=${compact(change.describe())}"
        }
    }

    override fun listOpenApps(): String {
        val activities = runCommand("dumpsys activity activities")
        if (activities.exitCode != 0) return "ОШИБКА: ${compact(activities.describe())}"

        val packages = linkedSetOf<String>()
        val packagePattern = Regex("""u\d+\s+([A-Za-z0-9._]+)/""")
        activities.stdout.lineSequence().forEach { line ->
            packagePattern.find(line)?.groupValues?.getOrNull(1)?.let { packages.add(it) }
        }
        return "OK:\n" + packages.joinToString("\n")
    }

    override fun destroy() {
        // Недаэмонская служба Shizuku завершается вместе с клиентом.
    }

    private fun runCommand(command: String): CommandResult {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            CommandResult(process.waitFor(), stdout, stderr)
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(180)

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }
}
