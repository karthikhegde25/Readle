package com.readle.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A document the library knows about. [uri] is a SAF content URI, never assumed to be a path. */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["title"]),
        Index(value = ["author"]),
        Index(value = ["series"]),
        Index(value = ["lastOpenedAt"]),
        Index(value = ["fingerprint"]),
        Index(value = ["locationId"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val title: String,
    val author: String? = null,
    val series: String? = null,
    val language: String? = null,
    /** [com.readle.app.domain.DocFormat] name. */
    val format: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
    val addedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    val coverCacheKey: String? = null,
    val isFavorite: Boolean = false,
    /** PENDING, OK, FAILED, PASSWORD_PROTECTED or EDITED (user supplied values, never overwritten by rescans). */
    val metadataStatus: String = METADATA_PENDING,
    /** AVAILABLE or UNAVAILABLE. */
    val availabilityStatus: String = AVAILABLE,
    /** Cheap content identity (size + hash of the first 64 KB) used for duplicate detection. */
    val fingerprint: String? = null,
    /** Granted folder this document was found in, or null for individually picked files. */
    val locationId: Long? = null,
    /** Path relative to the granted folder, for display and filtering. */
    val folderPath: String? = null
) {
    companion object {
        const val METADATA_PENDING = "PENDING"
        const val METADATA_OK = "OK"
        const val METADATA_FAILED = "FAILED"
        const val METADATA_PASSWORD = "PASSWORD_PROTECTED"
        const val METADATA_EDITED = "EDITED"
        const val AVAILABLE = "AVAILABLE"
        const val UNAVAILABLE = "UNAVAILABLE"
    }
}

/** A folder tree the user granted through the system folder picker. */
@Entity(tableName = "locations", indices = [Index(value = ["treeUri"], unique = true)])
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long? = null
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadingProgressEntity(
    @PrimaryKey val documentId: Long,
    /** See [com.readle.app.domain.Locator]. */
    val positionType: String,
    val positionValue: String,
    val progressFraction: Float,
    val currentPage: Int,
    val totalPages: Int,
    val updatedAt: Long
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int
)

@Entity(
    tableName = "collection_documents",
    primaryKeys = ["collectionId", "documentId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class CollectionDocumentEntity(
    val collectionId: Long,
    val documentId: Long,
    val addedAt: Long
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val label: String,
    val positionType: String,
    val positionValue: String,
    val progressFraction: Float,
    val createdAt: Long
)

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** HIGHLIGHT, QUOTE or NOTE. */
    val type: String,
    val selectedText: String,
    val noteText: String? = null,
    /** ARGB color of the highlight. */
    val color: Int,
    val startLocation: String,
    val endLocation: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val HIGHLIGHT = "HIGHLIGHT"
        const val QUOTE = "QUOTE"
        const val NOTE = "NOTE"
    }
}

@Entity(
    tableName = "reading_history",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class ReadingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val openedAt: Long,
    val positionSnapshot: String?
)

/** Optional per-book overrides of the global reader preferences. Null columns follow the global value. */
@Entity(
    tableName = "book_reader_settings",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookReaderSettingsEntity(
    @PrimaryKey val documentId: Long,
    val theme: String? = null,
    val fontFamily: String? = null,
    val fontSizePercent: Int? = null,
    val lineSpacing: Float? = null,
    val paragraphSpacing: Float? = null,
    val marginDp: Int? = null,
    val textAlign: String? = null
)

@Entity(
    tableName = "vocabulary",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("sourceDocumentId")]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val definition: String? = null,
    val sourceDocumentId: Long? = null,
    val sourceLocation: String? = null,
    val createdAt: Long
)

// ---- Query result holders -------------------------------------------------------------

data class CollectionWithCount(
    @Embedded val collection: CollectionEntity,
    val docCount: Int
)

data class BookmarkWithDoc(
    @Embedded val bookmark: BookmarkEntity,
    val docTitle: String
)

data class AnnotationWithDoc(
    @Embedded val annotation: AnnotationEntity,
    val docTitle: String
)

data class CollectionLink(
    val collectionId: Long,
    val documentId: Long
)
