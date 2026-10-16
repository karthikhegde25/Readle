package com.readle.app.data.repo

import com.readle.app.data.db.AnnotationEntity
import com.readle.app.data.db.AnnotationWithDoc
import com.readle.app.data.db.BookReaderSettingsEntity
import com.readle.app.data.db.BookmarkEntity
import com.readle.app.data.db.BookmarkWithDoc
import com.readle.app.data.db.ReadingHistoryEntity
import com.readle.app.data.db.ReadingProgressEntity
import com.readle.app.data.db.ReadleDatabase
import com.readle.app.data.db.VocabularyEntity
import com.readle.app.data.prefs.SettingsStore
import com.readle.app.domain.BookmarkRules
import com.readle.app.domain.Locator
import com.readle.app.domain.MarkdownExport
import com.readle.app.domain.TextAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Reading position, bookmarks, annotations, per-book settings and vocabulary. */
class ReaderRepository(
    private val db: ReadleDatabase,
    private val settings: SettingsStore
) {
    // ---- progress & history ----

    suspend fun getProgress(documentId: Long): ReadingProgressEntity? = db.progress().get(documentId)

    suspend fun saveProgress(
        documentId: Long,
        locator: Locator,
        fraction: Float,
        currentPage: Int,
        totalPages: Int
    ) {
        db.progress().upsert(
            ReadingProgressEntity(
                documentId = documentId,
                positionType = locator.type,
                positionValue = locator.encode(),
                progressFraction = fraction.coerceIn(0f, 1f),
                currentPage = currentPage,
                totalPages = totalPages,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Marks a document as just opened (feeds the Recent list) unless the user turned history off. */
    suspend fun recordOpen(documentId: Long, snapshot: Locator?) {
        if (!settings.settings.first().keepHistory) return
        val now = System.currentTimeMillis()
        db.documents().setLastOpened(documentId, now)
        db.history().insert(ReadingHistoryEntity(documentId = documentId, openedAt = now, positionSnapshot = snapshot?.encode()))
    }

    // ---- bookmarks ----

    fun observeBookmarks(documentId: Long): Flow<List<BookmarkEntity>> = db.bookmarks().observeForDocument(documentId)

    fun observeAllBookmarks(): Flow<List<BookmarkWithDoc>> = db.bookmarks().observeAllWithDoc()

    fun observeBookmarkCount(documentId: Long): Flow<Int> = db.bookmarks().observeCountForDocument(documentId)

    /** @return false when an equivalent bookmark already exists. */
    suspend fun addBookmark(documentId: Long, locator: Locator, overallFraction: Float, label: String): Boolean {
        val existing = db.bookmarks().getForDocument(documentId)
        if (BookmarkRules.isDuplicate(existing, locator)) return false
        db.bookmarks().insert(
            BookmarkEntity(
                documentId = documentId,
                label = label.trim().ifEmpty { "Bookmark" },
                positionType = locator.type,
                positionValue = locator.encode(),
                progressFraction = overallFraction.coerceIn(0f, 1f),
                createdAt = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun renameBookmark(id: Long, label: String) {
        db.bookmarks().rename(id, label.trim().ifEmpty { "Bookmark" })
    }

    suspend fun deleteBookmark(id: Long) = db.bookmarks().delete(id)

    // ---- annotations ----

    fun observeAnnotations(documentId: Long): Flow<List<AnnotationEntity>> = db.annotations().observeForDocument(documentId)

    fun observeAllAnnotations(): Flow<List<AnnotationWithDoc>> = db.annotations().observeAllWithDoc()

    fun observeAnnotationCount(documentId: Long): Flow<Int> = db.annotations().observeCountForDocument(documentId)

    suspend fun addAnnotation(
        documentId: Long,
        type: String,
        selectedText: String,
        noteText: String?,
        color: Int,
        anchor: TextAnchor
    ): Long {
        val now = System.currentTimeMillis()
        return db.annotations().insert(
            AnnotationEntity(
                documentId = documentId,
                type = type,
                selectedText = selectedText.trim(),
                noteText = noteText?.trim()?.takeIf { it.isNotEmpty() },
                color = color,
                startLocation = anchor.encodeStart(),
                endLocation = anchor.encodeEnd(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateAnnotation(id: Long, type: String, noteText: String?, color: Int) {
        val a = db.annotations().getById(id) ?: return
        db.annotations().update(
            a.copy(
                type = type,
                noteText = noteText?.trim()?.takeIf { it.isNotEmpty() },
                color = color,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteAnnotation(id: Long) {
        val a = db.annotations().getById(id) ?: return
        db.annotations().delete(a)
    }

    // ---- per-book reader settings ----

    fun observeBookSettings(documentId: Long): Flow<BookReaderSettingsEntity?> = db.bookSettings().observe(documentId)

    suspend fun saveBookSettings(s: BookReaderSettingsEntity) = db.bookSettings().upsert(s)

    suspend fun clearBookSettings(documentId: Long) = db.bookSettings().delete(documentId)

    // ---- vocabulary ----

    fun observeVocabulary(): Flow<List<VocabularyEntity>> = db.vocabulary().observeAll()

    suspend fun addWord(word: String, definition: String?, sourceDocumentId: Long?, sourceLocation: Locator?) {
        val w = word.trim()
        if (w.isEmpty()) return
        db.vocabulary().insert(
            VocabularyEntity(
                word = w,
                definition = definition?.trim()?.takeIf { it.isNotEmpty() },
                sourceDocumentId = sourceDocumentId,
                sourceLocation = sourceLocation?.let { Locator.toRouteArg(it) },
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateWord(entry: VocabularyEntity) = db.vocabulary().update(entry)

    suspend fun deleteWord(id: Long) = db.vocabulary().delete(id)

    // ---- export ----

    suspend fun exportMarkdown(): String = MarkdownExport.build(
        docs = db.documents().getAll(),
        bookmarks = db.bookmarks().getAll(),
        annotations = db.annotations().getAll()
    )
}
