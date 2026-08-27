package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Runs the cycle while the foreground service keeps the app process active. Shizuku performs the privileged time change. */
object TimeShizukuCycleRunner {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledTask: Runnable? = null
    private var commandInFlight = false
    private var generation = 0L

    fun start(context: Context) {
        val appContext = context.applicationContext
        stopScheduledTask()
        commandInFlight = false
        generation += 1L
        val currentGeneration = generation
        val savedDue = TimeCycleStore.nextDueElapsed(appContext)
        if (savedDue != null) {
            scheduleAt(appContext, savedDue, currentGeneration)
            return
        }
        if (TimeCycleStore.completedCycles(appContext) == 0) {
            prepareCycle(appContext, currentGeneration)
        } else {
            scheduleAt(appContext, SystemClock.elapsedRealtime(), currentGeneration)
        }
    }

    fun stop() {
        generation += 1L
        stopScheduledTask()
        commandInFlight = false
    }

    private fun prepareCycle(context: Context, expectedGeneration: Long) {
        if (expectedGeneration != generation || !TimeCycleStore.isRunning(context) || commandInFlight) return
        commandInFlight = true
        TimeShizukuController.setAutomaticTime(context, false) { outcome ->
            if (expectedGeneration != generation) return@setAutomaticTime
            commandInFlight = false
            if (!TimeCycleStore.isRunning(context)) return@setAutomaticTime
            if (!outcome.isSuccess) {
                TimeCycleStore.markAttemptFailed(context, TimeCycleStore.targetForCurrentCycle(context), "Не удалось отключить автоматическое время. ${outcome.detail}")
                TimeCycleForegroundService.stop(context)
                return@setAutomaticTime
            }
            TimeCycleStore.setAutomaticTimeEnabled(context, false)
            scheduleAt(context, SystemClock.elapsedRealtime(), expectedGeneration)
        }
    }

    private fun scheduleAt(context: Context, dueElapsed: Long, expectedGeneration: Long) {
        stopScheduledTask()
        TimeCycleStore.setNextDueElapsed(context, dueElapsed)
        val task = object : Runnable {
            override fun run() {
                if (expectedGeneration != generation || !TimeCycleStore.isRunning(context)) return
                val remaining = (dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remaining > 0L) {
                    handler.postDelayed(this, remaining)
                    return
                }
                scheduledTask = null
                TimeCycleStore.clearNextDueElapsed(context)
                applyCurrentTarget(context, expectedGeneration)
            }
        }
        scheduledTask = task
        handler.post(task)
    }

    private fun applyCurrentTarget(context: Context, expectedGeneration: Long) {
        if (expectedGeneration != generation || !TimeCycleStore.isRunning(context) || commandInFlight) return
        val completed = TimeCycleStore.completedCycles(context)
        val total = TimeCycleStore.totalCycles(context)
        if (completed >= total) {
            TimeCycleStore.finishIfComplete(context)
            TimeCycleForegroundService.stop(context)
            return
        }

        val repeats = TimeCycleStore.repeatsPerSeries(context)
        val seriesTotal = TimeCycleStore.totalSeries(context)
        val seriesIndex = completed / repeats + 1
        val repeatIndex = completed % repeats + 1
        val targetMillis = TimeCycleStore.targetForCurrentCycle(context)
        TimeCycleStore.addEvent(context, "Главный цикл $seriesIndex из $seriesTotal, повтор $repeatIndex из $repeats.")
        commandInFlight = true
        TimeShizukuController.applyTime(context, targetMillis) { outcome ->
            if (expectedGeneration != generation) return@applyTime
            commandInFlight = false
            if (!TimeCycleStore.isRunning(context)) return@applyTime
            if (!outcome.isSuccess) {
                TimeCycleStore.markAttemptFailed(context, targetMillis, "Shizuku не применил системное время. ${outcome.detail}")
                TimeCycleForegroundService.stop(context)
                return@applyTime
            }

            val appliedElapsed = outcome.appliedElapsedRealtime ?: SystemClock.elapsedRealtime()
            TimeCycleStore.setAutomaticTimeEnabled(context, false)
            val continueRunning = TimeCycleStore.markAttemptSucceeded(context, targetMillis, outcome.detail)
            if (!continueRunning) {
                TimeCycleForegroundService.stop(context)
                return@applyTime
            }

            val betweenSeries = TimeCycleStore.isBetweenSeriesPause(context)
            val pauseMillis = TimeCycleStore.pauseBeforeNextMillis(context)
            val pauseSeconds = pauseMillis / 1000L
            if (betweenSeries) {
                TimeCycleStore.addEvent(context, "Пауза между главными циклами: $pauseSeconds сек.")
            } else {
                TimeCycleStore.addEvent(context, "Пауза между повторами: $pauseSeconds сек.")
            }
            scheduleAt(context, appliedElapsed + pauseMillis, expectedGeneration)
        }
    }

    private fun stopScheduledTask() {
        scheduledTask?.let(handler::removeCallbacks)
        scheduledTask = null
    }
}
