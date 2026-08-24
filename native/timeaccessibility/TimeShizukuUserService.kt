package __PACKAGE__.timeaccessibility

import kotlin.math.abs

class TimeShizukuUserService : ITimeShizukuService.Stub() {
    override fun applyTime(targetMillis: Long): String {
        val disableAutomatic = runCommand("settings put global auto_time 0")
        val setByAlarm = runCommand("cmd alarm set-time $targetMillis")
        val setResult = if (setByAlarm.exitCode == 0) setByAlarm else runCommand("date -s @${targetMillis / 1000L}")
        val automaticValue = runCommand("settings get global auto_time")
        val currentTime = runCommand("date +%s")
        val currentMillis = currentTime.stdout.trim().toLongOrNull()?.times(1000L)
        val verified = disableAutomatic.exitCode == 0 && setResult.exitCode == 0 && automaticValue.stdout.trim() == "0" && currentMillis != null && abs(currentMillis - targetMillis) <= 90_000L
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
        val processList = runCommand("ps -A -o NAME")
        val processes = if (processList.exitCode == 0) processList.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() else emptyList()
        val packages = linkedSetOf<String>()
        val packagePattern = Regex("""u\d+\s+([A-Za-z0-9._]+)/""")
        activities.stdout.lineSequence().forEach { line -> packagePattern.find(line)?.groupValues?.getOrNull(1)?.let { packages.add(it) } }
        return "OK:\n" + packages.joinToString("\n") { packageName ->
            val names = processes.filter { it == packageName || it.startsWith("$packageName:") }.distinct()
            packageName + "\t" + names.joinToString(",")
        }
    }

    override fun inspectApp(packageName: String, returnPackage: String): String {
        if (!SAFE_PACKAGE.matches(packageName) || !SAFE_PACKAGE.matches(returnPackage)) return "ОШИБКА: некорректное имя пакета."
        val targetComponent = resolveLauncher(packageName)
        if (targetComponent.isBlank()) return "ОШИБКА: не удалось определить запускаемый Activity выбранного приложения."
        val returnComponent = resolveLauncher(returnPackage)
        val dumpFile = "/data/local/tmp/timecycler_ui_${System.nanoTime()}.xml"
        val launch = runCommand("am start -n '$targetComponent'")
        if (launch.exitCode != 0) return "ОШИБКА: не удалось открыть приложение: ${compact(launch.describe())}"
        Thread.sleep(900L)
        val dump = runCommand("uiautomator dump '$dumpFile'")
        val xml = if (dump.exitCode == 0) runCommand("cat '$dumpFile'") else CommandResult(-1, "", dump.describe())
        runCommand("rm -f '$dumpFile'")
        if (returnComponent.isNotBlank()) {
            runCommand("am start -n '$returnComponent'")
            Thread.sleep(250L)
        }
        if (dump.exitCode != 0 || xml.exitCode != 0 || !xml.stdout.contains("<hierarchy")) {
            return "ОШИБКА: не удалось получить UI hierarchy: ${compact(dump.describe())}; ${compact(xml.describe())}"
        }
        return "OK:\n" + xml.stdout
    }

    override fun destroy() {
        // Недаэмонская служба Shizuku завершается вместе с клиентом.
    }

    private fun resolveLauncher(packageName: String): String {
        val result = runCommand("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p '$packageName'")
        if (result.exitCode != 0) return ""
        return result.stdout.lineSequence().map { it.trim() }.lastOrNull { it.contains('/') } ?: ""
    }

    private fun runCommand(command: String): CommandResult {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            CommandResult(process.waitFor(), stdout, stderr)
        }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(220)

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"
    }

    companion object {
        private val SAFE_PACKAGE = Regex("^[A-Za-z0-9._]+$")
    }
}
