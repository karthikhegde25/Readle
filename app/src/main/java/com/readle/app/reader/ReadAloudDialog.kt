package com.readle.app.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.readle.app.domain.AppSettings
import com.readle.app.ui.components.minTouchTarget
import com.readle.app.ui.settings.EnginePickerDialog
import com.readle.app.ui.settings.VoicePickerDialog
import com.readle.app.ui.settings.openVoiceDownloads

private val TIMER_PRESETS = listOf(0, 5, 10, 15, 30, 45, 60)

/**
 * Opens from the read button: speed, voice, voice engine and sleep timer in one place. [idle] is true when
 * nothing is being read yet, in which case the main button starts reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadAloudDialog(
    idle: Boolean,
    settings: AppSettings,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRate: (Float) -> Unit,
    onStart: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showVoices by remember { mutableStateOf(false) }
    var showEngines by remember { mutableStateOf(false) }
    var custom by remember {
        mutableStateOf(settings.sleepTimerMinutes.takeIf { it > 0 && it !in TIMER_PRESETS }?.toString().orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read aloud") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speed ${"%.1f".format(settings.ttsRate)}×", style = MaterialTheme.typography.labelLarge)
                Slider(value = settings.ttsRate, onValueChange = { onRate(Math.round(it * 10f) / 10f) }, valueRange = 0.5f..2.5f)
                HorizontalDivider()

                Row(
                    Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { showEngines = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice engine", style = MaterialTheme.typography.bodyLarge)
                        Text(settings.ttsEngine ?: "Phone's default engine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { showVoices = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.bodyLarge)
                        Text(settings.ttsVoice ?: "Automatic (matches the book's language)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { openVoiceDownloads(context) }) { Text("Add more voices") }
                HorizontalDivider()

                Text("Sleep timer", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TIMER_PRESETS.forEach { m ->
                        FilterChip(
                            selected = settings.sleepTimerMinutes == m,
                            onClick = { custom = ""; onSettings { it.copy(sleepTimerMinutes = m) } },
                            label = { Text(if (m == 0) "Off" else "$m min") }
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(3)
                        custom = digits
                        val minutes = digits.toIntOrNull()
                        if (minutes != null) onSettings { it.copy(sleepTimerMinutes = minutes.coerceIn(1, 600)) }
                    },
                    label = { Text("Other (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The timer starts when reading starts and keeps counting while paused. A blue countdown shows at the top left. When it ends, reading stops, your place is saved and the book closes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = if (idle) ({ onStart(custom.toIntOrNull()?.coerceIn(1, 600) ?: settings.sleepTimerMinutes) }) else onDismiss) { Text(if (idle) "Start reading" else "Done") } },
        dismissButton = if (idle) ({ TextButton(onClick = onDismiss) { Text("Cancel") } }) else null
    )

    if (showEngines) EnginePickerDialog(
        current = settings.ttsEngine,
        onPick = { pkg -> onSettings { it.copy(ttsEngine = pkg, ttsVoice = null) } },
        onDismiss = { showEngines = false }
    )
    if (showVoices) VoicePickerDialog(
        engine = settings.ttsEngine,
        current = settings.ttsVoice,
        onPick = { name -> onSettings { it.copy(ttsVoice = name) } },
        onDismiss = { showVoices = false }
    )
}
