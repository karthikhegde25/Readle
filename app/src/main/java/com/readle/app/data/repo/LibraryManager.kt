package com.readle.app.data.repo

import android.content.Context
import android.net.Uri
import com.readle.app.data.db.DocumentEntity
import com.readle.app.data.db.LocationEntity
import com.readle.app.data.db.ReadleDatabase
import com.readle.app.domain.DocFormat
import com.readle.app.domain.FormatSupport
import com.readle.app.domain.MetadataNormalizer
import com.readle.app.files.CoverCache
import com.readle.app.files.FileCache
import com.readle.app.files.FileInfo
import com.readle.app.files.MetadataExtractor
import com.readle.app.files.SafFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val phase: String, val found: Int, val processed: Int, val total: Int) : ScanState
    data class Done(val added: Int, val missing: Int, val unsupportedSkipped: Int) : ScanState
    data class Failed(val message: String) : ScanState
}

data class AddResult(val added: Int, val alreadyPresent: Int, val unsupported: Int, val firstDocId: Long?)

/** Result of a file operation, with a user-readable reason on failure. */
sealed interface FileOpResult {
    data object Success : FileOpResult
    data class Failure(val reason: String) : FileOpResult
}

/**
 * Coordinates everything that keeps the database in step with the files the user granted:
 * adding picked files, scanning granted folders, extracting metadata in the background,
 * marking vanished files as unavailable, relinking and file operations.
 *
 * Scans run in [scope] (not in a screen's scope) so leaving the Library screen does not abort them,
 * and are cancellable through [cancelScan].
 */
