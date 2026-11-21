package dev.popcat19.thorutil.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun FeedbackCard() {
    val ctx = LocalContext.current
    var invert by remember { mutableStateOf(Feedback.inverted(ctx)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Feedback", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(1f))
                Text("Toast", Modifier.width(64.dp), textAlign = TextAlign.Center)
                Text("Haptic", Modifier.width(64.dp), textAlign = TextAlign.Center)
            }
            FeedbackType.entries.forEach { t ->
                var toast by remember { mutableStateOf(Feedback.toastOn(ctx, t)) }
                var haptic by remember { mutableStateOf(Feedback.hapticOn(ctx, t)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, Modifier.weight(1f))
                    Checkbox(toast, { toast = it; Feedback.setToast(ctx, t, it) }, Modifier.width(64.dp))
                    Checkbox(haptic, { haptic = it; Feedback.setHaptic(ctx, t, it) }, Modifier.width(64.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Invert bip and brt")
                    Text(
                        if (invert) "Brt: on, top, Auto. Bip: off, bottom, actions"
                        else "Bip: on, top, Auto. Brt: off, bottom, actions",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(invert, {
                    invert = it
                    Feedback.setInverted(ctx, it)
                    Feedback.buzz(ctx, Feedback.hapticFor(ctx, true))
                })
            }
        }
    }
}
