package com.tfitapp.ui.food

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfitapp.application.nutrition.DaySummary
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.ui.theme.Field
import com.tfitapp.ui.theme.Heading
import com.tfitapp.ui.theme.Notice
import com.tfitapp.ui.theme.Primary
import com.tfitapp.ui.theme.Quiet
import com.tfitapp.ui.theme.Rule
import com.tfitapp.ui.theme.ScreenList
import com.tfitapp.ui.theme.TFit
import com.tfitapp.ui.theme.Toggle
import com.tfitapp.ui.theme.numberStyle
import kotlin.math.roundToInt

@Composable
fun FoodScreen(onOpenReview: () -> Unit, vm: FoodViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()

    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            kotlinx.coroutines.delay(2_500)
            vm.onEvent(FoodEvent.DismissMessage)
        }
    }

    ScreenList {
        item { SentenceField(ui, vm::onEvent) }

        if (ui.rows.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Auto", ui.slot == null) { vm.onEvent(FoodEvent.SlotChanged(null)) }
                    MealSlot.entries.forEach { s -> Toggle(s.displayName, ui.slot == s) { vm.onEvent(FoodEvent.SlotChanged(s)) } }
                }
            }
            if (!ui.jevUsed) {
                item { Notice("Jev skipped" + (ui.jevNote?.let { " (${it.replaceFirstChar(Char::lowercase)})" } ?: "") + ". Check each line before logging.") }
            }
            items(ui.rows, key = { "row-" + it.entryId }) { row -> ProposedRow(row, vm::onEvent) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val kcal = ui.rows.sumOf { it.kcal ?: 0.0 }.roundToInt()
                    val n = ui.rows.size
                    Primary("Log $n ${if (n == 1) "item" else "items"}, $kcal kcal", { vm.onEvent(FoodEvent.LogAll) }, Modifier.weight(1f))
                    Quiet("Discard", { vm.onEvent(FoodEvent.Discard) })
                }
            }
        } else {
            item { Budget(summary) }
            item { Quiet("This week", onOpenReview, color = TFit.colors.ironMuted, modifier = Modifier.offset(x = (-8).dp)) }
        }

        ui.message?.let { item { Notice(it, tone = TFit.colors.shade) } }

        val entries = summary?.entries.orEmpty()
        if (entries.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)); Rule() }
            items(entries, key = { "entry-" + it.id }) { e ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            e.mealSlot.displayName, style = MaterialTheme.typography.labelMedium, color = TFit.colors.ironMuted,
                            modifier = Modifier.width(72.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.label, style = MaterialTheme.typography.bodyLarge, color = TFit.colors.iron)
                            val grams = e.grams?.let { "${it.roundToInt()} g" }
                            val estimate = if (e.source == EntrySource.QUICK) "estimate" else null
                            Text(
                                listOfNotNull(grams, estimate).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall, color = TFit.colors.ironMuted,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${e.kcal.roundToInt()}", style = numberStyle(MaterialTheme.typography.titleLarge), color = TFit.colors.iron)
                            Text("${e.protein.roundToInt()} g protein", style = MaterialTheme.typography.bodySmall, color = TFit.colors.ironMuted)
                        }
                        Quiet("Remove", { vm.onEvent(FoodEvent.DeleteEntry(e.id)) }, color = TFit.colors.ironMuted)
                    }
                    Rule()
                }
            }
        }

        if (meals.isNotEmpty() || entries.isNotEmpty()) {
            item { Heading("Meals") }
        }
        if (meals.isNotEmpty()) {
            item {
                Text(
                    "Say a meal by name in the field, with changes if you like: weekday breakfast but 3 eggs, without toast.",
                    style = MaterialTheme.typography.bodySmall, color = TFit.colors.ironMuted, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }
        }
        items(meals, key = { "meal-" + it.id }) { m -> MealRow(m, vm::onEvent) }
        if (entries.isNotEmpty()) {
            item { SaveMeal(ui, entries.map { it.mealSlot }.distinct(), vm::onEvent) }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Field(
                    value = ui.weightInput, onChange = { vm.onEvent(FoodEvent.WeightChanged(it)) }, label = "this morning's weight, kg",
                    numeric = true, placeholder = summary?.latestWeightKg?.let { "%.1f".format(it) } ?: "—",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.onEvent(FoodEvent.SaveWeight) }),
                    modifier = Modifier.weight(1f),
                )
                Quiet("Save", { vm.onEvent(FoodEvent.SaveWeight) }, enabled = ui.weightInput.isNotBlank())
            }
        }
    }
}

