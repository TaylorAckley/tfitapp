package com.tfitapp.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Every tab is a list with the same gutters and the status bar inset paid once, here. */
@Composable
fun ScreenList(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: LazyListScope.() -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("screen"),
        verticalArrangement = Arrangement.spacedBy(gap),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 20.dp, bottom = 24.dp),
        content = content,
    )
}

/** A hairline between rows. Structure, not decoration: it separates records. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(TFit.colors.rule))
}

/** A section heading with its rule; the heading names what follows, the rule bounds it. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = TFit.colors.iron)
        Rule(Modifier.padding(top = 6.dp))
    }
}

/** Short facts side by side, spaced instead of joined by punctuation. */
@Composable
fun Facts(vararg facts: String?, modifier: Modifier = Modifier, color: Color = TFit.colors.ironMuted) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        facts.filterNotNull().filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = color) }
    }
}

/** A quiet status line with a colour bar: amber for "degraded", shade for "info". */
@Composable
fun Notice(text: String, tone: Color = TFit.colors.amber, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(18.dp).background(tone))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TFit.colors.iron, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The one orange action on a screen. */
@Composable
fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = TFit.colors.signal, contentColor = TFit.colors.onSignal,
            disabledContainerColor = TFit.colors.rule, disabledContentColor = TFit.colors.ironMuted,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Everything that is not the primary action: iron on chalk with a rule around it. */
@Composable
fun Secondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = TFit.colors.chalk, contentColor = TFit.colors.iron,
            disabledContainerColor = TFit.colors.chalk, disabledContentColor = TFit.colors.rule,
        ),
        border = BorderStroke(1.dp, TFit.colors.rule),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** A small text action that sits inside a row. */
@Composable
fun Quiet(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = TFit.colors.iron) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else TFit.colors.rule,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** One-of-N. The chosen option is iron; the rest chalk. */
@Composable
fun <T> Choice(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> Toggle(label(o), selected == o) { onSelect(o) } }
    }
}

@Composable
fun Toggle(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = TFit.colors
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.chalk else c.iron,
        modifier = Modifier
            .background(if (selected) c.iron else c.chalk, MaterialTheme.shapes.small)
            .border(1.dp, if (selected) c.iron else c.rule, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * A chalk field with the label under the value. Numbers are set condensed so a row of
 * them reads like a dashboard rather than a form.
 */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    numeric: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val c = TFit.colors
    val style = if (numeric) MaterialTheme.typography.headlineLarge.copy(color = c.iron) else MaterialTheme.typography.bodyLarge.copy(color = c.iron)
    Column(modifier.background(c.chalk, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 8.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, textStyle = style, singleLine = singleLine, enabled = enabled,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            cursorBrush = SolidColor(c.signal),
            // The label doubles as the test tag; it is the one stable, human-readable handle a field has.
            modifier = Modifier.fillMaxWidth().testTag(label),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.ironMuted.copy(alpha = 0.6f))
                    inner()
                }
            },
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.ironMuted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A number that is part of a sentence: same size as its neighbours, condensed and heavier. */
@Composable
fun numberStyle(base: TextStyle): TextStyle = base.copy(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold)
