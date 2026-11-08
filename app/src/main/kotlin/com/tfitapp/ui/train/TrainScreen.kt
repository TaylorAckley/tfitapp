package com.tfitapp.ui.train

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.Location
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.SessionBlock
import com.tfitapp.ui.theme.Facts
import com.tfitapp.ui.theme.Field
import com.tfitapp.ui.theme.Notice
import com.tfitapp.ui.theme.Primary
import com.tfitapp.ui.theme.Quiet
import com.tfitapp.ui.theme.Rule
import com.tfitapp.ui.theme.ScreenList
import com.tfitapp.ui.theme.Secondary
import com.tfitapp.ui.theme.TFit
import com.tfitapp.ui.theme.Toggle
import com.tfitapp.ui.theme.numberStyle

@Composable
fun TrainScreen(onOpenTimer: () -> Unit, vm: TrainViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = TFit.colors

    LaunchedEffect(ui.message) {
        if (ui.message == "Timer started") onOpenTimer()
        if (ui.message != null) {
            kotlinx.coroutines.delay(2_500)
            vm.onEvent(TrainEvent.DismissMessage)
        }
    }

    val session = ui.session
    val started = session?.startedAt != null

    ScreenList {
        ui.checkOut?.let { co ->
            checkOut(co, vm::onEvent)
            return@ScreenList
        }

        if (!started) {
            item {
                Field(
                    value = ui.checkIn,
                    onChange = { vm.onEvent(TrainEvent.CheckInChanged(it)) },
                    label = "how you are today, if it matters",
                    placeholder = "shoulder a bit tweaky, slept 5 h, got 30 min",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Primary(if (session == null) "Plan today" else "Plan again", { vm.onEvent(TrainEvent.Plan) }, enabled = !ui.busy)
                    if (ui.busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = c.signal)
                }
            }
        }

        if (session == null) {
            item {
                Text(
                    "Nothing planned yet. Say how you feel, or don't, and plan the day.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@ScreenList
        }

        if (ui.offerBoth.size == 2) {
            item { Text("Jev couldn't separate these two. Pick one.", style = MaterialTheme.typography.titleMedium, color = c.iron, modifier = Modifier.padding(top = 8.dp)) }
            items(ui.offerBoth, key = { "offer-" + it.id }) { cand ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cand.title, style = MaterialTheme.typography.titleMedium, color = c.iron)
                            Facts("${cand.estimatedMinutes} min", where(cand.location))
                        }
                        Quiet("This one", { vm.onEvent(TrainEvent.Choose(cand.id)) }, color = c.signal)
                    }
                    Rule()
                }
            }
        }

        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text(session.candidate.title, style = MaterialTheme.typography.headlineMedium, color = c.iron)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text("${session.candidate.estimatedMinutes}", style = MaterialTheme.typography.displaySmall, color = c.iron)
                    Text(
                        "min, ${where(session.candidate.location)}" + (ui.forecastSummary?.let { ", $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
                if (started) Text("In progress", style = MaterialTheme.typography.labelMedium, color = c.signal, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (ui.notes.isNotEmpty()) {
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ui.notes.forEach { Notice(it) } } }
        }

        item { Spacer(Modifier.height(4.dp)); Rule() }
        itemsIndexed(session.candidate.blocks) { i, block ->
            BlockRow(i + 1, block, vm.blockFacts(block), ui.setsDone[block.id] ?: 0, started = started, onEvent = vm::onEvent)
        }

        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!started) Primary("Start session", { vm.onEvent(TrainEvent.Start) }, Modifier.fillMaxWidth())
                else Primary("Finish session", { vm.onEvent(TrainEvent.Finish) }, Modifier.fillMaxWidth())

                if (session.alternatives.isNotEmpty() && !started) {
                    var showAlt by rememberSaveable { mutableStateOf(false) }
                    Quiet(if (showAlt) "Hide alternatives" else "Other options for today", { showAlt = !showAlt }, color = c.ironMuted)
                    if (showAlt) {
                        session.alternatives.forEach { alt ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(alt.title, style = MaterialTheme.typography.titleMedium, color = c.iron)
                                    Facts(alt.axis.displayName, "${alt.estimatedMinutes} min", where(alt.location))
                                }
                                Quiet("Use this", { vm.onEvent(TrainEvent.Choose(alt.id)) }, color = c.signal)
                            }
                        }
                    }
                }
            }
        }

        ui.message?.let { item { Notice(it, tone = c.shade) } }
    }
}

