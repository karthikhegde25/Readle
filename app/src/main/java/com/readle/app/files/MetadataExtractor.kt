package com.readle.app.files

import android.content.Context
import com.readle.app.data.db.DocumentEntity
import com.readle.app.domain.DocFormat
import com.readle.app.domain.Fingerprint
import com.readle.app.domain.MetadataNormalizer
import com.readle.app.reader.DocumentOpenException
import com.readle.app.reader.OpenFailure
import com.readle.app.reader.pdf.PdfDocumentHandle
import com.readle.app.reader.reflow.BookOpenException
import com.readle.app.reader.reflow.BookOpener
import com.readle.app.reader.reflow.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri

/** Result of reading one document's embedded metadata, cover and content fingerprint. */
data class ExtractedMetadata(
    val title: String?,
    val author: String?,
    val series: String?,
    val language: String?,
    val status: String,
    val fingerprint: String?,
    val coverKey: String?
)

/**
 * Reads titles, authors, series and covers off the main thread.
 *  - EPUB, MOBI, FB2, DOCX, RTF, HTML, Markdown, CBZ: by briefly opening the book (via a short-lived local copy).
 *  - PDF: first page rendered as the cover; PDFs expose no portable title/author through PdfRenderer,
 *    so the title comes from the file name and author stays empty rather than being guessed.
 *  - TXT: title from the file name.
 */
class MetadataExtractor(
    private val context: Context,
    private val fileCache: FileCache,
    private val covers: CoverCache
) {
    suspend fun extract(doc: DocumentEntity): ExtractedMetadata {
        val format = DocFormat.fromStored(doc.format)
        val uri = Uri.parse(doc.uri)
        val fingerprint = fingerprintOf(uri, doc.sizeBytes)
        val coverKey = covers.keyFor(doc.id, doc.lastModified)
        return try {
            when (format) {
                DocFormat.PDF -> extractPdf(uri, fingerprint, coverKey)
                DocFormat.TXT -> ExtractedMetadata(null, null, null, null, DocumentEntity.METADATA_OK, fingerprint, null)
                null -> ExtractedMetadata(null, null, null, null, DocumentEntity.METADATA_FAILED, fingerprint, null)
                else -> extractReflow(format, doc, uri, fingerprint, coverKey)
            }
        } catch (e: DocumentOpenException) {
            val status = if (e.failure == OpenFailure.PASSWORD) DocumentEntity.METADATA_PASSWORD else DocumentEntity.METADATA_FAILED
            ExtractedMetadata(null, null, null, null, status, fingerprint, null)
        } catch (e: BookOpenException) {
            ExtractedMetadata(null, null, null, null, DocumentEntity.METADATA_FAILED, fingerprint, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes OutOfMemoryError and native renderer failures: a bad file must never take the app down.
            ExtractedMetadata(null, null, null, null, DocumentEntity.METADATA_FAILED, fingerprint, null)
        }
    }

    /** EPUB, MOBI, FB2, DOCX, RTF, HTML, Markdown and CBZ: open the book briefly and read its metadata and cover. */
    private suspend fun extractReflow(format: DocFormat, doc: DocumentEntity, uri: Uri, fingerprint: String?, coverKey: String): ExtractedMetadata =
        fileCache.withTempCopy(uri, format.ext) { file ->
            withContext(Dispatchers.IO) {
                BookOpener.open(format, file, null).use { book ->
                    var storedCover: String? = null
                    book.meta.coverPath?.let { path ->
                        val bytes = book.open(path)?.stream?.use { it.readBounded(12 * 1024 * 1024) }
                        val bmp = bytes?.let { CoverCache.decodeSampled(it) }
                        if (bmp != null) {
                            covers.save(coverKey, bmp)
                            storedCover = coverKey
                        }
                    }
                    ExtractedMetadata(
                        title = MetadataNormalizer.cleanField(book.meta.title),
                        author = MetadataNormalizer.cleanField(book.meta.author),
                        series = MetadataNormalizer.cleanField(book.meta.series),
                        language = MetadataNormalizer.cleanLanguage(book.meta.language),
                        status = DocumentEntity.METADATA_OK,
                        fingerprint = fingerprint,
                        coverKey = storedCover
                    )
                }
            }
        }

    private suspend fun extractPdf(uri: Uri, fingerprint: String?, coverKey: String): ExtractedMetadata {
        val handle = PdfDocumentHandle.open(context, uri) { fileCache.materializeTemp(uri, "pdf") }
        handle.use { h ->
            val bmp = h.render(0, CoverCache.THUMB_WIDTH)
            // Do NOT recycle: the cache may keep this very bitmap in memory (it is already thumbnail-sized),
            // and drawing a recycled bitmap crashes the library screen. The GC reclaims it.
            covers.save(coverKey, bmp)
        }
        return ExtractedMetadata(null, null, null, null, DocumentEntity.METADATA_OK, fingerprint, coverKey)
    }

    private suspend fun fingerprintOf(uri: Uri, size: Long): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(Fingerprint.HEAD_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                Fingerprint.of(size, buf, read)
            }
        } catch (e: Exception) {
            null
        }
    }
}
