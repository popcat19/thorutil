package dev.popcat19.thorutil.launcher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.SwitchRow

@Composable
fun LauncherCard(epoch: Int) {
    val ctx = LocalContext.current
    var enabled by remember(epoch) { mutableStateOf(Prefs.launcherHome(ctx)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gamepad home screen", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = "ThorUtil home",
                subtitle = "Clock, app search and a D-pad navigable app grid on each display. Makes ThorUtil selectable as a Home app; pick it in the system Home picker to use it.",
                checked = enabled,
            ) { on ->
                enabled = on
                LauncherSwitch.set(ctx, on)
            }
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Choose home app") }
                }
                Text(
                    "D-pad / stick pick an app, A opens, Y focuses search, B clears the query.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
