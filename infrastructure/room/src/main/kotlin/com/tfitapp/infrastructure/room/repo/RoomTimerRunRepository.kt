package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.TimerRun
import com.tfitapp.application.port.TimerRunRepository
import com.tfitapp.domain.timer.Split
import com.tfitapp.infrastructure.room.dao.TimerRunDao
import com.tfitapp.infrastructure.room.entity.TimerRunEntity
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

class RoomTimerRunRepository(private val dao: TimerRunDao) : TimerRunRepository {
    override suspend fun upsert(run: TimerRun) = dao.upsert(
        TimerRunEntity(
            id = run.id, planId = run.planId, planName = run.planName, sessionBlockId = run.sessionBlockId,
            startedAtMs = run.startedAt.toEpochMilli(), finishedAtMs = run.finishedAt?.toEpochMilli(),
            completedPhases = run.completedPhases, pausedTotalMs = run.pausedTotalMs,
            splitsJson = roomJson.encodeToString(ListSerializer(Split.serializer()), run.splits),
        ),
    )

    override suspend fun recent(limit: Int): List<TimerRun> = dao.recent(limit).map {
        TimerRun(
            id = it.id, planId = it.planId, planName = it.planName, sessionBlockId = it.sessionBlockId,
            startedAt = Instant.ofEpochMilli(it.startedAtMs), finishedAt = it.finishedAtMs?.let(Instant::ofEpochMilli),
            completedPhases = it.completedPhases, pausedTotalMs = it.pausedTotalMs,
            splits = roomJson.decodeFromString(ListSerializer(Split.serializer()), it.splitsJson),
        )
    }
}
