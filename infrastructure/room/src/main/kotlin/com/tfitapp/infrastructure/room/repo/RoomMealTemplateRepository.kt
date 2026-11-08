package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.MealItem
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.infrastructure.room.dao.MealTemplateDao
import com.tfitapp.infrastructure.room.entity.MealTemplateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

class RoomMealTemplateRepository(private val dao: MealTemplateDao) : MealTemplateRepository {
    override fun observeActive(): Flow<List<MealTemplate>> = dao.observeActive().map { rows -> rows.map(::decode) }
    override suspend fun active(): List<MealTemplate> = dao.active().map(::decode)
    override suspend fun byNameKey(nameKey: String): MealTemplate? = dao.byNameKey(nameKey)?.let(::decode)
    override suspend fun archive(id: String, at: Instant) = dao.archive(id, at.toEpochMilli())

    override suspend fun upsert(template: MealTemplate) = dao.upsert(
        MealTemplateEntity(
            id = template.id,
            name = template.name,
            nameKey = template.nameKey,
            itemsJson = roomJson.encodeToString(ListSerializer(MealItem.serializer()), template.items),
            createdAtMs = template.createdAt.toEpochMilli(),
            archivedAtMs = template.archivedAt?.toEpochMilli(),
        ),
    )

    private fun decode(row: MealTemplateEntity) = MealTemplate(
        id = row.id,
        name = row.name,
        items = roomJson.decodeFromString(ListSerializer(MealItem.serializer()), row.itemsJson),
        createdAt = Instant.ofEpochMilli(row.createdAtMs),
        archivedAt = row.archivedAtMs?.let(Instant::ofEpochMilli),
    )
}
