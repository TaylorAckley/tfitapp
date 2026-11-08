package com.tfitapp.infrastructure.room.repo

import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.Portion
import com.tfitapp.domain.nutrition.WeightEntry
import com.tfitapp.infrastructure.room.entity.FoodEntity
import com.tfitapp.infrastructure.room.entity.FoodLogEntity
import com.tfitapp.infrastructure.room.entity.WeightEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/** One JSON configuration for every column that stores a serialized value. */
internal val roomJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun Food.toEntity() = FoodEntity(
    id = id, name = name, kcalPer100g = kcalPer100g, proteinPer100g = proteinPer100g,
    carbsPer100g = carbsPer100g, fatPer100g = fatPer100g,
    portionsJson = roomJson.encodeToString(ListSerializer(Portion.serializer()), portions),
    origin = origin.name, externalId = externalId, archived = archived,
)

internal fun FoodEntity.toDomain() = Food(
    id = id, name = name, kcalPer100g = kcalPer100g, proteinPer100g = proteinPer100g,
    carbsPer100g = carbsPer100g, fatPer100g = fatPer100g,
    portions = roomJson.decodeFromString(ListSerializer(Portion.serializer()), portionsJson),
    origin = FoodOrigin.valueOf(origin), externalId = externalId, archived = archived,
)

internal fun FoodLogEntry.toEntity() = FoodLogEntity(
    id = id, loggedAtMs = loggedAt.toEpochMilli(), localDate = localDate.toString(), mealSlot = mealSlot.name,
    foodId = foodId, label = label, grams = grams, kcal = kcal, protein = protein, source = source.name,
    jevConfidence = jevConfidence, userOverrode = userOverrode, deletedAtMs = deletedAt?.toEpochMilli(),
)

internal fun FoodLogEntity.toDomain() = FoodLogEntry(
    id = id, loggedAt = Instant.ofEpochMilli(loggedAtMs), localDate = LocalDate.parse(localDate), mealSlot = MealSlot.valueOf(mealSlot),
    foodId = foodId, label = label, grams = grams, kcal = kcal, protein = protein, source = EntrySource.valueOf(source),
    jevConfidence = jevConfidence, userOverrode = userOverrode, deletedAt = deletedAtMs?.let(Instant::ofEpochMilli),
)

internal fun WeightEntry.toEntity() = WeightEntity(id, at.toEpochMilli(), localDate.toString(), kg)
internal fun WeightEntity.toDomain() = WeightEntry(id, Instant.ofEpochMilli(atMs), LocalDate.parse(localDate), kg)
