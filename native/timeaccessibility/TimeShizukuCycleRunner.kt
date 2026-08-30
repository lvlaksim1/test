package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.SystemClock

/** Coordinates persisted state, scheduling and the privileged Shizuku time setter. */
object TimeShizukuCycleRunner {
    private val scheduler = CycleScheduler()
    private var commandInFlight = false
    private var generation = 0L

    fun startOrResume(context: Context) {
        val appContext = context.applicationContext
        if (scheduler.hasScheduledTask() || commandInFlight) return
        if (!TimeCycleStore.validateRuntimeForService(appContext)) {
            TimeCycleForegroundService.stop(appContext)
            return
        }

        val state = TimeCycleStore.loadState(appContext)
        when (state.phase) {
            CyclePhase.APPLYING -> {
                TimeCycleStore.stopInterrupted(
                    appContext,
                    "Цикл остановлен: приложение было перезапущено во время изменения системного времени. Проверьте время и запустите цикл заново.",
                )
                TimeCycleForegroundService.stop(appContext)
                return
            }
            CyclePhase.STOPPING -> {
                TimeCycleStore.stop(appContext, "Цикл остановлен после восстановления приложения.")
                TimeCycleForegroundService.stop(appContext)
                return
            }
            CyclePhase.IDLE, CyclePhase.COMPLETED, CyclePhase.FAILED -> {
                TimeCycleForegroundService.stop(appContext)
                return
            }
            CyclePhase.WAITING -> Unit
        }

        generation += 1L
        val expectedGeneration = generation
        val dueElapsed = state.nextDueElapsed ?: SystemClock.elapsedRealtime().also { due ->
            TimeCycleStore.saveState(appContext, state.copy(nextDueElapsed = due))
        }
        scheduleAt(appContext, dueElapsed, expectedGeneration)
    }

    fun stop(context: Context) {
        generation += 1L
        scheduler.cancel()
        if (commandInFlight) {
            TimeCycleStore.markStopping(context.applicationContext)
            TimeShizukuController.cancelActiveCommand()
        }
        commandInFlight = false
    }

    private fun scheduleAt(context: Context, dueElapsed: Long, expectedGeneration: Long) {
        scheduler.scheduleAt(dueElapsed) {
            if (expectedGeneration != generation || !TimeCycleStore.isRunning(context)) return@scheduleAt
            applyCurrentTarget(context, expectedGeneration)
        }
    }

    private fun applyCurrentTarget(context: Context, expectedGeneration: Long) {
        if (expectedGeneration != generation || !TimeCycleStore.isRunning(context) || commandInFlight) return
        val plan = TimeCycleStore.loadPlan(context)
        if (plan == null) {
            TimeCycleStore.stopInterrupted(context, "Цикл остановлен: не удалось загрузить план выполнения.")
            TimeCycleForegroundService.stop(context)
            return
        }

        val state = TimeCycleStore.loadState(context)
        val step = CycleEngine.currentStep(plan, state)
        if (step == null) {
            if (state.completedCycles >= plan.totalCycles) {
                TimeCycleStore.saveState(
                    context,
                    state.copy(phase = CyclePhase.COMPLETED, completedCycles = plan.totalCycles, nextDueElapsed = null),
                )
                TimeCycleStore.logCompleted(context, plan)
            } else {
                TimeCycleStore.stopInterrupted(context, "Цикл остановлен: внутреннее состояние выполнения некорректно.")
            }
            TimeCycleForegroundService.stop(context)
            return
        }

        TimeCycleStore.saveState(context, CycleEngine.markApplying(state))
        TimeCycleStore.addEvent(
            context,
            "Главный цикл ${step.seriesIndex} из ${step.totalSeries}, повтор ${step.repeatIndex} из ${step.repeatsPerSeries}.",
        )
        commandInFlight = true
        TimeShizukuController.applyTime(context, step.targetMillis) { outcome ->
            if (expectedGeneration != generation) return@applyTime
            commandInFlight = false
            if (!TimeCycleStore.isRunning(context)) return@applyTime

            val currentState = TimeCycleStore.loadState(context)
            if (!outcome.isSuccess) {
                TimeCycleStore.saveState(context, CycleEngine.markFailed(currentState))
                TimeCycleStore.logAttemptFailed(
                    context,
                    step.targetMillis,
                    "Shizuku не применил системное время. ${outcome.detail}",
                )
                TimeCycleForegroundService.stop(context)
                return@applyTime
            }

            val appliedElapsed = SystemClock.elapsedRealtime()
            val transition = CycleEngine.afterSuccess(plan, currentState, step.targetMillis, appliedElapsed)
            TimeCycleStore.saveState(context, transition.state)
            TimeCycleStore.setAutomaticTimeEnabled(context, false)
            TimeCycleStore.logAttemptSucceeded(context, step.targetMillis, outcome.detail)

            if (transition.state.phase == CyclePhase.COMPLETED) {
                TimeCycleStore.logCompleted(context, plan)
                TimeCycleForegroundService.stop(context)
                return@applyTime
            }

            val pauseMillis = transition.pauseMillis ?: 0L
            val pauseSeconds = pauseMillis / 1000L
            if (transition.betweenSeries) {
                TimeCycleStore.addEvent(context, "Пауза между главными циклами: $pauseSeconds сек.")
            } else {
                TimeCycleStore.addEvent(context, "Пауза между повторами: $pauseSeconds сек.")
            }
            val dueElapsed = transition.state.nextDueElapsed ?: appliedElapsed
            scheduleAt(context, dueElapsed, expectedGeneration)
        }
    }
}
