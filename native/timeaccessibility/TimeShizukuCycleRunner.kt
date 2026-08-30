package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Executes one persisted cycle state at a time while the foreground service keeps the process alive. */
object TimeShizukuCycleRunner {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledTask: Runnable? = null
    private var commandInFlight = false
    private var generation = 0L

    fun startOrResume(context: Context) {
        val appContext = context.applicationContext
        if (scheduledTask != null || commandInFlight) return
        if (!TimeCycleStore.validateRuntimeForService(appContext)) {
            TimeCycleForegroundService.stop(appContext)
            return
        }
        when (TimeCycleStore.phase(appContext)) {
            CyclePhase.APPLYING -> {
                TimeCycleStore.stopInterrupted(appContext, "Цикл остановлен: приложение было перезапущено во время изменения системного времени. Проверьте время и запустите цикл заново.")
                TimeCycleForegroundService.stop(appContext)
                return
            }
            CyclePhase.IDLE -> {
                TimeCycleStore.stopInterrupted(appContext, "Цикл остановлен из-за некорректного сохранённого состояния.")
                TimeCycleForegroundService.stop(appContext)
                return
            }
            CyclePhase.WAITING -> Unit
        }

        generation += 1L
        val dueElapsed = TimeCycleStore.nextDueElapsed(appContext) ?: SystemClock.elapsedRealtime().also {
            TimeCycleStore.setWaitingUntil(appContext, it)
        }
        scheduleAt(appContext, dueElapsed, generation)
    }

    fun stop() {
        generation += 1L
        stopScheduledTask()
        if (commandInFlight) TimeShizukuController.cancelActiveCommand()
        commandInFlight = false
    }

    private fun scheduleAt(context: Context, dueElapsed: Long, expectedGeneration: Long) {
        stopScheduledTask()
        val task = object : Runnable {
            override fun run() {
                if (expectedGeneration != generation || !TimeCycleStore.isRunning(context)) return
                val remaining = (dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (remaining > 0L) {
                    handler.postDelayed(this, remaining)
                    return
                }
                scheduledTask = null
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
        TimeCycleStore.markApplying(context)
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

            val appliedElapsed = SystemClock.elapsedRealtime()
            TimeCycleStore.setAutomaticTimeEnabled(context, false)
            val continueRunning = TimeCycleStore.markAttemptSucceeded(context, targetMillis, outcome.detail)
            if (!continueRunning) {
                TimeCycleForegroundService.stop(context)
                return@applyTime
            }

            val betweenSeries = TimeCycleStore.isBetweenSeriesPause(context)
            val pauseMillis = TimeCycleStore.pauseBeforeNextMillis(context)
            val dueElapsed = appliedElapsed + pauseMillis
            TimeCycleStore.setWaitingUntil(context, dueElapsed)
            val pauseSeconds = pauseMillis / 1000L
            if (betweenSeries) {
                TimeCycleStore.addEvent(context, "Пауза между главными циклами: $pauseSeconds сек.")
            } else {
                TimeCycleStore.addEvent(context, "Пауза между повторами: $pauseSeconds сек.")
            }
            scheduleAt(context, dueElapsed, expectedGeneration)
        }
    }

    private fun stopScheduledTask() {
        scheduledTask?.let(handler::removeCallbacks)
        scheduledTask = null
    }
}