/**
 * After "Finish session": one line about how it went, read by Jev into a feel per
 * block, each of which the user can correct with a tap before saving. Without Jev
 * the taps alone do the job.
 */
private fun LazyListScope.checkOut(co: CheckOutState, onEvent: (TrainEvent) -> Unit) {
    item {
        Column(Modifier.padding(bottom = 4.dp)) {
            Text("How did it go?", style = MaterialTheme.typography.headlineMedium, color = TFit.colors.iron)
            Text(
                "${co.session.candidate.title}, ${co.session.candidate.estimatedMinutes} min. A line is enough; it decides what gets harder next time.",
                style = MaterialTheme.typography.bodySmall, color = TFit.colors.ironMuted, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    item {
        Field(
            value = co.text,
            onChange = { onEvent(TrainEvent.CheckOutChanged(it)) },
            label = "how it felt",
            placeholder = "swings were easy, Tank at 4 was brutal",
            singleLine = false,
            enabled = !co.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    co.jevNote?.let { item { Notice("Jev skipped (${it.replaceFirstChar(Char::lowercase)}). Tap how each block felt.") } }
    item { Spacer(Modifier.height(4.dp)); Rule() }
    itemsIndexed(co.session.candidate.blocks, key = { _, b -> "co-" + b.id }) { i, block ->
        val c = TFit.colors
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.headlineLarge, color = c.ironMuted, modifier = Modifier.width(32.dp))
                Text(block.title, style = MaterialTheme.typography.titleMedium, color = c.iron, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockFeel.entries.forEach { f -> Toggle(f.displayName, co.feels[block.id] == f) { onEvent(TrainEvent.FeelChanged(block.id, f)) } }
                }
            }
            Rule()
        }
    }
    item {
        val c = TFit.colors
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (co.text.isNotBlank() && !co.read) {
                    Primary("Read it", { onEvent(TrainEvent.ReadCheckOut) }, Modifier.weight(1f), enabled = !co.busy)
                } else {
                    Primary("Save session", { onEvent(TrainEvent.SaveCheckOut) }, Modifier.weight(1f), enabled = !co.busy)
                }
                if (co.busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = c.signal)
                else Quiet("Skip", { onEvent(TrainEvent.SkipCheckOut) }, color = c.ironMuted)
            }
            if (co.read && co.feels.isEmpty() && co.jevNote == null) {
                Text("Nothing in that line pointed at a block. Tap a feel, or save as is.", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
            }
        }
    }
}

private fun where(location: Location) = when (location) {
    Location.OUTDOOR -> "outside"
    Location.INDOOR -> "indoors"
}

/** Blocks are a sequence, so they are numbered. The number is the only ornament. */
@Composable
private fun BlockRow(ordinal: Int, block: SessionBlock, facts: List<String>, setsDone: Int, started: Boolean, onEvent: (TrainEvent) -> Unit) {
    val c = TFit.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Text("$ordinal", style = MaterialTheme.typography.headlineLarge, color = c.ironMuted, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(block.title, style = MaterialTheme.typography.titleLarge, color = c.iron)
                Column {
                    facts.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.iron) }
                    if (facts.size > 1) Text(facts.drop(1).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (val p = block.prescription) {
                        is Prescription.SetsReps -> {
                            var reps by rememberSaveable(block.id) { mutableStateOf(p.repsTarget.toString()) }
                            Field(
                                value = reps, onChange = { reps = it.filter(Char::isDigit) }, label = "reps", numeric = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(88.dp),
                            )
                            Secondary("Done set", { onEvent(TrainEvent.DoneSet(block.id, reps.toIntOrNull())) }, enabled = started)
                            SetsTally(setsDone, p.sets)
                        }
                        else -> {
                            Secondary(if (setsDone > 0) "Timer again" else "Start timer", { onEvent(TrainEvent.StartTimer(block.id)) })
                            if (setsDone > 0) Text("done", style = MaterialTheme.typography.labelMedium, color = c.moss)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!started) Quiet("Swap", { onEvent(TrainEvent.Swap(block.id)) }, color = c.ironMuted)
                }
            }
        }
        Rule()
    }
}

/** One mark per set, filled as they are done: a tally, not a fraction. */
@Composable
private fun SetsTally(done: Int, total: Int) {
    val c = TFit.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i -> Box(Modifier.width(6.dp).height(18.dp).background(if (i < done) c.iron else c.rule)) }
        if (done > total) Text("+${done - total}", style = numberStyle(MaterialTheme.typography.labelMedium), color = c.iron, modifier = Modifier.padding(start = 4.dp))
    }
}
