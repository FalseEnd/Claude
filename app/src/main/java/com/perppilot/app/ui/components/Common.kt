package com.perppilot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.perppilot.app.ui.theme.Mono
import com.perppilot.app.ui.theme.Outline
import com.perppilot.app.ui.theme.Surface1
import com.perppilot.app.ui.theme.Surface2
import com.perppilot.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
    }
}

/** Grid of stats, [columns] per row. */
@Composable
fun StatGrid(items: List<Triple<String, String, Color?>>, columns: Int = 3) {
    items.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            row.forEach { (l, v, c) -> Stat(l, v, Modifier.weight(1f), c ?: MaterialTheme.colorScheme.onSurface) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Horizontal bar for a score in [-100, 100]. */
@Composable
fun ScoreBar(score: Int, up: Color, down: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Surface2, RoundedCornerShape(3.dp))) {
        val frac = (kotlin.math.abs(score) / 100f).coerceIn(0f, 1f) / 2f
        Row(Modifier.fillMaxWidth()) {
            if (score >= 0) {
                Spacer(Modifier.weight(0.5f))
                Box(Modifier.weight(frac.coerceAtLeast(0.001f)).height(6.dp).background(up, RoundedCornerShape(3.dp)))
                Spacer(Modifier.weight((0.5f - frac).coerceAtLeast(0.001f)))
            } else {
                Spacer(Modifier.weight((0.5f - frac).coerceAtLeast(0.001f)))
                Box(Modifier.weight(frac.coerceAtLeast(0.001f)).height(6.dp).background(down, RoundedCornerShape(3.dp)))
                Spacer(Modifier.weight(0.5f))
            }
        }
        Box(Modifier.align(Alignment.Center).width(1.dp).height(6.dp).background(Outline))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Numeric text field. The value is committed when the field loses focus or the user presses Done,
 * so intermediate input such as "0." is never clamped mid-typing.
 */
@Composable
fun NumberField(label: String, value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier, suffix: String = "", integer: Boolean = false) {
    fun show(v: Double) = if (integer) v.toLong().toString() else trimNum(v)
    var text by remember(value) { mutableStateOf(show(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        val v = text.toDoubleOrNull()
        if (v == null) text = show(value) else if (v != value) onValue(v)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.padding(vertical = 4.dp).onFocusChanged { st ->
            if (focused && !st.isFocused) commit()
            focused = st.isFocused
        },
    )
}

@Composable
fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun Hint(text: String, color: Color = TextSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(vertical = 4.dp))
}

private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private val dtf = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val dtfFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun fmtTime(ms: Long): String = if (ms <= 0) "-" else dtf.format(Instant.ofEpochMilli(ms))
fun fmtDateTime(ms: Long): String = if (ms <= 0) "-" else dtfFull.format(Instant.ofEpochMilli(ms))
fun fmtPct(v: Double?, digits: Int = 2): String = if (v == null || v.isNaN()) "-" else "%+.${digits}f%%".format(v)
fun fmtUsd(v: Double?): String = when {
    v == null || v.isNaN() -> "-"
    kotlin.math.abs(v) >= 1e9 -> "%.2fB".format(v / 1e9)
    kotlin.math.abs(v) >= 1e6 -> "%.2fM".format(v / 1e6)
    kotlin.math.abs(v) >= 1e4 -> "%.1fK".format(v / 1e3)
    else -> "%.2f".format(v)
}
fun fmtNum(v: Double?, digits: Int = 2): String = if (v == null || v.isNaN()) "-" else "%.${digits}f".format(v)
fun shortSym(instId: String): String = instId.removeSuffix("-SWAP").replace("-USDT", "")
