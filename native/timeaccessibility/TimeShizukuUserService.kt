package __PACKAGE__.timeaccessibility

import kotlin.math.abs

class TimeShizukuUserService : ITimeShizukuService.Stub() {
    override fun applyTime(targetMillis: Long): String { val a=runCommand("settings put global auto_time 0"); val b=runCommand("cmd alarm set-time $targetMillis"); val c=if(b.exitCode==0)b else runCommand("date -s @${targetMillis/1000L}"); val d=runCommand("settings get global auto_time"); val e=runCommand("date +%s"); val now=e.stdout.trim().toLongOrNull()?.times(1000L); return if(a.exitCode==0&&c.exitCode==0&&d.stdout.trim()=="0"&&now!=null&&abs(now-targetMillis)<=90000L) "OK: автоматическое время выключено, системные часы установлены." else "ОШИБКА: auto=${compact(d.stdout)}; cmd=${compact(b.describe())}; fallback=${compact(c.describe())}; now=${now?:"?"}" }
    override fun setAutomaticTime(enabled:Boolean):String { val expected=if(enabled)"1" else "0"; val change=runCommand("settings put global auto_time $expected"); val actual=runCommand("settings get global auto_time"); return if(change.exitCode==0&&actual.stdout.trim()==expected) "OK: автоматическая синхронизация времени ${if(enabled)"включена" else "выключена"}." else "ОШИБКА: auto=${compact(actual.stdout)}; change=${compact(change.describe())}" }
    override fun listOpenApps():String { val activities=runCommand("dumpsys activity activities"); if(activities.exitCode!=0)return "ОШИБКА: ${compact(activities.describe())}"; val ps=runCommand("ps -A -o NAME"); val processes=if(ps.exitCode==0)ps.stdout.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList() else emptyList(); val packages=linkedSetOf<String>(); Regex("""u\d+\s+([A-Za-z0-9._]+)/""").let{p->activities.stdout.lineSequence().forEach{line->p.find(line)?.groupValues?.getOrNull(1)?.let{packages.add(it)}}}; return "OK:\n"+packages.joinToString("\n"){pkg->pkg+"\t"+processes.filter{it==pkg||it.startsWith("$pkg:")}.distinct().joinToString(",")} }
    override fun inspectApp(packageName:String,returnPackage:String):String { if(!SAFE_PACKAGE.matches(packageName)||!SAFE_PACKAGE.matches(returnPackage))return "ОШИБКА: некорректное имя пакета."; val target=resolveLauncher(packageName); if(target.isBlank())return "ОШИБКА: не удалось определить запускаемый Activity выбранного приложения."; val back=resolveLauncher(returnPackage); val file="/data/local/tmp/timecycler_ui_${System.nanoTime()}.xml"; val launch=runCommand("am start -n '$target'"); if(launch.exitCode!=0)return "ОШИБКА: не удалось открыть приложение: ${compact(launch.describe())}"; Thread.sleep(900); val dump=runCommand("uiautomator dump --compressed '$file'"); val xml=if(dump.exitCode==0)runCommand("cat '$file'") else CommandResult(-1,"",dump.describe()); runCommand("rm -f '$file'"); if(back.isNotBlank()){runCommand("am start -n '$back'");Thread.sleep(250)}; return if(dump.exitCode==0&&xml.exitCode==0&&xml.stdout.contains("<hierarchy"))"OK:\n"+xml.stdout else "ОШИБКА: не удалось получить UI hierarchy: ${compact(dump.describe())}; ${compact(xml.describe())}" }
    override fun invokeElement(packageName:String,bounds:String,returnPackage:String):String { if(!SAFE_PACKAGE.matches(packageName)||!SAFE_PACKAGE.matches(returnPackage))return "ОШИБКА: некорректное имя пакета."; val m=BOUNDS_PATTERN.matchEntire(bounds)?:return "ОШИБКА: некорректные координаты элемента."; val l=m.groupValues[1].toInt();val t=m.groupValues[2].toInt();val r=m.groupValues[3].toInt();val b=m.groupValues[4].toInt();if(r<=l||b<=t)return "ОШИБКА: пустая область элемента."; val x=l+(r-l)/2;val y=t+(b-t)/2;val target=resolveLauncher(packageName);if(target.isBlank())return "ОШИБКА: не удалось определить Activity.";val back=resolveLauncher(returnPackage);runCommand("am start -n '$target'");Thread.sleep(650);val tap=runCommand("input tap $x $y");Thread.sleep(450);if(back.isNotBlank())runCommand("am start -n '$back'");return if(tap.exitCode==0)"OK: нажатие выполнено в точке $x,$y." else "ОШИБКА: ${compact(tap.describe())}" }
    override fun diagnosePackage(packageName:String):String {
        if(!SAFE_PACKAGE.matches(packageName)) return "ОШИБКА: некорректное имя пакета."
        val pid=runCommand("pidof '$packageName'").stdout.trim().split(Regex("\\s+")).firstOrNull{it.matches(Regex("\\d+"))}.orEmpty()
        val root="/sdcard/Android/data/$packageName/files"
        val snapshot="/data/local/tmp/timecycler_state_${packageName.replace('.','_')}.txt"
        val current="${snapshot}.new"
        val build="find '$root' -maxdepth 1 -type f 2>/dev/null | while IFS= read -r f; do b=\$(basename \"\$f\"); d=\$(printf '%s' \"\$b\" | base64 -d 2>/dev/null | tr -d '\\000' | head -c 180); [ -n \"\$d\" ] || d=\"\$b\"; s=\$(stat -c '%Y|%s' \"\$f\" 2>/dev/null); h=\$(sha256sum \"\$f\" 2>/dev/null | awk '{print \$1}'); printf '%s|%s|%s|%s\\n' \"\$b\" \"\$d\" \"\$s\" \"\$h\"; done | sort"
        val make=runCommand("$build > '$current'")
        if(make.exitCode!=0) return "ОШИБКА: не удалось снять state snapshot: ${compact(make.describe())}"
        val hasPrevious=runCommand("test -f '$snapshot'").exitCode==0
        val report=if(!hasPrevious) {
            runCommand("cp '$current' '$snapshot'")
            "Первый снимок сохранён. Перейдите на другой экран игры и снова запустите диагностику."
        } else {
            val diff=runCommand("awk -F'|' 'NR==FNR{old[\$1]=\$0;next}{if(!(\$1 in old)){print \"ADDED|\"\$0}else if(old[\$1]!=\$0){print \"CHANGED|OLD|\"old[\$1];print \"CHANGED|NEW|\"\$0} seen[\$1]=1} END{for(k in old)if(!(k in seen))print \"REMOVED|\"old[k]}' '$snapshot' '$current' | sort")
            runCommand("cp '$current' '$snapshot'")
            if(diff.stdout.isBlank()) "Изменений state-файлов между снимками нет." else diff.stdout.take(50000)
        }
        return buildString {
            append("OK:\nV29 XP Hero State Diff\npackage=").append(packageName).append("\npid=").append(if(pid.isBlank())"NOT_FOUND" else pid).append('\n')
            append("\nV29 сравнивает весь каталог state-файлов с предыдущим снимком. Формат: status|encoded-name|decoded-name|mtime|size|sha256. После сравнения текущий снимок становится базовым для следующего запуска.\n\n=== state-diff ===\n")
            append(report)
        }
    }
    override fun destroy() {}
    private fun resolveLauncher(packageName:String):String { val r=runCommand("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p '$packageName'");return if(r.exitCode==0)r.stdout.lineSequence().map{it.trim()}.lastOrNull{it.contains('/')}?:"" else "" }
    private fun runCommand(command:String):CommandResult=runCatching{val p=Runtime.getRuntime().exec(arrayOf("sh","-c",command));val out=p.inputStream.bufferedReader().use{it.readText()};val err=p.errorStream.bufferedReader().use{it.readText()};CommandResult(p.waitFor(),out,err)}.getOrElse{CommandResult(-1,"",it.message.orEmpty())}
    private fun compact(v:String)=v.replace(Regex("\\s+")," ").trim().take(220)
    private data class CommandResult(val exitCode:Int,val stdout:String,val stderr:String){fun describe()="exit=$exitCode out=${stdout.trim()} err=${stderr.trim()}"}
    companion object { private val SAFE_PACKAGE=Regex("^[A-Za-z0-9._]+$");private val BOUNDS_PATTERN=Regex("^\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]$") }
}
