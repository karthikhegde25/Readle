package com.readle.app.data.repo

import com.readle.app.data.db.DocumentEntity
import com.readle.app.data.db.LocationEntity
import com.readle.app.data.db.ReadleDatabase
import com.readle.app.data.prefs.SettingsStore
import com.readle.app.domain.LibraryItem
import com.readle.app.domain.LibraryLogic
import com.readle.app.domain.MetadataNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

/** Read models and simple mutations for the library. File-level work lives in LibraryManager. */
class LibraryRepository(
    private val db: ReadleDatabase,
    private val settings: SettingsStore
) {
    /** Documents joined with progress, collection membership and duplicate flags. */
    fun observeItems(): Flow<List<LibraryItem>> = combine(
        db.documents().observeAll(),
        db.progress().observeAll(),
        db.collections().observeAllLinks(),
        settings.settings.map { it.detectDuplicates }.distinctUntilChanged()
    ) { docs, progress, links, detectDuplicates ->
        val progressById = progress.associateBy { it.documentId }
        val linksByDoc = links.groupBy({ it.documentId }, { it.collectionId })
        val dups = if (detectDuplicates) LibraryLogic.duplicateIds(docs) else emptySet()
        docs.map { d ->
            val p = progressById[d.id]
            LibraryItem(
                doc = d,
                progressFraction = p?.progressFraction ?: 0f,
                hasProgress = p != null,
                collectionIds = linksByDoc[d.id]?.toSet() ?: emptySet(),
                isDuplicate = d.id in dups
            )
        }
    }.flowOn(Dispatchers.Default)

    fun observeDocument(id: Long): Flow<DocumentEntity?> = db.documents().observeById(id)

    suspend fun getDocument(id: Long): DocumentEntity? = db.documents().getById(id)

    suspend fun getAnnotation(id: Long) = db.annotations().getById(id)

    fun observeAuthorGroups(): Flow<List<LibraryLogic.Group>> =
        db.documents().observeAll().map { LibraryLogic.authorGroups(it) }.flowOn(Dispatchers.Default)

    fun observeSeriesGroups(): Flow<List<LibraryLogic.Group>> =
        db.documents().observeAll().map { LibraryLogic.seriesGroups(it) }.flowOn(Dispatchers.Default)

    fun observeLocations(): Flow<List<LocationEntity>> = db.locations().observeAll()

    suspend fun setFavorite(ids: Collection<Long>, favorite: Boolean) {
        for (id in ids) db.documents().setFavorite(id, favorite)
    }

    /**
     * Metadata edits live in Readle's database only; the source file is never modified.
     * Edited documents keep their values across rescans.
     */
    suspend fun updateMetadata(id: Long, title: String, author: String?, series: String?) {
        val doc = db.documents().getById(id) ?: return
        db.documents().update(
            doc.copy(
                title = MetadataNormalizer.collapseWhitespace(title).ifEmpty { doc.title },
                author = MetadataNormalizer.cleanField(author),
                series = MetadataNormalizer.cleanField(series),
                metadataStatus = DocumentEntity.METADATA_EDITED
            )
        )
    }

    /** Removes a document from the Recent list. The file, progress and notes are untouched. */
    suspend fun removeFromHistory(ids: Collection<Long>) {
        for (id in ids) {
            db.documents().setLastOpened(id, null)
            db.history().deleteForDocument(id)
        }
    }

    suspend fun clearHistory() {
        db.documents().clearAllLastOpened()
        db.history().deleteAll()
    }
}
