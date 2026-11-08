package com.tfitapp.domain.nutrition

import kotlinx.serialization.Serializable
import java.time.Instant

/** One line of a saved meal. Numbers are frozen at save time so a later food edit does not rewrite history. */
@Serializable
public data class MealItem(
    /** Null for items that were quick-add estimates. */
    val foodId: String?,
    val label: String,
    val grams: Double?,
    val kcal: Double,
    val protein: Double,
) {
    init {
        require(kcal >= 0 && protein >= 0)
    }
}

/**
 * A meal the user eats often enough to name: "weekday breakfast". Design basis §4.4
 * ("Usual"). The name is the natural key — saving the same name again replaces the
 * items, so a template is never duplicated.
 */
public data class MealTemplate(
    val id: String,
    val name: String,
    val items: List<MealItem>,
    val createdAt: Instant,
    val archivedAt: Instant? = null,
) {
    init {
        require(name.isNotBlank()) { "A meal needs a name" }
        require(items.isNotEmpty()) { "A meal needs at least one item" }
    }

    public val nameKey: String get() = nameKey(name)
    public val kcal: Double get() = items.sumOf { it.kcal }
    public val protein: Double get() = items.sumOf { it.protein }

    public companion object {
        /** Case- and whitespace-insensitive form of a name, for the unique index and for matching. */
        public fun nameKey(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

        public fun fromEntries(id: String, name: String, entries: List<FoodLogEntry>, at: Instant): MealTemplate = MealTemplate(
            id = id,
            name = name.trim(),
            items = entries.filter { it.deletedAt == null }.map { MealItem(it.foodId, it.label, it.grams, it.kcal, it.protein) },
            createdAt = at,
        )
    }
}
