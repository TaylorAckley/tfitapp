package com.tfitapp.domain.training

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressionTest {

    private val day = LocalDate.of(2026, 9, 18)
    private val pushUp = ExerciseLibrary.seed().first { it.loadModel == LoadModel.FIXED && it.ladder.isNotEmpty() }
    private val spec = ProgramTemplate.seed().slots.flatMap { it.blocks }.first { it.kind == BlockKind.STRENGTH }

    private fun last(target: Int, achieved: List<Int>, feel: BlockFeel? = null, misses: Int = 0) =
        LastPerformance(pushUp.id, day, target, achieved, null, null, null, misses, feel)

    @Test
    fun `all sets hit steps two reps, or four when it felt easy`() {
        val hit = List(spec.sets) { spec.repsMin }
        assertEquals(spec.repsMin + 2, Progression.fixedLoad(spec, last(spec.repsMin, hit), pushUp).repsTarget)
        assertEquals(spec.repsMin + 4, Progression.fixedLoad(spec, last(spec.repsMin, hit, BlockFeel.TOO_EASY), pushUp).repsTarget)
        assertEquals(spec.repsMax, Progression.fixedLoad(spec, last(spec.repsMax - 1, List(spec.sets) { spec.repsMax - 1 }, BlockFeel.TOO_EASY), pushUp).repsTarget, "capped at the top of the range")
    }

    @Test
    fun `too hard holds even when every set was hit`() {
        val hit = List(spec.sets) { spec.repsMin }
        val d = Progression.fixedLoad(spec, last(spec.repsMin, hit, BlockFeel.TOO_HARD), pushUp)
        assertEquals(spec.repsMin, d.repsTarget)
        assertNull(d.promoteToExerciseId)
    }

    @Test
    fun `a miss streak still backs off regardless of feel`() {
        val target = spec.repsMin + 4
        val missed = List(spec.sets) { target - 1 }
        val d = Progression.fixedLoad(spec, last(target, missed, BlockFeel.TOO_EASY, misses = 1), pushUp)
        assertEquals(target - 2, d.repsTarget)
    }

    @Test
    fun `topping out promotes to the ladder`() {
        val d = Progression.fixedLoad(spec, last(spec.repsMax, List(spec.sets) { spec.repsMax }), pushUp)
        assertEquals(pushUp.ladder.first(), d.promoteToExerciseId)
    }

    @Test
    fun `sled and lines hold on too hard and progress otherwise`() {
        val sledSpec = ProgramTemplate.seed().slots.flatMap { it.blocks }.first { it.kind == BlockKind.SLED }
        val sledLast = LastPerformance("ex.tank", day, null, emptyList(), 2, null, null, 0)
        assertEquals(3, Progression.sled(sledSpec, sledLast, maxSetting = 4).setting)
        assertEquals(2, Progression.sled(sledSpec, sledLast.copy(feel = BlockFeel.TOO_HARD), maxSetting = 4).setting)

        val linesSpec = ProgramTemplate.seed().slots.flatMap { it.blocks }.first { it.kind == BlockKind.LINES }
        val linesLast = LastPerformance("ex.lines", day, null, emptyList(), null, 3, 3, 0)
        assertEquals(4, Progression.lines(linesSpec, linesLast, coursePoints = 4).pointsUsed)
        assertEquals(3, Progression.lines(linesSpec, linesLast.copy(feel = BlockFeel.TOO_HARD), coursePoints = 4).pointsUsed)
    }
}