class LibraryManager(
    private val context: Context,
    private val db: ReadleDatabase,
    private val fileCache: FileCache,
    private val covers: CoverCache,
    private val extractor: MetadataExtractor
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
                // Background work (scans, metadata) must never crash the app; surface it as a failed scan instead.
                _scanState.value = ScanState.Failed("Something went wrong while reading your books. Try again.")
            }
    )
    private val scanMutex = Mutex()
    private var scanJob: Job? = null

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // ---- adding individual files ----

    /** Registers files picked through the system file picker. Unsupported files are counted, not added. */
    suspend fun addFiles(uris: List<Uri>): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        var present = 0
        var unsupported = 0
        var first: Long? = null
        for (uri in uris) {
            SafFiles.takePersistableRead(context, uri)
            val info = SafFiles.describe(context, uri)
            if (info == null) {
                unsupported++
                continue
            }
            val format = FormatSupport.detect(info.name, info.mime)
            if (format == null) {
                unsupported++
                continue
            }
            val existing = db.documents().getByUri(uri.toString())
            if (existing != null) {
                present++
                if (first == null) first = existing.id
                // Re-adding a file the app marked unavailable is how a user relinks it.
                if (existing.availabilityStatus != DocumentEntity.AVAILABLE) {
                    db.documents().setAvailability(existing.id, DocumentEntity.AVAILABLE)
                }
                continue
            }
            val id = insertDocument(info, format, locationId = null, folderPath = null)
            if (id > 0) {
                added++
                if (first == null) first = id
            }
        }
        if (added > 0) extractPendingInBackground()
        AddResult(added, present, unsupported, first)
    }

    private suspend fun insertDocument(info: FileInfo, format: DocFormat, locationId: Long?, folderPath: String?): Long =
        db.documents().insert(
            DocumentEntity(
                uri = info.uri.toString(),
                displayName = info.name,
                title = MetadataNormalizer.titleFromFileName(info.name),
                format = format.name,
                mimeType = info.mime,
                sizeBytes = info.size,
                lastModified = info.lastModified,
                addedAt = System.currentTimeMillis(),
                locationId = locationId,
                folderPath = folderPath
            )
        )

    // ---- folders ----

    /** Adds a granted folder (persisting the grant) and scans it. Returns false if the grant could not be kept. */
    suspend fun addFolder(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val persisted = SafFiles.takePersistableTree(context, treeUri)
        val existing = db.locations().getByTreeUri(treeUri.toString())
        if (existing == null) {
            db.locations().insert(
                LocationEntity(
                    treeUri = treeUri.toString(),
                    displayName = SafFiles.treeDisplayName(context, treeUri),
                    addedAt = System.currentTimeMillis()
                )
            )
        }
        rescanAll()
        persisted
    }

    /** Stops tracking a folder and forgets its documents. Files on the device are untouched. */
    suspend fun removeFolder(locationId: Long) = withContext(Dispatchers.IO) {
        val loc = db.locations().getAll().firstOrNull { it.id == locationId } ?: return@withContext
        db.documents().deleteByLocation(locationId)
        db.locations().deleteById(locationId)
        SafFiles.releasePersistable(context, Uri.parse(loc.treeUri))
    }

    // ---- scanning ----

    fun rescanAll() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { runScan() }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = ScanState.Idle
    }

    fun dismissScanResult() {
        if (_scanState.value is ScanState.Done || _scanState.value is ScanState.Failed) _scanState.value = ScanState.Idle
    }

    private suspend fun runScan() = scanMutex.withLock {
        try {
            _scanState.value = ScanState.Scanning("Looking for books", 0, 0, 0)
            var added = 0
            var skipped = 0
            var missing = 0
            val locations = db.locations().getAll()

            for (loc in locations) {
                val tree = Uri.parse(loc.treeUri)
                if (!SafFiles.hasPersistedPermission(context, tree)) {
                    // Access was revoked: keep the records but flag them so the UI can offer relinking.
                    db.documents().getByLocation(loc.id).forEach {
                        db.documents().setAvailability(it.id, DocumentEntity.UNAVAILABLE)
                    }
                    continue
                }
                val known = db.documents().getByLocation(loc.id).associateBy { it.uri }.toMutableMap()
                val seen = HashSet<String>()
                SafFiles.scanTree(context, tree) { scanned ->
                    val format = FormatSupport.detect(scanned.info.name, scanned.info.mime)
                    if (format == null) {
                        skipped++
                        return@scanTree
                    }
                    val key = scanned.info.uri.toString()
                    seen.add(key)
                    val existing = known[key] ?: db.documents().getByUri(key)
                    if (existing == null) {
                        if (insertDocument(scanned.info, format, loc.id, scanned.folderPath.ifEmpty { null }) > 0) added++
                    } else {
                        val changed = existing.sizeBytes != scanned.info.size || existing.lastModified != scanned.info.lastModified
                        db.documents().update(
                            existing.copy(
                                displayName = scanned.info.name,
                                sizeBytes = scanned.info.size,
                                lastModified = scanned.info.lastModified,
                                availabilityStatus = DocumentEntity.AVAILABLE,
                                locationId = loc.id,
                                folderPath = scanned.folderPath.ifEmpty { null },
                                // A modified file gets its metadata re-read, unless the user edited it by hand.
                                metadataStatus = if (changed && existing.metadataStatus != DocumentEntity.METADATA_EDITED)
                                    DocumentEntity.METADATA_PENDING else existing.metadataStatus
                            )
                        )
                    }
                    _scanState.update { s ->
                        if (s is ScanState.Scanning) s.copy(found = seen.size) else s
                    }
                }
                // Files that used to be in this folder but were not seen are moved/renamed/deleted.
                for ((uriStr, doc) in known) {
                    if (uriStr !in seen && doc.availabilityStatus == DocumentEntity.AVAILABLE) {
                        db.documents().setAvailability(doc.id, DocumentEntity.UNAVAILABLE)
                        missing++
                    }
                }
                db.locations().update(loc.copy(lastScanAt = System.currentTimeMillis()))
            }

            // Individually picked files: confirm they can still be opened.
            for (doc in db.documents().getAll().filter { it.locationId == null }) {
                val ok = SafFiles.isAccessible(context, Uri.parse(doc.uri))
                val status = if (ok) DocumentEntity.AVAILABLE else DocumentEntity.UNAVAILABLE
                if (doc.availabilityStatus != status) db.documents().setAvailability(doc.id, status)
                if (!ok) missing++
            }

            extractPending()
            _scanState.value = ScanState.Done(added, missing, skipped)
        } catch (e: CancellationException) {
            _scanState.value = ScanState.Idle
            throw e
        } catch (e: Exception) {
            _scanState.value = ScanState.Failed("The scan stopped unexpectedly. Try again.")
        }
    }

    private fun extractPendingInBackground() {
        scope.launch {
            scanMutex.withLock {
                try {
                    extractPending()
                } finally {
                    if (_scanState.value is ScanState.Scanning) _scanState.value = ScanState.Idle
                }
            }
        }
    }

    /** Reads metadata + covers for every document that still needs it, a few at a time, cancellable. */
    private suspend fun extractPending() {
        val pending = db.documents().getPendingMetadata()
        if (pending.isEmpty()) return
        pending.forEachIndexed { index, doc ->
            _scanState.value = ScanState.Scanning("Reading book details", pending.size, index, pending.size)
            val fresh = db.documents().getById(doc.id) ?: return@forEachIndexed
            if (fresh.metadataStatus != DocumentEntity.METADATA_PENDING) return@forEachIndexed
            val meta = extractor.extract(fresh)
            // Re-read before writing so a user edit made meanwhile is not overwritten.
            val latest = db.documents().getById(doc.id) ?: return@forEachIndexed
            if (latest.metadataStatus == DocumentEntity.METADATA_EDITED) return@forEachIndexed
            db.documents().update(
                latest.copy(
                    title = MetadataNormalizer.cleanTitle(meta.title, latest.displayName),
                    author = meta.author,
                    series = meta.series,
                    language = meta.language,
                    metadataStatus = meta.status,
                    fingerprint = meta.fingerprint ?: latest.fingerprint,
                    coverCacheKey = meta.coverKey
                )
            )
        }
    }

    // ---- availability / relinking ----

    /** Points an existing record at a newly chosen file, keeping its progress, bookmarks and notes. */
    suspend fun relink(documentId: Long, newUri: Uri): FileOpResult = withContext(Dispatchers.IO) {
        val doc = db.documents().getById(documentId) ?: return@withContext FileOpResult.Failure("That book is no longer in your library")
        SafFiles.takePersistableRead(context, newUri)
        val info = SafFiles.describe(context, newUri) ?: return@withContext FileOpResult.Failure("Readle can't read that file")
        val format = FormatSupport.detect(info.name, info.mime)
            ?: return@withContext FileOpResult.Failure("That file type isn't supported")
        if (format.name != doc.format) return@withContext FileOpResult.Failure("Choose a ${DocFormat.fromStored(doc.format)?.label ?: "matching"} file")
        val clash = db.documents().getByUri(newUri.toString())
        if (clash != null && clash.id != documentId) return@withContext FileOpResult.Failure("That file is already in your library")
        db.documents().update(
            doc.copy(
                uri = newUri.toString(),
                displayName = info.name,
                sizeBytes = info.size,
                lastModified = info.lastModified,
                availabilityStatus = DocumentEntity.AVAILABLE,
                locationId = null,
                folderPath = null,
                metadataStatus = if (doc.metadataStatus == DocumentEntity.METADATA_EDITED) doc.metadataStatus else DocumentEntity.METADATA_PENDING
            )
        )
        extractPendingInBackground()
        FileOpResult.Success
    }

    suspend fun refreshAvailability(documentId: Long): Boolean = withContext(Dispatchers.IO) {
        val doc = db.documents().getById(documentId) ?: return@withContext false
        val ok = SafFiles.isAccessible(context, Uri.parse(doc.uri))
        db.documents().setAvailability(documentId, if (ok) DocumentEntity.AVAILABLE else DocumentEntity.UNAVAILABLE)
        ok
    }

    // ---- removing & destructive operations ----

    /** Removes records from Readle only. Files on the device are not touched. */
    suspend fun removeFromLibrary(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val doc = db.documents().getById(id) ?: continue
            db.documents().deleteById(id)
            doc.coverCacheKey?.let { /* thumbnails are evicted by the bounded cache */ }
            if (doc.locationId == null) SafFiles.releasePersistable(context, Uri.parse(doc.uri))
        }
    }

    /** A file Android has not yet let Readle delete: the user must grant write access to it first. */
    data class PendingDelete(val id: Long, val name: String, val uri: String)

    /** A file the provider can't delete directly but Android can delete after the user confirms a system dialog. */
    data class SystemDelete(val id: Long, val name: String, val mediaUri: String)

    data class DeleteOutcome(
        val deleted: Int,
        val needAccess: List<PendingDelete>,
        val failures: List<String>,
        val systemConfirm: List<SystemDelete> = emptyList()
    )

    /** Forgets library entries whose files were deleted outside [deleteFiles] (the system confirmation path). */
    suspend fun forgetDeleted(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val doc = db.documents().getById(id) ?: continue
            db.documents().deleteById(id)
            if (doc.locationId == null) SafFiles.releasePersistable(context, Uri.parse(doc.uri))
        }
    }

    /** Deletes the real file through the document provider, then forgets it. The caller must have confirmed. */
    suspend fun deleteFiles(ids: Collection<Long>): DeleteOutcome = withContext(Dispatchers.IO) {
        var deleted = 0
        val need = ArrayList<PendingDelete>()
        val failures = ArrayList<String>()
        val system = ArrayList<SystemDelete>()
        for (id in ids) {
            val doc = db.documents().getById(id) ?: continue
            val result = SafFiles.delete(context, Uri.parse(doc.uri))
            if (result.isSuccess) {
                db.documents().deleteById(id)
                deleted++
            } else if (SafFiles.needsWriteAccess(result.exceptionOrNull())) {
                need.add(PendingDelete(id, doc.displayName, doc.uri))
            } else {
                val media = SafFiles.mediaStoreUri(context, Uri.parse(doc.uri))
                if (media != null) system.add(SystemDelete(id, doc.displayName, media.toString()))
                else failures.add("${doc.displayName}: ${result.exceptionOrNull()?.message ?: "could not be deleted"}")
            }
        }
        DeleteOutcome(deleted, need, failures, system)
    }

    suspend fun renameFile(documentId: Long, newBaseName: String): FileOpResult = withContext(Dispatchers.IO) {
        val doc = db.documents().getById(documentId) ?: return@withContext FileOpResult.Failure("That book is no longer in your library")
        val clean = newBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return@withContext FileOpResult.Failure("Enter a file name")
        val ext = doc.displayName.substringAfterLast('.', "")
        val newName = if (ext.isEmpty()) clean else "$clean.$ext"
        val result = SafFiles.rename(context, Uri.parse(doc.uri), newName)
        result.fold(
            onSuccess = { newUri ->
                db.documents().update(
                    doc.copy(
                        uri = newUri.toString(),
                        displayName = SafFiles.describe(context, newUri)?.name ?: newName
                    )
                )
                FileOpResult.Success
            },
            onFailure = { FileOpResult.Failure(it.message ?: "This location does not allow renaming") }
        )
    }

    // ---- local data management ----

    fun clearCaches() {
        fileCache.clear()
        covers.clear()
    }

    suspend fun deleteAllLocalData() = withContext(Dispatchers.IO) {
        cancelScan()
        db.locations().getAll().forEach { SafFiles.releasePersistable(context, Uri.parse(it.treeUri)) }
        db.documents().getAll().filter { it.locationId == null }
            .forEach { SafFiles.releasePersistable(context, Uri.parse(it.uri)) }
        // Order matters: documents cascade to progress, bookmarks, annotations, history and links.
        db.documents().deleteAll()
        db.locations().deleteAll()
        db.collections().deleteAll()
        db.vocabulary().deleteAll()
        clearCaches()
    }
}
