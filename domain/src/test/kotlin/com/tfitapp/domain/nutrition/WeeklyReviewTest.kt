package com.tfitapp.domain.nutrition

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeeklyReviewTest {

    private val today = LocalDate.of(2026, 9, 19)
    private val body = BodyProfile(Sex.MALE, 38, 180.0, 84.0, 1.4)
    private val cut = GoalSettings(GoalPhase.CUT)

    private fun entry(date: LocalDate, kcal: Double, protein: Double = 40.0, source: EntrySource = EntrySource.JEV) = FoodLogEntry(
        id = "$date-$kcal-$source", loggedAt = date.atTime(12, 0).toInstant(ZoneOffset.UTC), localDate = date, mealSlot = MealSlot.LUNCH,
        foodId = null, label = "x", grams = null, kcal = kcal, protein = protein, source = source,
    )

    /** One weigh-in a day for [days] days ending today, sliding linearly from [startKg] to [endKg]. */
    private fun weights(days: Int, startKg: Double, endKg: Double): List<WeightEntry> = (days - 1 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        val t = (days - 1 - back).toDouble() / (days - 1)
        WeightEntry("w$back", d.atTime(7, 0).toInstant(ZoneOffset.UTC), d, startKg + (endKg - startKg) * t)
    }

    private fun review(entries: List<FoodLogEntry>, ws: List<WeightEntry>, goal: GoalSettings = cut, sessions: Int = 3, profile: BodyProfile? = body) =
        WeeklyReview.build(today, entries, ws, profile, goal, sessions, trainingDaysPerWeek = 4)

    @Test
    fun `seven days oldest first, gaps are not fasts`() {
        val r = review(listOf(entry(today, 1800.0), entry(today.minusDays(2), 2000.0)), emptyList())
        assertEquals(7, r.days.size)
        assertEquals(today.minusDays(6), r.days.first().date)
        assertEquals(2, r.daysLogged)
        assertEquals(1900, r.averageKcal, "average over logged days only")
    }

    @Test
    fun `nothing logged says so and stops`() {
        val r = review(emptyList(), emptyList())
        assertEquals(listOf("Nothing logged this week, so there is nothing to read yet."), r.verdict)
    }

    @Test
    fun `no body means no budget and the verdict says where to fix it`() {
        val r = review((0..6).map { entry(today.minusDays(it.toLong()), 2000.0) }, emptyList(), profile = null)
        assertNull(r.budgetKcal)
        assertTrue(r.verdict.any { "Enter your body in Settings" in it })
    }

    @Test
    fun `on a cut, losing at the expected rate reads as about right`() {
        // 21 days of intake so TDEE goes adaptive, and a weight slide of ~0.45 kg/week over 28 days.
        val entries = (0..20).map { entry(today.minusDays(it.toLong()), 1900.0, protein = 170.0) }
        val r = review(entries, weights(28, 85.8, 84.0))
        assertTrue(r.tdee?.adaptive == true)
        assertEquals(-0.45, r.expectedRateKgPerWeek, 0.01)
        assertTrue(r.rateKgPerWeek!! < -0.3 && r.rateKgPerWeek!! > -0.6, "rate was ${r.rateKgPerWeek}")
        assertTrue(r.verdict.any { "about what the plan expects" in it }, r.verdict.toString())
        assertTrue(r.verdict.any { it.startsWith("Protein floor of ${r.proteinFloor} g hit on 7 of 7") }, r.verdict.toString())
    }

    @Test
    fun `on a cut, a flat scale suggests taking kcal off after another week`() {
        val entries = (0..20).map { entry(today.minusDays(it.toLong()), 2200.0) }
        val r = review(entries, weights(28, 84.0, 84.0))
        assertTrue(r.verdict.any { "slower than the plan" in it && "take 150 kcal off" in it }, r.verdict.toString())
    }

    @Test
    fun `too few weigh-ins asks for more instead of guessing`() {
        val r = review((0..6).map { entry(today.minusDays(it.toLong()), 2000.0) }, weights(2, 84.0, 84.0))
        assertTrue(r.verdict.any { "Weigh in most mornings" in it })
    }

    @Test
    fun `estimated share is called out past a third`() {
        val entries = listOf(entry(today, 1000.0), entry(today, 1000.0, source = EntrySource.QUICK))
        val r = review(entries, emptyList())
        assertEquals(0.5, r.estimatedShare, 0.001)
        assertTrue(r.verdict.any { it.startsWith("50% of the week's kcal were estimates") })
    }

    @Test
    fun `sessions line pluralises and compares to the plan`() {
        val entries = listOf(entry(today, 1000.0))
        assertTrue(review(entries, emptyList(), sessions = 1).verdict.any { it == "1 of 4 planned sessions finished." })
        assertTrue(review(entries, emptyList(), sessions = 4).verdict.any { it == "4 sessions finished, the plan is 4 a week." })
        assertTrue(review(entries, emptyList(), sessions = 0).verdict.any { it.startsWith("No sessions finished") })
    }

    @Test
    fun `deleted entries are ignored`() {
        val gone = entry(today, 5000.0).copy(deletedAt = Instant.parse("2026-09-19T13:00:00Z"))
        val r = review(listOf(gone, entry(today, 1500.0)), emptyList())
        assertEquals(1500, r.days.last().kcal)
    }
}
