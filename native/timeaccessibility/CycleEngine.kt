package __PACKAGE__.timeaccessibility

import java.util.Calendar

enum class CyclePhase {
    IDLE,
    WAITING,
    APPLYING,
    STOPPING,
    COMPLETED,
    FAILED,
}

data class CyclePlan(
    val startAtMillis: Long,
    val stepDays: Int,
    val stepHours: Int,
    val stepMinutes: Int,
    val pauseMillis: Long,
    val repeatsPerSeries: Int,
    val seriesPauseMillis: Long,
    val totalSeries: Int,
) {
    val totalCycles: Int
        get() = repeatsPerSeries * totalSeries
}

data class CycleRuntimeState(
    val phase: CyclePhase,
    val completedCycles: Int,
    val nextDueElapsed: Long? = null,
    val lastAppliedMillis: Long? = null,
)

data class CycleStep(
    val targetMillis: Long,
    val seriesIndex: Int,
    val repeatIndex: Int,
    val totalSeries: Int,
    val repeatsPerSeries: Int,
)

data class CycleSuccessTransition(
    val state: CycleRuntimeState,
    val pauseMillis: Long?,
    val betweenSeries: Boolean,
)

object CycleEngine {
    fun validatePlan(plan: CyclePlan) {
        require(plan.stepDays in -999..999 && plan.stepHours in -999..999 && plan.stepMinutes in -999..999) {
            "Шаг задан вне допустимого диапазона."
        }
        require(plan.stepDays != 0 || plan.stepHours != 0 || plan.stepMinutes != 0) {
            "Шаг изменения не может состоять только из нулей."
        }
        require(plan.pauseMillis in 1_000L..86_400_000L) {
            "Пауза между повторами должна быть от 1 секунды до 24 часов."
        }
        require(plan.seriesPauseMillis in 0L..86_400_000L) {
            "Пауза между главными циклами должна быть от 0 секунд до 24 часов."
        }
        require(plan.repeatsPerSeries in 1..99_999) {
            "Количество повторов во вложенном цикле должно быть от 1 до 99999."
        }
        require(plan.totalSeries in 1..99_999) {
            "Количество главных циклов должно быть от 1 до 99999."
        }
        val total = plan.repeatsPerSeries.toLong() * plan.totalSeries.toLong()
        require(total in 1L..99_999L) {
            "Общее количество изменений не должно превышать 99999."
        }
    }

    fun initialState(nowElapsed: Long): CycleRuntimeState = CycleRuntimeState(
        phase = CyclePhase.WAITING,
        completedCycles = 0,
        nextDueElapsed = nowElapsed,
    )

    fun currentStep(plan: CyclePlan, state: CycleRuntimeState): CycleStep? {
        if (state.completedCycles !in 0 until plan.totalCycles) return null
        val completed = state.completedCycles
        return CycleStep(
            targetMillis = targetForIndex(plan, completed),
            seriesIndex = completed / plan.repeatsPerSeries + 1,
            repeatIndex = completed % plan.repeatsPerSeries + 1,
            totalSeries = plan.totalSeries,
            repeatsPerSeries = plan.repeatsPerSeries,
        )
    }

    fun markApplying(state: CycleRuntimeState): CycleRuntimeState = state.copy(
        phase = CyclePhase.APPLYING,
        nextDueElapsed = null,
    )

    fun markStopping(state: CycleRuntimeState): CycleRuntimeState = state.copy(
        phase = CyclePhase.STOPPING,
        nextDueElapsed = null,
    )

    fun markStopped(state: CycleRuntimeState): CycleRuntimeState = state.copy(
        phase = CyclePhase.IDLE,
        nextDueElapsed = null,
    )

    fun markFailed(state: CycleRuntimeState): CycleRuntimeState = state.copy(
        phase = CyclePhase.FAILED,
        nextDueElapsed = null,
    )

    fun markInterrupted(state: CycleRuntimeState): CycleRuntimeState = markFailed(state)

    fun afterSuccess(
        plan: CyclePlan,
        state: CycleRuntimeState,
        targetMillis: Long,
        appliedElapsed: Long,
    ): CycleSuccessTransition {
        val completed = state.completedCycles + 1
        if (completed >= plan.totalCycles) {
            return CycleSuccessTransition(
                state = state.copy(
                    phase = CyclePhase.COMPLETED,
                    completedCycles = plan.totalCycles,
                    nextDueElapsed = null,
                    lastAppliedMillis = targetMillis,
                ),
                pauseMillis = null,
                betweenSeries = false,
            )
        }

        val betweenSeries = completed % plan.repeatsPerSeries == 0
        val pause = if (betweenSeries) plan.seriesPauseMillis else plan.pauseMillis
        return CycleSuccessTransition(
            state = state.copy(
                phase = CyclePhase.WAITING,
                completedCycles = completed,
                nextDueElapsed = appliedElapsed + pause,
                lastAppliedMillis = targetMillis,
            ),
            pauseMillis = pause,
            betweenSeries = betweenSeries,
        )
    }

    fun targetForIndex(plan: CyclePlan, cycleIndex: Int): Long {
        require(cycleIndex >= 0) { "Индекс изменения не может быть отрицательным." }
        val calendar = Calendar.getInstance().apply { timeInMillis = plan.startAtMillis }
        calendar.add(Calendar.DAY_OF_YEAR, plan.stepDays * cycleIndex)
        calendar.add(Calendar.HOUR_OF_DAY, plan.stepHours * cycleIndex)
        calendar.add(Calendar.MINUTE, plan.stepMinutes * cycleIndex)
        return calendar.timeInMillis
    }
}
