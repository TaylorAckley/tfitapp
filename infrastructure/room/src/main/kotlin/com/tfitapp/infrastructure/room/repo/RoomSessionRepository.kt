package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.SessionRepository
import com.tfitapp.domain.training.BlockFeedback
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.domain.training.LastPerformance
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionCandidate
import com.tfitapp.domain.training.SetLog
import com.tfitapp.infrastructure.room.dao.SessionDao
import com.tfitapp.infrastructure.room.entity.BlockFeedbackEntity
import com.tfitapp.infrastructure.room.entity.SessionEntity
import com.tfitapp.infrastructure.room.entity.SetLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate

class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {

    override suspend fun upsert(session: Session) {
        val existing = dao.get(session.id)
        dao.upsert(
            SessionEntity(
                id = session.id,
                plannedFor = session.plannedFor.toString(),
                slotId = session.slotId,
                candidateJson = roomJson.encodeToString(SessionCandidate.serializer(), session.candidate),
                alternativesJson = roomJson.encodeToString(ListSerializer(SessionCandidate.serializer()), session.alternatives),
                checkInText = session.checkInText,
                jevConfidence = session.jevConfidence,
                startedAtMs = session.startedAt?.toEpochMilli(),
                finishedAtMs = session.finishedAt?.toEpochMilli(),
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                checkOutText = session.checkOutText,
            ),
        )
    }

    override suspend fun get(id: String): Session? = dao.get(id)?.let(::decode)
    override fun observeLatest(): Flow<Session?> = dao.observeLatest().map { it?.let(::decode) }
    override suspend fun recent(limit: Int): List<Session> = dao.recent(limit).map(::decode)

    override suspend fun upsertSetLog(log: SetLog) = dao.upsertSetLog(
        SetLogEntity(log.id, log.sessionId, log.blockId, log.exerciseId, log.ordinal, log.reps, log.loadLabel, log.sledSetting, log.rpe, log.at.toEpochMilli()),
    )

    override suspend fun setLogs(sessionId: String): List<SetLog> = dao.setLogs(sessionId).map(::decode)

    override suspend fun replaceFeedback(sessionId: String, feedback: List<BlockFeedback>) =
        dao.replaceFeedback(sessionId, feedback.map { BlockFeedbackEntity(it.id, it.sessionId, it.blockId, it.exerciseId, it.feel.name, it.source.name, it.at.toEpochMilli()) })

    override suspend fun feedback(sessionId: String): List<BlockFeedback> = dao.feedback(sessionId).map(::decode)

    override suspend fun lastUsed(): Map<String, LocalDate> {
        val finished = dao.recentFinished(HISTORY_LIMIT).map(::decode)
        return buildMap {
            finished.forEach { s ->
                s.candidate.blocks.flatMap { it.exerciseIds }.forEach { id ->
                    if (id !in this) put(id, s.plannedFor)
                }
            }
        }
    }

    /**
     * Reconstructs "what happened last time" from finished sessions and their set logs.
     * Consecutive misses count backwards from the most recent occurrence.
     */
    override suspend fun lastPerformances(): Map<String, LastPerformance> {
        val finished = dao.recentFinished(HISTORY_LIMIT).map(::decode)
        if (finished.isEmpty()) return emptyMap()
        val logs = dao.setLogsFor(finished.map { it.id }).map(::decode).groupBy { it.sessionId }
        val feedback = dao.feedbackFor(finished.map { it.id }).map(::decode).associateBy { it.blockId }
        val misses = mutableMapOf<String, Int>()
        val result = mutableMapOf<String, LastPerformance>()
        // Oldest first so the miss streak accumulates correctly.
        finished.sortedBy { it.finishedAt }.forEach { s ->
            val sessionLogs = logs[s.id].orEmpty()
            s.candidate.blocks.forEach { block ->
                val exerciseId = block.exerciseIds.firstOrNull() ?: return@forEach
                val blockLogs = sessionLogs.filter { it.blockId == block.id }
                val (target, achieved, sled) = when (val p = block.prescription) {
                    is Prescription.SetsReps -> Triple(p.repsTarget, blockLogs.mapNotNull { it.reps }, null)
                    is Prescription.Intervals -> Triple(null, emptyList(), p.sledSetting ?: blockLogs.firstNotNullOfOrNull { it.sledSetting })
                    else -> Triple(null, emptyList(), null)
                }
                val lines = block.prescription as? Prescription.Lines
                val completed = when (block.prescription) {
                    is Prescription.SetsReps -> target != null && achieved.size >= (block.prescription as Prescription.SetsReps).sets && achieved.all { it >= target }
                    else -> blockLogs.isNotEmpty()
                }
                val streak = if (completed) 0 else (misses[exerciseId] ?: 0) + 1
                misses[exerciseId] = streak
                result[exerciseId] = LastPerformance(
                    exerciseId = exerciseId,
                    date = s.plannedFor,
                    repsTarget = target,
                    repsAchieved = achieved,
                    sledSetting = sled,
                    linesPointsUsed = lines?.pointsUsed,
                    linesRounds = lines?.rounds,
                    consecutiveMisses = streak,
                    feel = feedback[block.id]?.feel,
                )
            }
        }
        return result
    }

    private fun decode(row: SessionEntity) = Session(
        id = row.id,
        plannedFor = LocalDate.parse(row.plannedFor),
        slotId = row.slotId,
        candidate = roomJson.decodeFromString(SessionCandidate.serializer(), row.candidateJson),
        alternatives = roomJson.decodeFromString(ListSerializer(SessionCandidate.serializer()), row.alternativesJson),
        checkInText = row.checkInText,
        jevConfidence = row.jevConfidence,
        startedAt = row.startedAtMs?.let(Instant::ofEpochMilli),
        finishedAt = row.finishedAtMs?.let(Instant::ofEpochMilli),
        checkOutText = row.checkOutText,
    )

    private fun decode(row: BlockFeedbackEntity) = BlockFeedback(
        row.sessionId, row.blockId, row.exerciseId, BlockFeel.valueOf(row.feel), FeedbackSource.valueOf(row.source), Instant.ofEpochMilli(row.atMs),
    )

    private fun decode(row: SetLogEntity) = SetLog(
        row.id, row.sessionId, row.blockId, row.exerciseId, row.ordinal, row.reps, row.loadLabel, row.sledSetting, row.rpe, Instant.ofEpochMilli(row.atMs),
    )

    private companion object {
        const val HISTORY_LIMIT = 40
    }
}
