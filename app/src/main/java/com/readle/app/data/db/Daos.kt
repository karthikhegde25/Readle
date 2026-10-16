package com.readle.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE metadataStatus = 'PENDING' AND availabilityStatus = 'AVAILABLE'")
    suspend fun getPendingMetadata(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE locationId = :locationId")
    suspend fun getByLocation(locationId: Long): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("UPDATE documents SET availabilityStatus = :status WHERE id = :id")
    suspend fun setAvailability(id: Long, status: String)

    @Query("UPDATE documents SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE documents SET lastOpenedAt = :time WHERE id = :id")
    suspend fun setLastOpened(id: Long, time: Long?)

    @Query("UPDATE documents SET lastOpenedAt = NULL")
    suspend fun clearAllLastOpened()

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM documents WHERE locationId = :locationId")
    suspend fun deleteByLocation(locationId: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY addedAt")
    fun observeAll(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations ORDER BY addedAt")
    suspend fun getAll(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE treeUri = :treeUri LIMIT 1")
    suspend fun getByTreeUri(treeUri: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(location: LocationEntity): Long

    @Update
    suspend fun update(location: LocationEntity)

    @Query("DELETE FROM locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM locations")
    suspend fun deleteAll()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun get(documentId: Long): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress")
    suspend fun deleteAll()
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: ReadingHistoryEntity)

    @Query("DELETE FROM reading_history WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("DELETE FROM reading_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM reading_history")
    suspend fun count(): Int
}

@Dao
interface CollectionDao {
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM collection_documents cd WHERE cd.collectionId = c.id) AS docCount " +
            "FROM collections c ORDER BY c.sortOrder, c.name COLLATE NOCASE"
    )
    fun observeWithCounts(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeById(id: Long): Flow<CollectionEntity?>

    @Query("SELECT collectionId, documentId FROM collection_documents")
    fun observeAllLinks(): Flow<List<CollectionLink>>

    @Query("SELECT collectionId FROM collection_documents WHERE documentId = :documentId")
    fun observeCollectionIdsForDocument(documentId: Long): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM collections")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE collections SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addLink(link: CollectionDocumentEntity)

    @Query("DELETE FROM collection_documents WHERE collectionId = :collectionId AND documentId = :documentId")
    suspend fun removeLink(collectionId: Long, documentId: Long)

    @Query("DELETE FROM collections")
    suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY progressFraction, createdAt")
    fun observeForDocument(documentId: Long): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT b.*, d.title AS docTitle FROM bookmarks b JOIN documents d ON d.id = b.documentId " +
            "ORDER BY b.createdAt DESC"
    )
    fun observeAllWithDoc(): Flow<List<BookmarkWithDoc>>

    @Query("SELECT * FROM bookmarks")
    suspend fun getAll(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId")
    suspend fun getForDocument(documentId: Long): List<BookmarkEntity>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE documentId = :documentId")
    fun observeCountForDocument(documentId: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM bookmarks WHERE documentId = :documentId AND positionType = :type " +
            "AND positionValue = :value"
    )
    suspend fun countAt(documentId: Long, type: String, value: String): Int

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY createdAt")
    fun observeForDocument(documentId: Long): Flow<List<AnnotationEntity>>

    @Query(
        "SELECT a.*, d.title AS docTitle FROM annotations a JOIN documents d ON d.id = a.documentId " +
            "ORDER BY a.createdAt DESC"
    )
    fun observeAllWithDoc(): Flow<List<AnnotationWithDoc>>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun getById(id: Long): AnnotationEntity?

    @Query("SELECT * FROM annotations")
    suspend fun getAll(): List<AnnotationEntity>

    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :documentId")
    fun observeCountForDocument(documentId: Long): Flow<Int>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity)

    @Delete
    suspend fun delete(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations")
    suspend fun deleteAll()
}

@Dao
interface BookSettingsDao {
    @Query("SELECT * FROM book_reader_settings WHERE documentId = :documentId")
    fun observe(documentId: Long): Flow<BookReaderSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: BookReaderSettingsEntity)

    @Query("DELETE FROM book_reader_settings WHERE documentId = :documentId")
    suspend fun delete(documentId: Long)
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Insert
    suspend fun insert(entry: VocabularyEntity): Long

    @Update
    suspend fun update(entry: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vocabulary")
    suspend fun deleteAll()
}
