package com.tfitapp.timer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import com.tfitapp.application.port.TimerRun
import com.tfitapp.application.port.TimerRunRepository
import com.tfitapp.domain.time.MonotonicClock
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.TimerEngine
import com.tfitapp.domain.timer.TimerEvent
import com.tfitapp.domain.timer.TimerSnapshot
import com.tfitapp.domain.timer.TimerState
import com.tfitapp.domain.timer.TimerStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Cue the host should play. Derived from engine events plus the countdown into each phase change. */
sealed interface TimerCue {
    data class Countdown(val secondsLeft: Int) : TimerCue
    data class PhaseStarted(val label: String, val speak: Boolean) : TimerCue
    data object Finished : TimerCue
}

/**
 * The single owner of the running timer. Screens observe [snapshot]; the foreground
 * service observes [cues]. The engine is pure, so this class only supplies time,
 * runs the ticker, persists state for process death, and starts the service.
 */
@Singleton
class TimerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monotonic: MonotonicClock,
    private val wallClock: WallClock,
    private val runs: TimerRunRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<TimerState?>(null)
    val state: StateFlow<TimerState?> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<TimerSnapshot?>(null)
    val snapshot: StateFlow<TimerSnapshot?> = _snapshot.asStateFlow()

    private val _cues = MutableSharedFlow<TimerCue>(extraBufferCapacity = 16)
    val cues: SharedFlow<TimerCue> = _cues.asSharedFlow()

    private var ticker: Job? = null
    private var runId: String? = null
    private var runStartedAt: Instant? = null
    private var sessionBlockId: String? = null
    private var lastCountdownSecond = -1

    init {
        restore()
    }

    fun start(plan: IntervalPlan, forSessionBlockId: String? = null) {
        val (s, events) = TimerEngine.start(plan, monotonic.nowMs())
        runId = UUID.randomUUID().toString()
        runStartedAt = wallClock.now()
        sessionBlockId = forSessionBlockId
        apply(s, events)
        context.startForegroundService(Intent(context, TimerService::class.java))
    }

    fun pause() = _state.value?.let { apply(TimerEngine.pause(it, monotonic.nowMs()), emptyList()) }
    fun resume() = _state.value?.let { apply(TimerEngine.resume(it, monotonic.nowMs()), emptyList()) }

    /** Tap for a self-paced phase, skip for a timed one. */
    fun advance() = _state.value?.let { s ->
        val (next, events) = TimerEngine.advance(s, monotonic.nowMs())
        apply(next, events)
    }

    fun stop() {
        _state.value?.let { saveRun(it, finished = false) }
        ticker?.cancel()
        _state.value = null
        _snapshot.value = null
        prefs.edit().remove(KEY_STATE).apply()
    }

    private fun apply(next: TimerState, events: List<TimerEvent>) {
        _state.value = next
        _snapshot.value = TimerEngine.snapshot(next, monotonic.nowMs())
        persist(next)
        events.forEach { e ->
            when (e) {
                is TimerEvent.PhaseStarted -> {
                    lastCountdownSecond = -1
                    _cues.tryEmit(TimerCue.PhaseStarted(e.phase.label, speak = e.phase.kind != com.tfitapp.domain.timer.PhaseKind.REST))
                }
                TimerEvent.Finished -> {
                    _cues.tryEmit(TimerCue.Finished)
                    saveRun(next, finished = true)
                }
            }
        }
        if (next.status == TimerStatus.RUNNING) ensureTicker() else ticker?.cancel()
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = _state.value ?: break
                if (current.status != TimerStatus.RUNNING) break
                val now = monotonic.nowMs()
                val (next, events) = TimerEngine.tick(current, now)
                if (events.isNotEmpty() || next != current) {
                    apply(next, events)
                } else {
                    val snap = TimerEngine.snapshot(current, now)
                    _snapshot.value = snap
                    snap.remainingWholeSeconds?.let { s ->
                        if (s in 1..3 && s != lastCountdownSecond) {
                            lastCountdownSecond = s
                            _cues.tryEmit(TimerCue.Countdown(s))
                        }
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun persist(state: TimerState) {
        if (state.status == TimerStatus.FINISHED) {
            prefs.edit().remove(KEY_STATE).apply()
        } else {
            prefs.edit()
                .putString(KEY_STATE, json.encodeToString(TimerState.serializer(), state))
                .putLong(KEY_BOOT_ANCHOR, SystemClock.elapsedRealtime() - System.currentTimeMillis())
                .apply()
        }
    }

    /** After process death: resume if the device has not rebooted since the state was saved. */
    private fun restore() {
        val saved = prefs.getString(KEY_STATE, null) ?: return
        val anchorThen = prefs.getLong(KEY_BOOT_ANCHOR, Long.MIN_VALUE)
        val anchorNow = SystemClock.elapsedRealtime() - System.currentTimeMillis()
        // The boot anchor drifts by clock adjustments only; a reboot changes it by the whole uptime.
        if (anchorThen == Long.MIN_VALUE || kotlin.math.abs(anchorNow - anchorThen) > REBOOT_TOLERANCE_MS) {
            prefs.edit().remove(KEY_STATE).apply()
            return
        }
        runCatching { json.decodeFromString(TimerState.serializer(), saved) }
            .onSuccess { s ->
                Timber.i("Restoring timer run at phase ${s.phaseIndex}")
                runId = UUID.randomUUID().toString()
                runStartedAt = wallClock.now()
                apply(s, emptyList())
                if (s.status == TimerStatus.RUNNING) context.startForegroundService(Intent(context, TimerService::class.java))
            }
            .onFailure { prefs.edit().remove(KEY_STATE).apply() }
    }

    private fun saveRun(state: TimerState, finished: Boolean) {
        val id = runId ?: return
        val startedAt = runStartedAt ?: return
        val run = TimerRun(
            id = id,
            planId = state.plan.id,
            planName = state.plan.name,
            sessionBlockId = sessionBlockId,
            startedAt = startedAt,
            finishedAt = if (finished) wallClock.now() else null,
            completedPhases = if (finished) state.plan.phases.size else state.phaseIndex,
            pausedTotalMs = state.pausedTotalMs,
            splits = state.splits,
        )
        scope.launch { runCatching { runs.upsert(run) }.onFailure { Timber.w(it, "Could not save timer run") } }
    }

    private companion object {
        const val PREFS = "tfit.timer"
        const val KEY_STATE = "state"
        const val KEY_BOOT_ANCHOR = "bootAnchor"
        const val TICK_MS = 100L
        const val REBOOT_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
