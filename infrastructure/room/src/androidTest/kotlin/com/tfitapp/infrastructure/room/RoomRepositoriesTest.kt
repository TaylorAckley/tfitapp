package com.tfitapp.infrastructure.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.nutrition.Portion
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.Phase
import com.tfitapp.domain.timer.PhaseKind
import com.tfitapp.domain.training.BlockFeedback
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.BlockKind
import com.tfitapp.domain.training.CandidateAxis
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.domain.training.Location
import com.tfitapp.domain.training.Pattern
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionBlock
import com.tfitapp.domain.training.SessionCandidate
import com.tfitapp.infrastructure.room.repo.RoomFoodLogRepository
import com.tfitapp.infrastructure.room.repo.RoomFoodRepository
import com.tfitapp.infrastructure.room.repo.RoomMealTemplateRepository
import com.tfitapp.infrastructure.room.repo.RoomSessionRepository
import com.tfitapp.infrastructure.room.repo.RoomSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomRepositoriesTest {

    private lateinit var db: TFitDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TFitDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedIsIdempotentAndSearchable() = runBlocking {
        // First search seeds from assets/seed_foods.json; a second repository instance must not re-add rows.
        val first = RoomFoodRepository(db.foods(), context)
        assertTrue(first.search("egg", 5).any { it.id == "seed:egg" }, "seed egg found by FTS")
        val seededCount = db.foods().count()
        assertTrue(seededCount > 50, "seed loaded, got $seededCount")

        val second = RoomFoodRepository(db.foods(), context)
        second.search("toast", 5)
        assertEquals(seededCount, db.foods().count())

        // Upserting an existing id twice changes values, not row count.
        val egg = Food("seed:egg", "Egg, whole, cooked", 156.0, 12.6, portions = listOf(Portion("1 large", 50.0)), origin = FoodOrigin.BUNDLED)
        second.upsert(egg)
        second.upsert(egg)
        assertEquals(seededCount, db.foods().count())
        assertEquals(156.0, second.get("seed:egg")!!.kcalPer100g, 0.0)

        assertTrue(second.search("eg", 5).isNotEmpty(), "prefix match")
        assertTrue(second.search("zzqx", 5).isEmpty(), "miss is empty, not an error")
    }

    @Test
    fun foodLogUpsertTwiceIsOneRow() = runBlocking {
        val log = RoomFoodLogRepository(db.foodLog())
        val entry = FoodLogEntry(
            id = "e1", loggedAt = Instant.parse("2026-09-19T12:00:00Z"), localDate = LocalDate.of(2026, 9, 19), mealSlot = MealSlot.LUNCH,
            foodId = null, label = "takeout", grams = null, kcal = 600.0, protein = 30.0, source = EntrySource.QUICK,
        )
        log.upsert(entry)
        log.upsert(entry.copy(kcal = 650.0))
        val day = log.observeDay(LocalDate.of(2026, 9, 19)).first()
        assertEquals(1, day.size)
        assertEquals(650.0, day.single().kcal, 0.0)

        log.softDelete("e1", Instant.parse("2026-09-19T13:00:00Z"))
        log.softDelete("e1", Instant.parse("2026-09-19T14:00:00Z"))
        val after = log.observeDay(LocalDate.of(2026, 9, 19)).first().single()
        assertEquals(Instant.parse("2026-09-19T13:00:00Z"), after.deletedAt)
    }

    @Test
    fun settingsRoundTripAndDefault() = runBlocking {
        val repo = RoomSettingsRepository(db.settings())
        assertEquals(45, repo.current().timeBudgetMinutes)
        repo.update { it.copy(timeBudgetMinutes = 30) }
        repo.update { it.copy(timeBudgetMinutes = 30) }
        assertEquals(30, repo.current().timeBudgetMinutes)
        assertEquals(30, repo.settings.first().timeBudgetMinutes)
    }

    @Test
    fun mealTemplateSavedTwiceByNameIsOneRow() = runBlocking {
        val repo = RoomMealTemplateRepository(db.mealTemplates())
        val at = Instant.parse("2026-09-19T08:00:00Z")
        val entries = listOf(
            FoodLogEntry("e1", at, LocalDate.of(2026, 9, 19), MealSlot.BREAKFAST, "seed:egg", "Egg", 100.0, 143.0, 12.6, EntrySource.JEV),
            FoodLogEntry("e2", at, LocalDate.of(2026, 9, 19), MealSlot.BREAKFAST, null, "coffee", null, 5.0, 0.0, EntrySource.QUICK),
        )
        repo.upsert(MealTemplate.fromEntries("m1", "Weekday breakfast", entries, at))
        repo.upsert(MealTemplate.fromEntries("m1", "Weekday breakfast", entries.take(1), at))
        val active = repo.active()
        assertEquals(1, active.size)
        assertEquals(1, active.single().items.size)
        assertEquals("m1", repo.byNameKey("weekday breakfast")!!.id)

        repo.archive("m1", at)
        repo.archive("m1", at)
        assertTrue(repo.active().isEmpty(), "archived meals are hidden")
        assertEquals(at, repo.byNameKey("weekday breakfast")!!.archivedAt)
    }

    @Test
    fun feedbackReplaceIsAbsoluteAndFeedsLastPerformance() = runBlocking {
        val repo = RoomSessionRepository(db.sessions())
        val plan = IntervalPlan("p", "Tank", false, listOf(Phase("Work", PhaseKind.WORK, 30)))
        val blocks = listOf(
            SessionBlock("b1", 0, BlockKind.STRENGTH, Pattern.HINGE, listOf("ex.swing"), "KB swing", Prescription.SetsReps(3, 10, 15, 90, "45 lb"), 8),
            SessionBlock("b2", 1, BlockKind.SLED, Pattern.SLED_PUSH, listOf("ex.tank"), "Tank push", Prescription.Intervals(plan, sledSetting = 2), 8),
        )
        val done = Instant.parse("2026-09-18T18:00:00Z")
        val session = Session("s1", LocalDate.of(2026, 9, 18), "slot.a", SessionCandidate("c1", CandidateAxis.DEFAULT, "Day A", Location.OUTDOOR, blocks), emptyList(), null, null, done, done)
        repo.upsert(session)
        repo.upsert(session.copy(checkOutText = "swings easy"))
        assertEquals("swings easy", repo.get("s1")!!.checkOutText)

        val both = listOf(
            BlockFeedback("s1", "b1", "ex.swing", BlockFeel.TOO_EASY, FeedbackSource.JEV, done),
            BlockFeedback("s1", "b2", "ex.tank", BlockFeel.TOO_HARD, FeedbackSource.MANUAL, done),
        )
        repo.replaceFeedback("s1", both)
        repo.replaceFeedback("s1", both)
        assertEquals(2, repo.feedback("s1").size)

        // Saving a smaller set removes the rest: the set is the state, not a log.
        repo.replaceFeedback("s1", both.take(1))
        assertEquals(listOf("b1"), repo.feedback("s1").map { it.blockId })

        val perf = repo.lastPerformances()
        assertEquals(BlockFeel.TOO_EASY, perf.getValue("ex.swing").feel)
        assertNull(perf.getValue("ex.tank").feel)
    }
}

private fun assertTrue(condition: Boolean, message: String) = assertTrue(message, condition)
