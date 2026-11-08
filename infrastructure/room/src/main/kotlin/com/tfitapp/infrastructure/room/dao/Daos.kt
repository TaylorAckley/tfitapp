package com.tfitapp.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.tfitapp.infrastructure.room.entity.BlockFeedbackEntity
import com.tfitapp.infrastructure.room.entity.ExerciseEntity
import com.tfitapp.infrastructure.room.entity.FoodEntity
import com.tfitapp.infrastructure.room.entity.FoodLogEntity
import com.tfitapp.infrastructure.room.entity.MealTemplateEntity
import com.tfitapp.infrastructure.room.entity.SessionEntity
import com.tfitapp.infrastructure.room.entity.SetLogEntity
import com.tfitapp.infrastructure.room.entity.SettingsEntity
import com.tfitapp.infrastructure.room.entity.TimerRunEntity
import com.tfitapp.infrastructure.room.entity.WeightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {
    @Upsert suspend fun upsert(food: FoodEntity)
    @Upsert suspend fun upsertAll(foods: List<FoodEntity>)
    @Query("SELECT * FROM food WHERE id = :id") suspend fun get(id: String): FoodEntity?
    @Query("SELECT * FROM food WHERE externalId = :externalId") suspend fun byExternalId(externalId: String): FoodEntity?
    @Query("SELECT COUNT(*) FROM food") suspend fun count(): Int

    /** FTS prefix match; user foods first, then shorter names (closer matches) first. */
    @Query(
        """
        SELECT food.* FROM food JOIN food_fts ON food.rowid = food_fts.rowid
        WHERE food_fts MATCH :ftsQuery AND food.archived = 0
        ORDER BY CASE food.origin WHEN 'USER' THEN 0 WHEN 'FDC' THEN 1 ELSE 2 END, LENGTH(food.name)
        LIMIT :limit
        """,
    )
    suspend fun searchFts(ftsQuery: String, limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food WHERE name LIKE '%' || :needle || '%' AND archived = 0 ORDER BY LENGTH(name) LIMIT :limit")
    suspend fun searchLike(needle: String, limit: Int): List<FoodEntity>

    @Query(
        """
        SELECT food.* FROM food JOIN (
            SELECT foodId, COUNT(*) AS n FROM food_log WHERE foodId IS NOT NULL AND deletedAtMs IS NULL GROUP BY foodId
        ) AS f ON f.foodId = food.id ORDER BY f.n DESC LIMIT :limit
        """,
    )
    suspend fun favourites(limit: Int): List<FoodEntity>
}

@Dao
interface FoodLogDao {
    @Upsert suspend fun upsert(entry: FoodLogEntity)
    @Query("SELECT * FROM food_log WHERE localDate = :date ORDER BY loggedAtMs") fun observeDay(date: String): Flow<List<FoodLogEntity>>
    @Query("SELECT * FROM food_log WHERE localDate BETWEEN :from AND :to ORDER BY loggedAtMs") suspend fun between(from: String, to: String): List<FoodLogEntity>
    @Query("UPDATE food_log SET deletedAtMs = :atMs WHERE id = :id AND deletedAtMs IS NULL") suspend fun softDelete(id: String, atMs: Long)
}

@Dao
interface WeightDao {
    @Upsert suspend fun upsert(entry: WeightEntity)
    @Query("SELECT * FROM weight ORDER BY atMs") fun observeAll(): Flow<List<WeightEntity>>
    @Query("SELECT * FROM weight ORDER BY atMs") suspend fun all(): List<WeightEntity>
}

@Dao
interface SettingsDao {
    @Upsert suspend fun upsert(row: SettingsEntity)
    @Query("SELECT * FROM settings WHERE id = 1") fun observe(): Flow<SettingsEntity?>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun get(): SettingsEntity?
}

@Dao
interface ExerciseDao {
    @Upsert suspend fun upsert(row: ExerciseEntity)
    @Upsert suspend fun upsertAll(rows: List<ExerciseEntity>)
    @Query("SELECT * FROM exercise") fun observeAll(): Flow<List<ExerciseEntity>>
    @Query("SELECT * FROM exercise") suspend fun all(): List<ExerciseEntity>
    @Query("SELECT COUNT(*) FROM exercise") suspend fun count(): Int
}

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(row: SessionEntity)
    @Query("SELECT * FROM session WHERE id = :id") suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM session ORDER BY createdAtMs DESC LIMIT 1") fun observeLatest(): Flow<SessionEntity?>
    @Query("SELECT * FROM session ORDER BY createdAtMs DESC LIMIT :limit") suspend fun recent(limit: Int): List<SessionEntity>
    @Query("SELECT * FROM session WHERE finishedAtMs IS NOT NULL ORDER BY finishedAtMs DESC LIMIT :limit") suspend fun recentFinished(limit: Int): List<SessionEntity>
    @Upsert suspend fun upsertSetLog(row: SetLogEntity)
    @Query("SELECT * FROM set_log WHERE sessionId = :sessionId ORDER BY blockId, ordinal") suspend fun setLogs(sessionId: String): List<SetLogEntity>
    @Query("SELECT * FROM set_log WHERE sessionId IN (:sessionIds) ORDER BY atMs") suspend fun setLogsFor(sessionIds: List<String>): List<SetLogEntity>

    @Upsert suspend fun upsertFeedback(rows: List<BlockFeedbackEntity>)
    @Query("DELETE FROM block_feedback WHERE sessionId = :sessionId AND id NOT IN (:keepIds)") suspend fun deleteFeedbackExcept(sessionId: String, keepIds: List<String>)
    @Query("SELECT * FROM block_feedback WHERE sessionId = :sessionId") suspend fun feedback(sessionId: String): List<BlockFeedbackEntity>
    @Query("SELECT * FROM block_feedback WHERE sessionId IN (:sessionIds)") suspend fun feedbackFor(sessionIds: List<String>): List<BlockFeedbackEntity>

    /** Delete-then-upsert in one transaction so the session's set is replaced, never merged. */
    @Transaction
    suspend fun replaceFeedback(sessionId: String, rows: List<BlockFeedbackEntity>) {
        deleteFeedbackExcept(sessionId, rows.map { it.id }.ifEmpty { listOf("") })
        if (rows.isNotEmpty()) upsertFeedback(rows)
    }
}

@Dao
interface TimerRunDao {
    @Upsert suspend fun upsert(row: TimerRunEntity)
    @Query("SELECT * FROM timer_run ORDER BY startedAtMs DESC LIMIT :limit") suspend fun recent(limit: Int): List<TimerRunEntity>
}

@Dao
interface MealTemplateDao {
    @Upsert suspend fun upsert(row: MealTemplateEntity)
    @Query("SELECT * FROM meal_template WHERE archivedAtMs IS NULL ORDER BY name") fun observeActive(): Flow<List<MealTemplateEntity>>
    @Query("SELECT * FROM meal_template WHERE archivedAtMs IS NULL ORDER BY name") suspend fun active(): List<MealTemplateEntity>
    @Query("SELECT * FROM meal_template WHERE nameKey = :nameKey") suspend fun byNameKey(nameKey: String): MealTemplateEntity?
    @Query("UPDATE meal_template SET archivedAtMs = :atMs WHERE id = :id AND archivedAtMs IS NULL") suspend fun archive(id: String, atMs: Long)
}
