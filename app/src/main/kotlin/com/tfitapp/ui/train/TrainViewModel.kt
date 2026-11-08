package com.tfitapp.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.application.training.PlanForBlock
import com.tfitapp.application.training.ProposeSession
import com.tfitapp.application.training.ReadCheckOut
import com.tfitapp.application.training.RecordSet
import com.tfitapp.application.training.SaveCheckOut
import com.tfitapp.application.training.SessionProposal
import com.tfitapp.application.training.StartOrFinishSession
import com.tfitapp.application.training.SwapBlock
import com.tfitapp.domain.timer.PhaseKind
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionBlock
import com.tfitapp.timer.TimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrainUiState(
    val checkIn: String = "",
    val busy: Boolean = false,
    val session: Session? = null,
    val offerBoth: List<com.tfitapp.domain.training.SessionCandidate> = emptyList(),
    val notes: List<String> = emptyList(),
    val forecastSummary: String? = null,
    val jevUsed: Boolean = false,
    /** Sets logged per block id this session. */
    val setsDone: Map<String, Int> = emptyMap(),
    val message: String? = null,
    /** Present between "Finish session" and the check-out being saved or skipped. */
    val checkOut: CheckOutState? = null,
)

/** The finished session, what the user typed about it, and how each block felt: Jev's reading, corrected by taps. */
data class CheckOutState(
    val session: Session,
    val text: String = "",
    val feels: Map<String, BlockFeel> = emptyMap(),
    val sources: Map<String, FeedbackSource> = emptyMap(),
    /** True once Jev has read the current text; typing again clears it. */
    val read: Boolean = false,
    val busy: Boolean = false,
    val jevNote: String? = null,
)

sealed interface TrainEvent {
    data class CheckInChanged(val text: String) : TrainEvent
    data object Plan : TrainEvent
    data class Choose(val candidateId: String) : TrainEvent
    data object Start : TrainEvent
    data class Swap(val blockId: String) : TrainEvent
    data class DoneSet(val blockId: String, val reps: Int?) : TrainEvent
    data class StartTimer(val blockId: String) : TrainEvent
    data object Finish : TrainEvent
    data class CheckOutChanged(val text: String) : TrainEvent
    data object ReadCheckOut : TrainEvent
    data class FeelChanged(val blockId: String, val feel: BlockFeel) : TrainEvent
    data object SaveCheckOut : TrainEvent
    data object SkipCheckOut : TrainEvent
    data object DismissMessage : TrainEvent
}

