package com.tfitapp.domain.nutrition

import kotlinx.serialization.Serializable

/** Where a food's numbers came from. Drives trust display and what is editable. */
@Serializable
public enum class FoodOrigin {
    /** Shipped with the app from USDA Foundation / SR Legacy. */
    BUNDLED,
    /** Fetched from the FDC API and cached locally. */
    FDC,
    /** Created or edited by the user. */
    USER,
}

/** A named serving: "1 large egg" = 50 g. */
@Serializable
public data class Portion(val label: String, val grams: Double) {
    init {
        require(grams > 0) { "Portion '$label' must have positive grams" }
    }
}

@Serializable
public data class Food(
    val id: String,
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val portions: List<Portion> = emptyList(),
    val origin: FoodOrigin,
    /** Stable key at the source (FDC id) so the same food is never fetched twice. */
    val externalId: String? = null,
    val archived: Boolean = false,
) {
    init {
        require(kcalPer100g >= 0 && proteinPer100g >= 0)
    }

    public fun kcalFor(grams: Double): Double = kcalPer100g * grams / 100.0
    public fun proteinFor(grams: Double): Double = proteinPer100g * grams / 100.0

    /** The portion to preselect when the user did not say how much. */
    public val defaultPortion: Portion get() = portions.firstOrNull() ?: Portion("100 g", 100.0)
}
