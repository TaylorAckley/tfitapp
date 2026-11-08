package com.tfitapp.application.nutrition

import com.tfitapp.application.judgement.Answer
import com.tfitapp.application.judgement.JevQuestions
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.judgement.Question
import com.tfitapp.application.port.FoodLookupPort
import com.tfitapp.application.port.FoodRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.MealItem
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.nutrition.Portion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant
import kotlin.time.Duration

class ProposeFoodRowsTest {

    private val egg = Food("egg", "Egg, whole, raw", 143.0, 12.6, portions = listOf(Portion("1 large", 50.0), Portion("1 medium", 44.0)), origin = FoodOrigin.BUNDLED)
    private val toast = Food("toast", "Bread, white, toasted", 293.0, 9.0, portions = listOf(Portion("1 slice", 25.0)), origin = FoodOrigin.BUNDLED)
    private val bagel = Food("bagel", "Bagel, plain", 257.0, 10.0, portions = listOf(Portion("1 bagel", 98.0)), origin = FoodOrigin.BUNDLED)
    private val questBar = Food("fdc-1", "QUEST Protein Bar", 380.0, 35.0, portions = listOf(Portion("1 bar", 60.0)), origin = FoodOrigin.FDC, externalId = "1")

    private val repo = object : FoodRepository {
        override suspend fun search(query: String, limit: Int) = when {
            "egg" in query -> listOf(egg)
            "toast" in query -> listOf(toast, bagel)
            else -> emptyList()
        }
        override suspend fun get(id: String): Food? = listOf(egg, toast, bagel).firstOrNull { it.id == id }
        override suspend fun byExternalId(externalId: String): Food? = null
        override suspend fun upsert(food: Food) {}
        override suspend fun favourites(limit: Int) = emptyList<Food>()
    }

    private val lookup = object : FoodLookupPort {
        override suspend fun search(query: String, limit: Int, timeout: Duration) =
            if ("quest" in query.lowercase()) listOf(questBar) else emptyList()
    }

