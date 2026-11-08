package com.tfitapp.domain.nutrition

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Serializable
public enum class MealSlot(public val displayName: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack"),
    ;

    public companion object {
        /** Best-guess slot from the time of day; the user can change it. */
        public fun forTime(time: LocalTime): MealSlot = when (time.hour) {
            in 4..10 -> BREAKFAST
            in 11..14 -> LUNCH
            in 17..21 -> DINNER
            else -> SNACK
        }
    }
}

/** How an entry got its numbers — kept so weekly stats can show how much was estimated. */
@Serializable
public enum class EntrySource { JEV, MANUAL, QUICK, COPY }

/**
 * One logged item. [id] is generated the moment the user starts the action, so a
 * retried save is an upsert of the same row, not a duplicate.
 */
public data class FoodLogEntry(
    val id: String,
    val loggedAt: Instant,
    /** The user's calendar day at the time of logging; never recomputed from [loggedAt]. */
    val localDate: LocalDate,
    val mealSlot: MealSlot,
    val foodId: String?,
    val label: String,
    val grams: Double?,
    val kcal: Double,
    val protein: Double,
    val source: EntrySource,
    val jevConfidence: Double? = null,
    val userOverrode: Boolean = false,
    val deletedAt: Instant? = null,
) {
    init {
        require(kcal >= 0 && protein >= 0)
    }
}

public data class WeightEntry(val id: String, val at: Instant, val localDate: LocalDate, val kg: Double) {
    init {
        require(kg > 0)
    }
}
