package com.tfitapp.domain.nutrition

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

@Serializable
public enum class Sex { MALE, FEMALE }

@Serializable
public enum class GoalPhase(
    public val displayName: String,
    public val defaultBudgetOffsetKcal: Int,
    public val defaultProteinPerKg: Double,
) {
    CUT("Cut", -500, 2.0),
    RECOMP("Recomp", 0, 1.8),
    LEAN_GAIN("Lean gain", 250, 1.8),
}

/** Body facts the energy math needs. All editable in Settings. */
@Serializable
public data class BodyProfile(
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Double,
    val weightKg: Double,
    /** Sedentary 1.2 … very active 1.9. 1.4 until adaptive TDEE takes over. */
    val activityFactor: Double = 1.4,
) {
    init {
        require(ageYears in 10..120 && heightCm > 0 && weightKg > 0 && activityFactor in 1.0..2.5)
    }
}

@Serializable
public data class GoalSettings(
    val phase: GoalPhase = GoalPhase.RECOMP,
    val budgetOffsetKcal: Int = phase.defaultBudgetOffsetKcal,
    val proteinPerKg: Double = phase.defaultProteinPerKg,
)

/** A day's intake paired with the smoothed weight, the input to adaptive TDEE. */
public data class DailyIntake(val date: LocalDate, val kcal: Double)

public data class TdeeEstimate(
    val kcal: Int,
    /** True once there is enough data to trust intake-vs-weight over the formula. */
    val adaptive: Boolean,
    val windowDays: Int,
)

/**
 * Energy arithmetic. All deterministic; nothing here asks a model anything.
 */
public object EnergyMath {
    private const val KCAL_PER_KG_BODYWEIGHT = 7700.0
    public const val ADAPTIVE_MIN_DAYS: Int = 14
    public const val ADAPTIVE_WINDOW_DAYS: Int = 21
    public const val WEIGHT_EMA_ALPHA: Double = 0.1

    /** Mifflin-St Jeor resting energy, before the activity factor. */
    public fun bmr(profile: BodyProfile): Double {
        val base = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * profile.ageYears
        return if (profile.sex == Sex.MALE) base + 5 else base - 161
    }

    public fun formulaTdee(profile: BodyProfile): Int = (bmr(profile) * profile.activityFactor).roundToInt()

    /**
     * Exponentially smoothed weight series (oldest first). Filters water noise so a
     * three-week trend means something.
     */
    public fun smoothWeights(entries: List<WeightEntry>, alpha: Double = WEIGHT_EMA_ALPHA): List<Pair<LocalDate, Double>> {
        val sorted = entries.sortedBy { it.at }
        var ema: Double? = null
        return sorted.map { e ->
            val next = ema?.let { it + alpha * (e.kg - it) } ?: e.kg
            ema = next
            e.localDate to next
        }
    }

    /**
     * TDEE ≈ mean intake − (Δ smoothed weight × 7700) / days. Falls back to the
     * formula until there are [ADAPTIVE_MIN_DAYS] days with both intake and weight.
     */
    public fun estimateTdee(
        profile: BodyProfile,
        intakes: List<DailyIntake>,
        weights: List<WeightEntry>,
        today: LocalDate,
    ): TdeeEstimate {
        val windowStart = today.minusDays(ADAPTIVE_WINDOW_DAYS.toLong() - 1)
        val intakeInWindow = intakes.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(today) }
        val smoothed = smoothWeights(weights).filter { !it.first.isBefore(windowStart) && !it.first.isAfter(today) }
        val daysCovered = intakeInWindow.map { it.date }.toSet().intersect(smoothed.map { it.first }.toSet()).size
        if (daysCovered < ADAPTIVE_MIN_DAYS || smoothed.size < 2) {
            return TdeeEstimate(formulaTdee(profile), adaptive = false, windowDays = daysCovered)
        }
        val first = smoothed.first()
        val last = smoothed.last()
        val spanDays = java.time.temporal.ChronoUnit.DAYS.between(first.first, last.first).coerceAtLeast(1)
        val meanIntake = intakeInWindow.map { it.kcal }.average()
        val deltaKg = last.second - first.second
        val tdee = meanIntake - deltaKg * KCAL_PER_KG_BODYWEIGHT / spanDays
        return TdeeEstimate(tdee.roundToInt(), adaptive = true, windowDays = daysCovered)
    }

    public fun dailyBudget(tdee: TdeeEstimate, goal: GoalSettings): Int = tdee.kcal + goal.budgetOffsetKcal

    public fun proteinFloorGrams(profile: BodyProfile, goal: GoalSettings): Int =
        (profile.weightKg * goal.proteinPerKg).roundToInt()
}
