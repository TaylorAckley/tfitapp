package com.tfitapp.application.nutrition

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.time.WallClock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Names a set of logged entries as a meal. The name is the key: saving "weekday
 * breakfast" again replaces its items and keeps its id, so the second save is a no-op
 * on row count.
 */
public class SaveMealTemplate @Inject constructor(
    private val meals: MealTemplateRepository,
    private val clock: WallClock,
) {
    public suspend operator fun invoke(name: String, entries: List<FoodLogEntry>): MealTemplate {
        val existing = meals.byNameKey(MealTemplate.nameKey(name))
        val template = MealTemplate.fromEntries(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            entries = entries,
            at = existing?.createdAt ?: clock.now(),
        ).copy(archivedAt = null)
        meals.upsert(template)
        return template
    }
}

/** The one-tap "Usual" path (design basis §4.4): every item of a meal goes into the log as a copy. */
public class LogMealTemplate @Inject constructor(
    private val log: FoodLogRepository,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    /** [entryIds] are fixed by the caller when the tap happens, so a retry upserts the same rows. */
    public suspend operator fun invoke(template: MealTemplate, slot: MealSlot?, entryIds: List<String> = template.items.map { UUID.randomUUID().toString() }) {
        require(entryIds.size == template.items.size) { "One entry id per item" }
        val now = clock.now()
        val local = now.atZone(zone)
        val mealSlot = slot ?: MealSlot.forTime(local.toLocalTime())
        template.items.forEachIndexed { i, item ->
            log.upsert(
                FoodLogEntry(
                    id = entryIds[i], loggedAt = now, localDate = local.toLocalDate(), mealSlot = mealSlot,
                    foodId = item.foodId, label = item.label, grams = item.grams, kcal = item.kcal, protein = item.protein,
                    source = if (item.foodId == null) EntrySource.QUICK else EntrySource.COPY,
                ),
            )
        }
    }
}

public class ArchiveMealTemplate @Inject constructor(
    private val meals: MealTemplateRepository,
    private val clock: WallClock,
) {
    public suspend operator fun invoke(id: String): Unit = meals.archive(id, clock.now())
}
