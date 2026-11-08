package com.tfitapp.application.nutrition

import com.tfitapp.application.judgement.Answer
import com.tfitapp.application.judgement.JevQuestions
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.port.FoodLookupPort
import com.tfitapp.application.port.FoodRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.Fragment
import com.tfitapp.domain.nutrition.MealItem
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.nutrition.Portion
import com.tfitapp.domain.nutrition.Quantity
import com.tfitapp.domain.nutrition.SentenceSplitter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** One line the user will confirm. Everything is prefilled; [needsReview] opens the picker. */
public data class ProposedRow(
    val fragment: Fragment,
    val candidates: List<Food>,
    val selected: Food?,
    val portion: Portion?,
    val grams: Double?,
    val needsReview: Boolean,
    val matchConfidence: Double?,
    /** No food found anywhere; becomes a quick-add with the fragment as label. */
    val quickAdd: Boolean,
    /** Set when the row was expanded from a saved meal; the entry is then a copy. */
    val mealName: String? = null,
    /** Numbers carried by a meal item that has no food behind it (it was an estimate when saved). */
    val fixedKcal: Double? = null,
    val fixedProtein: Double? = null,
) {
    val kcal: Double? get() = selected?.let { f -> grams?.let { f.kcalFor(it) } } ?: fixedKcal
    val protein: Double? get() = selected?.let { f -> grams?.let { f.proteinFor(it) } } ?: fixedProtein
}

public data class FoodProposal(val sentence: String, val rows: List<ProposedRow>, val jevUsed: Boolean, val jevNote: String?)

/**
 * Design basis §4.2. Split (code) → candidates (local FTS, FDC on miss) → one Jev
 * call → route on confidence. Every failure narrows to one row or to "Jev absent";
 * the screen always renders.
 *
 * Saved meals (§4.4): a fragment that names one — exactly, or by a nickname Jev
 * recognises — expands to the meal's items. Other fragments then edit that
 * expansion: a food the meal already holds replaces the item, and a negated
 * fragment ("without toast") removes it.
 */
