package com.tfitapp.domain.timer

import kotlinx.serialization.Serializable

@Serializable
public enum class TimerStatus { RUNNING, PAUSED, FINISHED }

/** Time taken for one self-paced phase, recorded when the user taps. */
@Serializable
public data class Split(val phaseIndex: Int, val pointIndex: Int?, val elapsedMs: Long)

/**
 * Everything needed to resume a run after process death. All times are monotonic
 * milliseconds from the same [com.tfitapp.domain.time.MonotonicClock]; a reboot
 * invalidates them, and the host must treat a stale state as finished.
 */
@Serializable
public data class TimerState(
    val plan: IntervalPlan,
    val phaseIndex: Int,
    /** Monotonic ms at which the current phase (re)started, shifted forward by any pauses. */
    val phaseStartMs: Long,
    val pausedAtMs: Long?,
    val status: TimerStatus,
    val splits: List<Split>,
    val runStartedMs: Long,
    val pausedTotalMs: Long,
) {
    val phase: Phase get() = plan.phases[phaseIndex]
    val isLastPhase: Boolean get() = phaseIndex == plan.phases.lastIndex
}

public sealed interface TimerEvent {
    public data class PhaseStarted(val phaseIndex: Int, val phase: Phase) : TimerEvent
    public data object Finished : TimerEvent
}

/** What the UI renders. Derived, never stored. */
public data class TimerSnapshot(
    val status: TimerStatus,
    val phaseIndex: Int,
    val phase: Phase,
    val phaseCount: Int,
    /** Milliseconds left in a timed phase; null for self-paced. */
    val remainingMs: Long?,
    /** Milliseconds spent in the current phase so far. */
    val elapsedInPhaseMs: Long,
    /** 0..1 through a timed phase; null for self-paced. */
    val progress: Float?,
    val splits: List<Split>,
) {
    val remainingWholeSeconds: Int? get() = remainingMs?.let { ((it + 999) / 1000).toInt() }
}

/**
 * Pure interval-timer state machine. Every transition takes the current monotonic
 * time and returns a new state; nothing here counts ticks. The host calls [tick]
 * as often as it likes — every 100 ms or once after a ten-minute gap — and gets
 * the same answer.
 */
public object TimerEngine {

    public fun start(plan: IntervalPlan, nowMs: Long): Pair<TimerState, List<TimerEvent>> {
        val state = TimerState(
            plan = plan,
            phaseIndex = 0,
            phaseStartMs = nowMs,
            pausedAtMs = null,
            status = TimerStatus.RUNNING,
            splits = emptyList(),
            runStartedMs = nowMs,
            pausedTotalMs = 0,
        )
        return state to listOf(TimerEvent.PhaseStarted(0, plan.phases[0]))
    }

    /** Advances through any timed phases that have elapsed. Self-paced phases never auto-advance. */
    public fun tick(state: TimerState, nowMs: Long): Pair<TimerState, List<TimerEvent>> {
        if (state.status != TimerStatus.RUNNING) return state to emptyList()
        var current = state
        val events = mutableListOf<TimerEvent>()
        while (current.status == TimerStatus.RUNNING) {
            val duration = current.phase.seconds ?: return current to events
            val endMs = current.phaseStartMs + duration * 1000L
            if (nowMs < endMs) break
            val (next, evs) = moveToNextPhase(current, startAtMs = endMs)
            current = next
            events += evs
        }
        return current to events
    }

    /** User tap or skip. For a self-paced phase this records the split. */
    public fun advance(state: TimerState, nowMs: Long): Pair<TimerState, List<TimerEvent>> {
        if (state.status == TimerStatus.FINISHED) return state to emptyList()
        val effectiveNow = state.pausedAtMs ?: nowMs
        val withSplit = if (state.phase.isSelfPaced) {
            state.copy(
                splits = state.splits + Split(
                    phaseIndex = state.phaseIndex,
                    pointIndex = state.phase.pointIndex,
                    elapsedMs = effectiveNow - state.phaseStartMs,
                ),
            )
        } else state
        val (next, events) = moveToNextPhase(withSplit, startAtMs = effectiveNow)
        // Advancing while paused keeps the run paused, anchored at the pause instant.
        return (if (state.pausedAtMs != null && next.status == TimerStatus.RUNNING) {
            next.copy(pausedAtMs = state.pausedAtMs, status = TimerStatus.PAUSED)
        } else next) to events
    }

    public fun pause(state: TimerState, nowMs: Long): TimerState =
        if (state.status != TimerStatus.RUNNING) state
        else state.copy(status = TimerStatus.PAUSED, pausedAtMs = nowMs)

    public fun resume(state: TimerState, nowMs: Long): TimerState {
        val pausedAt = state.pausedAtMs ?: return state
        if (state.status != TimerStatus.PAUSED) return state
        val pausedFor = (nowMs - pausedAt).coerceAtLeast(0)
        return state.copy(
            status = TimerStatus.RUNNING,
            pausedAtMs = null,
            phaseStartMs = state.phaseStartMs + pausedFor,
            pausedTotalMs = state.pausedTotalMs + pausedFor,
        )
    }

    public fun snapshot(state: TimerState, nowMs: Long): TimerSnapshot {
        val effectiveNow = state.pausedAtMs ?: nowMs
        val elapsed = (effectiveNow - state.phaseStartMs).coerceAtLeast(0)
        val duration = state.phase.seconds?.let { it * 1000L }
        val remaining = duration?.let { (it - elapsed).coerceAtLeast(0) }
        return TimerSnapshot(
            status = state.status,
            phaseIndex = state.phaseIndex,
            phase = state.phase,
            phaseCount = state.plan.phases.size,
            remainingMs = if (state.status == TimerStatus.FINISHED) 0 else remaining,
            elapsedInPhaseMs = elapsed,
            progress = duration?.let { (elapsed.toFloat() / it).coerceIn(0f, 1f) },
            splits = state.splits,
        )
    }

    private fun moveToNextPhase(state: TimerState, startAtMs: Long): Pair<TimerState, List<TimerEvent>> {
        if (state.isLastPhase) {
            return state.copy(status = TimerStatus.FINISHED, pausedAtMs = null, phaseStartMs = startAtMs) to
                listOf(TimerEvent.Finished)
        }
        val nextIndex = state.phaseIndex + 1
        val next = state.copy(phaseIndex = nextIndex, phaseStartMs = startAtMs)
        return next to listOf(TimerEvent.PhaseStarted(nextIndex, next.phase))
    }
}
