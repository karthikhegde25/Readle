package com.readle.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readle.app.data.repo.ScanState
import com.readle.app.domain.DocFormat
import com.readle.app.domain.LibraryItem
import com.readle.app.domain.LibraryLayout
import com.readle.app.domain.LibraryScope
import com.readle.app.domain.ReadStatus
import com.readle.app.domain.SmartList
import com.readle.app.domain.SortKey
import com.readle.app.ui.components.CloseIconButton
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.TextInputDialog
import com.readle.app.ui.components.minTouchTarget

/** MIME types the system picker is asked for. Anything else the user forces through is counted as unsupported. */
val PICKER_MIME_TYPES: Array<String> = com.readle.app.domain.FormatSupport.pickerMimeTypes

private enum class Pending { REMOVE_LIBRARY, DELETE_FILES, REMOVE_HISTORY, NEW_COLLECTION, PICK_COLLECTION }

/**
 * One screen for every library scope (All, Recent, a collection, an author, a smart list, a folder).
 * [navigation] is the leading icon (menu or back), supplied by the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    scope: LibraryScope,
    title: String,
    navigation: @Composable () -> Unit,
    onOpenBook: (Long) -> Unit,
    onShowDetails: (Long) -> Unit,
    onOpenFolders: () -> Unit,
    onReviewUnavailable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(
        key = "library:" + scope.encode(),
        factory = LibraryViewModel.Factory(context.applicationContext as Application, scope)
    )
    val state by vm.state.collectAsState()
    val liveSearch by vm.liveSearchText.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var searching by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var relinkTarget by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is LibraryEvent.Message -> snackbar.showSnackbar(e.text)
                is LibraryEvent.OpenReader -> onOpenBook(e.docId)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(com.readle.app.files.OpenDocumentsReadWrite()) { uris: List<Uri> ->
        vm.addFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addFolder(uri)
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val id = relinkTarget
        relinkTarget = null
        if (uri != null && id != null) vm.relink(id, uri)
    }

    val runDelete = rememberDeleteFlow { message, _ -> vm.showMessage(message) }

    val canAdd = scope is LibraryScope.All || scope is LibraryScope.Recent

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selecting) {
                SelectionBar(
                    count = state.selection.size,
                    allFavorite = state.items.filter { it.doc.id in state.selection }.all { it.doc.isFavorite },
                    scope = scope,
                    onClear = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onFavorite = { vm.toggleFavorite() },
                    onAddToCollection = { pending = Pending.PICK_COLLECTION },
                    onRemoveFromCollection = { vm.removeFromCollection() },
                    onRemoveHistory = { pending = Pending.REMOVE_HISTORY },
                    onRemoveLibrary = { pending = Pending.REMOVE_LIBRARY },
                    onDelete = { pending = Pending.DELETE_FILES },
                    onDetails = if (state.selection.size == 1) ({ onShowDetails(state.selection.first()) }) else null
                )
            } else {
                TopAppBar(
                    navigationIcon = navigation,
                    title = {
                        if (searching) {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            OutlinedTextField(
                                value = liveSearch,
                                onValueChange = vm::setSearch,
                                placeholder = { Text("Search title, author, file name") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {}),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            )
                        } else {
                            Text(title, maxLines = 1)
                        }
                    },
                    actions = {
                        if (searching) {
                            CloseIconButton(onClick = { searching = false; vm.setSearch("") }, description = "Close search")
                        } else {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search library") }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort and layout") }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    Text("Sort by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                    SortKey.entries.forEach { key ->
                                        val selected = key == state.settings.sortKey
                                        DropdownMenuItem(
                                            text = { Text(key.label) },
                                            leadingIcon = {
                                                if (selected) Icon(
                                                    if (state.settings.sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                                    contentDescription = if (state.settings.sortAscending) "Ascending" else "Descending"
                                                )
                                            },
                                            onClick = {
                                                // Choosing the active key flips direction; a new key starts ascending
                                                // (except recency/progress which read better newest/most first).
                                                val asc = if (selected) !state.settings.sortAscending
                                                else key != SortKey.RECENTLY_OPENED && key != SortKey.LAST_MODIFIED && key != SortKey.PROGRESS
                                                vm.setSort(key, asc)
                                                sortMenu = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    Text("Layout", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                    LibraryLayout.entries.forEach { l ->
                                        DropdownMenuItem(
                                            text = { Text(l.label) },
                                            leadingIcon = {
                                                Icon(
                                                    when (l) {
                                                        LibraryLayout.GRID -> Icons.Filled.ViewModule
                                                        LibraryLayout.COMPACT -> Icons.Filled.ViewList
                                                        LibraryLayout.DETAILED -> Icons.Filled.ViewAgenda
                                                    },
                                                    contentDescription = null
                                                )
                                            },
                                            trailingIcon = { if (l == state.settings.layout) Text("✓") },
                                            onClick = { vm.setLayout(l); sortMenu = false }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { showFilters = true }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = if (state.filters.active) "Filters (active)" else "Filters",
                                    tint = if (state.filters.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Box {
                                IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More options") }
                                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Rescan folders") },
                                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                        onClick = { overflow = false; vm.rescan() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Manage folders") },
                                        leadingIcon = { Icon(Icons.Filled.Folder, null) },
                                        onClick = { overflow = false; onOpenFolders() }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (canAdd && !state.selecting) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text("Add books") }
                    )
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Choose files") },
                            leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                            onClick = { addMenu = false; filePicker.launch(PICKER_MIME_TYPES) }
                        )
                        DropdownMenuItem(
                            text = { Text("Add a folder") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                            onClick = { addMenu = false; folderPicker.launch(null) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScanBanner(state.scan, onCancel = vm::cancelScan, onDismiss = vm::dismissScan)
            // Once reviewed, the banner stays hidden until additional files become unavailable.
            LaunchedEffect(state.unavailableCount) {
                if (state.unavailableCount < UnavailableNotice.reviewedCount) UnavailableNotice.reviewedCount = state.unavailableCount
            }
            if (state.unavailableCount > UnavailableNotice.reviewedCount && scope !is LibraryScope.Smart) {
                UnavailableBanner(state.unavailableCount, onReview = { vm.markUnavailableReviewed(state.unavailableCount); onReviewUnavailable() })
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(0.5f))
                }
                state.totalInScope == 0 -> EmptyForScope(
                    scope = scope,
                    onAddFiles = { filePicker.launch(PICKER_MIME_TYPES) },
                    onAddFolder = { folderPicker.launch(null) }
                )
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "No matches",
                    message = "Nothing in this list matches your search or filters.",
                    primaryLabel = "Clear search and filters",
                    onPrimary = { vm.clearFiltersAndSearch(); searching = false }
                )
                else -> ItemList(
                    items = state.items,
                    layout = state.settings.layout,
                    showCovers = state.settings.showCovers,
                    selection = state.selection,
                    selecting = state.selecting,
                    onTap = { item ->
                        when {
                            state.selecting -> vm.toggleSelected(item.doc.id)
                            !item.isAvailable -> onShowDetails(item.doc.id)
                            else -> onOpenBook(item.doc.id)
                        }
                    },
                    onLong = { item -> vm.toggleSelected(item.doc.id) }
                )
            }
        }
    }

    // ---- dialogs ----

    when (pending) {
        Pending.REMOVE_LIBRARY -> ConfirmDialog(
            title = "Remove from library?",
            message = "${state.selection.size} selected. Readle forgets them, along with their progress, bookmarks and notes. The files on your device are not deleted.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { pending = null; vm.removeFromLibrary() },
            onDismiss = { pending = null }
        )
        Pending.DELETE_FILES -> ConfirmDialog(
            title = "Delete files permanently?",
            message = "${state.selection.size} selected. The actual files are deleted from your device and cannot be recovered. Their progress, bookmarks and notes are removed too.",
            confirmLabel = "Delete files",
            destructive = true,
            onConfirm = {
                pending = null
                val ids = state.selection.toList()
                vm.clearSelection()
                runDelete(ids)
            },
            onDismiss = { pending = null }
        )
        Pending.REMOVE_HISTORY -> ConfirmDialog(
            title = "Remove from Recent?",
            message = "They disappear from Recent only. Your progress is kept and the files are not deleted.",
            confirmLabel = "Remove",
            onConfirm = { pending = null; vm.removeFromHistory() },
            onDismiss = { pending = null }
        )
        Pending.NEW_COLLECTION -> TextInputDialog(
            title = "New collection",
            label = "Name",
            confirmLabel = "Create",
            onConfirm = { pending = null; vm.createCollectionAndAdd(it) },
            onDismiss = { pending = null }
        )
        Pending.PICK_COLLECTION -> CollectionPickerDialog(
            collections = state.collections.map { it.collection.id to it.collection.name },
            onPick = { pending = null; vm.addToCollection(it) },
            onNew = { pending = Pending.NEW_COLLECTION },
            onDismiss = { pending = null }
        )
        null -> Unit
    }

    if (showFilters) {
        FilterSheet(
            filters = state.filters,
            onChange = vm::setFilters,
            onClear = { vm.setFilters { LibraryFilters() } },
            onDismiss = { showFilters = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    allFavorite: Boolean,
    scope: LibraryScope,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onAddToCollection: () -> Unit,
    onRemoveFromCollection: () -> Unit,
    onRemoveHistory: () -> Unit,
    onRemoveLibrary: () -> Unit,
    onDelete: () -> Unit,
    onDetails: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { CloseIconButton(onClick = onClear, description = "Cancel selection") },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (allFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (allFavorite) "Remove from favorites" else "Add to favorites"
                )
            }
            IconButton(onClick = onAddToCollection) { Icon(Icons.Outlined.CollectionsBookmark, "Add to collection") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onDetails != null) DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetails() })
                    if (scope is LibraryScope.Collection) {
                        DropdownMenuItem(text = { Text("Remove from this collection") }, onClick = { menu = false; onRemoveFromCollection() })
                    }
                    if (scope is LibraryScope.Recent) {
                        DropdownMenuItem(text = { Text("Remove from Recent") }, onClick = { menu = false; onRemoveHistory() })
                    }
                    DropdownMenuItem(text = { Text("Remove from library") }, onClick = { menu = false; onRemoveLibrary() })
                    DropdownMenuItem(
                        text = { Text("Delete files…", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    )
}

@Composable
private fun ScanBanner(scan: ScanState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (scan) {
        ScanState.Idle -> Unit
        is ScanState.Scanning -> Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Scanning folders. ${scan.phase}. ${scan.found} books found." }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(scan.phase, style = MaterialTheme.typography.labelLarge)
                    Text("${scan.found} found", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (scan.total > 0) {
                LinearProgressIndicator(progress = { scan.processed.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is ScanState.Done -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val text = buildString {
                append("Scan finished: ${scan.added} added")
                if (scan.missing > 0) append(", ${scan.missing} no longer found")
                if (scan.unsupportedSkipped > 0) append(", ${scan.unsupportedSkipped} unsupported skipped")
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
        is ScanState.Failed -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.padding(4.dp))
            Text(scan.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun UnavailableBanner(count: Int, onReview: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.WarningAmber, null)
        Spacer(Modifier.padding(4.dp))
        Text(
            "$count ${if (count == 1) "file is" else "files are"} unavailable and hidden from the library.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onReview) { Text("Review") }
    }
}

@Composable
private fun EmptyForScope(scope: LibraryScope, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    when (scope) {
        LibraryScope.All -> EmptyState(
            icon = Icons.Filled.LibraryBooks,
            title = "Your library is empty",
            message = "Add PDF, EPUB, MOBI, FB2, DOCX, comics and other supported files, or pick a folder and Readle will find the books in it. Readle only reads what you choose.",
            primaryLabel = "Choose files",
            onPrimary = onAddFiles,
            secondaryLabel = "Add a folder",
            onSecondary = onAddFolder
        )
        LibraryScope.Recent -> EmptyState(
            icon = Icons.Filled.LibraryBooks,
            title = "Nothing opened yet",
            message = "Books you open appear here so you can pick up where you left off."
        )
        is LibraryScope.Collection -> EmptyState(
            icon = Icons.Outlined.CollectionsBookmark,
            title = "This collection is empty",
            message = "In the library, long-press books and choose Add to collection."
        )
        is LibraryScope.Smart -> EmptyState(
            icon = Icons.Filled.LibraryBooks,
            title = when (scope.list) {
                SmartList.FAVORITES -> "No favorites yet"
                SmartList.READING -> "Nothing in progress"
                SmartList.FINISHED -> "No finished books yet"
                SmartList.UNREAD -> "Nothing left to start"
                SmartList.DUPLICATES -> "No duplicates found"
                SmartList.UNAVAILABLE -> "All files are available"
            },
            message = when (scope.list) {
                SmartList.FAVORITES -> "Select books and tap the star to collect them here."
                SmartList.READING -> "Books you've started but not finished show up here."
                SmartList.FINISHED -> "Books you've read to the end show up here."
                SmartList.UNREAD -> "Books you haven't opened yet show up here."
                SmartList.DUPLICATES -> "Readle compares file size and content to spot copies of the same file."
                SmartList.UNAVAILABLE -> "Files that were moved, deleted, or lost permission show up here so you can relink them."
            }
        )
        else -> EmptyState(
            icon = Icons.Filled.LibraryBooks,
            title = "No books here",
            message = "Nothing in this group is currently available."
        )
    }
}

@Composable
private fun ItemList(
    items: List<LibraryItem>,
    layout: LibraryLayout,
    showCovers: Boolean,
    selection: Set<Long>,
    selecting: Boolean,
    onTap: (LibraryItem) -> Unit,
    onLong: (LibraryItem) -> Unit
) {
    when (layout) {
        LibraryLayout.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.doc.id }) { item ->
                DocGridItem(item, item.doc.id in selection, showCovers, { onTap(item) }, { onLong(item) })
            }
        }
        LibraryLayout.COMPACT -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(items, key = { it.doc.id }) { item ->
                DocCompactRow(item, item.doc.id in selection, showCovers, { onTap(item) }, { onLong(item) })
            }
        }
        LibraryLayout.DETAILED -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(items, key = { it.doc.id }) { item ->
                DocDetailedRow(item, item.doc.id in selection, showCovers, { onTap(item) }, { onLong(item) })
            }
        }
    }
}

@Composable
private fun CollectionPickerDialog(
    collections: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (collections.isEmpty()) {
                    Text("You don't have any collections yet.", style = MaterialTheme.typography.bodyMedium)
                }
                collections.forEach { (id, name) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 14.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("New collection…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filters: LibraryFilters,
    onChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Format", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DocFormat.entries.forEach { f ->
                    FilterChip(
                        selected = f in filters.formats,
                        onClick = { onChange { it.copy(formats = if (f in it.formats) it.formats - f else it.formats + f) } },
                        label = { Text(f.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Reading status", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadStatus.entries.forEach { s ->
                    FilterChip(
                        selected = filters.status == s,
                        onClick = { onChange { it.copy(status = if (it.status == s) null else s) } },
                        label = { Text(s.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            CheckRow("Favorites only", filters.favoritesOnly) { c -> onChange { it.copy(favoritesOnly = c) } }
            CheckRow("Possible duplicates only", filters.duplicatesOnly) { c -> onChange { it.copy(duplicatesOnly = c) } }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear, enabled = filters.active) { Text("Clear all") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = minTouchTarget()).clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.padding(6.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

