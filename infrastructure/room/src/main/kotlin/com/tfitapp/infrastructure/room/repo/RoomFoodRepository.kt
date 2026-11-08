package com.tfitapp.infrastructure.room.repo

import android.content.Context
import com.tfitapp.application.port.FoodRepository
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.infrastructure.room.dao.FoodDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Local foods. On first use the bundled seed (`assets/seed_foods.json`) is loaded;
 * seeding is idempotent because seed ids are stable and the write is an upsert.
 */
class RoomFoodRepository(
    private val dao: FoodDao,
    private val context: Context,
) : FoodRepository {

    private val seedLock = Mutex()
    private var seeded = false

    override suspend fun search(query: String, limit: Int): List<Food> {
        ensureSeeded()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
        val fts = if (tokens.isEmpty()) emptyList() else runCatching {
            dao.searchFts(tokens.joinToString(" ") { "$it*" }, limit)
        }.getOrDefault(emptyList())
        val results = if (fts.isNotEmpty()) fts else dao.searchLike(needle, limit)
        return results.map { it.toDomain() }
    }

    override suspend fun get(id: String): Food? = dao.get(id)?.toDomain()
    override suspend fun byExternalId(externalId: String): Food? = dao.byExternalId(externalId)?.toDomain()
    override suspend fun upsert(food: Food) = dao.upsert(food.toEntity())
    override suspend fun favourites(limit: Int): List<Food> = dao.favourites(limit).map { it.toDomain() }

    private suspend fun ensureSeeded() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            if (dao.count() == 0) {
                val json = context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
                val foods = roomJson.decodeFromString(ListSerializer(Food.serializer()), json)
                dao.upsertAll(foods.map { it.toEntity() })
            }
            seeded = true
        }
    }

    private companion object {
        const val SEED_ASSET = "seed_foods.json"
    }
}
