package com.tfitapp.domain.training

import kotlinx.serialization.Serializable

@Serializable
public enum class EquipmentKind(public val displayName: String) {
    BODY("Bodyweight"),
    SLED("Sled"),
    KETTLEBELL("Kettlebell"),
    BATTLE_ROPE("Battle rope"),
    JUMP_ROPE("Jump rope"),
    PULL_UP_BAR("Pull-up bar"),
    DUMBBELL("Dumbbell"),
    BAND("Resistance band"),
    OTHER("Other"),
}

@Serializable
public enum class Location(public val displayName: String) { OUTDOOR("Outdoor"), INDOOR("Indoor") }

/**
 * One piece of gear. [present] lets something be parked without losing its history.
 * Bodyweight is always present and cannot be removed.
 */
@Serializable
public data class Equipment(
    val id: String,
    val kind: EquipmentKind,
    val detail: String,
    val loadKg: Double? = null,
    val present: Boolean = true,
    val isCustom: Boolean = false,
) {
    public companion object {
        public const val BODY_ID: String = "eq.body"

        public fun body(): Equipment = Equipment(BODY_ID, EquipmentKind.BODY, "Always available")

        /** The user's inventory as of 2026-09-19. Editable in Settings; this is only the seed. */
        public fun seed(): List<Equipment> = listOf(
            body(),
            Equipment("eq.tank", EquipmentKind.SLED, "Torque Tank, tow rope, wheelbarrow handles"),
            Equipment("eq.kb45", EquipmentKind.KETTLEBELL, "45 lb", loadKg = 20.4),
            Equipment("eq.kb10", EquipmentKind.KETTLEBELL, "10 lb", loadKg = 4.5),
            Equipment("eq.battlerope", EquipmentKind.BATTLE_ROPE, "Battle rope"),
            Equipment("eq.jumprope", EquipmentKind.JUMP_ROPE, "Weighted jump rope"),
        )
    }
}
