package com.readle.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.readle.app.domain.CustomFont
import com.readle.app.domain.CustomRules
import com.readle.app.domain.CustomTheme
import com.readle.app.domain.ReaderTheme
import com.readle.app.domain.ThemeText
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.minTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings block for imported fonts: add a file, see what was added, remove one. */
@Composable
fun CustomFontsSection(onMessage: (String) -> Unit) {
    val assets = LocalAppContainer.current.customAssets
    val settingsStore = LocalAppContainer.current.settings
    val fonts by assets.fonts.collectAsState()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<CustomFont?>(null) }

    // Font files have no reliable MIME type, so the picker shows every file and Readle checks the extension and header itself.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = assets.addFont(uri)
            onMessage(r.fold({ "Added font \"${it.name}\"" }, { it.message ?: "That font couldn't be added." }))
        }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Add your own fonts (.ttf, .otf, .woff or .woff2). They are copied into Readle's private storage, then appear next to Serif, Sans and Mono in the reader's text settings. They apply to EPUB, MOBI, FB2, DOCX, RTF, HTML, Markdown and TXT books; PDFs keep their own fonts.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
            Text("Add a font file")
        }
        if (fonts.isEmpty()) Text("No custom fonts yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fonts.forEach { f ->
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                    Text(".${f.format} font", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { removing = f }) { Icon(Icons.Filled.Delete, "Remove font ${f.name}") }
            }
        }
    }
    removing?.let { f ->
        ConfirmDialog(
            "Remove \"${f.name}\"?", "The font is deleted from Readle. Books using it go back to the standard font. Your original file isn't touched.", "Remove", destructive = true,
            onConfirm = {
                removing = null
                assets.removeFont(f.id)
                scope.launch {
                    // Anything pointing at the removed font falls back to the default font.
                    settingsStore.update { s -> if (s.typography.customFontId == f.id) s.copy(typography = s.typography.copy(customFontId = null)) else s }
                }
                onMessage("Removed font \"${f.name}\"")
            },
            onDismiss = { removing = null }
        )
    }
}

/** What the theme editor dialog is editing: a new picture theme, a new colour theme, or an existing one. */
private data class ThemeEditorState(
    val id: String?,
    val imageUri: Uri?,
    val isImage: Boolean,
    val name: String,
    val background: Int,
    val textAuto: Boolean,
    val text: Int
)

