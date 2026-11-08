package com.tfitapp.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfitapp.domain.nutrition.DayIntake
import com.tfitapp.domain.nutrition.WeightPoint
import com.tfitapp.ui.theme.Heading
import com.tfitapp.ui.theme.Quiet
import com.tfitapp.ui.theme.ScreenList
import com.tfitapp.ui.theme.TFit
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The week, read out loud. The sentences come first because they are the point;
 * the charts underneath are there so the sentences can be checked.
 */
@Composable
fun ReviewScreen(onBack: () -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val review by vm.review.collectAsStateWithLifecycle()
    val c = TFit.colors
    val r = review

    ScreenList(gap = 0.dp) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This week", style = MaterialTheme.typography.headlineMedium, color = c.iron)
                    r?.let {
                        val fmt = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
                        Text("${it.days.first().date.format(DateTimeFormatter.ofPattern("d", Locale.getDefault()))} to ${it.today.format(fmt)}", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
                    }
                }
                Quiet("Done", onBack)
            }
        }
        if (r == null) return@ScreenList

        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                r.verdict.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.iron) }
            }
        }

        item { Heading("Intake") }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${r.averageKcal ?: 0}", style = MaterialTheme.typography.displaySmall, color = c.iron)
                    Text(
                        r.budgetKcal?.let { "kcal a day, budget $it" } ?: "kcal a day",
                        style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                IntakeBars(r.days, r.budgetKcal)
            }
        }

        item { Heading("Weight") }
        item {
            Column(Modifier.padding(top = 10.dp)) {
                val latest = r.weights.lastOrNull()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(latest?.let { "%.1f".format(it.rawKg) } ?: "—", style = MaterialTheme.typography.displaySmall, color = c.iron)
                    Text(
                        when {
                            latest == null -> "no weigh-ins yet"
                            r.changeKg == null -> "kg"
                            else -> "kg, ${signed(r.changeKg ?: 0.0)} over ${r.weightSpanDays} days"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
                if (r.weights.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    WeightLine(r.weights)
                    Text("Dots are mornings; the line is smoothed.", style = MaterialTheme.typography.bodySmall, color = c.ironMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item { Heading("Protein") }
        item {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text("${r.averageProtein ?: 0} g", style = MaterialTheme.typography.displaySmall, color = c.iron)
                Text(
                    r.proteinFloor?.let { "a day, floor $it g, hit ${r.proteinDaysHit} of ${r.daysLogged} days" } ?: "a day",
                    style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
        }

        item { Heading("Training") }
        item {
            Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("${r.sessionsFinished}", style = MaterialTheme.typography.displaySmall, color = c.iron)
                Text(
                    "of ${r.trainingDaysPerWeek} sessions finished",
                    style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Seven bars, the budget as a line across them. An unlogged day is a gap, drawn as a stub in the rule colour. */
@Composable
private fun IntakeBars(days: List<DayIntake>, budget: Int?) {
    val c = TFit.colors
    val top = maxOf(days.maxOfOrNull { it.kcal } ?: 0, budget ?: 0, 1) * 1.1f
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val gap = 6.dp.toPx()
            val barW = (size.width - gap * (days.size - 1)) / days.size
            days.forEachIndexed { i, d ->
                val x = i * (barW + gap)
                val h = if (d.logged) size.height * (d.kcal / top) else 2.dp.toPx()
                drawRect(if (d.logged) c.iron else c.rule, topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(barW, h))
            }
            budget?.let { b ->
                val y = size.height - size.height * (b / top)
                drawLine(c.shade, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            days.forEach { d ->
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall, color = c.ironMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Raw weigh-ins as dots, the smoothed series as a line; x is real days so gaps show as gaps. */
@Composable
private fun WeightLine(points: List<WeightPoint>) {
    val c = TFit.colors
    val first = points.first().date
    val span = ChronoUnit.DAYS.between(first, points.last().date).coerceAtLeast(1).toFloat()
    val lo = points.minOf { minOf(it.rawKg, it.smoothedKg) }
    val hi = points.maxOf { maxOf(it.rawKg, it.smoothedKg) }
    val pad = maxOf((hi - lo) * 0.15, 0.3)
    val yLo = lo - pad
    val yHi = hi + pad
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        fun x(p: WeightPoint) = ChronoUnit.DAYS.between(first, p.date) / span * size.width
        fun y(kg: Double) = (size.height * (1 - (kg - yLo) / (yHi - yLo))).toFloat()
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p.smoothedKg)) else path.lineTo(x(p), y(p.smoothedKg)) }
        drawPath(path, c.iron, style = Stroke(width = 2.dp.toPx()))
        points.forEach { p -> drawCircle(c.ironMuted, radius = 3.dp.toPx(), center = Offset(x(p), y(p.rawKg))) }
    }
}

private fun signed(kg: Double): String = (if (kg > 0) "+" else "") + "%.1f".format(kg)
