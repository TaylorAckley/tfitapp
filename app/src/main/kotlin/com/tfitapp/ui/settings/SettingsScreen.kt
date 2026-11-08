package com.tfitapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tfitapp.application.port.SecretKey
import com.tfitapp.domain.nutrition.GoalPhase
import com.tfitapp.domain.nutrition.Sex
import com.tfitapp.domain.training.Equipment
import com.tfitapp.ui.theme.Choice
import com.tfitapp.ui.theme.Field
import com.tfitapp.ui.theme.Heading
import com.tfitapp.ui.theme.Notice
import com.tfitapp.ui.theme.Quiet
import com.tfitapp.ui.theme.Rule
import com.tfitapp.ui.theme.ScreenList
import com.tfitapp.ui.theme.Secondary
import com.tfitapp.ui.theme.TFit
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val supabase by vm.supabase.collectAsStateWithLifecycle()
    val c = TFit.colors

    ScreenList {
        item { Heading("Keys") }
        item { KeyRow("TypeSafe, for Jev", keys.typesafeSet, onSave = { vm.saveKey(SecretKey.TYPESAFE_API_KEY, it) }, onClear = { vm.clearKey(SecretKey.TYPESAFE_API_KEY) }) }
        item { KeyRow("USDA FoodData Central", keys.fdcSet, onSave = { vm.saveKey(SecretKey.FDC_API_KEY, it) }, onClear = { vm.clearKey(SecretKey.FDC_API_KEY) }) }
        item { Help("Stored in the Android Keystore. Each key goes only to the service it belongs to.") }

        item { Heading("Body") }
        item {
            val body = s.body
            var sex by rememberSaveable(body) { mutableStateOf(body?.sex ?: Sex.MALE) }
            var age by rememberSaveable(body) { mutableStateOf(body?.ageYears?.toString() ?: "") }
            var height by rememberSaveable(body) { mutableStateOf(body?.heightCm?.toString() ?: "") }
            var weight by rememberSaveable(body) { mutableStateOf(body?.weightKg?.toString() ?: "") }
            var activity by rememberSaveable(body) { mutableStateOf(body?.activityFactor?.toString() ?: "1.4") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice(Sex.entries, sex, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { sex = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num(age, { age = it }, "age", Modifier.weight(1f))
                    Num(height, { height = it }, "height, cm", Modifier.weight(1f))
                    Num(weight, { weight = it }, "weight, kg", Modifier.weight(1f))
                    Num(activity, { activity = it }, "activity", Modifier.weight(1f))
                }
                Help("Activity: 1.2 desk-bound, 1.4 on your feet some of the day, 1.6 physical work. Training is added on top.")
                Secondary("Save body", {
                    val a = age.toIntOrNull() ?: return@Secondary
                    val h = height.toDoubleOrNull() ?: return@Secondary
                    val w = weight.toDoubleOrNull() ?: return@Secondary
                    vm.saveBody(sex, a, h, w, activity.toDoubleOrNull() ?: 1.4)
                })
            }
        }

        item { Heading("Goal") }
        item {
            var offset by rememberSaveable(s.goal) { mutableStateOf(s.goal.budgetOffsetKcal.toString()) }
            var protein by rememberSaveable(s.goal) { mutableStateOf(s.goal.proteinPerKg.toString()) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice(GoalPhase.entries, s.goal.phase, { it.displayName }, { vm.setPhase(it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Num(offset, { offset = it }, "kcal above or below burn", Modifier.weight(1f), signed = true)
                    Num(protein, { protein = it }, "g protein per kg", Modifier.weight(1f))
                    Quiet("Save", { vm.setGoalNumbers(offset.toIntOrNull() ?: 0, protein.toDoubleOrNull() ?: 1.8) })
                }
                Help("Changing the phase resets these two to its defaults.")
            }
        }

        item { Heading("Gear") }
        s.equipment.filter { it.id != Equipment.BODY_ID }.forEach { eq ->
            item(key = eq.id) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(eq.kind.displayName, style = MaterialTheme.typography.bodyLarge, color = c.iron)
                            Text(eq.detail, style = MaterialTheme.typography.bodySmall, color = c.ironMuted)
                        }
                        Switch(
                            checked = eq.present, onCheckedChange = { vm.toggleEquipment(eq.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = c.chalk, checkedTrackColor = c.iron, uncheckedThumbColor = c.ironMuted, uncheckedTrackColor = c.chalk, uncheckedBorderColor = c.rule),
                        )
                    }
                    Rule()
                }
            }
        }

        item { Heading("Home, for the forecast") }
        item {
            var lat by rememberSaveable(s.home) { mutableStateOf(s.home?.latitude?.toString() ?: "") }
            var lon by rememberSaveable(s.home) { mutableStateOf(s.home?.longitude?.toString() ?: "") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Num(lat, { lat = it }, "latitude", Modifier.weight(1f), signed = true)
                    Num(lon, { lon = it }, "longitude", Modifier.weight(1f), signed = true)
                    Quiet("Save", {
                        val a = lat.toDoubleOrNull(); val b = lon.toDoubleOrNull()
                        if (a != null && b != null) vm.saveHome(a, b) else vm.clearHome()
                    })
                }
                Help("Rounded to two decimals, about a kilometre, before it goes to the National Weather Service. Leave blank to plan indoors only.")
            }
        }

        item { Heading("Training") }
        item {
            var budget by rememberSaveable(s.timeBudgetMinutes) { mutableStateOf(s.timeBudgetMinutes.toString()) }
            var hour by rememberSaveable(s.trainingStartHour) { mutableStateOf(s.trainingStartHour.toString()) }
            var sled by rememberSaveable(s.sledMaxSetting) { mutableStateOf(s.sledMaxSetting.toString()) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Num(budget, { budget = it }, "minutes per session", Modifier.weight(1f))
                    Num(hour, { hour = it }, "usual start hour", Modifier.weight(1f))
                    Num(sled, { sled = it }, "Tank top setting", Modifier.weight(1f))
                    Quiet("Save", { vm.saveTraining(budget.toIntOrNull() ?: 45, hour.toIntOrNull() ?: 17, sled.toIntOrNull() ?: 3) })
                }
                Help("The start hour picks which forecast hour decides outdoors or indoors.")
            }
        }

        item { Heading("Bodyspec + goals sync, for Claude") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Help("Mirrors body, goal, training days, and time budget to your own Supabase project so Claude can read and update them (design basis §2 exception). Everything else in Settings stays on this phone only.")
                KeyRow("Supabase project URL", supabase.urlSet, onSave = { vm.saveKey(SecretKey.SUPABASE_URL, it) }, onClear = { vm.clearKey(SecretKey.SUPABASE_URL) })
                KeyRow("Supabase anon key", supabase.anonKeySet, onSave = { vm.saveKey(SecretKey.SUPABASE_ANON_KEY, it) }, onClear = { vm.clearKey(SecretKey.SUPABASE_ANON_KEY) })
                if (!supabase.signedIn) {
                    var email by rememberSaveable { mutableStateOf("") }
                    var password by rememberSaveable { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(value = email, onChange = { email = it }, label = "email", modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        Field(value = password, onChange = { password = it }, label = "password", visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                    }
                    Secondary("Sign in", { vm.signInToSupabase(email, password) }, enabled = supabase.configured && email.isNotBlank() && password.isNotBlank() && !supabase.syncing)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Signed in", style = MaterialTheme.typography.bodyMedium, color = c.moss)
                        Quiet("Sync now", { vm.syncProfileNow() }, enabled = !supabase.syncing)
                    }
                    s.profileSyncedAt?.let { Help("Last synced ${DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(it)}") }
                }
                supabase.error?.let { Notice(it, tone = c.amber) }
            }
        }

        item { Heading("Lines course") }
        item {
            var text by rememberSaveable(s.linesCourse) { mutableStateOf(s.linesCourse?.points?.joinToString(", ") { it.metres.toString() } ?: "") }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    value = text, onChange = { text = it }, label = "distances in metres, nearest point first",
                    placeholder = "20, 40, 60, 80", modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Secondary("Save course", { vm.saveCourse(text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }) })
            }
        }
    }
}

@Composable
private fun Help(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = TFit.colors.ironMuted)

@Composable
private fun KeyRow(label: String, isSet: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(isSet) { if (isSet) value = "" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(
            value = value, onChange = { value = it }, label = if (isSet) "$label, set" else label,
            placeholder = if (isSet) "paste a new key to replace it" else "paste key",
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
        )
        Quiet("Save", { onSave(value); value = "" }, enabled = value.isNotBlank())
        if (isSet) Quiet("Clear", onClear, color = TFit.colors.ironMuted)
    }
}

@Composable
private fun Num(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, signed: Boolean = false) {
    Field(
        value = value,
        onChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' || (signed && ch == '-') }) },
        label = label, numeric = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
