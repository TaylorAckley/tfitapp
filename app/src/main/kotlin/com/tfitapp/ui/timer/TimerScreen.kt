package com.tfitapp.ui.timer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.PhaseKind
import com.tfitapp.domain.timer.TimerSnapshot
import com.tfitapp.domain.timer.TimerStatus
import com.tfitapp.ui.theme.Field
import com.tfitapp.ui.theme.Heading
import com.tfitapp.ui.theme.Primary
import com.tfitapp.ui.theme.Rule
import com.tfitapp.ui.theme.ScreenList
import com.tfitapp.ui.theme.TFit

@Composable
fun TimerScreen(vm: TimerViewModel = hiltViewModel()) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val running = snap
    if (running != null) RunningTimer(running, vm) else PlanPicker(vm)
}

/**
 * The screen is the phase. Work is orange, rest and prep are shade, done is iron. Nothing
 * else on the page competes with the number: it has to read from the far end of the course.
 */
@Composable
private fun RunningTimer(snap: TimerSnapshot, vm: TimerViewModel) {
    val c = TFit.colors
    val finished = snap.status == TimerStatus.FINISHED
    val field = when {
        finished -> c.iron
        snap.phase.kind == PhaseKind.WORK || snap.phase.kind == PhaseKind.LEG || snap.phase.kind == PhaseKind.ORIGIN -> c.signal
        else -> c.shade
    }
    val bg by animateColorAsState(field, label = "phase")
    val next by vm.nextPhase.collectAsStateWithLifecycle()
    LightStatusBarIcons()
    val ink = Color.White
    val inkMuted = Color.White.copy(alpha = 0.72f)

    Box(Modifier.fillMaxSize().background(bg)) {
        // The progress line along the top edge: the only thing that moves besides the digits.
        snap.progress?.let { p ->
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth(1f - p).height(4.dp).background(ink))
        }

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(snap.phase.label, style = MaterialTheme.typography.headlineMedium, color = ink)
                Text(
                    when {
                        finished -> "Done"
                        snap.status == TimerStatus.PAUSED -> "Paused, ${snap.phaseIndex + 1} of ${snap.phaseCount}"
                        else -> "${snap.phaseIndex + 1} of ${snap.phaseCount}"
                    },
                    style = MaterialTheme.typography.bodyLarge, color = inkMuted,
                )
            }

            Spacer(Modifier.weight(1f))

            val ms = snap.remainingMs ?: snap.elapsedInPhaseMs
            Text(
                formatClock(ms, countUp = snap.remainingMs == null),
                style = MaterialTheme.typography.displayLarge, color = ink, textAlign = TextAlign.Center,
            )
            snap.phase.reps?.let {
                Text("$it reps", style = MaterialTheme.typography.displaySmall, color = inkMuted)
            }
            if (snap.phase.isSelfPaced) {
                Text("Tap Done when you're back", style = MaterialTheme.typography.bodyLarge, color = inkMuted, modifier = Modifier.padding(top = 8.dp))
            }
            if (!finished) {
                Text(
                    next?.let { n -> "then " + n.label.replaceFirstChar(Char::lowercase) + (n.seconds?.let { ", $it s" } ?: "") } ?: "last one",
                    style = MaterialTheme.typography.bodyLarge, color = inkMuted, modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            if (snap.splits.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    snap.splits.takeLast(4).forEach { s ->
                        Column {
                            Text(formatClock(s.elapsedMs, countUp = true), style = MaterialTheme.typography.headlineLarge, color = ink)
                            Text(s.pointIndex?.let { "P${it + 1}" } ?: "split", style = MaterialTheme.typography.labelSmall, color = inkMuted)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (snap.status) {
                    TimerStatus.RUNNING -> Ghost("Pause", vm::pause, Modifier.weight(1f), ink)
                    TimerStatus.PAUSED -> Ghost("Resume", vm::resume, Modifier.weight(1f), ink)
                    TimerStatus.FINISHED -> {}
                }
                if (!finished) {
                    Solid(if (snap.phase.isSelfPaced) "Done" else "Skip", vm::advance, Modifier.weight(1.4f), ink, bg)
                }
                Ghost(if (finished) "Close" else "Stop", vm::stop, Modifier.weight(if (finished) 1f else 0.8f), ink)
            }
        }
    }
}

/** Outlined in the ink colour so it sits on any phase field. */
@Composable
private fun Ghost(text: String, onClick: () -> Unit, modifier: Modifier, ink: Color) {
    Box(
        modifier.height(64.dp)
            .border(2.dp, ink, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleLarge, color = ink) }
}

/** Filled with the ink colour; text takes the field colour. The big affordance. */
@Composable
private fun Solid(text: String, onClick: () -> Unit, modifier: Modifier, ink: Color, field: Color) {
    Box(
        modifier.height(64.dp).background(ink, MaterialTheme.shapes.medium).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.headlineSmall, color = field) }
}