/** Settings block for custom themes: from a picture or a plain colour, with a chosen text colour; edit, rename or delete them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomThemesSection(onMessage: (String) -> Unit) {
    val assets = LocalAppContainer.current.customAssets
    val settingsStore = LocalAppContainer.current.settings
    val themes by assets.themes.collectAsState()
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ThemeEditorState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CustomTheme?>(null) }
    val reserved = remember { ReaderTheme.entries.map { it.label } }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Make your own reading themes by choosing a background colour and a text colour. Your themes appear in the reader's text settings next to Day, Night and Sepia, and you can edit, rename or delete them here.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = {
                error = null
                editor = ThemeEditorState(null, null, false, "", CustomRules.PALETTE[8], true, 0xFF000000.toInt())
            },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())
        ) { Text("Create a theme") }
        if (themes.isEmpty()) Text("No custom themes yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        themes.forEach { t ->
            Row(Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()), verticalAlignment = Alignment.CenterVertically) {
                ThemeThumb(t)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (t.file.isEmpty()) "Colour theme" else "Picture theme") + " · text " + CustomRules.toHex(t.text),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    error = null
                    editor = ThemeEditorState(t.id, null, t.file.isNotEmpty(), t.name, t.background, false, t.text)
                }) { Icon(Icons.Filled.Edit, "Edit theme ${t.name}") }
                IconButton(onClick = { removing = t }) { Icon(Icons.Filled.Delete, "Remove theme ${t.name}") }
            }
        }
    }

    editor?.let { e ->
        ThemeEditorDialog(
            state = e,
            error = error,
            saving = saving,
            onChange = { editor = it; error = null },
            onDismiss = { if (!saving) editor = null },
            onSave = {
                val textOrNull = if (e.textAuto) null else e.text
                saving = true
                scope.launch {
                    val result: Result<CustomTheme> = when {
                        e.id != null -> assets.updateTheme(e.id, e.name, if (e.isImage) null else e.background, textOrNull, reserved)
                        else -> assets.addColorTheme(e.name, e.background, textOrNull, reserved)
                    }
                    saving = false
                    result.fold(
                        onSuccess = { t ->
                            editor = null
                            onMessage(if (e.id != null) "Saved theme \"${t.name}\"" else "Added theme \"${t.name}\". Choose it in the reader's text settings.")
                        },
                        onFailure = { ex -> error = ex.message }
                    )
                }
            }
        )
    }
    removing?.let { t ->
        ConfirmDialog(
            "Remove \"${t.name}\"?", "The theme" + (if (t.file.isEmpty()) "" else " and its copy of the picture") + " is deleted from Readle. Books using it switch back to the standard theme. Your original picture isn't touched.", "Remove", destructive = true,
            onConfirm = {
                removing = null
                assets.removeTheme(t.id)
                scope.launch {
                    settingsStore.update { s -> if (s.typography.customThemeId == t.id) s.copy(typography = s.typography.copy(customThemeId = null)) else s }
                }
                onMessage("Removed theme \"${t.name}\"")
            },
            onDismiss = { removing = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeEditorDialog(
    state: ThemeEditorState,
    error: String?,
    saving: Boolean,
    onChange: (ThemeEditorState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val editingPicture = state.isImage
    val previewBg = state.background
    val previewText = if (state.textAuto) CustomRules.themeFor("x", "x", "", previewBg, ThemeText.AUTO, null).text else state.text
    val lowContrast = CustomRules.contrast(previewBg, previewText) < CustomRules.MIN_COMFORT_CONTRAST

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.id != null) "Edit theme" else "New theme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onChange(state.copy(name = it.take(CustomRules.MAX_NAME))) },
                    label = { Text("Theme name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
                if (editingPicture) {
                    Text("The picture stays as it is. You can rename the theme and change its text colour.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Background colour", style = MaterialTheme.typography.labelLarge)
                    ColorPicker(state.background) { onChange(state.copy(background = it)) }
                }

                Text("Text colour", style = MaterialTheme.typography.labelLarge)
                FilterChip(
                    selected = state.textAuto,
                    onClick = { onChange(state.copy(textAuto = true)) },
                    label = { Text("Automatic (black or white)") }
                )
                ColorPicker(state.text) { onChange(state.copy(text = it, textAuto = false)) }

                run {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(previewBg)).padding(16.dp)
                    ) {
                        Text("Aa  The quick brown fox jumps over the lazy dog.", color = Color(previewText))
                    }
                }
                if (lowContrast) Text(
                    "These colours are close together, so the text may be hard to read.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = onSave) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A grid of swatches plus a hex field (#RRGGBB). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(color: Int, onColor: (Int) -> Unit) {
    var hex by remember { mutableStateOf(CustomRules.toHex(color)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CustomRules.PALETTE.forEach { c ->
                val selected = (color and 0xFFFFFF) == (c and 0xFFFFFF)
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = "Colour " + CustomRules.toHex(c) + if (selected) ", selected" else "" }
                        .clickable { hex = CustomRules.toHex(c); onColor(c) }
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                hex = v.take(7)
                CustomRules.parseHex(hex)?.let(onColor)
            },
            label = { Text("Hex colour, for example #F0DEC6") },
            singleLine = true,
            isError = CustomRules.parseHex(hex) == null
        )
    }
}


@Composable
private fun ThemeThumb(theme: CustomTheme) {
    val assets = LocalAppContainer.current.customAssets
    val bitmap by produceState<ImageBitmap?>(null, theme.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                android.graphics.BitmapFactory.decodeFile(assets.themeFile(theme).absolutePath, opts)?.asImageBitmap()
            } catch (e: Throwable) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(48.dp).clip(shape).background(Color(theme.background))) {
        bitmap?.let { Image(it, null, Modifier.size(48.dp), contentScale = ContentScale.Crop) }
        Text("Aa", Modifier.align(Alignment.Center), color = Color(theme.text), style = MaterialTheme.typography.titleMedium)
    }
}
