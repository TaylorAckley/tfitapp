package com.tfitapp.domain.training

import kotlinx.serialization.Serializable

/** Movement patterns a program slot asks for. An exercise can satisfy more than one. */
@Serializable
public enum class Pattern {
    WARMUP, HINGE, SQUAT, PRESS, PULL, CARRY, CORE, LOCOMOTION,
    SLED_PUSH, SLED_PULL, LINES, CONDITIONING,
}

/** Decides which progression rule applies. See design basis §5.3. */
@Serializable
public enum class LoadModel { VARIABLE, FIXED, SLED, LINES, TIMED }

@Serializable
public data class Exercise(
    val id: String,
    val name: String,
    val patterns: Set<Pattern>,
    val equipment: Set<EquipmentKind>,
    val locations: Set<Location>,
    val loadModel: LoadModel,
    /** Harder variations in order, by exercise id. Empty when there is nowhere to go. */
    val ladder: List<String> = emptyList(),
    /** > 1 appears more often, < 1 less. Adjusted by Swap history, never by Jev. */
    val preferenceWeight: Double = 1.0,
    val excluded: Boolean = false,
    val isUserDefined: Boolean = false,
) {
    init {
        require(patterns.isNotEmpty() && equipment.isNotEmpty() && locations.isNotEmpty())
        require(preferenceWeight > 0)
    }

    /** Sled and lines only work outside; everything indoor-capable is weather-proof. */
    val weatherSensitive: Boolean get() = Location.INDOOR !in locations
}
