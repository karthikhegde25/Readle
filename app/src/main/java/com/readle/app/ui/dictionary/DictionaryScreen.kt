package com.readle.app.ui.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.readle.app.dict.DictionaryProvider
import com.readle.app.dict.Entry
import com.readle.app.dict.WordNet
import com.readle.app.reader.tts.WordSpeaker
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.minTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Lookup {
    data object Idle : Lookup
    data object Loading : Lookup
    data object Unavailable : Lookup
    data class NotFound(val word: String) : Lookup
    data class Found(val word: String, val entries: List<Entry>) : Lookup
}

/**
 * Offline dictionary: search any English word or phrase and see every meaning, grouped by part of speech,
 * with synonyms and example sentences. The microphone button speaks the word with the device's text-to-speech
 * voice (WordNet has no recorded pronunciations).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(navigation: @Composable () -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }

    var canSpeak by remember { mutableStateOf(false) }
    var speakerReady by remember { mutableStateOf(false) }
    val speaker = remember { WordSpeaker(context) { ok -> canSpeak = ok; speakerReady = true } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    // Looks up as you type, after a short pause so every keystroke doesn't trigger a search.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            lookup = Lookup.Idle
            return@LaunchedEffect
        }
        lookup = Lookup.Loading
        delay(250)
        val wn = DictionaryProvider.get(context)
        lookup = if (wn == null) Lookup.Unavailable else {
            val shown = WordNet.normalise(q).replace('_', ' ').ifEmpty { q }
            val r = withContext(Dispatchers.Default) { wn.define(q) }
            if (r.isEmpty()) Lookup.NotFound(shown) else Lookup.Found(shown, r)
        }
    }

    fun search(word: String) {
        query = word
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(navigationIcon = navigation, title = { Text("Dictionary") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Search a word") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear search") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = query.trim()
                    if (q.isNotEmpty() && recent.firstOrNull() != q) recent = (listOf(q) + recent.filter { it != q }).take(8)
                })
            )

            when (val l = lookup) {
                Lookup.Idle -> Box(Modifier.fillMaxSize()) {
                    if (recent.isEmpty()) EmptyState(
                        Icons.Filled.Book, "Look up any word",
                        "Type an English word or phrase to see its meanings, synonyms and examples. It works offline."
                    ) else Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Recent searches", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            recent.forEach { w -> AssistChip(onClick = { search(w) }, label = { Text(w) }) }
                        }
                    }
                }
                Lookup.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.padding(top = 32.dp))
                }
                Lookup.Unavailable -> Text(
                    "The built-in dictionary could not be opened on this device.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium
                )
                is Lookup.NotFound -> Text(
                    "No definition found for “${l.word}”. The built-in dictionary covers English words and common phrases. Names and other languages are not included. Check the spelling and try again.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium
                )
                is Lookup.Found -> {
                    // Remember what was actually found, not every half-typed prefix.
                    LaunchedEffect(l.word) {
                        if (recent.firstOrNull() != l.word) recent = (listOf(l.word) + recent.filter { it != l.word }).take(8)
                    }
                    ResultView(
                        found = l,
                        canSpeak = canSpeak,
                        speakerReady = speakerReady,
                        onPronounce = { speaker.speak(l.word) },
                        onSave = {
                            val first = l.entries.firstOrNull()?.senses?.firstOrNull()?.definition
                            scope.launch {
                                container.readerRepository.addWord(l.word, first, null, null)
                                snackbar.showSnackbar("Saved “${l.word}” to Vocabulary")
                            }
                        },
                        onOpen = { search(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultView(
    found: Lookup.Found,
    canSpeak: Boolean,
    speakerReady: Boolean,
    onPronounce: () -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(found.word, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onPronounce, enabled = canSpeak, modifier = Modifier.sizeIn(minWidth = minTouchTarget(), minHeight = minTouchTarget())) {
                Icon(Icons.Filled.Mic, "Pronounce ${found.word}")
            }
        }
        if (speakerReady && !canSpeak) Text(
            "Pronunciation needs an English text-to-speech voice. Install one in Android's text-to-speech settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onSave, modifier = Modifier.padding(top = 4.dp)) { Text("Save to Vocabulary") }
        Spacer(Modifier.height(8.dp))

        found.entries.forEach { entry -> EntryView(entry, showHeadword = found.entries.size > 1 || entry.lemma != found.word, onOpen = onOpen) }

        Spacer(Modifier.height(12.dp))
        Text(
            "Definitions: Princeton WordNet 3.1. Pronunciation is spoken by your device's text-to-speech voice, not recorded.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryView(entry: Entry, showHeadword: Boolean, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (showHeadword) Text(entry.lemma.replace('_', ' '), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        var lastPos = ' '
        var n = 0
        entry.senses.forEach { sense ->
            if (sense.pos != lastPos) {
                lastPos = sense.pos
                n = 0
                Text(
                    sense.posLabel, style = MaterialTheme.typography.labelLarge, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)
                )
            }
            n++
            Text("$n. ${sense.definition}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            sense.examples.forEach {
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
            val syn = sense.synonyms.filter { !it.equals(entry.lemma, ignoreCase = true) }
            if (syn.isNotEmpty()) {
                Text("Synonyms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                FlowRow(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    syn.forEach { s -> AssistChip(onClick = { onOpen(s.replace('_', ' ')) }, label = { Text(s.replace('_', ' ')) }) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}
