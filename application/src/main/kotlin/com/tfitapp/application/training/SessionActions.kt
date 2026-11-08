package com.tfitapp.application.training

import com.tfitapp.application.port.ExerciseRepository
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.LinesPlanBuilder
import com.tfitapp.domain.training.ComposeContext
import com.tfitapp.domain.training.Composer
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionBlock
import com.tfitapp.domain.training.SetLog
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Swap one block's movement for the next candidate in the same pattern. No Jev. */
public class SwapBlock @Inject constructor(
    private val sessions: SessionRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    public suspend operator fun invoke(session: Session, blockId: String): Session {
        val s = settings.current()
        val library = exercises.all()
        val composer = Composer(library, s.equipment)
        val block = session.candidate.blocks.first { it.id == blockId }
        val ctx = ComposeContext(
            today = clock.now().atZone(zone).toLocalDate(),
            outdoorFeasible = session.candidate.location == com.tfitapp.domain.training.Location.OUTDOOR,
            timeBudgetMinutes = s.timeBudgetMinutes,
            lastUsed = sessions.lastUsed(),
            lastPerformance = sessions.lastPerformances(),
            linesCourse = s.linesCourse,
            sledMaxSetting = s.sledMaxSetting,
            newId = { UUID.randomUUID().toString() },
        )
        val alt = composer.alternative(block, session.candidate.location, ctx) ?: return session
        // Swapping out three times in a row down-weights the movement (design basis §5.2).
        block.exerciseIds.firstOrNull()?.let { swappedId ->
            library.firstOrNull { it.id == swappedId }?.let { ex ->
                exercises.upsert(ex.copy(preferenceWeight = (ex.preferenceWeight * 0.9).coerceAtLeast(0.3)))
            }
        }
        val newBlock = block.copy(
            exerciseIds = listOf(alt.id),
            title = alt.name,
            prescription = when (val p = block.prescription) {
                is Prescription.Circuit -> p.copy(movementNames = listOf(alt.name) + p.movementNames.drop(1))
                else -> p
            },
        )
        val updated = session.copy(candidate = session.candidate.copy(blocks = session.candidate.blocks.map { if (it.id == blockId) newBlock else it }))
        sessions.upsert(updated)
        return updated
    }
}

public class RecordSet @Inject constructor(
    private val sessions: SessionRepository,
    private val clock: WallClock,
) {
    public suspend operator fun invoke(session: Session, block: SessionBlock, ordinal: Int, reps: Int?, sledSetting: Int?, rpe: Int?, id: String = UUID.randomUUID().toString()) {
        val prescription = block.prescription
        sessions.upsertSetLog(
            SetLog(
                id = id,
                sessionId = session.id,
                blockId = block.id,
                exerciseId = block.exerciseIds.first(),
                ordinal = ordinal,
                reps = reps,
                loadLabel = (prescription as? Prescription.SetsReps)?.loadLabel,
                sledSetting = sledSetting ?: (prescription as? Prescription.Intervals)?.sledSetting,
                rpe = rpe,
                at = clock.now(),
            ),
        )
    }
}

public class StartOrFinishSession @Inject constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val clock: WallClock,
) {
    public suspend fun start(session: Session): Session {
        val updated = session.copy(startedAt = session.startedAt ?: clock.now())
        sessions.upsert(updated)
        return updated
    }

    public suspend fun finish(session: Session): Session {
        val updated = session.copy(startedAt = session.startedAt ?: clock.now(), finishedAt = clock.now())
        sessions.upsert(updated)
        settings.update { it.copy(lastSlotId = session.slotId) }
        return updated
    }

    public suspend fun choose(session: Session, candidateId: String): Session {
        val all = listOf(session.candidate) + session.alternatives
        val chosen = all.firstOrNull { it.id == candidateId } ?: return session
        val updated = session.copy(candidate = chosen, alternatives = all.filter { it.id != candidateId })
        sessions.upsert(updated)
        return updated
    }
}

/** Turns a block's prescription into something the timer can run. Null for plain sets × reps. */
public class PlanForBlock @Inject constructor(private val settings: SettingsRepository) {
    public suspend operator fun invoke(block: SessionBlock): IntervalPlan? = when (val p = block.prescription) {
        is Prescription.Intervals -> p.plan
        is Prescription.Circuit -> p.plan
        is Prescription.Lines -> settings.current().linesCourse?.let { course ->
            LinesPlanBuilder.build(UUID.randomUUID().toString(), course, p.pointsUsed, p.rounds, p.restSeconds, p.origin)
        }
        is Prescription.SetsReps -> null
    }
}
