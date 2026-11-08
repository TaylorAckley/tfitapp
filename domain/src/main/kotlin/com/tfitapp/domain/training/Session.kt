package com.tfitapp.domain.training

import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.OriginMovement
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** What to do in a block. The shape follows the exercise's [LoadModel]. */
@Serializable
public sealed interface Prescription {
    @Serializable
    public data class SetsReps(val sets: Int, val repsTarget: Int, val repsMax: Int, val restSeconds: Int, val loadLabel: String?) : Prescription

    /** Timed work/rest handed to the interval timer. */
    @Serializable
    public data class Intervals(val plan: IntervalPlan, val sledSetting: Int? = null) : Prescription

    @Serializable
    public data class Lines(val pointsUsed: Int, val rounds: Int, val restSeconds: Int, val origin: OriginMovement?) : Prescription

    /** A circuit: several movements rotated inside one interval plan. */
    @Serializable
    public data class Circuit(val movementNames: List<String>, val plan: IntervalPlan) : Prescription
}

@Serializable
public data class SessionBlock(
    val id: String,
    val ordinal: Int,
    val kind: BlockKind,
    val pattern: Pattern,
    val exerciseIds: List<String>,
    val title: String,
    val prescription: Prescription,
    val estimatedMinutes: Int,
)

@Serializable
public enum class CandidateAxis(public val displayName: String) {
    DEFAULT("Planned"), SHORTER("Shorter"), INDOOR("Indoor"), LIGHTER("Lighter"),
}

/** One of the three sessions offered for today. */
@Serializable
public data class SessionCandidate(
    val id: String,
    val axis: CandidateAxis,
    val title: String,
    val location: Location,
    val blocks: List<SessionBlock>,
) {
    val estimatedMinutes: Int get() = blocks.sumOf { it.estimatedMinutes }
}

public data class Session(
    val id: String,
    val plannedFor: LocalDate,
    val slotId: String,
    val candidate: SessionCandidate,
    val alternatives: List<SessionCandidate>,
    val checkInText: String?,
    val jevConfidence: Double?,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** What the user said after finishing: "swings were easy, Tank at 4 was brutal". */
    val checkOutText: String? = null,
)

/** How a block felt, in the user's words or from Jev reading them. The input progression could not get from set logs. */
public enum class BlockFeel(public val displayName: String) {
    TOO_EASY("Easy"), RIGHT("Right"), TOO_HARD("Hard"),
}

public enum class FeedbackSource { JEV, MANUAL }

/** One block's check-out verdict. Keyed by (session, block) so a re-read replaces, never duplicates. */
public data class BlockFeedback(
    val sessionId: String,
    val blockId: String,
    val exerciseId: String,
    val feel: BlockFeel,
    val source: FeedbackSource,
    val at: Instant,
) {
    public val id: String get() = id(sessionId, blockId)

    public companion object {
        public fun id(sessionId: String, blockId: String): String = "$sessionId:$blockId"
    }
}

public data class SetLog(
    val id: String,
    val sessionId: String,
    val blockId: String,
    val exerciseId: String,
    val ordinal: Int,
    val reps: Int?,
    val loadLabel: String?,
    val sledSetting: Int?,
    val rpe: Int?,
    val at: Instant,
)

/** Summary of the last time an exercise was done, the input to progression. */
public data class LastPerformance(
    val exerciseId: String,
    val date: LocalDate,
    val repsTarget: Int?,
    val repsAchieved: List<Int>,
    val sledSetting: Int?,
    val linesPointsUsed: Int?,
    val linesRounds: Int?,
    val consecutiveMisses: Int,
    /** From the last session's check-out, when the user said anything about this block. */
    val feel: BlockFeel? = null,
)
