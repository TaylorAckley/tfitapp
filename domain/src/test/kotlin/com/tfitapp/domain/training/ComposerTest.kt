package com.tfitapp.domain.training

import com.tfitapp.domain.timer.CoursePoint
import com.tfitapp.domain.timer.LinesCourse
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerTest {

    private val today = LocalDate.of(2026, 9, 19)
    private val course = LinesCourse("c", "Yard", listOf(CoursePoint(0, "P1", 20), CoursePoint(1, "P2", 40), CoursePoint(2, "P3", 60), CoursePoint(3, "P4", 80)))
    private val program = ProgramTemplate.seed()

    private fun ctx(outdoor: Boolean, budget: Int = 45, lastUsed: Map<String, LocalDate> = emptyMap(), perf: Map<String, LastPerformance> = emptyMap()): ComposeContext {
        val n = AtomicInteger()
        return ComposeContext(today, outdoor, budget, lastUsed, perf, course, newId = { "id-${n.incrementAndGet()}" })
    }

    private val composer = Composer(ExerciseLibrary.seed(), Equipment.seed())

    @Test
    fun `three candidates on distinct axes, outdoor when feasible`() {
        val out = composer.compose(program.slots.first { it.id == "slot.sled" }, ctx(outdoor = true))
        assertEquals(listOf(CandidateAxis.DEFAULT, CandidateAxis.SHORTER, CandidateAxis.INDOOR), out.map { it.axis })
        assertEquals(Location.OUTDOOR, out[0].location)
        assertEquals(Location.INDOOR, out[2].location)
        assertTrue(out[1].estimatedMinutes < out[0].estimatedMinutes)
    }

    @Test
    fun `no sled or lines indoors`() {
        val lib = ExerciseLibrary.seed().associateBy { it.id }
        composer.compose(program.slots.first { it.id == "slot.lines" }, ctx(outdoor = false)).forEach { cand ->
            assertEquals(Location.INDOOR, cand.location)
            cand.blocks.flatMap { it.exerciseIds }.forEach { id ->
                assertTrue(Location.INDOOR in lib.getValue(id).locations, "$id is outdoor-only but appeared indoors")
            }
            assertTrue(cand.blocks.none { it.kind == BlockKind.LINES || it.kind == BlockKind.SLED })
        }
    }

    @Test
    fun `bad weather yields a lighter third candidate instead of an indoor duplicate`() {
        val out = composer.compose(program.slots.first(), ctx(outdoor = false))
        assertEquals(CandidateAxis.LIGHTER, out[2].axis)
        val plannedSets = (out[0].blocks.first { it.kind == BlockKind.STRENGTH }.prescription as Prescription.SetsReps).sets
        val lighterSets = (out[2].blocks.first { it.kind == BlockKind.STRENGTH }.prescription as Prescription.SetsReps).sets
        assertEquals(plannedSets - 1, lighterSets)
    }

    @Test
    fun `lines day uses the course and progression`() {
        val perf = LastPerformance("ex.lines", today.minusDays(7), null, emptyList(), null, linesPointsUsed = 3, linesRounds = 3, consecutiveMisses = 0)
        val out = composer.compose(program.slots.first { it.id == "slot.lines" }, ctx(outdoor = true, perf = mapOf("ex.lines" to perf)))
        val lines = out[0].blocks.first { it.kind == BlockKind.LINES }.prescription as Prescription.Lines
        assertEquals(4, lines.pointsUsed, "completed P1–P3 → add a point")
        assertEquals(3, lines.rounds)
    }

    @Test
    fun `rotation prefers the movement not done recently`() {
        val slot = program.slots.first()
        val lastUsed = mapOf("ex.kb-swing" to today.minusDays(1))
        val out = composer.compose(slot, ctx(outdoor = true, lastUsed = lastUsed))
        val hinge = out[0].blocks.first { it.pattern == Pattern.HINGE }
        assertEquals(listOf("ex.kb-swing-1arm"), hinge.exerciseIds)
    }

    @Test
    fun `swap offers a different movement for the same pattern`() {
        val out = composer.compose(program.slots.first(), ctx(outdoor = true))
        val press = out[0].blocks.first { it.pattern == Pattern.PRESS }
        val alt = composer.alternative(press, Location.OUTDOOR, ctx(outdoor = true))
        assertTrue(alt != null && alt.id !in press.exerciseIds && Pattern.PRESS in alt.patterns)
    }

    @Test
    fun `equipment that is not present is never chosen`() {
        val noSled = Equipment.seed().map { if (it.kind == EquipmentKind.SLED) it.copy(present = false) else it }
        val out = Composer(ExerciseLibrary.seed(), noSled).compose(program.slots.first { it.id == "slot.sled" }, ctx(outdoor = true))
        val lib = ExerciseLibrary.seed().associateBy { it.id }
        out.flatMap { it.blocks }.flatMap { it.exerciseIds }.forEach {
            assertTrue(EquipmentKind.SLED !in lib.getValue(it).equipment, "$it needs the sled")
        }
    }
}