@HiltViewModel
class TrainViewModel @Inject constructor(
    private val propose: ProposeSession,
    private val swap: SwapBlock,
    private val recordSet: RecordSet,
    private val lifecycle: StartOrFinishSession,
    private val planForBlock: PlanForBlock,
    private val readCheckOut: ReadCheckOut,
    private val saveCheckOut: SaveCheckOut,
    private val sessions: SessionRepository,
    private val timer: TimerController,
) : ViewModel() {

    private val _ui = MutableStateFlow(TrainUiState())
    val ui: StateFlow<TrainUiState> = _ui.asStateFlow()

    init {
        // Pick up an unfinished session from earlier today (e.g. after the app was killed).
        viewModelScope.launch {
            val latest = sessions.recent(1).firstOrNull() ?: return@launch
            if (latest.finishedAt == null) {
                val done = sessions.setLogs(latest.id).groupBy { it.blockId }.mapValues { it.value.size }
                _ui.update { it.copy(session = latest, setsDone = done, checkIn = latest.checkInText ?: "") }
            }
        }
    }

    fun onEvent(e: TrainEvent) {
        when (e) {
            is TrainEvent.CheckInChanged -> _ui.update { it.copy(checkIn = e.text) }
            TrainEvent.Plan -> plan()
            is TrainEvent.Choose -> withSession { s -> _ui.update { it.copy(session = lifecycle.choose(s, e.candidateId), offerBoth = emptyList()) } }
            TrainEvent.Start -> withSession { s -> _ui.update { it.copy(session = lifecycle.start(s)) } }
            is TrainEvent.Swap -> withSession { s -> _ui.update { it.copy(session = swap(s, e.blockId)) } }
            is TrainEvent.DoneSet -> withSession { s ->
                val block = s.candidate.blocks.first { it.id == e.blockId }
                val ordinal = (_ui.value.setsDone[e.blockId] ?: 0) + 1
                val sled = (block.prescription as? Prescription.Intervals)?.sledSetting
                recordSet(s, block, ordinal, e.reps, sled, null)
                _ui.update { it.copy(setsDone = it.setsDone + (e.blockId to ordinal)) }
            }
            is TrainEvent.StartTimer -> withSession { s ->
                val block = s.candidate.blocks.first { it.id == e.blockId }
                val plan = planForBlock(block)
                if (plan == null) _ui.update { it.copy(message = "That block is sets × reps — use Done set") }
                else {
                    if (s.startedAt == null) _ui.update { it.copy(session = lifecycle.start(s)) }
                    timer.start(plan, forSessionBlockId = block.id)
                    // A timed block counts as one completed set once started; the run itself is logged by the timer.
                    recordSet(s, block, (_ui.value.setsDone[e.blockId] ?: 0) + 1, null, (block.prescription as? Prescription.Intervals)?.sledSetting, null)
                    _ui.update { it.copy(setsDone = it.setsDone + (e.blockId to (it.setsDone[e.blockId] ?: 0) + 1), message = "Timer started") }
                }
            }
            TrainEvent.Finish -> withSession { s ->
                val finished = lifecycle.finish(s)
                _ui.update { TrainUiState(checkOut = CheckOutState(finished)) }
            }
            is TrainEvent.CheckOutChanged -> updateCheckOut { it.copy(text = e.text, read = false) }
            TrainEvent.ReadCheckOut -> readCheckOutNow()
            is TrainEvent.FeelChanged -> updateCheckOut { co ->
                // Tapping the chosen feel again clears it; a tap is always the user's own word over Jev's.
                if (co.feels[e.blockId] == e.feel) co.copy(feels = co.feels - e.blockId, sources = co.sources - e.blockId)
                else co.copy(feels = co.feels + (e.blockId to e.feel), sources = co.sources + (e.blockId to FeedbackSource.MANUAL))
            }
            TrainEvent.SaveCheckOut -> saveCheckOutNow()
            TrainEvent.SkipCheckOut -> _ui.update { TrainUiState(message = "Session saved") }
            TrainEvent.DismissMessage -> _ui.update { it.copy(message = null) }
        }
    }

    private fun plan() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result: SessionProposal = runCatching { propose(_ui.value.checkIn.takeIf { it.isNotBlank() }) }.getOrElse { t ->
                _ui.update { it.copy(busy = false, message = "Could not plan: ${t.message}") }
                return@launch
            }
            _ui.update {
                it.copy(
                    busy = false,
                    session = result.session,
                    offerBoth = result.offerBoth,
                    notes = result.notes,
                    forecastSummary = result.forecastSummary,
                    jevUsed = result.jevUsed,
                    setsDone = emptyMap(),
                )
            }
        }
    }

    private fun readCheckOutNow() {
        val co = _ui.value.checkOut ?: return
        if (co.busy || co.text.isBlank()) return
        updateCheckOut { it.copy(busy = true) }
        viewModelScope.launch {
            val reading = runCatching { readCheckOut(co.session, co.text) }.getOrElse { t ->
                updateCheckOut { it.copy(busy = false, jevNote = t.message ?: "could not read it") }
                return@launch
            }
            updateCheckOut { cur ->
                // Jev fills only the blocks the user has not tapped themselves.
                val manual = cur.sources.filterValues { it == FeedbackSource.MANUAL }.keys
                val fromJev = reading.feels.filterKeys { it !in manual }
                cur.copy(
                    busy = false, read = true, jevNote = reading.jevNote,
                    feels = cur.feels.filterKeys { it in manual } + fromJev,
                    sources = cur.sources.filterKeys { it in manual } + fromJev.mapValues { FeedbackSource.JEV },
                )
            }
        }
    }

    private fun saveCheckOutNow() {
        val co = _ui.value.checkOut ?: return
        if (co.busy) return
        updateCheckOut { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { saveCheckOut(co.session, co.text, co.feels, co.sources) }
                .onSuccess { _ui.update { TrainUiState(message = "Session saved") } }
                .onFailure { t -> updateCheckOut { it.copy(busy = false, jevNote = t.message ?: "could not save") } }
        }
    }

    private fun updateCheckOut(transform: (CheckOutState) -> CheckOutState) =
        _ui.update { s -> s.copy(checkOut = s.checkOut?.let(transform)) }

    private fun withSession(block: suspend (Session) -> Unit) {
        val s = _ui.value.session ?: return
        viewModelScope.launch { runCatching { block(s) }.onFailure { t -> _ui.update { it.copy(message = t.message ?: "Something went wrong") } } }
    }

    /** Short facts about a block, shown side by side; the screen decides how to separate them. */
    fun blockFacts(block: SessionBlock): List<String> = when (val p = block.prescription) {
        is Prescription.SetsReps -> listOfNotNull("${p.sets} sets of ${p.repsTarget}, up to ${p.repsMax}", p.loadLabel, "rest ${p.restSeconds} s")
        is Prescription.Intervals -> {
            val work = p.plan.phases.firstOrNull { it.kind == PhaseKind.WORK }
            val rest = p.plan.phases.firstOrNull { it.kind == PhaseKind.REST }
            listOfNotNull(
                "${p.plan.phases.count { it.kind == PhaseKind.WORK }} rounds of ${work?.seconds ?: 0} s",
                rest?.let { "rest ${it.seconds} s" },
                p.sledSetting?.let { "Tank setting $it" },
            )
        }
        is Prescription.Circuit -> listOf("${p.plan.phases.count { it.kind == PhaseKind.WORK } / p.movementNames.size.coerceAtLeast(1)} rounds", p.movementNames.joinToString(", "))
        is Prescription.Lines -> listOfNotNull("P1 to P${p.pointsUsed}, ${p.rounds} rounds", "rest ${p.restSeconds} s", p.origin?.let { "${it.reps} ${it.name} between legs" })
    }
}
