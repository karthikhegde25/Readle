package com.readle.app.files

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FileInfo(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val size: Long,
    val lastModified: Long
)

data class TreeEntry(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mime: String?,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/** A document discovered while walking a granted folder. */
data class ScannedFile(
    val info: FileInfo,
    /** Path relative to the granted folder root ("" for the root itself). */
    val folderPath: String
)

/**
 * Everything that touches the Storage Access Framework lives here, so the rest of the app only deals
 * with content URIs and never assumes a filesystem path or blanket storage permission.
 */
object SafFiles {

    /** Reads name/size/modified for a single document URI. Returns null if it cannot be queried. */
    fun describe(context: Context, uri: Uri): FileInfo? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                FileInfo(
                    uri = uri,
                    name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else (uri.lastPathSegment ?: "document"),
                    mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else context.contentResolver.getType(uri),
                    size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L,
                    lastModified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True if the URI can still be opened (permission not revoked, file not deleted or moved). */
    fun isAccessible(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Persists read access across restarts where the provider allows it. Failure is not fatal. */
    fun takePersistableRead(context: Context, uri: Uri): Boolean = takePersistableReadWrite0(context, uri)

    private fun takePersistableReadWrite0(context: Context, uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(uri, rw)
            true
        } catch (e: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** Folder grants are requested read+write so rename and delete work inside them when the provider allows. */
    fun takePersistableTree(context: Context, treeUri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(treeUri, rw)
            true
        } catch (e: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun releasePersistable(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    fun hasPersistedPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    // ---- folder trees ----

    fun treeRootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun treeDisplayName(context: Context, treeUri: Uri): String {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeRootDocumentId(treeUri))
        return describe(context, rootUri)?.name
            ?: treeRootDocumentId(treeUri).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }
    }

    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String): List<TreeEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<TreeEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val mime = c.getString(2)
                out.add(
                    TreeEntry(
                        documentId = id,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name = c.getString(1) ?: id.substringAfterLast('/'),
                        mime = mime,
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return out
    }

    /**
     * Depth-first walk of a granted folder, reporting every file through [onFile].
     * Cooperative: checks for coroutine cancellation between entries so a scan can be stopped.
     * Returns the number of directories visited.
     */
    suspend fun scanTree(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 24,
        onFile: suspend (ScannedFile) -> Unit
    ): Int {
        var dirs = 0
        data class Frame(val docId: String, val path: String, val depth: Int)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(treeRootDocumentId(treeUri), "", 0))
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frame = stack.removeLast()
            val children = try {
                listChildren(context, treeUri, frame.docId)
            } catch (e: Exception) {
                emptyList() // an unreadable subfolder must not abort the whole scan
            }
            dirs++
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (child.isDirectory) {
                    if (frame.depth < maxDepth) {
                        val childPath = if (frame.path.isEmpty()) child.name else "${frame.path}/${child.name}"
                        stack.addLast(Frame(child.documentId, childPath, frame.depth + 1))
                    }
                } else {
                    onFile(
                        ScannedFile(
                            FileInfo(child.uri, child.name, child.mime, child.size, child.lastModified),
                            frame.path
                        )
                    )
                }
            }
        }
        return dirs
    }

    // ---- file operations (all return Result so the UI can explain failures) ----

    fun rename(context: Context, uri: Uri, newName: String): Result<Uri> = runCatching {
        DocumentsContract.renameDocument(context.contentResolver, uri, newName)
            ?: throw UnsupportedOperationException("This location does not allow renaming")
    }

    /**
     * Tries the document provider first, then a plain content delete for providers that don't implement
     * "deleteDocument" (those throw "Unsupported call"). If both are unsupported the caller can still try
     * [mediaStoreUri] and a system confirmation.
     */
    fun delete(context: Context, uri: Uri): Result<Unit> {
        val first = runCatching {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) throw UnsupportedOperationException(NO_DELETE)
        }
        if (first.isSuccess || needsWriteAccess(first.exceptionOrNull())) return first
        val second = runCatching {
            if (context.contentResolver.delete(uri, null, null) < 1) throw UnsupportedOperationException(NO_DELETE)
        }
        if (second.isSuccess) return second
        return Result.failure(UnsupportedOperationException(NO_DELETE))
    }

    private const val NO_DELETE = "This storage location doesn't let apps delete files. Delete it from your Files app instead."

    /**
     * The MediaStore row behind a picked file, when there is one. Android 11+ can delete such a file after the
     * user confirms a system dialog, without any extra permission.
     */
    fun mediaStoreUri(context: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < 30) return null
        val found = runCatching {
            val auth = uri.authority
            when {
                auth == "media" -> uri
                auth == "com.android.providers.media.documents" || auth == "com.android.providers.downloads.documents" ->
                    // "msf:123" / "document:123" / "image:123": the number is the MediaStore row id.
                    DocumentsContract.getDocumentId(uri).substringAfter(':').toLongOrNull()
                        ?.let { ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it) }
                Build.VERSION.SDK_INT >= 31 -> MediaStore.getMediaUri(context, uri)
                else -> null
            }
        }.getOrNull()
        // The system dialog only accepts one specific row (…/123). Anything else would be rejected with
        // "All requested items must be referenced by specific ID", so treat it as not deletable here.
        return found?.takeIf { runCatching { ContentUris.parseId(it) }.getOrDefault(-1L) > 0L }
    }

    /** True when the failure means Android has not given Readle write access to this file (yet). */
    fun needsWriteAccess(e: Throwable?): Boolean =
        e is SecurityException || (e?.message?.contains("permission", ignoreCase = true) == true && e !is UnsupportedOperationException)

    /** Persists read and write access (write is what deleting needs). Falls back to read-only. */
    fun takePersistableReadWrite(context: Context, uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(uri, rw)
            true
        } catch (e: Exception) {
            takePersistableRead(context, uri)
            false
        }
    }

    fun openInputStream(resolver: ContentResolver, uri: Uri) = resolver.openInputStream(uri)
}
