package com.tfitapp.domain.training

import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.LinesCourse
import com.tfitapp.domain.timer.LinesPlanBuilder
import com.tfitapp.domain.timer.OriginMovement
import com.tfitapp.domain.timer.Phase
import com.tfitapp.domain.timer.PhaseKind
import com.tfitapp.domain.timer.Presets
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Everything the composer needs to know about today. Gathered by the use case. */
public data class ComposeContext(
    val today: LocalDate,
    val outdoorFeasible: Boolean,
    val timeBudgetMinutes: Int,
    val lastUsed: Map<String, LocalDate>,
    val lastPerformance: Map<String, LastPerformance>,
    val linesCourse: LinesCourse?,
    val sledMaxSetting: Int = 3,
    val newId: () -> String,
)

/**
 * The rules engine. Filters the library by equipment, location and exclusions,
 * rotates movements by staleness and preference, prescribes by load model, and
 * returns three candidates that differ on one axis each. No model is consulted;
 * Jev ranks the output later.
 */
public class Composer(
    private val library: List<Exercise>,
    equipment: List<Equipment>,
) {
    private val presentKinds: Set<EquipmentKind> = equipment.filter { it.present }.map { it.kind }.toSet() + EquipmentKind.BODY

    public fun compose(slot: SlotTemplate, ctx: ComposeContext): List<SessionCandidate> {
        val primaryLocation = if (ctx.outdoorFeasible) Location.OUTDOOR else Location.INDOOR
        val planned = build(slot, primaryLocation, ctx, CandidateAxis.DEFAULT, lighter = false)
        val shorter = shorten(planned, ctx.timeBudgetMinutes)
        val third = if (ctx.outdoorFeasible) build(slot, Location.INDOOR, ctx, CandidateAxis.INDOOR, lighter = false)
        else build(slot, Location.INDOOR, ctx, CandidateAxis.LIGHTER, lighter = true)
        return listOf(planned, shorter, third)
    }

    /** Next best movement for the same pattern, used by Swap. Never asks Jev. */
    public fun alternative(block: SessionBlock, location: Location, ctx: ComposeContext): Exercise? =
        rank(block.pattern, location, ctx, exclude = block.exerciseIds.toSet()).firstOrNull()

    private fun build(slot: SlotTemplate, location: Location, ctx: ComposeContext, axis: CandidateAxis, lighter: Boolean): SessionCandidate {
        val used = mutableSetOf<String>()
        val blocks = slot.blocks.mapIndexedNotNull { i, spec -> block(i, spec, location, ctx, used, lighter) }
        return SessionCandidate(
            id = ctx.newId(),
            axis = axis,
            title = slot.name,
            location = location,
            blocks = blocks,
        )
    }

    private fun block(ordinal: Int, spec: BlockSpec, location: Location, ctx: ComposeContext, used: MutableSet<String>, lighter: Boolean): SessionBlock? {
        val effective = if (lighter) spec.copy(sets = maxOf(spec.sets - 1, 1), rounds = maxOf(spec.rounds - 1, 1)) else spec
        val resolved = when (effective.kind) {
            BlockKind.LINES -> if (ctx.linesCourse != null && location == Location.OUTDOOR) effective
            else effective.copy(kind = BlockKind.CONDITIONING, pattern = Pattern.CONDITIONING, rounds = 6, workSeconds = 30, restSeconds = 30, estimatedMinutes = 8)
            else -> effective
        }
        return when (resolved.kind) {
            BlockKind.WARMUP, BlockKind.CIRCUIT -> circuit(ordinal, resolved, location, ctx, used)
            BlockKind.STRENGTH -> strength(ordinal, resolved, location, ctx, used)
            BlockKind.SLED -> sled(ordinal, resolved, location, ctx, used)
            BlockKind.LINES -> lines(ordinal, resolved, ctx)
            BlockKind.CONDITIONING -> conditioning(ordinal, resolved, location, ctx, used)
        }
    }

    private fun circuit(ordinal: Int, spec: BlockSpec, location: Location, ctx: ComposeContext, used: MutableSet<String>): SessionBlock? {
        val count = if (spec.kind == BlockKind.WARMUP) 2 else spec.movements
        val picks = rank(spec.pattern, location, ctx, used).take(count).ifEmpty { return null }
        used += picks.map { it.id }
        val phases = buildList {
            add(Phase("Get ready", PhaseKind.PREP, 10))
            repeat(spec.rounds) { r ->
                picks.forEachIndexed { i, ex ->
                    add(Phase(ex.name, PhaseKind.WORK, spec.workSeconds))
                    val last = r == spec.rounds - 1 && i == picks.lastIndex
                    if (!last) add(Phase("Rest", PhaseKind.REST, spec.restSeconds))
                }
            }
        }
        val plan = IntervalPlan(ctx.newId(), "${if (spec.kind == BlockKind.WARMUP) "Warm-up" else "Circuit"} ×${spec.rounds}", isPreset = false, phases)
        return SessionBlock(
            id = ctx.newId(), ordinal = ordinal, kind = spec.kind, pattern = spec.pattern,
            exerciseIds = picks.map { it.id },
            title = if (spec.kind == BlockKind.WARMUP) "Warm-up" else "Circuit",
            prescription = Prescription.Circuit(picks.map { it.name }, plan),
            estimatedMinutes = spec.estimatedMinutes,
        )
    }

    private fun strength(ordinal: Int, spec: BlockSpec, location: Location, ctx: ComposeContext, used: MutableSet<String>): SessionBlock? {
        var exercise = rank(spec.pattern, location, ctx, used).firstOrNull() ?: return null
        val prescription: Prescription
        when (exercise.loadModel) {
            LoadModel.SLED -> {
                val d = Progression.sled(spec, ctx.lastPerformance[exercise.id], ctx.sledMaxSetting)
                prescription = Prescription.Intervals(Presets.workRest(ctx.newId(), exercise.name, work = 30, rest = 60, rounds = spec.sets, isPreset = false), sledSetting = d.setting)
            }
            LoadModel.TIMED -> {
                prescription = Prescription.Intervals(Presets.workRest(ctx.newId(), exercise.name, work = spec.workSeconds, rest = spec.restSeconds, rounds = spec.sets, isPreset = false))
            }
            else -> {
                val d = Progression.fixedLoad(spec, ctx.lastPerformance[exercise.id], exercise)
                d.promoteToExerciseId?.let { next -> library.firstOrNull { it.id == next && it.id !in used }?.let { exercise = it } }
                prescription = Prescription.SetsReps(spec.sets, d.repsTarget, spec.repsMax, restSeconds = 90, loadLabel = loadLabel(exercise))
            }
        }
        used += exercise.id
        return SessionBlock(ctx.newId(), ordinal, spec.kind, spec.pattern, listOf(exercise.id), exercise.name, prescription, spec.estimatedMinutes)
    }

    private fun sled(ordinal: Int, spec: BlockSpec, location: Location, ctx: ComposeContext, used: MutableSet<String>): SessionBlock? {
        if (location != Location.OUTDOOR) return conditioning(ordinal, spec.copy(pattern = Pattern.CONDITIONING, kind = BlockKind.CONDITIONING), location, ctx, used)
        val exercise = rank(spec.pattern, location, ctx, used).firstOrNull() ?: return null
        used += exercise.id
        val d = Progression.sled(spec, ctx.lastPerformance[exercise.id], ctx.sledMaxSetting)
        val plan = Presets.workRest(ctx.newId(), exercise.name, work = spec.workSeconds, rest = d.restSeconds, rounds = d.rounds, isPreset = false)
        return SessionBlock(
            ctx.newId(), ordinal, BlockKind.SLED, spec.pattern, listOf(exercise.id),
            exercise.name,
            Prescription.Intervals(plan, sledSetting = d.setting), spec.estimatedMinutes,
        )
    }

    private fun lines(ordinal: Int, spec: BlockSpec, ctx: ComposeContext): SessionBlock? {
        val course = ctx.linesCourse ?: return null
        val exercise = library.firstOrNull { Pattern.LINES in it.patterns && !it.excluded } ?: return null
        val d = Progression.lines(spec, ctx.lastPerformance[exercise.id], course.points.size)
        val origin = rank(Pattern.CONDITIONING, Location.OUTDOOR, ctx, setOf(exercise.id))
            .firstOrNull { it.loadModel == LoadModel.FIXED }
            ?.let { OriginMovement(it.name, 5) }
        LinesPlanBuilder.build(ctx.newId(), course, d.pointsUsed, d.rounds, d.restSeconds, origin) // validates
        return SessionBlock(
            ctx.newId(), ordinal, BlockKind.LINES, Pattern.LINES, listOf(exercise.id),
            exercise.name,
            Prescription.Lines(d.pointsUsed, d.rounds, d.restSeconds, origin), spec.estimatedMinutes,
        )
    }

    private fun conditioning(ordinal: Int, spec: BlockSpec, location: Location, ctx: ComposeContext, used: MutableSet<String>): SessionBlock? {
        val exercise = rank(spec.pattern, location, ctx, used).firstOrNull { it.loadModel != LoadModel.LINES } ?: return null
        used += exercise.id
        val plan = Presets.workRest(ctx.newId(), exercise.name, work = spec.workSeconds, rest = spec.restSeconds, rounds = spec.rounds, isPreset = false)
        return SessionBlock(
            ctx.newId(), ordinal, BlockKind.CONDITIONING, spec.pattern, listOf(exercise.id),
            exercise.name,
            Prescription.Intervals(plan), spec.estimatedMinutes,
        )
    }

    /** Eligible exercises for a pattern, best first: preference × staleness, ties by id. */
    private fun rank(pattern: Pattern, location: Location, ctx: ComposeContext, exclude: Set<String>): List<Exercise> =
        library.asSequence()
            .filter { !it.excluded && it.id !in exclude && pattern in it.patterns }
            .filter { location in it.locations && it.equipment.all { k -> k in presentKinds } }
            .sortedWith(compareByDescending<Exercise> { score(it, ctx) }.thenBy { it.id })
            .toList()

    private fun score(exercise: Exercise, ctx: ComposeContext): Double {
        val days = ctx.lastUsed[exercise.id]?.let { ChronoUnit.DAYS.between(it, ctx.today).toDouble() } ?: 30.0
        return exercise.preferenceWeight * (1.0 + (days / 14.0).coerceAtMost(3.0))
    }

    private fun shorten(candidate: SessionCandidate, budgetMinutes: Int): SessionCandidate {
        val target = (budgetMinutes * 0.6).toInt().coerceAtLeast(15)
        val kept = mutableListOf<SessionBlock>()
        var minutes = 0
        for (b in candidate.blocks) {
            if (minutes + b.estimatedMinutes > target && kept.size >= 2) break
            kept += b
            minutes += b.estimatedMinutes
        }
        return candidate.copy(
            id = candidate.id + ".short",
            axis = CandidateAxis.SHORTER,
            title = candidate.title,
            blocks = kept,
        )
    }

    private fun loadLabel(exercise: Exercise): String? = when {
        EquipmentKind.KETTLEBELL in exercise.equipment -> if ("10 lb" in exercise.name) "10 lb" else "45 lb"
        else -> null
    }
}
