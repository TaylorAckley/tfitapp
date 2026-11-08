package com.tfitapp.domain.nutrition

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

public data class DayIntake(val date: LocalDate, val kcal: Int, val protein: Int) {
    /** A day with nothing logged is a gap, not a fast. */
    val logged: Boolean get() = kcal > 0
}

public data class WeightPoint(val date: LocalDate, val rawKg: Double, val smoothedKg: Double)

/**
 * The week in numbers and in sentences. Everything here is arithmetic on the log;
 * the verdict is a fixed set of rules so the same week always reads the same way.
 * Design basis §4.5 (trend view).
 */
public data class WeeklyReview(
    val today: LocalDate,
    /** Seven days, oldest first, ending today. */
    val days: List<DayIntake>,
    val budgetKcal: Int?,
    val proteinFloor: Int?,
    val tdee: TdeeEstimate?,
    /** Trailing [WEIGHT_WINDOW_DAYS] of weigh-ins with the smoothed series alongside. */
    val weights: List<WeightPoint>,
    val expectedRateKgPerWeek: Double,
    /** Share of the week's kcal that were quick-add guesses. */
    val estimatedShare: Double,
    val sessionsFinished: Int,
    val trainingDaysPerWeek: Int,
) {
    val daysLogged: Int get() = days.count { it.logged }
    val averageKcal: Int? get() = days.filter { it.logged }.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()?.roundToInt()
    val averageProtein: Int? get() = days.filter { it.logged }.takeIf { it.isNotEmpty() }?.map { it.protein }?.average()?.roundToInt()
    val deltaPerDay: Int? get() = budgetKcal?.let { b -> averageKcal?.let { it - b } }
    val proteinDaysHit: Int get() = proteinFloor?.let { f -> days.count { it.logged && it.protein >= f } } ?: 0

    val weightSpanDays: Int get() = weights.takeIf { it.size >= 2 }?.let { ChronoUnit.DAYS.between(it.first().date, it.last().date).toInt() } ?: 0

    /**
     * Least-squares slope through the raw weigh-ins, per week. A fitted line reads a
     * steady trend without the lag an EMA endpoint would add; the smoothed series is
     * for the chart, not the verdict.
     */
    val rateKgPerWeek: Double? get() {
        val w = weights.takeIf { it.size >= 2 && weightSpanDays >= 1 } ?: return null
        val xs = w.map { ChronoUnit.DAYS.between(w.first().date, it.date).toDouble() }
        val ys = w.map { it.rawKg }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        return slope * 7
    }

    /** The fitted change over the window: slope × span. */
    val changeKg: Double? get() = rateKgPerWeek?.let { it / 7 * weightSpanDays }

    val verdict: List<String> get() = Verdict.sentences(this)

    public companion object {
        public const val WEIGHT_WINDOW_DAYS: Int = 28
        private const val KCAL_PER_KG = 7700.0

        public fun build(
            today: LocalDate,
            entries: List<FoodLogEntry>,
            weights: List<WeightEntry>,
            body: BodyProfile?,
            goal: GoalSettings,
            sessionsFinished: Int,
            trainingDaysPerWeek: Int,
        ): WeeklyReview {
            val live = entries.filter { it.deletedAt == null }
            val byDay = live.groupBy { it.localDate }
            val days = (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                val es = byDay[d].orEmpty()
                DayIntake(d, es.sumOf { it.kcal }.roundToInt(), es.sumOf { it.protein }.roundToInt())
            }
            val weekEntries = live.filter { !it.localDate.isBefore(today.minusDays(6)) }
            val weekKcal = weekEntries.sumOf { it.kcal }
            val estimated = if (weekKcal <= 0) 0.0 else weekEntries.filter { it.source == EntrySource.QUICK }.sumOf { it.kcal } / weekKcal

            val intakes = byDay.map { (d, es) -> DailyIntake(d, es.sumOf { it.kcal }) }
            val tdee = body?.let { EnergyMath.estimateTdee(it, intakes, weights, today) }

            val windowStart = today.minusDays(WEIGHT_WINDOW_DAYS.toLong() - 1)
            val smoothed = EnergyMath.smoothWeights(weights).associate { it }
            val points = weights.sortedBy { it.at }
                .filter { !it.localDate.isBefore(windowStart) && !it.localDate.isAfter(today) }
                .map { WeightPoint(it.localDate, it.kg, smoothed[it.localDate] ?: it.kg) }

            return WeeklyReview(
                today = today,
                days = days,
                budgetKcal = tdee?.let { EnergyMath.dailyBudget(it, goal) },
                proteinFloor = body?.let { EnergyMath.proteinFloorGrams(it, goal) },
                tdee = tdee,
                weights = points,
                expectedRateKgPerWeek = goal.budgetOffsetKcal * 7 / KCAL_PER_KG,
                estimatedShare = estimated,
                sessionsFinished = sessionsFinished,
                trainingDaysPerWeek = trainingDaysPerWeek,
            )
        }
    }

    /**
     * Plain sentences. Thresholds are deliberately coarse: a week is too short to
     * read anything finer than "about right", "too fast", "not moving".
     */
    internal object Verdict {
        private const val ON_BUDGET_KCAL = 100
        private const val RATE_TOLERANCE_KG_PER_WEEK = 0.15
        private const val NUDGE_KCAL = 150
        private const val MIN_WEIGH_INS = 4
        private const val ESTIMATED_SHARE_WORTH_SAYING = 0.3

        fun sentences(r: WeeklyReview): List<String> = buildList {
            if (r.daysLogged == 0) {
                add("Nothing logged this week, so there is nothing to read yet.")
                return@buildList
            }
            if (r.daysLogged < 4) add("Only ${r.daysLogged} of 7 days logged, so the numbers are rough.")

            val avg = r.averageKcal
            val budget = r.budgetKcal
            val delta = r.deltaPerDay
            when {
                avg == null -> Unit
                budget == null -> add("Averaging $avg kcal a day. Enter your body in Settings to get a budget.")
                delta != null && abs(delta) < ON_BUDGET_KCAL -> add("Averaging $avg kcal a day, on the budget of $budget.")
                delta != null && delta > 0 -> add("Averaging $avg kcal a day, $delta over the budget of $budget.")
                delta != null -> add("Averaging $avg kcal a day, ${-delta} under the budget of $budget.")
            }

            val rate = r.rateKgPerWeek
            when {
                r.weights.size < MIN_WEIGH_INS -> add("Weigh in most mornings and the trend line fills in.")
                rate == null || r.weightSpanDays < 7 -> add("A week of weigh-ins is needed before the trend means anything.")
                else -> {
                    val moved = "The scale moved ${signed(r.changeKg ?: 0.0)} kg over ${r.weightSpanDays} days"
                    val diff = rate - r.expectedRateKgPerWeek
                    when {
                        abs(diff) <= RATE_TOLERANCE_KG_PER_WEEK -> add("$moved, about what the plan expects. The budget is about right.")
                        r.expectedRateKgPerWeek < 0 && diff < 0 -> add("$moved, faster than the plan. If that holds another week, add $NUDGE_KCAL kcal to the budget.")
                        r.expectedRateKgPerWeek < 0 -> add("$moved, slower than the plan. If that holds another week, take $NUDGE_KCAL kcal off the budget.")
                        r.expectedRateKgPerWeek > 0 && diff > 0 -> add("$moved, faster than the plan. If that holds another week, take $NUDGE_KCAL kcal off the budget.")
                        r.expectedRateKgPerWeek > 0 -> add("$moved, slower than the plan. If that holds another week, add $NUDGE_KCAL kcal to the budget.")
                        diff > 0 -> add("$moved, drifting up on a hold. Watch it another week before changing anything.")
                        else -> add("$moved, drifting down on a hold. Watch it another week before changing anything.")
                    }
                }
            }

            r.proteinFloor?.let { floor -> add("Protein floor of $floor g hit on ${r.proteinDaysHit} of ${r.daysLogged} logged days.") }
            if (r.estimatedShare >= ESTIMATED_SHARE_WORTH_SAYING) add("${(r.estimatedShare * 100).roundToInt()}% of the week's kcal were estimates, so read the budget line loosely.")
            add(
                when {
                    r.sessionsFinished >= r.trainingDaysPerWeek -> "${sessions(r.sessionsFinished)} finished, the plan is ${r.trainingDaysPerWeek} a week."
                    r.sessionsFinished == 0 -> "No sessions finished this week; the plan is ${r.trainingDaysPerWeek}."
                    else -> "${r.sessionsFinished} of ${r.trainingDaysPerWeek} planned sessions finished."
                },
            )
        }

        private fun sessions(n: Int): String = if (n == 1) "1 session" else "$n sessions"
        private fun signed(kg: Double): String = (if (kg > 0) "+" else "") + "%.1f".format(kg)
    }
}
