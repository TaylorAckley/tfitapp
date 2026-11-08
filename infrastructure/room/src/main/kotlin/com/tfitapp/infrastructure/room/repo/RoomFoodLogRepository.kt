package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.infrastructure.room.dao.FoodLogDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class RoomFoodLogRepository(private val dao: FoodLogDao) : FoodLogRepository {
    override suspend fun upsert(entry: FoodLogEntry) = dao.upsert(entry.toEntity())

    override fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>> =
        dao.observeDay(date.toString()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun between(from: LocalDate, to: LocalDate): List<FoodLogEntry> =
        dao.between(from.toString(), to.toString()).map { it.toDomain() }

    override suspend fun softDelete(id: String, at: Instant) = dao.softDelete(id, at.toEpochMilli())
}
