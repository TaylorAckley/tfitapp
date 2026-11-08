package com.tfitapp.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfitapp.application.nutrition.ArchiveMealTemplate
import com.tfitapp.application.nutrition.ConfirmedRow
import com.tfitapp.application.nutrition.DaySummary
import com.tfitapp.application.nutrition.LogFoodRows
import com.tfitapp.application.nutrition.LogMealTemplate
import com.tfitapp.application.nutrition.LogWeight
import com.tfitapp.application.nutrition.ObserveDaySummary
import com.tfitapp.application.nutrition.ProposeFoodRows
import com.tfitapp.application.nutrition.ProposedRow
import com.tfitapp.application.nutrition.QuickAddFood
import com.tfitapp.application.nutrition.SaveMealTemplate
import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.nutrition.Portion
import com.tfitapp.domain.time.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** One editable line of the proposal. [entryId] is fixed at proposal time so re-saving is an upsert. */
data class RowState(
    val entryId: String = UUID.randomUUID().toString(),
    val proposed: ProposedRow,
    val selected: Food? = proposed.selected,
    val portion: Portion? = proposed.portion,
    val grams: Double? = proposed.grams,
    val quickKcal: String = proposed.fixedKcal?.let { whole(it) } ?: "",
    val quickProtein: String = proposed.fixedProtein?.let { whole(it) } ?: "",
    val overridden: Boolean = false,
    val expanded: Boolean = proposed.needsReview,
) {
    val label: String get() = selected?.name ?: proposed.fragment.text
    val kcal: Double? get() = selected?.let { f -> grams?.let { f.kcalFor(it) } } ?: quickKcal.toDoubleOrNull()
    val protein: Double? get() = selected?.let { f -> grams?.let { f.proteinFor(it) } } ?: quickProtein.toDoubleOrNull()
}

data class FoodUiState(
    val sentence: String = "",
    val busy: Boolean = false,
    val rows: List<RowState> = emptyList(),
    val jevUsed: Boolean = false,
    val jevNote: String? = null,
    val slot: MealSlot? = null,
    val weightInput: String = "",
    val message: String? = null,
    /** Which of today's meal slots is about to be saved as a meal, and under what name. */
    val saveMealSlot: MealSlot? = null,
    val saveMealName: String = "",
)

sealed interface FoodEvent {
    data class SentenceChanged(val text: String) : FoodEvent
    data object Propose : FoodEvent
    data class SlotChanged(val slot: MealSlot?) : FoodEvent
    data class ToggleRow(val entryId: String) : FoodEvent
    data class PickCandidate(val entryId: String, val food: Food) : FoodEvent
    data class PickPortion(val entryId: String, val portion: Portion, val count: Double) : FoodEvent
    data class GramsChanged(val entryId: String, val grams: String) : FoodEvent
    data class QuickKcalChanged(val entryId: String, val kcal: String, val protein: String) : FoodEvent
    data class RemoveRow(val entryId: String) : FoodEvent
    data object LogAll : FoodEvent
    data object Discard : FoodEvent
    data class WeightChanged(val text: String) : FoodEvent
    data object SaveWeight : FoodEvent
    data class DeleteEntry(val id: String) : FoodEvent
    data class SaveMealSlotChanged(val slot: MealSlot?) : FoodEvent
    data class SaveMealNameChanged(val text: String) : FoodEvent
    data object SaveMeal : FoodEvent
    data class LogMeal(val id: String) : FoodEvent
    data class RemoveMeal(val id: String) : FoodEvent
    data object DismissMessage : FoodEvent
}

