package com.tfitapp.infrastructure.room.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** Foods of every origin live in one table; [origin] says which. Portions are JSON. */
@Entity(tableName = "food", indices = [Index("externalId", unique = true), Index("name")])
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val portionsJson: String,
    val origin: String,
    val externalId: String?,
    val archived: Boolean,
)

/** Porter stemming so "eggs" finds "Egg" and "toasted" finds "toast". */
@Fts4(contentEntity = FoodEntity::class, tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "food_fts")
data class FoodFtsEntity(val name: String)

@Entity(tableName = "food_log", indices = [Index("localDate"), Index("foodId")])
data class FoodLogEntity(
    @PrimaryKey val id: String,
    val loggedAtMs: Long,
    val localDate: String,
    val mealSlot: String,
    val foodId: String?,
    val label: String,
    val grams: Double?,
    val kcal: Double,
    val protein: Double,
    val source: String,
    val jevConfidence: Double?,
    val userOverrode: Boolean,
    val deletedAtMs: Long?,
)

@Entity(tableName = "weight", indices = [Index("localDate")])
data class WeightEntity(
    @PrimaryKey val id: String,
    val atMs: Long,
    val localDate: String,
    val kg: Double,
)

/** One row. The whole settings document as JSON; it is small and changes rarely. */
@Entity(tableName = "settings")
data class SettingsEntity(@PrimaryKey val id: Int = 1, val json: String)

@Entity(tableName = "exercise")
data class ExerciseEntity(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "session", indices = [Index("plannedFor")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val plannedFor: String,
    val slotId: String,
    val candidateJson: String,
    val alternativesJson: String,
    val checkInText: String?,
    val jevConfidence: Double?,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    /** Monotonic creation order for "latest"; wall clock can repeat. */
    val createdAtMs: Long,
    /** Added in version 2. */
    val checkOutText: String?,
)

@Entity(tableName = "set_log", indices = [Index("sessionId"), Index("exerciseId")])
data class SetLogEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val blockId: String,
    val exerciseId: String,
    val ordinal: Int,
    val reps: Int?,
    val loadLabel: String?,
    val sledSetting: Int?,
    val rpe: Int?,
    val atMs: Long,
)

@Entity(tableName = "timer_run")
data class TimerRunEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val planName: String,
    val sessionBlockId: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val completedPhases: Int,
    val pausedTotalMs: Long,
    val splitsJson: String,
)

/** A saved meal. [nameKey] is the lower-cased name and the natural key; items are JSON. Added in version 2. */
@Entity(tableName = "meal_template", indices = [Index("nameKey", unique = true)])
data class MealTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val itemsJson: String,
    val createdAtMs: Long,
    val archivedAtMs: Long?,
)

/** One block's check-out verdict; [id] is "sessionId:blockId" so a re-read replaces. Added in version 2. */
@Entity(tableName = "block_feedback", indices = [Index("sessionId"), Index("exerciseId")])
data class BlockFeedbackEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val blockId: String,
    val exerciseId: String,
    val feel: String,
    val source: String,
    val atMs: Long,
)
