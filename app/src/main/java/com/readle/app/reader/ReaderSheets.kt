package com.readle.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readle.app.data.db.AnnotationEntity
import com.readle.app.data.db.BookmarkEntity
import com.readle.app.domain.AppSettings
import com.readle.app.domain.Formatters
import com.readle.app.domain.OrientationPref
import com.readle.app.domain.PageTurnMode
import com.readle.app.domain.ReaderAlign
import com.readle.app.domain.ReaderFont
import com.readle.app.domain.ReaderTheme
import com.readle.app.domain.TypographySettings
import com.readle.app.reader.reflow.SearchHit
import com.readle.app.reader.reflow.TocEntry
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.minTouchTarget

// ---------------------------------------------------------------- contents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(toc: List<TocEntry>, currentSpine: Int, onPick: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (toc.isEmpty()) {
            Text("This book has no table of contents.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(toc.size, key = { it }) { i ->
                    val e = toc[i]
                    val current = e.spineIndex == currentSpine
                    Row(
                        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())
                            .clickable { onPick(e) }
                            .padding(start = (16 + 16 * e.depth.coerceIn(0, 4)).dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                            .semantics { if (current) contentDescription = "${e.title}, current section" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            e.title,
                            style = if (current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bookmarks, highlights, notes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksSheet(
    bookmarks: List<BookmarkEntity>,
    annotations: List<AnnotationEntity>,
    onBookmark: (BookmarkEntity) -> Unit,
    onRenameBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onAnnotation: (AnnotationEntity) -> Unit,
    onEditAnnotation: (AnnotationEntity) -> Unit,
    canAnnotate: Boolean,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bookmarks (${bookmarks.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Notes (${annotations.size})") })
        }
        if (tab == 0) {
            if (bookmarks.isEmpty()) {
                Box(Modifier.height(240.dp)) {
                    EmptyState(Icons.Filled.Edit, "No bookmarks", "Use the bookmark button in the toolbar to save your place.")
                }
            } else LazyColumn(Modifier.fillMaxWidth()) {
                items(bookmarks, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onBookmark(b) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${Formatters.percent(b.progressFraction)}% through", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRenameBookmark(b) }) { Icon(Icons.Filled.Edit, "Rename bookmark ${b.label}") }
                        IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Filled.Delete, "Delete bookmark ${b.label}") }
                    }
                    HorizontalDivider()
                }
            }
        } else {
            if (!canAnnotate) {
                Box(Modifier.height(240.dp)) {
                    EmptyState(
                        Icons.Filled.Edit, "Highlights aren't available for PDF",
                        "Readle shows PDF pages as images, so text can't be selected. Highlights and notes work in text-based books. Bookmarks work everywhere."
                    )
                }
            } else if (annotations.isEmpty()) {
                Box(Modifier.height(240.dp)) {
                    EmptyState(Icons.Filled.Edit, "No highlights or notes", "Select text in the book to highlight it, add a note, or save a quote.")
                }
            } else LazyColumn(Modifier.fillMaxWidth()) {
                items(annotations, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { onAnnotation(a) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                        Box(Modifier.padding(top = 4.dp).size(14.dp).background(Color(a.color), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.selectedText, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            a.noteText?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                when (a.type) { AnnotationEntity.NOTE -> "Note"; AnnotationEntity.QUOTE -> "Quote"; else -> "Highlight" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEditAnnotation(a) }) { Icon(Icons.Filled.Edit, "Edit this ${a.type.lowercase()}") }
                    }
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    state: SearchState,
    initial: String,
    onSearch: (String) -> Unit,
    onPick: (SearchHit) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Search in this book") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                trailingIcon = {
                    if (text.isNotEmpty()) TextButton(onClick = { text = ""; onClear() }) { Text("Clear") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching… ${state.hits.size} found", style = MaterialTheme.typography.bodyMedium)
                }
                state.completed && state.hits.isEmpty() -> Text("No matches for “${state.query}”.", style = MaterialTheme.typography.bodyMedium)
                state.completed -> Text(
                    if (state.truncated) "Showing the first ${state.hits.size} matches." else "${state.hits.size} ${if (state.hits.size == 1) "match" else "matches"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            items(state.hits) { hit ->
                Column(Modifier.fillMaxWidth().clickable { onPick(hit) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Section ${hit.spine + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- reading settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    reflow: Boolean,
    typography: TypographySettings,
    perBook: Boolean,
    settings: AppSettings,
    onTypography: ((TypographySettings) -> TypographySettings) -> Unit,
    onPerBook: (Boolean) -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onOpenThemes: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Reading settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            val assets = com.readle.app.ui.LocalAppContainer.current.customAssets
            val customThemes by assets.themes.collectAsState()
            val customFonts by assets.fonts.collectAsState()
            val customThemeActive = customThemes.any { it.id == typography.customThemeId }
            val customFontActive = customFonts.any { it.id == typography.customFontId }

            Text("Theme", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { t ->
                    FilterChip(
                        selected = !customThemeActive && typography.theme == t,
                        onClick = { onTypography { it.copy(theme = t, customThemeId = null) } },
                        label = { Text(t.label) }
                    )
                }
                customThemes.forEach { ct ->
                    FilterChip(
                        selected = typography.customThemeId == ct.id,
                        onClick = { onTypography { it.copy(customThemeId = ct.id) } },
                        label = { Text(ct.name) },
                        leadingIcon = {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(ct.background))
                            )
                        }
                    )
                }
                androidx.compose.material3.AssistChip(
                    onClick = onOpenThemes,
                    label = { Text("+ Add or edit themes") }
                )
            }

            if (reflow) {
                Spacer(Modifier.height(8.dp))
                Text("Font", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { f ->
                        FilterChip(
                            selected = !customFontActive && typography.font == f,
                            onClick = { onTypography { it.copy(font = f, customFontId = null) } },
                            label = { Text(f.label) }
                        )
                    }
                    customFonts.forEach { cf ->
                        FilterChip(
                            selected = typography.customFontId == cf.id,
                            onClick = { onTypography { it.copy(customFontId = cf.id) } },
                            label = { Text(cf.name) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Alignment", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderAlign.entries.forEach { a ->
                        FilterChip(selected = typography.align == a, onClick = { onTypography { it.copy(align = a) } }, label = { Text(a.label) })
                    }
                }
                LabeledSlider(
                    "Text size ${typography.fontSizePercent}%", typography.fontSizePercent.toFloat(),
                    TypographySettings.MIN_FONT_PERCENT.toFloat()..TypographySettings.MAX_FONT_PERCENT.toFloat()
                ) { v -> onTypography { it.copy(fontSizePercent = (Math.round(v / 5f) * 5)) } }
                LabeledSlider(
                    "Line spacing ${"%.1f".format(typography.lineSpacing)}", typography.lineSpacing,
                    TypographySettings.MIN_LINE_SPACING..TypographySettings.MAX_LINE_SPACING
                ) { v -> onTypography { it.copy(lineSpacing = Math.round(v * 10f) / 10f) } }
                LabeledSlider(
                    "Paragraph spacing ${"%.1f".format(typography.paragraphSpacing)}", typography.paragraphSpacing,
                    TypographySettings.MIN_PARAGRAPH_SPACING..TypographySettings.MAX_PARAGRAPH_SPACING
                ) { v -> onTypography { it.copy(paragraphSpacing = Math.round(v * 10f) / 10f) } }
                LabeledSlider(
                    "Margins ${typography.marginDp} dp", typography.marginDp.toFloat(),
                    TypographySettings.MIN_MARGIN.toFloat()..TypographySettings.MAX_MARGIN.toFloat()
                ) { v -> onTypography { it.copy(marginDp = v.toInt()) } }
                SwitchRow("Use these text settings for this book only", perBook, onPerBook)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "PDF pages keep their original layout, so font, size and spacing options only apply to reflowable books. Use pinch or double-tap to zoom.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Page turning", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageTurnMode.entries.forEach { m ->
                    FilterChip(selected = settings.pageTurn == m, onClick = { onSettings { it.copy(pageTurn = m) } }, label = { Text(m.label) })
                }
            }
            Text("Orientation", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrientationPref.entries.forEach { o ->
                    FilterChip(selected = settings.orientation == o, onClick = { onSettings { it.copy(orientation = o) } }, label = { Text(o.label) })
                }
            }
            SwitchRow("Use system brightness", settings.brightnessOverride == null) { system ->
                onSettings { it.copy(brightnessOverride = if (system) null else 0.6f) }
            }
            settings.brightnessOverride?.let { b ->
                LabeledSlider("Brightness ${(b * 100).toInt()}%", b, 0.05f..1f) { v -> onSettings { it.copy(brightnessOverride = v) } }
            }
            SwitchRow("Keep screen on while reading", settings.keepScreenOn) { v -> onSettings { it.copy(keepScreenOn = v) } }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}