/** A saved meal: name, what it adds up to, and the one-tap "Usual" path. */
@Composable
private fun MealRow(meal: MealTemplate, onEvent: (FoodEvent) -> Unit) {
    val c = TFit.colors
    Column(Modifier.testTag("meal-" + meal.name)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meal.name, style = MaterialTheme.typography.bodyLarge, color = c.iron)
                Text(
                    "${meal.items.size} ${if (meal.items.size == 1) "item" else "items"}",
                    style = MaterialTheme.typography.bodySmall, color = c.ironMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${meal.kcal.roundToInt()}", style = numberStyle(MaterialTheme.typography.titleLarge), color = c.iron)
                Text("${meal.protein.roundToInt()} g protein", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
            }
            Quiet("Log", { onEvent(FoodEvent.LogMeal(meal.id)) })
            Quiet("Remove", { onEvent(FoodEvent.RemoveMeal(meal.id)) }, color = c.ironMuted)
        }
        Rule()
    }
}

/** Turns one of today's meal slots into a saved meal. Only slots with something logged are offered. */
@Composable
private fun SaveMeal(ui: FoodUiState, slots: List<MealSlot>, onEvent: (FoodEvent) -> Unit) {
    val c = TFit.colors
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Save today's", style = MaterialTheme.typography.labelMedium, color = c.ironMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            slots.forEach { s -> Toggle(s.displayName.lowercase(), ui.saveMealSlot == s) { onEvent(FoodEvent.SaveMealSlotChanged(if (ui.saveMealSlot == s) null else s)) } }
        }
        AnimatedVisibility(ui.saveMealSlot != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    value = ui.saveMealName, onChange = { onEvent(FoodEvent.SaveMealNameChanged(it)) },
                    label = "as a meal called", placeholder = "weekday breakfast", modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onDone = { onEvent(FoodEvent.SaveMeal) }),
                )
                Quiet("Save meal", { onEvent(FoodEvent.SaveMeal) }, enabled = ui.saveMealName.isNotBlank())
            }
        }
    }
}

/** The whole point of the tab: one big field, one verb. */
@Composable
private fun SentenceField(ui: FoodUiState, onEvent: (FoodEvent) -> Unit) {
    val c = TFit.colors
    val style = MaterialTheme.typography.headlineSmall.copy(color = c.iron, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
    Column(Modifier.fillMaxWidth().background(c.chalk, MaterialTheme.shapes.large).padding(16.dp)) {
        BasicTextField(
            value = ui.sentence,
            onValueChange = { onEvent(FoodEvent.SentenceChanged(it)) },
            textStyle = style,
            cursorBrush = SolidColor(c.signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onGo = { onEvent(FoodEvent.Propose) }),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (ui.sentence.isEmpty()) Text("What did you eat?", style = style, color = c.ironMuted)
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.sentence.isEmpty()) "Say it how you'd say it: 2 eggs, toast with butter, black coffee" else "",
                style = MaterialTheme.typography.bodySmall, color = c.ironMuted, modifier = Modifier.weight(1f),
            )
            if (ui.busy) CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp, color = c.signal)
            else Primary("Go", { onEvent(FoodEvent.Propose) }, enabled = ui.sentence.isNotBlank())
        }
    }
}

