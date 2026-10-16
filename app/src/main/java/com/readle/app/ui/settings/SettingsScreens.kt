package com.readle.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.readle.app.BuildConfig
import com.readle.app.domain.AppSettings
import com.readle.app.domain.AppTheme
import com.readle.app.domain.LibraryLayout
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.SectionHeader
import com.readle.app.ui.components.minTouchTarget
import kotlinx.coroutines.launch

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onChecked(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(navigation: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s by container.settings.settings.collectAsState(initial = AppSettings())
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(t) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(navigationIcon = navigation, title = { Text("Settings") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Appearance")
            Text("App theme", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t -> FilterChip(s.appTheme == t, { update { it.copy(appTheme = t) } }, label = { Text(t.label) }) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Library layout", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryLayout.entries.forEach { l -> FilterChip(s.layout == l, { update { it.copy(layout = l) } }, label = { Text(l.label) }) }
            }
            ToggleRow("Show covers", "Use the book's cover when it has one. Otherwise a plain title tile is shown.", s.showCovers) { v -> update { it.copy(showCovers = v) } }

            SectionHeader("Library")
            ToggleRow("Keep reading history", "Powers the Recent list. Turning this off also stops new entries.", s.keepHistory) { v -> update { it.copy(keepHistory = v) } }
            ToggleRow("Flag possible duplicates", "Compares file size and the start of each file. Nothing is ever deleted automatically.", s.detectDuplicates) { v -> update { it.copy(detectDuplicates = v) } }

            SectionHeader("Accessibility")
            ToggleRow("High contrast", "Stronger colors in the app and in the reader.", s.highContrast) { v -> update { it.copy(highContrast = v) } }
            ToggleRow("Larger touch targets", "Makes buttons and rows taller.", s.largeTouchTargets) { v -> update { it.copy(largeTouchTargets = v) } }
            ToggleRow("Reduce motion", "Jumps instead of animating page scrolls.", s.reducedMotion) { v -> update { it.copy(reducedMotion = v) } }

            SectionHeader("Privacy and data")
            Text(
                "Readle works fully offline. It has no internet permission, no account, no ads and no analytics. Everything below is stored only on this device.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { container.libraryManager.clearCaches(); scope.launch { snackbar.showSnackbar("Caches cleared") } }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Clear cover and file caches")
                }
                OutlinedButton(onClick = { confirmHistory = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) { Text("Clear reading history") }
                OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
                    Text("Delete all Readle data…", color = MaterialTheme.colorScheme.error)
                }
            }

            SectionHeader("What to expect")
            WhatToExpectBlock()
            Spacer(Modifier.height(8.dp))

            SectionHeader("About Readle")
            AboutBlock()
            Spacer(Modifier.height(8.dp))

            SectionHeader("Open-source licenses")
            OpenSourceBlock()
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmHistory) ConfirmDialog(
        "Clear reading history?", "The Recent list is emptied. Your progress, bookmarks and notes are kept.", "Clear",
        onConfirm = { confirmHistory = false; scope.launch { container.libraryRepository.clearHistory(); snackbar.showSnackbar("History cleared") } },
        onDismiss = { confirmHistory = false }
    )
    if (confirmWipe) ConfirmDialog(
        "Delete all Readle data?",
        "This removes your library list, progress, bookmarks, notes, highlights, collections, vocabulary and caches, and releases every folder and file permission. Your actual book files are not touched.",
        "Delete everything", destructive = true,
        onConfirm = { confirmWipe = false; scope.launch { container.libraryManager.deleteAllLocalData(); container.settings.clearAll() } },
        onDismiss = { confirmWipe = false }
    )
}

private const val REPO_URL = "https://github.com/karthikhegde25/Readle"

@Composable
private fun WhatToExpectBlock() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Formats: PDF, EPUB, MOBI/AZW, FB2, DOCX, RTF, HTML, Markdown, TXT and CBZ comics (DRM-free).", style = MaterialTheme.typography.bodyMedium)
        listOf(
            "PDF pages are drawn as images by Android's PDF renderer, so PDF text can't be selected, highlighted or searched, and there is no PDF table of contents. Bookmarks work.",
            "Scanned PDFs show their page images, but Readle has no OCR.",
            "Read aloud uses the text-to-speech voices installed on your device and stops when you leave the reader.",
            "Password-protected PDFs and DRM-protected books can't be opened.",
            "KF8-only AZW3 books, HUFF/CDIC MOBI files, DJVU, CBR and old binary DOC files aren't supported. Convert them to EPUB or PDF.",
            "Deleting a file from your device needs Android's permission for that file. Readle asks for it when needed.",
            "Define uses a built-in English dictionary (WordNet) that works offline. Pronunciation is spoken by your device’s text-to-speech voice, only if it has an English voice."
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AboutBlock() {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.readle.app.R.drawable.readle_logo),
                contentDescription = "Readle logo",
                modifier = Modifier.height(104.dp).aspectRatio(800f / 824f).clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            )
            Text("Readle", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Readle version ${com.readle.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text("Developer: Karthik Hegde", style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())
                .clickable(onClickLabel = "Open the Readle repository on GitHub") { runCatching { uriHandler.openUri(REPO_URL) } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(androidx.compose.ui.res.painterResource(com.readle.app.R.drawable.ic_github), contentDescription = "GitHub", tint = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.padding(start = 12.dp)) {
                Text("github.com/karthikhegde25/Readle", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("License: GNU General Public License v3.0 (GPL-3.0). Readle is free software: you can use, study, share and change it under the terms of that license.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OpenSourceBlock() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Android Jetpack (Compose, Room, DataStore, Navigation, Lifecycle), Kotlin and kotlinx.coroutines — all Apache License 2.0. Readle contains no code from other reader apps.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "WordNet 3.1 — Copyright 2011 Princeton University. All rights reserved. Used under the WordNet license, which permits use, copying, modification and distribution without fee provided this notice and its disclaimer are kept. The full text ships inside the app (assets/wordnet/WORDNET-LICENSE.txt). The name of Princeton University may not be used in advertising or publicity for Readle.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** First-run screen: what Readle is, how access works, and one clear action. */
@Composable
fun OnboardingScreen(onAddFiles: () -> Unit, onAddFolder: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.AutoStories, null, Modifier.height(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Welcome to Readle", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("A private, offline reader for PDF, EPUB, MOBI, FB2, DOCX and more.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "  Readle only reads the files and folders you choose. It has no internet access and doesn't ask for storage permission.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAddFolder, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) {
            Icon(Icons.Filled.FolderOpen, null)
            Text("  Choose a folder of books")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAddFiles, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())) { Text("Choose individual files") }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.TextButton(onClick = onSkip) { Text("Skip for now") }
    }
}
