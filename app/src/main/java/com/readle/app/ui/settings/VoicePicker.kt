package com.readle.app.ui.settings

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.readle.app.ui.components.minTouchTarget
import java.util.Locale

/** One installed voice, ready to show. */
private data class VoiceRow(
    val name: String,
    val locale: Locale,
    val language: String,
    val quality: String,
    val needsInternet: Boolean,
    val voice: Voice
)

private fun qualityLabel(q: Int): String = when {
    q >= Voice.QUALITY_VERY_HIGH -> "very high quality"
    q >= Voice.QUALITY_HIGH -> "high quality"
    q >= Voice.QUALITY_NORMAL -> "normal quality"
    q >= Voice.QUALITY_LOW -> "low quality"
    else -> "basic quality"
}

/** Name of the app that provides the voices (for example Google's speech services), for display. */
private fun engineLabel(context: Context, tts: TextToSpeech): String {
    val pkg = try { tts.defaultEngine } catch (e: Exception) { null } ?: return "your speech engine"
    return try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}

/**
 * Lists the voices the phone's text-to-speech engine reports (on most phones these are Google's voices) and
 * lets the user choose one or leave it on Automatic. A chosen voice is used for books in its language.
 */
/**
 * Opens Android's text-to-speech settings, where more voices can be downloaded, and falls back to the engine's
 * own "install voice data" screen, then to the general settings.
 */
fun openVoiceDownloads(context: Context): Boolean {
    val attempts = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent(android.provider.Settings.ACTION_SETTINGS)
    )
    for (i in attempts) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) { }
    }
    return false
}

@Composable
fun VoicePickerDialog(engine: String?, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appContainer = com.readle.app.ui.LocalAppContainer.current
    // Bumped when the user comes back from Android's settings, so voices they just downloaded show up.
    var refresh by remember { mutableStateOf(0) }
    var wentToSettings by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && wentToSettings) {
                wentToSettings = false
                // The app's own speech engine must reconnect to see the new voice data too.
                appContainer.readAloud.controller.invalidateEngine()
                refresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var rows by remember { mutableStateOf<List<VoiceRow>?>(null) }
    var engine by remember { mutableStateOf("your speech engine") }
    var failed by remember { mutableStateOf(false) }
    var engineRef by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(engine, refresh) {
        rows = null
        failed = false
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext, { status ->
            val t = created
            if (status != TextToSpeech.SUCCESS || t == null) {
                failed = true
                return@TextToSpeech
            }
            engineRef = t
            engine = engineLabel(context, t)
            val all = try { t.voices } catch (e: Exception) { null }
            rows = (all ?: emptySet<Voice>())
                .filter { !it.features.contains("notInstalled") }
                .map {
                    VoiceRow(
                        name = it.name,
                        locale = it.locale,
                        language = it.locale.getDisplayName(Locale.getDefault()).ifBlank { it.locale.toLanguageTag() },
                        quality = qualityLabel(it.quality),
                        needsInternet = it.isNetworkConnectionRequired,
                        voice = it
                    )
                }
                .sortedWith(compareBy<VoiceRow> { it.language.lowercase() }.thenBy { it.needsInternet }.thenByDescending { it.voice.quality }.thenBy { it.name })
        }, engine)
        onDispose {
            try { created?.stop() } catch (_: Exception) { }
            try { created?.shutdown() } catch (_: Exception) { }
        }
    }

    fun preview(v: VoiceRow) {
        val t = engineRef ?: return
        try {
            t.stop()
            t.setVoice(v.voice)
            // English voices say a sentence; other voices say the name of their own language, which works in any language.
            val sample = if (v.locale.language == "en") "This is how this voice sounds." else v.locale.getDisplayLanguage(v.locale)
            t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
        } catch (_: Exception) { }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading voice") },
        text = {
            Column {
                Text(
                    "Voices installed on this phone, from $engine. A voice you choose is used for books in its language; other books use an automatic voice.",
                    style = MaterialTheme.typography.bodySmall
                )
                val list = rows
                when {
                    failed -> Text("Text-to-speech isn't available on this device.", Modifier.padding(top = 12.dp))
                    list == null -> Text("Loading voices…", Modifier.padding(top = 12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onPick(null) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == null, onClick = { onPick(null) })
                                Column(Modifier.weight(1f)) {
                                    Text("Automatic", style = MaterialTheme.typography.bodyLarge)
                                    Text("Best installed voice for each book's language", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                        if (list.isEmpty()) item {
                            Text("This engine didn't list any voices. Automatic still works.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        items(list, key = { it.name }) { v ->
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onPick(v.name) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == v.name, onClick = { onPick(v.name) })
                                Column(Modifier.weight(1f)) {
                                    Text(v.language, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        v.name + " · " + v.quality + if (v.needsInternet) " · needs internet" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { preview(v) }) { Icon(Icons.Filled.PlayArrow, "Preview ${v.language} voice ${v.name}") }
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    wentToSettings = openVoiceDownloads(context)
                }) { Text("Add more voices (opens Android's text-to-speech settings)") }
                Text(
                    "In Android's settings, open your speech engine (for example Speech Services by Google) and choose Install voice data. When you come back, new voices appear here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Lets the user choose which installed text-to-speech engine reads aloud, or leave it on the phone's default. */
@Composable
fun EnginePickerDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var engines by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val t = created
            if (status != TextToSpeech.SUCCESS || t == null) {
                failed = true
                return@TextToSpeech
            }
            val list = try { t.engines } catch (e: Exception) { null }
            engines = (list ?: emptyList<TextToSpeech.EngineInfo>()).map { it.name to it.label }
        }
        onDispose {
            try { created?.shutdown() } catch (_: Exception) { }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice engine") },
        text = {
            Column {
                Text(
                    "The engine is the app that produces the speech, such as Speech Services by Google. Each engine has its own voices.",
                    style = MaterialTheme.typography.bodySmall
                )
                val list = engines
                when {
                    failed -> Text("Text-to-speech isn't available on this device.", Modifier.padding(top = 12.dp))
                    list == null -> Text("Loading engines…", Modifier.padding(top = 12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp)) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onPick(null) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == null, onClick = { onPick(null) })
                                Text("Phone's default engine", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        items(list, key = { it.first }) { (pkg, label) ->
                            Row(
                                Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onPick(pkg) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = current == pkg, onClick = { onPick(pkg) })
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
