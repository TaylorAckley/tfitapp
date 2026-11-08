package com.tfitapp.domain.timer

import kotlinx.serialization.Serializable

@Serializable
public enum class PhaseKind {
    /** Get-ready countdown before the first work phase. */
    PREP,
    WORK,
    REST,
    /** A lines leg: out to a point and back. Self-paced, ended by tap. */
    LEG,
    /** A movement at the origin between legs (e.g. 5 burpees). Self-paced. */
    ORIGIN,
}

/**
 * One step of a plan. Timed phases carry [seconds]; self-paced phases ([PhaseKind.LEG],
 * [PhaseKind.ORIGIN]) carry none and end when the user taps.
 */
@Serializable
public data class Phase(
    val label: String,
    val kind: PhaseKind,
    val seconds: Int? = null,
    val pointIndex: Int? = null,
    val reps: Int? = null,
) {
    init {
        if (isSelfPaced) require(seconds == null) { "Self-paced phase '$label' cannot have a duration" }
        else requireNotNull(seconds) { "Timed phase '$label' needs a duration" }
        seconds?.let { require(it > 0) { "Phase '$label' duration must be positive" } }
    }

    val isSelfPaced: Boolean get() = kind == PhaseKind.LEG || kind == PhaseKind.ORIGIN
}

@Serializable
public data class IntervalPlan(
    val id: String,
    val name: String,
    val isPreset: Boolean,
    val phases: List<Phase>,
) {
    init {
        require(phases.isNotEmpty()) { "A plan needs at least one phase" }
    }

    /** Total timed seconds. Self-paced phases contribute nothing; the estimate is a floor. */
    val timedSeconds: Int get() = phases.sumOf { it.seconds ?: 0 }
    val hasSelfPacedPhases: Boolean get() = phases.any { it.isSelfPaced }
}

/** A point on the lines course, [metres] from the origin. */
@Serializable
public data class CoursePoint(val index: Int, val label: String, val metres: Int)

@Serializable
public data class LinesCourse(val id: String, val name: String, val points: List<CoursePoint>) {
    init {
        require(points.isNotEmpty()) { "A course needs at least one point" }
        require(points.map { it.index } == points.indices.toList()) { "Point indexes must be 0..n-1 in order" }
        require(points.zipWithNext().all { (a, b) -> a.metres < b.metres }) { "Points must increase in distance" }
    }

    /** Out-and-back distance for a round that uses the first [pointsUsed] points. */
    public fun roundMetres(pointsUsed: Int): Int = points.take(pointsUsed).sumOf { it.metres * 2 }
}

/** A movement done at the origin between legs. */
@Serializable
public data class OriginMovement(val name: String, val reps: Int)

/** Builds a lines plan: rounds of (P1 out-back, P2 out-back, …) with rest between rounds. */
public object LinesPlanBuilder {
    public fun build(
        id: String,
        course: LinesCourse,
        pointsUsed: Int,
        rounds: Int,
        restSeconds: Int,
        origin: OriginMovement? = null,
        prepSeconds: Int = 10,
    ): IntervalPlan {
        require(pointsUsed in 1..course.points.size) { "pointsUsed must be 1..${course.points.size}" }
        require(rounds >= 1)
        val phases = buildList {
            add(Phase("Get ready", PhaseKind.PREP, seconds = prepSeconds))
            repeat(rounds) { round ->
                course.points.take(pointsUsed).forEachIndexed { i, p ->
                    add(Phase("${p.label} out and back", PhaseKind.LEG, pointIndex = p.index))
                    if (origin != null && i < pointsUsed - 1) {
                        add(Phase(origin.name, PhaseKind.ORIGIN, reps = origin.reps))
                    }
                }
                if (round < rounds - 1 && restSeconds > 0) add(Phase("Rest", PhaseKind.REST, seconds = restSeconds))
            }
        }
        val originSuffix = origin?.let { " + ${it.reps} ${it.name}" } ?: ""
        return IntervalPlan(id, "Lines P1 to P$pointsUsed × $rounds$originSuffix", isPreset = false, phases)
    }
}

/** Ready-made plans. IDs are stable so runs can reference them across app updates. */
public object Presets {
    public fun tabata(): IntervalPlan = workRest("preset.tabata", "Tabata 20/10 × 8", work = 20, rest = 10, rounds = 8)

    public fun thirtyThirty(): IntervalPlan =
        workRest("preset.30-30", "30/30 × 10", work = 30, rest = 30, rounds = 10)

    public fun emom(minutes: Int = 10): IntervalPlan = IntervalPlan(
        id = "preset.emom-$minutes",
        name = "EMOM $minutes",
        isPreset = true,
        phases = listOf(Phase("Get ready", PhaseKind.PREP, 10)) +
            List(minutes) { Phase("Minute ${it + 1}", PhaseKind.WORK, 60) },
    )

    public fun restTimer(seconds: Int = 90): IntervalPlan = IntervalPlan(
        id = "preset.rest-$seconds",
        name = "Rest ${seconds}s",
        isPreset = true,
        phases = listOf(Phase("Rest", PhaseKind.REST, seconds)),
    )

    public fun all(): List<IntervalPlan> = listOf(tabata(), thirtyThirty(), emom(), restTimer())

    public fun workRest(
        id: String,
        name: String,
        work: Int,
        rest: Int,
        rounds: Int,
        prep: Int = 10,
        isPreset: Boolean = true,
    ): IntervalPlan = IntervalPlan(
        id = id,
        name = name,
        isPreset = isPreset,
        phases = buildList {
            add(Phase("Get ready", PhaseKind.PREP, prep))
            repeat(rounds) { r ->
                add(Phase("Work ${r + 1}/$rounds", PhaseKind.WORK, work))
                if (r < rounds - 1) add(Phase("Rest", PhaseKind.REST, rest))
            }
        },
    )
}
