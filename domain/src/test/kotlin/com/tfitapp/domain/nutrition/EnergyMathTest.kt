package com.tfitapp.domain.nutrition

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyMathTest {

    private val profile = BodyProfile(Sex.MALE, ageYears = 35, heightCm = 180.0, weightKg = 85.0, activityFactor = 1.4)
    private val today = LocalDate.of(2026, 9, 19)

    @Test
    fun `mifflin st jeor for a male`() {
        // 10*85 + 6.25*180 - 5*35 + 5 = 850 + 1125 - 175 + 5 = 1805
        assertEquals(1805.0, EnergyMath.bmr(profile))
        assertEquals(2527, EnergyMath.formulaTdee(profile))
    }

    @Test
    fun `falls back to the formula with too little data`() {
        val est = EnergyMath.estimateTdee(profile, intakes = emptyList(), weights = emptyList(), today = today)
        assertFalse(est.adaptive)
        assertEquals(2527, est.kcal)
    }

    @Test
    fun `adaptive estimate recovers a known TDEE from intake and weight change`() {
        // Eat 2200/day for 21 days and lose exactly 1 kg smoothly → TDEE ≈ 2200 + 7700/20 = 2585.
        val days = (0 until 21).map { today.minusDays(20L - it) }
        val intakes = days.map { DailyIntake(it, 2200.0) }
        // Feed a flat series then a lower flat series would be noisy under EMA; use a linear drop and
        // let the EMA lag — the test asserts direction and rough magnitude, not the exact value.
        val weights = days.mapIndexed { i, d ->
            WeightEntry("w$i", d.atStartOfDay(ZoneOffset.UTC).toInstant(), d, 86.0 - i * (1.0 / 20))
        }
        val est = EnergyMath.estimateTdee(profile, intakes, weights, today)
        assertTrue(est.adaptive)
        assertTrue(est.kcal > 2200, "losing weight on 2200 means TDEE above intake, got ${est.kcal}")
        assertTrue(est.kcal < 2700, "EMA-lagged 1 kg over 20 days should not imply more than ~500 kcal/day, got ${est.kcal}")
    }

    @Test
    fun `budget and protein floor follow the phase`() {
        val tdee = TdeeEstimate(2500, adaptive = false, windowDays = 0)
        assertEquals(2000, EnergyMath.dailyBudget(tdee, GoalSettings(GoalPhase.CUT)))
        assertEquals(2750, EnergyMath.dailyBudget(tdee, GoalSettings(GoalPhase.LEAN_GAIN)))
        assertEquals(170, EnergyMath.proteinFloorGrams(profile, GoalSettings(GoalPhase.CUT)))
        assertEquals(153, EnergyMath.proteinFloorGrams(profile, GoalSettings(GoalPhase.RECOMP)))
    }

    @Test
    fun `smoothing damps a one-day spike`() {
        val base = Instant.parse("2026-09-01T07:00:00Z")
        val entries = listOf(85.0, 85.0, 87.5, 85.0, 85.0).mapIndexed { i, kg ->
            WeightEntry("w$i", base.plusSeconds(i * 86400L), LocalDate.of(2026, 9, 1 + i), kg)
        }
        val smoothed = EnergyMath.smoothWeights(entries).map { it.second }
        assertTrue(smoothed[2] < 85.5, "spike should be damped, got ${smoothed[2]}")
    }
}