@Composable
private fun PlanPicker(vm: TimerViewModel) {
    val c = TFit.colors
    val course by vm.course.collectAsStateWithLifecycle()
    var work by rememberSaveable { mutableStateOf("40") }
    var rest by rememberSaveable { mutableStateOf("20") }
    var rounds by rememberSaveable { mutableStateOf("8") }
    var points by rememberSaveable { mutableStateOf("3") }
    var lRounds by rememberSaveable { mutableStateOf("3") }
    var lRest by rememberSaveable { mutableStateOf("90") }
    var origin by rememberSaveable { mutableStateOf("5") }

    ScreenList(gap = 0.dp) {
        item { Heading("Presets") }
        items(vm.presets, key = { it.id }) { plan -> PresetRow(plan) { vm.start(plan) } }

        item { Heading("Your own"); Spacer(Modifier.height(12.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(work, { work = it.filter(Char::isDigit) }, "work, s", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                Field(rest, { rest = it.filter(Char::isDigit) }, "rest, s", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                Field(rounds, { rounds = it.filter(Char::isDigit) }, "rounds", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
            }
            val w = work.toIntOrNull(); val r = rest.toIntOrNull(); val n = rounds.toIntOrNull()
            val total = if (w != null && r != null && n != null) (w + maxOf(r, 1)) * n - maxOf(r, 1) else null
            Primary(
                total?.let { "Start, ${formatMinutes(it)}" } ?: "Start",
                { if (w != null && r != null && n != null && w > 0 && r >= 0 && n > 0) vm.startCustom(w, maxOf(r, 1), n) },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = total != null && total > 0,
            )
        }

        item { Heading("Lines"); Spacer(Modifier.height(12.dp)) }
        val cs = course
        if (cs == null) {
            item { Text("Enter your course distances in Settings and lines appear here.", style = MaterialTheme.typography.bodyMedium, color = c.ironMuted) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    cs.points.forEach { p ->
                        Column {
                            Text("${p.metres}", style = MaterialTheme.typography.headlineLarge, color = c.iron)
                            Text("${p.label}, m", style = MaterialTheme.typography.labelSmall, color = c.ironMuted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(points, { points = it.filter(Char::isDigit) }, "points", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                    Field(lRounds, { lRounds = it.filter(Char::isDigit) }, "rounds", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                    Field(lRest, { lRest = it.filter(Char::isDigit) }, "rest, s", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                    Field(origin, { origin = it.filter(Char::isDigit) }, "burpees", Modifier.weight(1f), numeric = true, keyboardOptions = digits)
                }
                Primary(
                    "Start lines",
                    {
                        val p = points.toIntOrNull()?.coerceIn(1, cs.points.size) ?: return@Primary
                        vm.startLines(cs, p, lRounds.toIntOrNull() ?: 1, lRest.toIntOrNull() ?: 60, origin.toIntOrNull())
                    },
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = points.toIntOrNull() != null,
                )
            }
        }
    }
}

/** One row per preset: the name, then the duration set large on the right. Tap anywhere to start. */
@Composable
private fun PresetRow(plan: IntervalPlan, onStart: () -> Unit) {
    val c = TFit.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onStart)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleLarge, color = c.iron)
                Text(if (plan.phases.size == 1) "1 phase" else "${plan.phases.size} phases", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
            }
            Text(formatMinutes(plan.timedSeconds), style = MaterialTheme.typography.displaySmall, color = c.iron)
            Text("Start", style = MaterialTheme.typography.labelLarge, color = c.signal, modifier = Modifier.padding(start = 16.dp))
        }
        Rule()
    }
}

private val digits = KeyboardOptions(keyboardType = KeyboardType.Number)

private fun formatMinutes(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:%02d".format(s)
}

private fun formatClock(ms: Long, countUp: Boolean): String {
    val totalSeconds = if (countUp) ms / 1000 else (ms + 999) / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

/** The phase field is saturated whatever the system theme, so the clock and battery go white while it is up. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, dark) {
        val window = view.context.activity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = !dark }
    }
}

/** Compose hands out the Activity wrapped in theme contexts; unwrap until we find it. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
