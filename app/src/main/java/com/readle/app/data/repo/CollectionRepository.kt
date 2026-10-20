package com.readle.app.data.repo

import com.readle.app.data.db.CollectionDocumentEntity
import com.readle.app.data.db.CollectionEntity
import com.readle.app.data.db.CollectionWithCount
import com.readle.app.data.db.ReadleDatabase
import kotlinx.coroutines.flow.Flow

/** User collections / reading lists. Removing a document from a collection never touches its file. */
class CollectionRepository(private val db: ReadleDatabase) {

    fun observeCollections(): Flow<List<CollectionWithCount>> = db.collections().observeWithCounts()

    fun observeCollection(id: Long): Flow<CollectionEntity?> = db.collections().observeById(id)

    fun observeCollectionIdsFor(documentId: Long): Flow<List<Long>> =
        db.collections().observeCollectionIdsForDocument(documentId)

    /** @return the new id, or null if the name is blank or already used (case-insensitive). */
    suspend fun create(name: String): Long? {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        val existing = db.collections().getAll()
        if (existing.any { it.name.equals(clean, ignoreCase = true) }) return null
        return db.collections().insert(
            CollectionEntity(
                name = clean,
                createdAt = System.currentTimeMillis(),
                sortOrder = db.collections().nextSortOrder()
            )
        )
    }

    /** @return false if the name is blank or collides with another collection. */
    suspend fun rename(id: Long, name: String): Boolean {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return false
        val others = db.collections().getAll().filter { it.id != id }
        if (others.any { it.name.equals(clean, ignoreCase = true) }) return false
        db.collections().rename(id, clean)
        return true
    }

    suspend fun delete(id: Long) = db.collections().delete(id)

    suspend fun addDocuments(collectionId: Long, documentIds: Collection<Long>) {
        val now = System.currentTimeMillis()
        for (docId in documentIds) {
            db.collections().addLink(CollectionDocumentEntity(collectionId, docId, now))
        }
    }

    suspend fun removeDocuments(collectionId: Long, documentIds: Collection<Long>) {
        for (docId in documentIds) db.collections().removeLink(collectionId, docId)
    }

    /** Moves a collection one slot up (-1) or down (+1) and rewrites all sort orders densely. */
    suspend fun move(id: Long, delta: Int) {
        val list = db.collections().getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        val item = list.removeAt(from)
        list.add(to, item)
        list.forEachIndexed { index, c -> db.collections().setSortOrder(c.id, index) }
    }
}
