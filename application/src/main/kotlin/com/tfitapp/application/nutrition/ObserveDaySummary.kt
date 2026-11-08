package com.tfitapp.application.nutrition

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.port.WeightRepository
import com.tfitapp.domain.nutrition.DailyIntake
import com.tfitapp.domain.nutrition.EnergyMath
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.TdeeEstimate
import com.tfitapp.domain.nutrition.WeightEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/** The three numbers on the home screen, plus what they were computed from. */
public data class DaySummary(
    val date: LocalDate,
    val entries: List<FoodLogEntry>,
    val kcalEaten: Int,
    val proteinEaten: Int,
    /** Null until a body profile exists. */
    val budgetKcal: Int?,
    val proteinFloor: Int?,
    val tdee: TdeeEstimate?,
    val weekAverageKcal: Int?,
    /** Share of this week's kcal that came from quick-add guesses. */
    val weekEstimatedShare: Double?,
    val latestWeightKg: Double?,
) {
    val kcalLeft: Int? get() = budgetKcal?.let { it - kcalEaten }
}

public class ObserveDaySummary @Inject constructor(
    private val log: FoodLogRepository,
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
) {
    public operator fun invoke(date: LocalDate): Flow<DaySummary> =
        combine(log.observeDay(date), weights.observeAll(), settings.settings, weekFlow(date)) { today, ws, s, week ->
            val live = today.filter { it.deletedAt == null }
            val kcal = live.sumOf { it.kcal }
            val protein = live.sumOf { it.protein }
            val body = s.body
            val intakes = week.groupBy { it.localDate }.map { (d, es) -> DailyIntake(d, es.sumOf { it.kcal }) }
            val tdee = body?.let { EnergyMath.estimateTdee(it, intakes, ws, date) }
            val weekKcal = week.filter { !it.localDate.isBefore(date.minusDays(6)) }
            val weekDays = weekKcal.map { it.localDate }.toSet().size
            DaySummary(
                date = date,
                entries = live.sortedBy { it.loggedAt },
                kcalEaten = kcal.roundToInt(),
                proteinEaten = protein.roundToInt(),
                budgetKcal = tdee?.let { EnergyMath.dailyBudget(it, s.goal) },
                proteinFloor = body?.let { EnergyMath.proteinFloorGrams(it, s.goal) },
                tdee = tdee,
                weekAverageKcal = if (weekDays == 0) null else (weekKcal.sumOf { it.kcal } / weekDays).roundToInt(),
                weekEstimatedShare = weekKcal.takeIf { it.isNotEmpty() }?.let { w ->
                    w.filter { it.source == EntrySource.QUICK }.sumOf { it.kcal } / w.sumOf { it.kcal }.coerceAtLeast(1.0)
                },
                latestWeightKg = ws.maxByOrNull(WeightEntry::at)?.kg,
            )
        }

    /** Re-reads the trailing 21 days whenever today's log changes; cheap for one user. */
    private fun weekFlow(date: LocalDate): Flow<List<FoodLogEntry>> = log.observeDay(date).map {
        log.between(date.minusDays(EnergyMath.ADAPTIVE_WINDOW_DAYS.toLong() - 1), date).filter { e -> e.deletedAt == null }
    }
}