/** Today's budget as a line: what has been eaten fills it from the left. */
@Composable
private fun Budget(summary: DaySummary?) {
    val c = TFit.colors
    val s = summary
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (s?.budgetKcal == null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s?.kcalEaten ?: 0}", style = MaterialTheme.typography.displayMedium, color = c.iron)
                Text("kcal today", style = MaterialTheme.typography.bodyMedium, color = c.ironMuted, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            }
            Text("Enter your body in Settings and this becomes a budget.", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
            return
        }
        val budget = s.budgetKcal ?: return@Column
        val left = s.kcalLeft ?: 0
        val over = left < 0
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${kotlin.math.abs(left)}", style = MaterialTheme.typography.displayMedium,
                color = if (over) c.signal else c.iron,
            )
            Text(
                if (over) "over, of $budget" else "left of $budget", style = MaterialTheme.typography.bodyMedium, color = c.ironMuted,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }
        Bar(fraction = s.kcalEaten.toFloat() / budget, color = if (over) c.signal else c.iron)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.proteinEaten} g", style = MaterialTheme.typography.displaySmall, color = c.iron)
            Text(
                s.proteinFloor?.let { "protein, floor $it g" } ?: "protein", style = MaterialTheme.typography.bodyMedium, color = c.ironMuted,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
        s.proteinFloor?.let { floor -> Bar(fraction = s.proteinEaten.toFloat() / floor, color = c.shade) }
        Spacer(Modifier.height(10.dp))
        val avg = s.weekAverageKcal?.let { "7-day average $it kcal" }
        val tdee = s.tdee?.let { if (it.adaptive) "burning about ${it.kcal} (measured)" else "burning about ${it.kcal} (formula)" }
        Text(listOfNotNull(avg, tdee).joinToString(". ") + ".", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).background(TFit.colors.rule)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).background(color))
    }
}

@Composable
private fun ProposedRow(row: RowState, onEvent: (FoodEvent) -> Unit) {
    val c = TFit.colors
    val needsCheck = row.proposed.needsReview && !row.overridden
    Column(
        Modifier.fillMaxWidth()
            .background(c.chalk, MaterialTheme.shapes.medium)
            .clickable { onEvent(FoodEvent.ToggleRow(row.entryId)) }
            .padding(start = 0.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(40.dp).background(if (needsCheck) c.amber else androidx.compose.ui.graphics.Color.Transparent))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(row.label, style = MaterialTheme.typography.titleMedium, color = c.iron)
                val portion = row.grams?.let { g -> "${g.roundToInt()} g" + (row.portion?.let { " (${it.label})" } ?: "") }
                val from = row.proposed.mealName?.let { "from $it" }
                Text(
                    listOfNotNull(portion ?: "“${row.proposed.fragment.text}”".takeIf { from == null }, from).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall, color = c.ironMuted,
                )
            }
            row.kcal?.let { kcal ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("${kcal.roundToInt()}", style = numberStyle(MaterialTheme.typography.titleLarge), color = c.iron)
                    row.protein?.let { Text("${it.roundToInt()} g protein", style = MaterialTheme.typography.bodySmall, color = c.ironMuted) }
                }
            }
            Quiet("Remove", { onEvent(FoodEvent.RemoveRow(row.entryId)) }, color = c.ironMuted)
        }
        AnimatedVisibility(row.expanded) {
            Column(Modifier.padding(start = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (row.proposed.candidates.isNotEmpty()) {
                    Text("Which one?", style = MaterialTheme.typography.labelMedium, color = c.ironMuted)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.proposed.candidates.forEach { f ->
                            Toggle(f.name, row.selected?.id == f.id) { onEvent(FoodEvent.PickCandidate(row.entryId, f)) }
                        }
                    }
                }
                row.selected?.let { food ->
                    if (food.portions.isNotEmpty()) {
                        Text("How much?", style = MaterialTheme.typography.labelMedium, color = c.ironMuted)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            food.portions.forEach { p ->
                                Toggle(p.label, row.portion?.label == p.label) { onEvent(FoodEvent.PickPortion(row.entryId, p, 1.0)) }
                            }
                        }
                    }
                    Field(
                        value = row.grams?.let { if (it == it.roundToInt().toDouble()) it.roundToInt().toString() else it.toString() } ?: "",
                        onChange = { onEvent(FoodEvent.GramsChanged(row.entryId, it)) },
                        label = "grams", numeric = true, modifier = Modifier.width(120.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (row.selected == null) {
                    Text("Not in the list. Enter a guess and it's logged as an estimate.", style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(
                            value = row.quickKcal, onChange = { onEvent(FoodEvent.QuickKcalChanged(row.entryId, it, row.quickProtein)) },
                            label = "kcal", numeric = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Field(
                            value = row.quickProtein, onChange = { onEvent(FoodEvent.QuickKcalChanged(row.entryId, row.quickKcal, it)) },
                            label = "g protein", numeric = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
        }
    }
}
