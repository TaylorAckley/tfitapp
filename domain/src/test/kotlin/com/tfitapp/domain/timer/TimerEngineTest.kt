package com.tfitapp.domain.timer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerEngineTest {

    private val plan = Presets.workRest("t", "test", work = 20, rest = 10, rounds = 2, prep = 5)
    // phases: PREP 5, WORK 20, REST 10, WORK 20

    @Test
    fun `start begins in the first phase with a PhaseStarted event`() {
        val (state, events) = TimerEngine.start(plan, nowMs = 1_000)
        assertEquals(0, state.phaseIndex)
        assertEquals(TimerStatus.RUNNING, state.status)
        assertEquals(listOf<TimerEvent>(TimerEvent.PhaseStarted(0, plan.phases[0])), events)
    }

    @Test
    fun `tick before the boundary changes nothing`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val (s1, events) = TimerEngine.tick(s0, 4_999)
        assertEquals(s0, s1)
        assertTrue(events.isEmpty())
        assertEquals(1, TimerEngine.snapshot(s1, 4_999).remainingWholeSeconds)
    }

    @Test
    fun `tick at the boundary advances and anchors the next phase at the exact boundary not at now`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val (s1, events) = TimerEngine.tick(s0, 5_300) // 300 ms late
        assertEquals(1, s1.phaseIndex)
        assertEquals(5_000, s1.phaseStartMs)
        assertEquals(listOf<TimerEvent>(TimerEvent.PhaseStarted(1, plan.phases[1])), events)
    }

    @Test
    fun `a long gap advances through several phases with one event each and no drift`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val (s1, events) = TimerEngine.tick(s0, 36_000) // 5 + 20 + 10 = 35 s → now 1 s into last WORK
        assertEquals(3, s1.phaseIndex)
        assertEquals(35_000, s1.phaseStartMs)
        assertEquals(3, events.size)
        assertEquals(19_000, TimerEngine.snapshot(s1, 36_000).remainingMs)
    }

    @Test
    fun `finishes after the last phase`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val (s1, events) = TimerEngine.tick(s0, 55_000)
        assertEquals(TimerStatus.FINISHED, s1.status)
        assertTrue(TimerEvent.Finished in events)
        val (s2, more) = TimerEngine.tick(s1, 99_000)
        assertEquals(s1, s2)
        assertTrue(more.isEmpty())
    }

    @Test
    fun `pause freezes remaining time and resume shifts the anchor by the paused duration`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val paused = TimerEngine.pause(s0, 2_000)
        assertEquals(3_000, TimerEngine.snapshot(paused, 9_000).remainingMs)
        val (stillPaused, events) = TimerEngine.tick(paused, 60_000)
        assertEquals(paused, stillPaused)
        assertTrue(events.isEmpty())
        val resumed = TimerEngine.resume(paused, 12_000)
        assertEquals(10_000, resumed.phaseStartMs)
        assertEquals(10_000, resumed.pausedTotalMs)
        assertEquals(3_000, TimerEngine.snapshot(resumed, 12_000).remainingMs)
    }

    @Test
    fun `resume is a no-op when not paused and pause is a no-op when finished`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        assertEquals(s0, TimerEngine.resume(s0, 5))
        val (finished, _) = TimerEngine.tick(s0, 60_000)
        assertEquals(finished, TimerEngine.pause(finished, 61_000))
    }

    @Test
    fun `self-paced legs never auto-advance and record a split on tap`() {
        val course = LinesCourse("c", "Yard", listOf(CoursePoint(0, "P1", 20), CoursePoint(1, "P2", 40)))
        val lines = LinesPlanBuilder.build("l", course, pointsUsed = 2, rounds = 1, restSeconds = 0, prepSeconds = 5)
        // PREP 5, LEG P1, LEG P2
        val (s0, _) = TimerEngine.start(lines, 0)
        val (s1, _) = TimerEngine.tick(s0, 5_000)
        assertEquals(PhaseKind.LEG, s1.phase.kind)
        val (s2, none) = TimerEngine.tick(s1, 500_000)
        assertEquals(s1, s2)
        assertTrue(none.isEmpty())
        assertNull(TimerEngine.snapshot(s2, 500_000).remainingMs)
        assertEquals(495_000, TimerEngine.snapshot(s2, 500_000).elapsedInPhaseMs)

        val (s3, events) = TimerEngine.advance(s2, 41_000)
        assertEquals(listOf(Split(1, 0, 36_000)), s3.splits)
        assertEquals(2, s3.phaseIndex)
        assertEquals(41_000, s3.phaseStartMs)
        assertEquals(1, events.size)

        val (s4, fin) = TimerEngine.advance(s3, 100_000)
        assertEquals(TimerStatus.FINISHED, s4.status)
        assertEquals(2, s4.splits.size)
        assertTrue(TimerEvent.Finished in fin)
    }

    @Test
    fun `advancing while paused stays paused`() {
        val (s0, _) = TimerEngine.start(plan, 0)
        val paused = TimerEngine.pause(s0, 2_000)
        val (advanced, _) = TimerEngine.advance(paused, 50_000)
        assertEquals(TimerStatus.PAUSED, advanced.status)
        assertEquals(1, advanced.phaseIndex)
        assertNotNull(advanced.pausedAtMs)
        assertEquals(20_000, TimerEngine.snapshot(advanced, 99_000).remainingMs)
    }

    @Test
    fun `state round-trips through serialization for process-death recovery`() {
        val (s0, _) = TimerEngine.start(plan, 123)
        val json = kotlinx.serialization.json.Json.encodeToString(TimerState.serializer(), s0)
        val back = kotlinx.serialization.json.Json.decodeFromString(TimerState.serializer(), json)
        assertEquals(s0, back)
    }
}
