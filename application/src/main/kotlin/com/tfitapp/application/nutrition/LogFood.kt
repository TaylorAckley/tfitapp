package com.tfitapp.application.nutrition

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.FoodRepository
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.time.WallClock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** A confirmed row, after the user has had the chance to override the proposal. */
public data class ConfirmedRow(
    /** Generated when the row was proposed; the same id twice is the same entry. */
    val entryId: String,
    val row: ProposedRow,
    val userOverrode: Boolean,
    /** For quick-add rows: the kcal the user typed. */
    val quickKcal: Double? = null,
    val quickProtein: Double? = null,
)

public class LogFoodRows @Inject constructor(
    private val log: FoodLogRepository,
    private val foods: FoodRepository,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    public suspend operator fun invoke(rows: List<ConfirmedRow>, slot: MealSlot?, jevUsed: Boolean) {
        val now = clock.now()
        val local = now.atZone(zone)
        val mealSlot = slot ?: MealSlot.forTime(local.toLocalTime())
        rows.forEach { c ->
            val food = c.row.selected
            val fromMeal = c.row.mealName != null && !c.userOverrode
            val entry = if (food == null || c.row.quickAdd) {
                // An estimate copied out of a saved meal is still an estimate; QUICK keeps the weekly share honest.
                FoodLogEntry(
                    id = c.entryId, loggedAt = now, localDate = local.toLocalDate(), mealSlot = mealSlot,
                    foodId = null, label = c.row.fragment.text, grams = null,
                    kcal = c.quickKcal ?: c.row.fixedKcal ?: 0.0, protein = c.quickProtein ?: c.row.fixedProtein ?: 0.0,
                    source = EntrySource.QUICK, jevConfidence = null, userOverrode = c.userOverrode,
                )
            } else {
                // Anything that came from the FDC API is now a local food, so it is found offline next time.
                if (food.origin == FoodOrigin.FDC && foods.get(food.id) == null) foods.upsert(food)
                val grams = c.row.grams ?: food.defaultPortion.grams
                FoodLogEntry(
                    id = c.entryId, loggedAt = now, localDate = local.toLocalDate(), mealSlot = mealSlot,
                    foodId = food.id, label = food.name, grams = grams,
                    kcal = food.kcalFor(grams), protein = food.proteinFor(grams),
                    source = when {
                        fromMeal -> EntrySource.COPY
                        jevUsed && !c.userOverrode -> EntrySource.JEV
                        else -> EntrySource.MANUAL
                    },
                    jevConfidence = c.row.matchConfidence, userOverrode = c.userOverrode,
                )
            }
            log.upsert(entry)
        }
    }
}

public class QuickAddFood @Inject constructor(
    private val log: FoodLogRepository,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    public suspend operator fun invoke(label: String, kcal: Double, protein: Double?, slot: MealSlot?, entryId: String = UUID.randomUUID().toString()) {
        val now = clock.now()
        val local = now.atZone(zone)
        log.upsert(
            FoodLogEntry(
                id = entryId, loggedAt = now, localDate = local.toLocalDate(),
                mealSlot = slot ?: MealSlot.forTime(local.toLocalTime()),
                foodId = null, label = label, grams = null, kcal = kcal, protein = protein ?: 0.0,
                source = EntrySource.QUICK,
            ),
        )
    }
}
