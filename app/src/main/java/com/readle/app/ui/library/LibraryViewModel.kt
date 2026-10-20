package com.readle.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readle.app.ReadleApp
import com.readle.app.data.db.CollectionWithCount
import com.readle.app.data.repo.ScanState
import com.readle.app.domain.AppSettings
import com.readle.app.domain.DocFormat
import com.readle.app.domain.LibraryItem
import com.readle.app.domain.LibraryLogic
import com.readle.app.domain.LibraryLayout
import com.readle.app.domain.LibraryQuery
import com.readle.app.domain.LibraryScope
import com.readle.app.domain.ReadStatus
import com.readle.app.domain.ScopeLogic
import com.readle.app.domain.SmartList
import com.readle.app.domain.SortKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-controlled filters on top of the scope. Search text is kept separately so it can be debounced. */
data class LibraryFilters(
    val formats: Set<DocFormat> = emptySet(),
    val status: ReadStatus? = null,
    val favoritesOnly: Boolean = false,
    val duplicatesOnly: Boolean = false
) {
    val active: Boolean get() = formats.isNotEmpty() || status != null || favoritesOnly || duplicatesOnly
}

data class LibraryUiState(
    val loading: Boolean = true,
    val scope: LibraryScope = LibraryScope.All,
    val items: List<LibraryItem> = emptyList(),
    /** Items in the scope ignoring search and filters. 0 means the list is genuinely empty. */
    val totalInScope: Int = 0,
    val settings: AppSettings = AppSettings(),
    val searchText: String = "",
    val filters: LibraryFilters = LibraryFilters(),
    val collections: List<CollectionWithCount> = emptyList(),
    val unavailableCount: Int = 0,
    val selection: Set<Long> = emptySet(),
    val scan: ScanState = ScanState.Idle
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val hasQuery: Boolean get() = searchText.isNotBlank() || filters.active
}

