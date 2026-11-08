package com.tfitapp.application.port

import com.tfitapp.application.settings.UserSettings
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.nutrition.WeightEntry
import com.tfitapp.domain.timer.Split
import com.tfitapp.domain.training.BlockFeedback
import com.tfitapp.domain.training.Exercise
import com.tfitapp.domain.training.Forecast
import com.tfitapp.domain.training.LastPerformance
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SetLog
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration

public enum class SecretKey {
    TYPESAFE_API_KEY, FDC_API_KEY,
    SUPABASE_URL, SUPABASE_ANON_KEY, SUPABASE_ACCESS_TOKEN, SUPABASE_REFRESH_TOKEN,
}

/** Keystore-backed on Android. Never log a value read from here. */
public interface SecretStore {
    public suspend fun get(key: SecretKey): String?
    public suspend fun set(key: SecretKey, value: String?)
    public fun observeIsSet(key: SecretKey): Flow<Boolean>
}

public interface SettingsRepository {
    public val settings: Flow<UserSettings>
    public suspend fun current(): UserSettings
    public suspend fun update(transform: (UserSettings) -> UserSettings)
}

/** Local foods: bundled seed, FDC cache, and user-created. */
public interface FoodRepository {
    public suspend fun search(query: String, limit: Int): List<Food>
    public suspend fun get(id: String): Food?
    public suspend fun byExternalId(externalId: String): Food?
    public suspend fun upsert(food: Food)
    /** Most-logged foods first, for the favourites shortcut. */
    public suspend fun favourites(limit: Int): List<Food>
}

/** Remote lookup — the FDC API. Only called on a local miss. */
public interface FoodLookupPort {
    public suspend fun search(query: String, limit: Int, timeout: Duration): List<Food>
}

public interface FoodLogRepository {
    public suspend fun upsert(entry: FoodLogEntry)
    public fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>>
    public suspend fun between(from: LocalDate, to: LocalDate): List<FoodLogEntry>
    public suspend fun softDelete(id: String, at: Instant)
}

public interface WeightRepository {
    public suspend fun upsert(entry: WeightEntry)
    public fun observeAll(): Flow<List<WeightEntry>>
    public suspend fun all(): List<WeightEntry>
}

/** Saved meals (design basis §4.4 "Usual"). Archived templates stay for history; queries hide them. */
public interface MealTemplateRepository {
    public fun observeActive(): Flow<List<MealTemplate>>
    public suspend fun active(): List<MealTemplate>
    public suspend fun byNameKey(nameKey: String): MealTemplate?
    public suspend fun upsert(template: MealTemplate)
    public suspend fun archive(id: String, at: Instant)
}

public interface ForecastPort {
    public suspend fun hourly(latitude: Double, longitude: Double, timeout: Duration): Forecast?
}

public interface ExerciseRepository {
    public fun observeAll(): Flow<List<Exercise>>
    public suspend fun all(): List<Exercise>
    public suspend fun upsert(exercise: Exercise)
}

public interface SessionRepository {
    public suspend fun upsert(session: Session)
    public suspend fun get(id: String): Session?
    public fun observeLatest(): Flow<Session?>
    public suspend fun recent(limit: Int): List<Session>
    public suspend fun upsertSetLog(log: SetLog)
    public suspend fun setLogs(sessionId: String): List<SetLog>
    /** Most recent date each exercise appeared in a finished session. */
    public suspend fun lastUsed(): Map<String, LocalDate>
    public suspend fun lastPerformances(): Map<String, LastPerformance>
    /** Sets the session's check-out verdicts as a whole; a second save with the same set changes nothing. */
    public suspend fun replaceFeedback(sessionId: String, feedback: List<BlockFeedback>)
    public suspend fun feedback(sessionId: String): List<BlockFeedback>
}

public data class TimerRun(
    val id: String,
    val planId: String,
    val planName: String,
    val sessionBlockId: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val completedPhases: Int,
    val pausedTotalMs: Long,
    val splits: List<Split>,
)

public interface TimerRunRepository {
    public suspend fun upsert(run: TimerRun)
    public suspend fun recent(limit: Int): List<TimerRun>
}
