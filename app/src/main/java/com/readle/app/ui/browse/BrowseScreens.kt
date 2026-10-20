package com.readle.app.ui.browse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readle.app.data.db.AnnotationEntity
import com.readle.app.data.db.LocationEntity
import com.readle.app.data.repo.ScanState
import com.readle.app.domain.DocFormat
import com.readle.app.domain.Formatters
import com.readle.app.domain.FormatSupport
import com.readle.app.domain.LibraryScope
import com.readle.app.domain.Locator
import com.readle.app.domain.SmartList
import com.readle.app.files.SafFiles
import com.readle.app.ui.LocalAppContainer
import com.readle.app.ui.components.BackIconButton
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.SectionHeader
import com.readle.app.ui.components.TextInputDialog
import com.readle.app.ui.components.minTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================ collections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    navigation: @Composable () -> Unit,
    onOpenScope: (LibraryScope, String) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val collections by container.collectionRepository.observeCollections().collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<Long, String>?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(navigationIcon = navigation, title = { Text("Collections") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New collection") })
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Smart lists") }
            items(SmartList.entries.toList(), key = { it.key }) { list ->
                Row(
                    Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())
                        .clickable { onOpenScope(LibraryScope.Smart(list), list.label) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(list.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item { SectionHeader("Your collections") }
            if (collections.isEmpty()) {
                item {
                    Text(
                        "No collections yet. Create one, then add books from the library by long-pressing them.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(collections, key = { it.collection.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenScope(LibraryScope.Collection(c.collection.id), c.collection.name) }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.collection.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${c.docCount} ${if (c.docCount == 1) "book" else "books"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { container.collectionRepository.move(c.collection.id, -1) } }) {
                        Icon(Icons.Filled.ArrowUpward, "Move ${c.collection.name} up")
                    }
                    IconButton(onClick = { scope.launch { container.collectionRepository.move(c.collection.id, 1) } }) {
                        Icon(Icons.Filled.ArrowDownward, "Move ${c.collection.name} down")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Edit, "Edit ${c.collection.name}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = c.collection.id to c.collection.name })
                            DropdownMenuItem(
                                text = { Text("Delete collection", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = c.collection.id to c.collection.name }
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.size(88.dp)) }
        }
    }

    if (creating) TextInputDialog(
        title = "New collection", label = "Name", confirmLabel = "Create",
        onConfirm = { name ->
            creating = false
            scope.launch { if (container.collectionRepository.create(name) == null) snackbar.showSnackbar("A collection with that name already exists") }
        },
        onDismiss = { creating = false }
    )
    renaming?.let { (id, old) ->
        TextInputDialog(
            title = "Rename collection", initial = old, label = "Name",
            onConfirm = { name ->
                renaming = null
                scope.launch { if (!container.collectionRepository.rename(id, name)) snackbar.showSnackbar("That name is already used") }
            },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { (id, name) ->
        ConfirmDialog(
            title = "Delete “$name”?",
            message = "Only the collection is deleted. The books stay in your library and on your device.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { deleting = null; scope.launch { container.collectionRepository.delete(id) } },
            onDismiss = { deleting = null }
        )
    }
}

// ============================================================ authors / series

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    series: Boolean,
    navigation: @Composable () -> Unit,
    onOpenScope: (LibraryScope, String) -> Unit
) {
    val container = LocalAppContainer.current
    val flow = if (series) container.libraryRepository.observeSeriesGroups() else container.libraryRepository.observeAuthorGroups()
    val groups by flow.collectAsState(initial = emptyList())
    Scaffold(topBar = { TopAppBar(navigationIcon = navigation, title = { Text(if (series) "Series" else "Authors") }) }) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    Icons.Outlined.CollectionsBookmark,
                    if (series) "No series found" else "No authors yet",
                    if (series) "Books that declare a series in their metadata are grouped here. You can also set a series by editing a book's details."
                    else "Authors come from each book's metadata. Add books to see them grouped here."
                )
            }
        } else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(groups, key = { it.name }) { g ->
                val label = g.name.ifEmpty { if (series) "No series" else "Unknown author" }
                Row(
                    Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable {
                        onOpenScope(if (series) LibraryScope.Series(g.name) else LibraryScope.Author(g.name), label)
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${g.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

// ============================================================ folders

/** Folder picker that opens at Downloads when the system supports an initial location hint. */
private class OpenTreeAt(private val initial: Uri?) : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (initial != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

private val DOWNLOADS_URI: Uri = DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    navigation: @Composable () -> Unit,
    onOpenScope: (LibraryScope, String) -> Unit,
    onBrowse: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val locations by container.libraryRepository.observeLocations().collectAsState(initial = emptyList())
    val scan by container.libraryManager.scanState.collectAsState()
    var removing by remember { mutableStateOf<LocationEntity?>(null) }

    val onPicked: (Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            if (!container.libraryManager.addFolder(uri)) snackbar.showSnackbar("Android wouldn't keep access to that folder after a restart. You may need to add it again later.")
        }
    }
    val anyFolder = rememberLauncherForActivityResult(OpenTreeAt(null), onPicked)
    val downloads = rememberLauncherForActivityResult(OpenTreeAt(DOWNLOADS_URI), onPicked)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = navigation,
                title = { Text("Folders") },
                actions = {
                    IconButton(onClick = { container.libraryManager.rescanAll() }, enabled = scan !is ScanState.Scanning && locations.isNotEmpty()) {
                        Icon(Icons.Filled.Refresh, "Rescan all folders")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { anyFolder.launch(Unit) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add folder") })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = scan) {
                is ScanState.Scanning -> Text("Scanning: ${s.phase} (${s.found} found)", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp))
                is ScanState.Done -> Text("Last scan: ${s.added} added, ${s.missing} no longer found.", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp))
                is ScanState.Failed -> Text(s.message, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))
                ScanState.Idle -> Unit
            }
            Row(
                Modifier.fillMaxWidth().clickable { downloads.launch(Unit) }.sizeIn(minHeight = minTouchTarget()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Add your Downloads folder", style = MaterialTheme.typography.bodyLarge)
                    Text("Opens the system picker at Downloads. You choose what Readle can read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            if (locations.isEmpty()) {
                EmptyState(
                    Icons.Filled.Folder, "No folders added",
                    "Readle never browses your whole device. Pick a folder and it will find the supported books inside (PDF, EPUB, MOBI, FB2, DOCX, RTF, HTML, Markdown, TXT, CBZ), including subfolders."
                )
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(locations, key = { it.id }) { loc ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f).clickable { onBrowse(loc.id) }) {
                            Text(loc.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                loc.lastScanAt?.let { "Last scanned " + android.text.format.DateUtils.getRelativeTimeSpanString(it) } ?: "Not scanned yet",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onOpenScope(LibraryScope.Folder(loc.id), loc.displayName) }) { Text("Books") }
                        IconButton(onClick = { removing = loc }) { Icon(Icons.Filled.Delete, "Remove folder ${loc.displayName}") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    removing?.let { loc ->
        ConfirmDialog(
            title = "Remove folder?",
            message = "Readle stops watching “${loc.displayName}” and removes its books from the library, with their progress, bookmarks and notes. Nothing is deleted from your device.",
            confirmLabel = "Remove", destructive = true,
            onConfirm = { removing = null; scope.launch { container.libraryManager.removeFolder(loc.id) } },
            onDismiss = { removing = null }
        )
    }
}

/** Browses the real directory tree of one granted folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(locationId: Long, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val locations by container.libraryRepository.observeLocations().collectAsState(initial = emptyList())
    val loc = locations.firstOrNull { it.id == locationId }
    val items by container.libraryRepository.observeItems().collectAsState(initial = emptyList())
    val byUri = remember(items) { items.associateBy { it.doc.uri } }

    var stack by remember(locationId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // documentId to name
    val tree = loc?.let { Uri.parse(it.treeUri) }
    val currentId = stack.lastOrNull()?.first ?: tree?.let { SafFiles.treeRootDocumentId(it) }

    val entries by produceState<List<com.readle.app.files.TreeEntry>?>(null, tree, currentId) {
        value = null
        if (tree != null && currentId != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { SafFiles.listChildren(context, tree, currentId) }.getOrDefault(emptyList())
                    .sortedWith(compareByDescending<com.readle.app.files.TreeEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { BackIconButton { if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack() } },
            title = { Text(stack.lastOrNull()?.second ?: loc?.displayName ?: "Folder", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        )
    }) { padding ->
        val list = entries
        when {
            loc == null -> Box(Modifier.padding(padding)) { EmptyState(Icons.Filled.FolderOpen, "Folder not found", "This folder is no longer in your list.", primaryLabel = "Back", onPrimary = onBack) }
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.padding(padding)) { EmptyState(Icons.Filled.FolderOpen, "Empty folder", "Nothing here, or Android denied access to it.") }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.documentId }) { e ->
                    val format = if (e.isDirectory) null else FormatSupport.fromFileName(e.name)
                    val inLibrary = byUri[e.uri.toString()]
                    val supported = !e.isDirectory && format != null
                    Row(
                        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget())
                            .clickable(enabled = e.isDirectory || inLibrary != null) {
                                if (e.isDirectory) stack = stack + (e.documentId to e.name) else inLibrary?.let { onOpenBook(it.doc.id) }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (e.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null,
                            tint = if (supported || e.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            if (!e.isDirectory) Text(
                                when {
                                    !supported -> "Not a supported format"
                                    inLibrary != null -> "${format!!.label} · ${Formatters.fileSize(e.size)} · in library"
                                    else -> "${format!!.label} · ${Formatters.fileSize(e.size)} · not in library yet (rescan to add)"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ bookmarks & notes (all books)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    navigation: @Composable () -> Unit,
    onOpenReader: (docId: Long, startAt: Locator?, annotationId: Long?) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bookmarks by container.readerRepository.observeAllBookmarks().collectAsState(initial = emptyList())
    val annotations by container.readerRepository.observeAllAnnotations().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var deleteAnn by remember { mutableStateOf<Long?>(null) }
    var deleteBm by remember { mutableStateOf<Long?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = container.readerRepository.exportMarkdown()
                    container.appContext.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "Exported" else "Couldn't write the file")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = navigation,
                title = { Text("Bookmarks & notes") },
                actions = {
                    IconButton(
                        onClick = { exporter.launch("readle-notes.md") },
                        enabled = bookmarks.isNotEmpty() || annotations.isNotEmpty()
                    ) { Icon(Icons.Filled.Share, "Export as Markdown") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            androidx.compose.material3.TabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(tab == 0, { tab = 0 }, text = { Text("Notes (${annotations.size})") })
                androidx.compose.material3.Tab(tab == 1, { tab = 1 }, text = { Text("Bookmarks (${bookmarks.size})") })
            }
            if (tab == 0) {
                if (annotations.isEmpty()) EmptyState(Icons.Filled.Edit, "No highlights or notes", "Select text while reading a text-based book (not PDF or comics) to highlight it, add a note, or save a quote.")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(annotations, key = { it.annotation.id }) { row ->
                        val a = row.annotation
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenReader(a.documentId, null, a.id) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(Modifier.padding(top = 5.dp).size(14.dp).background(Color(a.color), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.docTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(a.selectedText, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                a.noteText?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                                Text(
                                    when (a.type) { AnnotationEntity.NOTE -> "Note"; AnnotationEntity.QUOTE -> "Quote"; else -> "Highlight" },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = { deleteAnn = a.id }) { Icon(Icons.Filled.Delete, "Delete this ${a.type.lowercase()}") }
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                if (bookmarks.isEmpty()) EmptyState(Icons.Filled.Edit, "No bookmarks", "Tap the bookmark button in the reader toolbar to save your place.")
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(bookmarks, key = { it.bookmark.id }) { row ->
                        val b = row.bookmark
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenReader(b.documentId, Locator.decode(b.positionType, b.positionValue), null) }
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.docTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(b.label, style = MaterialTheme.typography.bodyLarge)
                                Text("${Formatters.percent(b.progressFraction)}% through", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { deleteBm = b.id }) { Icon(Icons.Filled.Delete, "Delete bookmark ${b.label}") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    deleteAnn?.let { id ->
        ConfirmDialog("Delete?", "This highlight or note will be removed permanently.", "Delete", destructive = true,
            onConfirm = { deleteAnn = null; scope.launch { container.readerRepository.deleteAnnotation(id) } }, onDismiss = { deleteAnn = null })
    }
    deleteBm?.let { id ->
        ConfirmDialog("Delete bookmark?", "This bookmark will be removed.", "Delete", destructive = true,
            onConfirm = { deleteBm = null; scope.launch { container.readerRepository.deleteBookmark(id) } }, onDismiss = { deleteBm = null })
    }
}

// ============================================================ vocabulary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(navigation: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val words by container.readerRepository.observeVocabulary().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<com.readle.app.data.db.VocabularyEntity?>(null) }
    var deleting by remember { mutableStateOf<com.readle.app.data.db.VocabularyEntity?>(null) }

    Scaffold(topBar = { TopAppBar(navigationIcon = navigation, title = { Text("Vocabulary") }) }) { padding ->
        if (words.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.Filled.Edit, "No saved words", "Select a word while reading and choose Save word. You can add your own definition here.")
            }
        } else LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(words, key = { it.id }) { w ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.word, style = MaterialTheme.typography.titleMedium)
                        Text(w.definition ?: "No definition yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = w }) { Icon(Icons.Filled.Edit, "Edit definition of ${w.word}") }
                    IconButton(onClick = { deleting = w }) { Icon(Icons.Filled.Delete, "Delete ${w.word}") }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { w ->
        TextInputDialog(
            title = w.word, initial = w.definition.orEmpty(), label = "Definition",
            validate = { null },
            onConfirm = { text ->
                editing = null
                scope.launch { container.readerRepository.updateWord(w.copy(definition = text.trim().ifEmpty { null })) }
            },
            onDismiss = { editing = null }
        )
    }
    deleting?.let { w ->
        ConfirmDialog("Delete “${w.word}”?", "It will be removed from your vocabulary.", "Delete", destructive = true,
            onConfirm = { deleting = null; scope.launch { container.readerRepository.deleteWord(w.id) } }, onDismiss = { deleting = null })
    }
}