@HiltViewModel
class FoodViewModel @Inject constructor(
    private val propose: ProposeFoodRows,
    private val logRows: LogFoodRows,
    private val quickAdd: QuickAddFood,
    private val logWeight: LogWeight,
    private val foodLog: FoodLogRepository,
    private val saveMeal: SaveMealTemplate,
    private val logMeal: LogMealTemplate,
    private val archiveMeal: ArchiveMealTemplate,
    mealTemplates: MealTemplateRepository,
    observeDay: ObserveDaySummary,
    private val clock: WallClock,
    zone: ZoneId,
) : ViewModel() {

    private val today: LocalDate = clock.now().atZone(zone).toLocalDate()

    val summary: StateFlow<DaySummary?> = observeDay(today).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val meals: StateFlow<List<MealTemplate>> = mealTemplates.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(FoodUiState())
    val ui: StateFlow<FoodUiState> = _ui.asStateFlow()

    fun onEvent(e: FoodEvent) {
        when (e) {
            is FoodEvent.SentenceChanged -> _ui.update { it.copy(sentence = e.text) }
            FoodEvent.Propose -> proposeNow()
            is FoodEvent.SlotChanged -> _ui.update { it.copy(slot = e.slot) }
            is FoodEvent.ToggleRow -> updateRow(e.entryId) { it.copy(expanded = !it.expanded) }
            is FoodEvent.PickCandidate -> updateRow(e.entryId) { r ->
                val portion = e.food.defaultPortion
                r.copy(selected = e.food, portion = portion, grams = r.grams ?: portion.grams, overridden = true)
            }
            is FoodEvent.PickPortion -> updateRow(e.entryId) { it.copy(portion = e.portion, grams = e.portion.grams * e.count, overridden = true) }
            is FoodEvent.GramsChanged -> updateRow(e.entryId) { it.copy(grams = e.grams.toDoubleOrNull(), portion = null, overridden = true) }
            is FoodEvent.QuickKcalChanged -> updateRow(e.entryId) { it.copy(quickKcal = e.kcal, quickProtein = e.protein, overridden = true) }
            is FoodEvent.RemoveRow -> _ui.update { s -> s.copy(rows = s.rows.filterNot { it.entryId == e.entryId }) }
            FoodEvent.LogAll -> logAll()
            FoodEvent.Discard -> _ui.update { it.copy(rows = emptyList(), sentence = "", jevNote = null) }
            is FoodEvent.WeightChanged -> _ui.update { it.copy(weightInput = e.text) }
            FoodEvent.SaveWeight -> saveWeight()
            is FoodEvent.DeleteEntry -> viewModelScope.launch { foodLog.softDelete(e.id, clock.now()) }
            is FoodEvent.SaveMealSlotChanged -> _ui.update { it.copy(saveMealSlot = e.slot) }
            is FoodEvent.SaveMealNameChanged -> _ui.update { it.copy(saveMealName = e.text) }
            FoodEvent.SaveMeal -> saveMealNow()
            is FoodEvent.LogMeal -> logMealNow(e.id)
            is FoodEvent.RemoveMeal -> viewModelScope.launch { archiveMeal(e.id) }
            FoodEvent.DismissMessage -> _ui.update { it.copy(message = null) }
        }
    }

    private fun proposeNow() {
        val sentence = _ui.value.sentence.trim()
        if (sentence.isEmpty() || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val proposal = runCatching { propose(sentence) }.getOrElse { t ->
                _ui.update { it.copy(busy = false, message = "Could not read that: ${t.message}") }
                return@launch
            }
            _ui.update {
                it.copy(
                    busy = false,
                    rows = proposal.rows.map { r -> RowState(proposed = r) },
                    jevUsed = proposal.jevUsed,
                    jevNote = proposal.jevNote,
                    message = if (proposal.rows.isEmpty()) "Nothing I could read as food" else null,
                )
            }
        }
    }

    private fun logAll() {
        val s = _ui.value
        if (s.rows.isEmpty()) return
        viewModelScope.launch {
            val confirmed = s.rows.map { r ->
                ConfirmedRow(
                    entryId = r.entryId,
                    row = r.proposed.copy(selected = r.selected, portion = r.portion, grams = r.grams, quickAdd = r.selected == null),
                    userOverrode = r.overridden,
                    quickKcal = r.quickKcal.toDoubleOrNull(),
                    quickProtein = r.quickProtein.toDoubleOrNull(),
                )
            }
            logRows(confirmed, s.slot, s.jevUsed)
            _ui.update { it.copy(rows = emptyList(), sentence = "", jevNote = null, message = "Logged ${confirmed.size} item${if (confirmed.size == 1) "" else "s"}") }
        }
    }

    private fun saveWeight() {
        val kg = _ui.value.weightInput.replace(',', '.').toDoubleOrNull() ?: return
        viewModelScope.launch {
            logWeight(kg)
            _ui.update { it.copy(weightInput = "", message = "Weight saved") }
        }
    }

    private fun saveMealNow() {
        val s = _ui.value
        val slot = s.saveMealSlot ?: return
        val name = s.saveMealName.trim()
        val entries = summary.value?.entries.orEmpty().filter { it.mealSlot == slot }
        if (name.isEmpty() || entries.isEmpty()) return
        viewModelScope.launch {
            saveMeal(name, entries)
            _ui.update { it.copy(saveMealSlot = null, saveMealName = "", message = "Saved \"$name\"") }
        }
    }

    private fun logMealNow(id: String) {
        val template = meals.value.firstOrNull { it.id == id } ?: return
        // Ids are fixed here, at the tap, so a retried save is an upsert of the same rows.
        val entryIds = template.items.map { UUID.randomUUID().toString() }
        viewModelScope.launch {
            logMeal(template, _ui.value.slot, entryIds)
            _ui.update { it.copy(message = "Logged ${template.name}") }
        }
    }

    private fun updateRow(entryId: String, transform: (RowState) -> RowState) =
        _ui.update { s -> s.copy(rows = s.rows.map { if (it.entryId == entryId) transform(it) else it }) }

    @Suppress("unused")
    fun quickAdd(label: String, kcal: Double) = viewModelScope.launch { quickAdd(label, kcal, null, _ui.value.slot) }
}

private fun whole(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
