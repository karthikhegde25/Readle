package com.readle.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.readle.app.dict.DictionaryProvider
import com.readle.app.dict.Entry
import com.readle.app.dict.WordNet
import com.readle.app.reader.tts.WordSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface DefState {
    data object Loading : DefState
    data object Unavailable : DefState
    data object NotFound : DefState
    data class Found(val entries: List<Entry>) : DefState
}

private const val MAX_SENSES_PER_ENTRY = 6

/**
 * Meaning card for a selected word, answered entirely from the bundled WordNet. "Speak" appears only when
 * the device has an English text-to-speech voice, because WordNet itself has no pronunciation data.
 */
@Composable
fun DefinitionDialog(
    selected: String,
    onSave: (word: String, definition: String?) -> Unit,
    onOtherApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var state by remember(selected) { mutableStateOf<DefState>(DefState.Loading) }
    LaunchedEffect(selected) {
        val wn = DictionaryProvider.get(context)
        state = if (wn == null) DefState.Unavailable else {
            val r = withContext(Dispatchers.Default) { wn.define(selected) }
            if (r.isEmpty()) DefState.NotFound else DefState.Found(r)
        }
    }

    var canSpeak by remember { mutableStateOf(false) }
    val speaker = remember { WordSpeaker(context) { canSpeak = it } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    val shown = WordNet.normalise(selected).replace('_', ' ').ifEmpty { selected.trim() }
    val found = state as? DefState.Found
    val firstDefinition = found?.entries?.firstOrNull()?.senses?.firstOrNull()?.definition

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shown, modifier = Modifier.weight(1f))
                if (canSpeak) TextButton(
                    onClick = { speaker.speak(shown) },
                    modifier = Modifier.semantics { contentDescription = "Speak the word" }
                ) { Text("Speak") }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    DefState.Loading -> CircularProgressIndicator()
                    DefState.Unavailable -> Text("The built-in dictionary could not be opened on this device.")
                    DefState.NotFound -> Text(
                        "No definition found for “$shown”. The built-in dictionary covers English words and common phrases. " +
                            "Names and other languages are not included."
                    )
                    is DefState.Found -> s.entries.forEach { EntryBlock(it, showHeadword = s.entries.size > 1 || it.lemma != shown) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Definitions: Princeton WordNet 3.1" + if (canSpeak) ". Pronunciation is spoken by your device’s text-to-speech voice." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row {
                if (state is DefState.NotFound || state is DefState.Unavailable) TextButton(onClick = onOtherApp) { Text("Other app") }
                TextButton(onClick = { onSave(shown, firstDefinition); onDismiss() }) { Text("Save word") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun EntryBlock(entry: Entry, showHeadword: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (showHeadword) Text(entry.lemma, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        var lastPos = ' '
        entry.senses.take(MAX_SENSES_PER_ENTRY).forEachIndexed { i, sense ->
            if (sense.pos != lastPos) {
                lastPos = sense.pos
                Text(
                    sense.posLabel, style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text("${i + 1}. ${sense.definition}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            val syn = sense.synonyms.filter { !it.equals(entry.lemma, ignoreCase = true) }
            if (syn.isNotEmpty()) Text(
                "Also: ${syn.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            sense.examples.firstOrNull()?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entry.senses.size > MAX_SENSES_PER_ENTRY) Text(
            "+ ${entry.senses.size - MAX_SENSES_PER_ENTRY} more meanings", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
