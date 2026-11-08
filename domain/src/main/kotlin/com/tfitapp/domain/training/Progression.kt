package com.tfitapp.domain.training

/**
 * Turns "what happened last time" into "what to do today", by load model.
 * Design basis §5.3. Deterministic; every rule is a plain comparison.
 *
 * The check-out feel is the one input the set log cannot supply: "too hard" holds
 * a block that the numbers alone would have progressed, "too easy" takes a bigger
 * step. It never overrides a miss streak.
 */
public object Progression {
    private const val MISSES_BEFORE_REGRESS = 2
    private const val REP_STEP = 2
    private const val REP_STEP_WHEN_EASY = 4

    /** Fixed-load and bodyweight: reps up to the top of the range, then step the ladder. */
    public fun fixedLoad(spec: BlockSpec, last: LastPerformance?, exercise: Exercise): FixedLoadDecision {
        if (last == null || last.repsTarget == null) {
            return FixedLoadDecision(exercise, repsTarget = spec.repsMin, note = "First time — start at the bottom of the range")
        }
        val allHit = last.repsAchieved.size >= spec.sets && last.repsAchieved.all { it >= last.repsTarget }
        val missed = last.repsAchieved.any { it < last.repsTarget }
        return when {
            missed && last.consecutiveMisses + 1 >= MISSES_BEFORE_REGRESS ->
                FixedLoadDecision(exercise, repsTarget = maxOf(last.repsTarget - REP_STEP, spec.repsMin), note = "Two misses — back off 2 reps")
            last.feel == BlockFeel.TOO_HARD -> FixedLoadDecision(exercise, repsTarget = last.repsTarget, note = "Felt hard last time — hold")
            allHit && last.repsTarget >= spec.repsMax -> {
                val next = exercise.ladder.firstOrNull()
                if (next != null) FixedLoadDecision(exercise, repsTarget = spec.repsMin, promoteToExerciseId = next, note = "Topped out — move to the harder variation")
                else FixedLoadDecision(exercise, repsTarget = spec.repsMax, note = "At the top of the range; shorten rest")
            }
            allHit && last.feel == BlockFeel.TOO_EASY ->
                FixedLoadDecision(exercise, repsTarget = minOf(last.repsTarget + REP_STEP_WHEN_EASY, spec.repsMax), note = "All sets hit and it felt easy — +4 reps")
            allHit -> FixedLoadDecision(exercise, repsTarget = minOf(last.repsTarget + REP_STEP, spec.repsMax), note = "All sets hit — +2 reps")
            else -> FixedLoadDecision(exercise, repsTarget = last.repsTarget, note = "Hold")
        }
    }

    /** Sled: resistance setting first, then more rounds, then less rest. */
    public fun sled(spec: BlockSpec, last: LastPerformance?, maxSetting: Int): SledDecision {
        if (last == null || last.sledSetting == null) return SledDecision(setting = 1, rounds = spec.rounds, restSeconds = spec.restSeconds)
        val completed = last.consecutiveMisses == 0
        return when {
            !completed || last.feel == BlockFeel.TOO_HARD -> SledDecision(last.sledSetting, spec.rounds, spec.restSeconds)
            last.sledSetting < maxSetting -> SledDecision(last.sledSetting + 1, spec.rounds, spec.restSeconds)
            else -> SledDecision(last.sledSetting, spec.rounds + 1, maxOf(spec.restSeconds - 10, 30))
        }
    }

    /** Lines: more points, then more rounds, then less rest. */
    public fun lines(spec: BlockSpec, last: LastPerformance?, coursePoints: Int): LinesDecision {
        val startPoints = minOf(3, coursePoints)
        if (last == null || last.linesPointsUsed == null || last.linesRounds == null) {
            return LinesDecision(pointsUsed = startPoints, rounds = spec.rounds, restSeconds = spec.restSeconds)
        }
        val completed = last.consecutiveMisses == 0
        return when {
            !completed || last.feel == BlockFeel.TOO_HARD -> LinesDecision(last.linesPointsUsed, last.linesRounds, spec.restSeconds)
            last.linesPointsUsed < coursePoints -> LinesDecision(last.linesPointsUsed + 1, last.linesRounds, spec.restSeconds)
            else -> LinesDecision(last.linesPointsUsed, last.linesRounds + 1, maxOf(spec.restSeconds - 15, 45))
        }
    }

    public data class FixedLoadDecision(
        val exercise: Exercise,
        val repsTarget: Int,
        val promoteToExerciseId: String? = null,
        val note: String,
    )

    public data class SledDecision(val setting: Int, val rounds: Int, val restSeconds: Int)
    public data class LinesDecision(val pointsUsed: Int, val rounds: Int, val restSeconds: Int)
}