    private fun jev(answers: (Map<String, Question>) -> Map<String, Answer>) = object : JudgementPort {
        var lastQuestions: Map<String, Question> = emptyMap()
        override suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration): JudgementResult {
            lastQuestions = questions
            return JudgementResult.Answers(answers(questions), "jev-latest")
        }
    }

    private val jevDown = object : JudgementPort {
        override suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration) = JudgementResult.Unavailable("offline")
    }

    private val breakfast = MealTemplate(
        "m-bf", "Weekday breakfast",
        listOf(MealItem("egg", "Egg, whole, raw", 100.0, 143.0, 12.6), MealItem("toast", "Bread, white, toasted", 25.0, 73.25, 2.25), MealItem(null, "black coffee", null, 5.0, 0.0)),
        Instant.parse("2026-09-01T08:00:00Z"),
    )

    private fun meals(vararg templates: MealTemplate) = object : MealTemplateRepository {
        override fun observeActive(): Flow<List<MealTemplate>> = flowOf(templates.toList())
        override suspend fun active() = templates.toList()
        override suspend fun byNameKey(nameKey: String) = templates.firstOrNull { it.nameKey == nameKey }
        override suspend fun upsert(template: MealTemplate) {}
        override suspend fun archive(id: String, at: Instant) {}
    }

    private val noMeals = meals()

    @Test
    fun `confident matches are prefilled and explicit counts skip the portion question`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.95)) }
                put(JevQuestions.foodMatchId(0), Answer.Choice("egg", mapOf("egg" to 0.9), 0.9))
                put(JevQuestions.foodMatchId(1), Answer.Choice("toast", mapOf("toast" to 0.8, "bagel" to 0.2), 0.8))
            }
        }
        val proposal = ProposeFoodRows(repo, lookup, port, noMeals)("2 eggs, toast")
        assertTrue(proposal.jevUsed)
        assertEquals(2, proposal.rows.size)
        val eggs = proposal.rows[0]
        assertEquals(egg, eggs.selected)
        assertEquals(100.0, eggs.grams) // 2 × 1 large
        assertFalse(eggs.needsReview)
        assertNull(port.lastQuestions[JevQuestions.foodPortionId(0)], "explicit count → no portion question")
        // toast had no quantity but only one portion label across candidates? toast(1 slice)+bagel(1 bagel)=2 → question asked
        assertTrue(port.lastQuestions.containsKey(JevQuestions.foodPortionId(1)))
    }

    @Test
    fun `low confidence opens the picker for that row only`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.9)) }
                put(JevQuestions.foodMatchId(0), Answer.Choice("egg", mapOf("egg" to 0.9), 0.9))
                put(JevQuestions.foodMatchId(1), Answer.Choice("toast", mapOf("toast" to 0.5, "bagel" to 0.5), 0.3))
                put(JevQuestions.foodPortionId(1), Answer.Choice("1 slice", mapOf("1 slice" to 0.9), 0.9))
            }
        }
        val rows = ProposeFoodRows(repo, lookup, port, noMeals)("2 eggs, toast").rows
        assertFalse(rows[0].needsReview)
        assertTrue(rows[1].needsReview)
        assertEquals(toast, rows[1].selected, "still prefilled with the top pick, just flagged")
    }

    @Test
    fun `none_of_these leaves the row unselected but keeps candidates`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.9)) }
                put(JevQuestions.foodMatchId(0), Answer.Choice(JevQuestions.NONE_OF_THESE, emptyMap(), 0.8))
            }
        }
        val row = ProposeFoodRows(repo, lookup, port, noMeals)("toast").rows.single()
        assertNull(row.selected)
        assertTrue(row.needsReview)
        assertEquals(listOf(toast, bagel), row.candidates)
    }

    @Test
    fun `non-food fragments are dropped`() = runTest {
        val port = jev { qs ->
            buildMap {
                put(JevQuestions.foodIsFoodId(0), Answer.Noul(0.95))
                put(JevQuestions.foodIsFoodId(1), Answer.Noul(0.05))
                put(JevQuestions.foodMatchId(0), Answer.Choice("egg", mapOf("egg" to 1.0), 0.95))
            }
        }
        val rows = ProposeFoodRows(repo, lookup, port, noMeals)("2 eggs, then went for a walk").rows
        assertEquals(1, rows.size)
        assertEquals("eggs", rows[0].fragment.foodText)
    }

    @Test
    fun `a missing answer for one row degrades only that row`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.9)) }
                put(JevQuestions.foodMatchId(0), Answer.Choice("egg", mapOf("egg" to 0.9), 0.9))
                // no answer at all for fragment 1
            }
        }
        val rows = ProposeFoodRows(repo, lookup, port, noMeals)("2 eggs, toast").rows
        assertFalse(rows[0].needsReview)
        assertTrue(rows[1].needsReview)
        assertEquals(toast, rows[1].selected, "falls back to the top FTS candidate")
    }

    @Test
    fun `jev unavailable renders the same screen with top candidates flagged for review`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, noMeals)("2 eggs, toast")
        assertFalse(proposal.jevUsed)
        assertEquals("offline", proposal.jevNote)
        assertEquals(2, proposal.rows.size)
        assertEquals(egg, proposal.rows[0].selected)
        assertTrue(proposal.rows[0].needsReview)
        assertEquals(100.0, proposal.rows[0].grams)
    }

    @Test
    fun `local miss goes to FDC and a miss there becomes a quick-add row`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, noMeals)("quest bar, unicorn steak")
        assertEquals(questBar, proposal.rows[0].selected)
        assertTrue(proposal.rows[1].quickAdd)
        assertNull(proposal.rows[1].selected)
    }

    @Test
    fun `explicit grams override any portion`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, noMeals)("150g egg")
        assertEquals(150.0, proposal.rows[0].grams)
        assertNull(proposal.rows[0].portion)
    }

    // ───────────────────────── Saved meals ─────────────────────────

    @Test
    fun `an exact meal name expands without asking Jev, even when Jev is down`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, meals(breakfast))("weekday breakfast")
        assertEquals(listOf("Egg, whole, raw", "Bread, white, toasted", "black coffee"), proposal.rows.map { it.fragment.text })
        assertTrue(proposal.rows.all { it.mealName == "Weekday breakfast" && !it.needsReview })
        assertEquals(100.0, proposal.rows[0].grams)
        assertEquals(5.0, proposal.rows[2].kcal, "an estimate item carries its saved numbers")
        assertTrue(proposal.rows[2].quickAdd)
    }

    @Test
    fun `a nickname is Jev's call and is asked per fragment with the saved meals in state`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.9)) }
                put(JevQuestions.foodMealId(0), Answer.Choice("m-bf", mapOf("m-bf" to 0.9), 0.9))
            }
        }
        val proposal = ProposeFoodRows(repo, lookup, port, meals(breakfast))("the usual")
        assertTrue(port.lastQuestions.containsKey(JevQuestions.foodMealId(0)))
        assertEquals(3, proposal.rows.size)
        assertEquals(0.9, proposal.rows[0].matchConfidence)
    }

    @Test
    fun `a low-confidence meal guess falls back to plain food matching`() = runTest {
        val port = jev { qs ->
            buildMap {
                qs.keys.filter { it.endsWith("_is_food") }.forEach { put(it, Answer.Noul(0.9)) }
                put(JevQuestions.foodMealId(0), Answer.Choice("m-bf", mapOf("m-bf" to 0.5, JevQuestions.NONE_OF_THESE to 0.5), 0.3))
                put(JevQuestions.foodMatchId(0), Answer.Choice("toast", mapOf("toast" to 0.9), 0.9))
            }
        }
        val rows = ProposeFoodRows(repo, lookup, port, meals(breakfast))("toast").rows
        assertEquals(1, rows.size)
        assertEquals(toast, rows[0].selected)
        assertNull(rows[0].mealName)
    }

    @Test
    fun `no meal question is asked when nothing is saved`() = runTest {
        val port = jev { emptyMap() }
        ProposeFoodRows(repo, lookup, port, noMeals)("toast")
        assertTrue(port.lastQuestions.keys.none { it.endsWith("_meal") })
    }

    @Test
    fun `a food the meal already holds replaces that item`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, meals(breakfast))("weekday breakfast but 3 eggs")
        val labels = proposal.rows.map { it.fragment.text }
        assertEquals(listOf("Bread, white, toasted", "black coffee", "3 eggs"), labels)
        val eggs = proposal.rows.last()
        assertEquals(egg, eggs.selected)
        assertEquals(150.0, eggs.grams, "3 × 1 large")
        assertNull(eggs.mealName, "the edit is a fresh row, not a copy")
    }

    @Test
    fun `without removes the item and the negated fragment itself`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, meals(breakfast))("weekday breakfast without toast, no coffee")
        assertEquals(listOf("Egg, whole, raw"), proposal.rows.map { it.fragment.text })
    }

    @Test
    fun `a negated fragment with no meal to edit is dropped`() = runTest {
        val proposal = ProposeFoodRows(repo, lookup, jevDown, noMeals)("2 eggs without toast")
        assertEquals(listOf("2 eggs"), proposal.rows.map { it.fragment.text })
    }
}