public class ProposeFoodRows @Inject constructor(
    private val foods: FoodRepository,
    private val lookup: FoodLookupPort,
    private val judgement: JudgementPort,
    private val meals: MealTemplateRepository,
) {
    public suspend operator fun invoke(sentence: String): FoodProposal {
        val fragments = SentenceSplitter.split(sentence)
        if (fragments.isEmpty()) return FoodProposal(sentence, emptyList(), jevUsed = false, jevNote = null)
        val savedMeals = meals.active()

        val candidates = coroutineScope {
            fragments.map { f -> async { f to candidatesFor(f) } }.map { it.await() }
        }.toMap()

        val inputs = fragments.map { f ->
            JevQuestions.FoodFragmentInput(f, candidates.getValue(f), needsPortion = f.quantity == null)
        }
        val (state, questions) = JevQuestions.foodRequest(sentence, inputs, savedMeals)
        val result = if (questions.isEmpty()) JudgementResult.Unavailable("nothing to ask")
        else judgement.judge(state, questions, JevQuestions.Timeouts.food)

        val answers = result as? JudgementResult.Answers
        val expanded = fragments.mapNotNull { f -> mealFor(f, savedMeals, answers)?.let { f to it } }
        val foodRows = fragments.filter { f -> expanded.none { it.first == f } }.mapNotNull { f -> row(f, candidates.getValue(f), answers) }
        val mealRows = expanded.flatMap { (f, meal) -> meal.items.map { item -> mealRow(f, meal, item, answers) } }

        return FoodProposal(
            sentence = sentence,
            rows = merge(mealRows, foodRows),
            jevUsed = answers != null,
            jevNote = (result as? JudgementResult.Unavailable)?.reason,
        )
    }

    private suspend fun candidatesFor(fragment: Fragment): List<Food> {
        val local = foods.search(fragment.foodText, limit = CANDIDATE_LIMIT)
        if (local.isNotEmpty()) return local
        val remote = withTimeoutOrNull(FDC_TIMEOUT) {
            runCatching { lookup.search(fragment.foodText, CANDIDATE_LIMIT, FDC_TIMEOUT) }.getOrDefault(emptyList())
        } ?: emptyList()
        return remote
    }

    /** An exact name is code's call; a nickname is Jev's, above its threshold. Negated fragments never expand. */
    private fun mealFor(fragment: Fragment, saved: List<MealTemplate>, answers: JudgementResult.Answers?): MealTemplate? {
        if (fragment.negated || saved.isEmpty()) return null
        val keys = setOf(MealTemplate.nameKey(fragment.foodText), MealTemplate.nameKey(fragment.text))
        saved.firstOrNull { it.nameKey in keys }?.let { return it }
        val choice = answers?.choice(JevQuestions.foodMealId(fragment.index)) ?: return null
        if (choice.choice == JevQuestions.NONE_OF_THESE || choice.confidence < JevQuestions.Thresholds.MEAL_MATCH_CONFIDENCE) return null
        return saved.firstOrNull { it.id == choice.choice }
    }

    private suspend fun mealRow(fragment: Fragment, meal: MealTemplate, item: MealItem, answers: JudgementResult.Answers?): ProposedRow {
        val food = item.foodId?.let { foods.get(it) }
        val confidence = answers?.choice(JevQuestions.foodMealId(fragment.index))?.confidence
        // A synthetic fragment carries the item's own label so the row reads as the item, not as "usual breakfast".
        val itemFragment = Fragment(fragment.index, item.label, item.label, null)
        return if (food != null && item.grams != null) {
            ProposedRow(
                fragment = itemFragment, candidates = listOf(food), selected = food,
                portion = food.portions.firstOrNull { it.grams == item.grams }, grams = item.grams,
                needsReview = false, matchConfidence = confidence, quickAdd = false, mealName = meal.name,
            )
        } else {
            ProposedRow(
                fragment = itemFragment, candidates = emptyList(), selected = null, portion = null, grams = null,
                needsReview = false, matchConfidence = confidence, quickAdd = true, mealName = meal.name,
                fixedKcal = item.kcal, fixedProtein = item.protein,
            )
        }
    }

    /**
     * Food rows edit meal rows: the same food replaces the meal's item, a negated
     * fragment removes it. Negated fragments that touch nothing are simply dropped —
     * the user said they did not eat it.
     */
    private fun merge(mealRows: List<ProposedRow>, foodRows: List<ProposedRow>): List<ProposedRow> {
        if (mealRows.isEmpty()) return foodRows.filterNot { it.fragment.negated }
        val (negated, eaten) = foodRows.partition { it.fragment.negated }
        val kept = mealRows.filterNot { m -> eaten.any { it.replaces(m) } || negated.any { it.removes(m) } }
        return kept + eaten
    }

    private fun ProposedRow.replaces(mealRow: ProposedRow): Boolean =
        selected != null && selected.id == mealRow.selected?.id

    private fun ProposedRow.removes(mealRow: ProposedRow): Boolean =
        replaces(mealRow) || mealRow.fragment.text.contains(fragment.foodText, ignoreCase = true)

    private fun row(fragment: Fragment, candidates: List<Food>, answers: JudgementResult.Answers?): ProposedRow? {
        val i = fragment.index
        val isFood = answers?.noul(JevQuestions.foodIsFoodId(i))
        if (isFood != null && isFood < JevQuestions.Thresholds.IS_FOOD_PROBABILITY) return null

        if (candidates.isEmpty()) {
            return ProposedRow(fragment, emptyList(), null, null, null, needsReview = true, matchConfidence = null, quickAdd = true)
        }

        val match: Answer.Choice? = answers?.choice(JevQuestions.foodMatchId(i))
        val selected: Food?
        val confident: Boolean
        when {
            match == null -> { selected = candidates.first(); confident = false }
            match.choice == JevQuestions.NONE_OF_THESE -> { selected = null; confident = false }
            else -> {
                selected = candidates.firstOrNull { it.id == match.choice } ?: candidates.first()
                confident = match.confidence >= JevQuestions.Thresholds.FOOD_MATCH_CONFIDENCE
            }
        }

        val portionAnswer = answers?.choice(JevQuestions.foodPortionId(i))
        val (portion, portionConfident) = portionFor(fragment, selected, portionAnswer)
        val grams = gramsFor(fragment, selected, portion)

        return ProposedRow(
            fragment = fragment,
            candidates = candidates,
            selected = selected,
            portion = portion,
            grams = grams,
            needsReview = selected == null || !confident || !portionConfident,
            matchConfidence = match?.confidence,
            quickAdd = false,
        )
    }

    private fun portionFor(fragment: Fragment, food: Food?, answer: Answer.Choice?): Pair<Portion?, Boolean> {
        if (food == null) return null to false
        if (fragment.quantity is Quantity.Grams) return null to true
        val chosen = answer?.let { a -> food.portions.firstOrNull { it.label == a.choice } }
        return when {
            chosen != null -> chosen to (answer.confidence >= JevQuestions.Thresholds.FOOD_PORTION_CONFIDENCE)
            fragment.quantity is Quantity.Count -> food.defaultPortion to true
            else -> food.defaultPortion to (answer == null && food.portions.size <= 1)
        }
    }

    private fun gramsFor(fragment: Fragment, food: Food?, portion: Portion?): Double? {
        if (food == null) return null
        return when (val q = fragment.quantity) {
            is Quantity.Grams -> q.grams
            is Quantity.Count -> (portion ?: food.defaultPortion).grams * q.n
            null -> (portion ?: food.defaultPortion).grams
        }
    }

    private companion object {
        const val CANDIDATE_LIMIT = 5
        val FDC_TIMEOUT = 3.seconds
    }
}
