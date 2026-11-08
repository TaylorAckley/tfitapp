package com.tfitapp.ui.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.LinesCourse
import com.tfitapp.domain.timer.LinesPlanBuilder
import com.tfitapp.domain.timer.OriginMovement
import com.tfitapp.domain.timer.Phase
import com.tfitapp.domain.timer.Presets
import com.tfitapp.domain.timer.TimerSnapshot
import com.tfitapp.timer.TimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val controller: TimerController,
    settings: SettingsRepository,
) : ViewModel() {

    val snapshot: StateFlow<TimerSnapshot?> = controller.snapshot

    /** The phase after the current one, so the screen can say what is coming. */
    val nextPhase: StateFlow<Phase?> = controller.state.map { s -> s?.plan?.phases?.getOrNull(s.phaseIndex + 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val presets: List<IntervalPlan> = Presets.all()
    val course: StateFlow<LinesCourse?> = settings.settings.map { it.linesCourse }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start(plan: IntervalPlan) = controller.start(plan)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun advance() = controller.advance()
    fun stop() = controller.stop()

    fun startCustom(work: Int, rest: Int, rounds: Int) =
        controller.start(Presets.workRest(UUID.randomUUID().toString(), "$work/$rest × $rounds", work, rest, rounds, isPreset = false))

    fun startLines(course: LinesCourse, points: Int, rounds: Int, rest: Int, originReps: Int?) =
        controller.start(
            LinesPlanBuilder.build(
                UUID.randomUUID().toString(), course, points, rounds, rest,
                origin = originReps?.takeIf { it > 0 }?.let { OriginMovement("Burpees", it) },
            ),
        )
}