/** One-shot results shown as snackbars. */
sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class OpenReader(val docId: Long) : LibraryEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application, val scope: LibraryScope) : AndroidViewModel(app) {
    private val container = (app as ReadleApp).container
    private val manager = container.libraryManager

    private val searchText = MutableStateFlow("")
    private val filters = MutableStateFlow(LibraryFilters())
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val debouncedSearch = searchText.debounce(250)

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val allItems = container.libraryRepository.observeItems()

    val state: StateFlow<LibraryUiState> = combine(
        combine(allItems, container.settings.settings) { items, settings -> items to settings },
        combine(debouncedSearch, filters, selection) { t, f, s -> Triple(t, f, s) },
        combine(container.collectionRepository.observeCollections(), manager.scanState) { c, s -> c to s }
    ) { (items, settings), (text, f, sel), (collections, scan) ->
        val base = LibraryQuery(
            text = text,
            sortKey = settings.sortKey,
            ascending = settings.sortAscending,
            formats = f.formats,
            status = f.status,
            favoritesOnly = f.favoritesOnly,
            duplicatesOnly = f.duplicatesOnly
        )
        val shown = ScopeLogic.apply(items, scope, base)
        val validSelection = sel.intersect(items.map { it.doc.id }.toSet())
        LibraryUiState(
            loading = false,
            scope = scope,
            items = shown,
            totalInScope = ScopeLogic.countInScope(items, scope),
            settings = settings,
            searchText = searchText.value,
            filters = f,
            collections = collections,
            unavailableCount = items.count { !it.isAvailable },
            selection = validSelection,
            scan = scan
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState(scope = scope))

    // ---- search / filters / sort / layout ----

    /** The text field is bound to this immediately; results follow after the debounce. */
    fun setSearch(text: String) {
        searchText.value = text
    }

    val liveSearchText: StateFlow<String> get() = searchText

    fun setFilters(transform: (LibraryFilters) -> LibraryFilters) = filters.update(transform)

    fun clearFiltersAndSearch() {
        searchText.value = ""
        filters.value = LibraryFilters()
    }

    fun setSort(key: SortKey, ascending: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(sortKey = key, sortAscending = ascending) } }
    }

    fun setLayout(layout: LibraryLayout) {
        viewModelScope.launch { container.settings.update { it.copy(layout = layout) } }
    }

    // ---- selection ----

    fun toggleSelected(id: Long) = selection.update { if (id in it) it - id else it + id }

    fun selectAll() {
        selection.value = state.value.items.map { it.doc.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // ---- adding / scanning ----

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val r = container.libraryManager.addFiles(uris)
            val msg = buildString {
                if (r.added > 0) append("Added ${r.added} ${if (r.added == 1) "book" else "books"}")
                if (r.alreadyPresent > 0) {
                    if (isNotEmpty()) append(". ")
                    append("${r.alreadyPresent} already in your library")
                }
                if (r.unsupported > 0) {
                    if (isNotEmpty()) append(". ")
                    append("${r.unsupported} unsupported ${if (r.unsupported == 1) "file was" else "files were"} skipped (Readle opens ${com.readle.app.domain.FormatSupport.supportedLabel()})")
                }
                if (isEmpty()) append("Nothing was added")
            }
            _events.tryEmit(LibraryEvent.Message(msg))
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val ok = container.libraryManager.addFolder(uri)
            if (!ok) {
                _events.tryEmit(
                    LibraryEvent.Message("Android wouldn't keep access to that folder after a restart, so you may need to add it again later.")
                )
            }
        }
    }

    fun rescan() = manager.rescanAll()

    fun cancelScan() = manager.cancelScan()

    fun dismissScan() = manager.dismissScanResult()

    /** The user opened the unavailable-files list: stop showing the banner until more files go missing. */
    fun markUnavailableReviewed(currentCount: Int) { UnavailableNotice.reviewedCount = currentCount }

    // ---- batch actions on the selection (or a single id) ----

    private fun targets(ids: Collection<Long>?) = ids ?: selection.value

    fun toggleFavorite(ids: Collection<Long>? = null) {
        val t = targets(ids)
        if (t.isEmpty()) return
        val items = state.value.items.filter { it.doc.id in t }
        val makeFavorite = items.any { !it.doc.isFavorite }
        viewModelScope.launch {
            container.libraryRepository.setFavorite(t, makeFavorite)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message(if (makeFavorite) "Added to favorites" else "Removed from favorites"))
        }
    }

    fun addToCollection(collectionId: Long, ids: Collection<Long>? = null) {
        val t = targets(ids)
        viewModelScope.launch {
            container.collectionRepository.addDocuments(collectionId, t)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message("Added to collection"))
        }
    }

    fun createCollectionAndAdd(name: String, ids: Collection<Long>? = null) {
        val t = targets(ids)
        viewModelScope.launch {
            val id = container.collectionRepository.create(name)
            if (id == null) {
                _events.tryEmit(LibraryEvent.Message("A collection with that name already exists"))
                return@launch
            }
            container.collectionRepository.addDocuments(id, t)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message("Created “${name.trim()}”"))
        }
    }

    fun removeFromCollection(ids: Collection<Long>? = null) {
        val c = scope as? LibraryScope.Collection ?: return
        val t = targets(ids)
        viewModelScope.launch {
            container.collectionRepository.removeDocuments(c.id, t)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message("Removed from collection (files are untouched)"))
        }
    }

    /** Forgets the books in Readle. Files on the device are not deleted. */
    fun removeFromLibrary(ids: Collection<Long>? = null) {
        val t = targets(ids).toList()
        viewModelScope.launch {
            container.libraryManager.removeFromLibrary(t)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message("Removed from your library. The files were not deleted."))
        }
    }

    fun removeFromHistory(ids: Collection<Long>? = null) {
        val t = targets(ids)
        viewModelScope.launch {
            container.libraryRepository.removeFromHistory(t)
            clearSelection()
            _events.tryEmit(LibraryEvent.Message("Removed from Recent. The files were not deleted."))
        }
    }

    fun showMessage(text: String) { _events.tryEmit(LibraryEvent.Message(text)) }

    fun relink(docId: Long, uri: Uri) {
        viewModelScope.launch {
            when (val r = container.libraryManager.relink(docId, uri)) {
                is com.readle.app.data.repo.FileOpResult.Success -> _events.tryEmit(LibraryEvent.Message("File relinked"))
                is com.readle.app.data.repo.FileOpResult.Failure -> _events.tryEmit(LibraryEvent.Message(r.reason))
            }
        }
    }

    class Factory(private val app: Application, private val scope: LibraryScope) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(app, scope) as T
    }
}

/** Remembers (for this run of the app) how many unavailable files the user has already looked at. */
object UnavailableNotice {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    var reviewedCount: Int
        get() = state.intValue
        set(value) { state.intValue = value }
}
