package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.WeightRepository
import com.tfitapp.domain.nutrition.WeightEntry
import com.tfitapp.infrastructure.room.dao.WeightDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomWeightRepository(private val dao: WeightDao) : WeightRepository {
    override suspend fun upsert(entry: WeightEntry) = dao.upsert(entry.toEntity())
    override fun observeAll(): Flow<List<WeightEntry>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    override suspend fun all(): List<WeightEntry> = dao.all().map { it.toDomain() }
}
