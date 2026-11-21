package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Slider plus a typed field and step buttons, so values can be set exactly with a gamepad.
@Composable
fun NumberSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    unit: String = "",
    slider: Boolean = true,
    onChange: (Float) -> Unit,
) {
    fun fmt(v: Float) = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)
    fun snap(v: Float) = (Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)
    var text by remember { mutableStateOf(fmt(value)) }
    LaunchedEffect(value) { if (text.toFloatOrNull() != value) text = fmt(value) }

    Column {
        Text(if (unit.isEmpty()) label else "$label ($unit)", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(snap(value - step)) }) { Text("−") }
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    t.toFloatOrNull()?.takeIf { it in range }?.let(onChange)
                },
                singleLine = true,
                isError = text.toFloatOrNull()?.let { it !in range } ?: true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(110.dp),
            )
            OutlinedButton(onClick = { onChange(snap(value + step)) }) { Text("+") }
        }
        if (slider) Slider(value = value, onValueChange = { onChange(snap(it)) }, valueRange = range)
    }
}
