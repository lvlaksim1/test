package __PACKAGE__.timeaccessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CycleEngineTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun calculatesTargetsAndSeriesCoordinatesFromOneEngine() {
        val plan = plan(
            startAtMillis = utcMillis(2026, Calendar.AUGUST, 21, 9, 30),
            stepDays = 1,
            stepHours = 2,
            stepMinutes = 15,
            repeatsPerSeries = 3,
            totalSeries = 2,
        )

        val step = CycleEngine.currentStep(plan, CycleRuntimeState(CyclePhase.WAITING, completedCycles = 4))
        assertNotNull(step)
        assertEquals(2, step!!.seriesIndex)
        assertEquals(2, step.repeatIndex)
        assertEquals(utcMillis(2026, Calendar.AUGUST, 25, 18, 30), step.targetMillis)
    }

    @Test
    fun usesNormalPauseThenSeriesPauseAndNoPauseAfterFinalChange() {
        val plan = plan(repeatsPerSeries = 2, totalSeries = 2, pauseMillis = 2_000L, seriesPauseMillis = 60_000L)

        val first = CycleEngine.afterSuccess(
            plan,
            CycleRuntimeState(CyclePhase.APPLYING, completedCycles = 0),
            targetMillis = 1_000L,
            appliedElapsed = 10_000L,
        )
        assertEquals(CyclePhase.WAITING, first.state.phase)
        assertEquals(1, first.state.completedCycles)
        assertEquals(12_000L, first.state.nextDueElapsed)
        assertEquals(2_000L, first.pauseMillis)
        assertFalse(first.betweenSeries)

        val second = CycleEngine.afterSuccess(
            plan,
            CycleRuntimeState(CyclePhase.APPLYING, completedCycles = 1),
            targetMillis = 2_000L,
            appliedElapsed = 20_000L,
        )
        assertEquals(80_000L, second.state.nextDueElapsed)
        assertEquals(60_000L, second.pauseMillis)
        assertTrue(second.betweenSeries)

        val final = CycleEngine.afterSuccess(
            plan,
            CycleRuntimeState(CyclePhase.APPLYING, completedCycles = 3),
            targetMillis = 4_000L,
            appliedElapsed = 30_000L,
        )
        assertEquals(CyclePhase.COMPLETED, final.state.phase)
        assertEquals(4, final.state.completedCycles)
        assertNull(final.state.nextDueElapsed)
        assertNull(final.pauseMillis)
    }

    @Test
    fun supportsNegativeAndMixedCalendarSteps() {
        val plan = plan(
            startAtMillis = utcMillis(2026, Calendar.MARCH, 1, 1, 0),
            stepDays = 1,
            stepHours = -2,
            stepMinutes = -30,
        )

        assertEquals(
            utcMillis(2026, Calendar.MARCH, 1, 22, 30),
            CycleEngine.targetForIndex(plan, 1),
        )
    }

    @Test
    fun rejectsOnlyActuallyZeroStep() {
        val mixed = plan(stepDays = 1, stepHours = -1, stepMinutes = 0)
        CycleEngine.validatePlan(mixed)

        val zero = plan(stepDays = 0, stepHours = 0, stepMinutes = 0)
        val result = runCatching { CycleEngine.validatePlan(zero) }
        assertTrue(result.isFailure)
    }

    @Test
    fun stopAndFailureAreExplicitStateTransitions() {
        val running = CycleRuntimeState(CyclePhase.WAITING, completedCycles = 1, nextDueElapsed = 55_000L)
        assertEquals(CyclePhase.STOPPING, CycleEngine.markStopping(running).phase)
        assertEquals(CyclePhase.IDLE, CycleEngine.markStopped(running).phase)
        assertEquals(CyclePhase.FAILED, CycleEngine.markFailed(running).phase)
    }

    private fun plan(
        startAtMillis: Long = utcMillis(2026, Calendar.AUGUST, 21, 9, 30),
        stepDays: Int = 0,
        stepHours: Int = 2,
        stepMinutes: Int = 0,
        pauseMillis: Long = 2_000L,
        repeatsPerSeries: Int = 2,
        seriesPauseMillis: Long = 60_000L,
        totalSeries: Int = 1,
    ) = CyclePlan(
        startAtMillis = startAtMillis,
        stepDays = stepDays,
        stepHours = stepHours,
        stepMinutes = stepMinutes,
        pauseMillis = pauseMillis,
        repeatsPerSeries = repeatsPerSeries,
        seriesPauseMillis = seriesPauseMillis,
        totalSeries = totalSeries,
    )

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
