package com.readle.app.domain

/**
 * Formats Readle can really open. Each entry is backed by an implemented reader:
 *  - PDF: Android's built-in PdfRenderer (fixed layout)
 *  - everything else: converted to HTML chapters by Readle's own parsers and shown by the shared
 *    reflow engine (themes, fonts, progress, bookmarks, search, TTS)
 * Anything not listed is reported as unsupported rather than shown as a half-working format.
 */
enum class DocFormat(
    val ext: String,
    val label: String,
    val mimeType: String,
    val reflowable: Boolean,
    /** Other file extensions that mean the same format. */
    val aliasExts: List<String> = emptyList(),
    /** Other MIME types providers use for this format. */
    val aliasMimes: List<String> = emptyList(),
    /** True for formats whose text can be selected, highlighted and searched. */
    val hasText: Boolean = true
) {
    PDF("pdf", "PDF", "application/pdf", false, hasText = false),
    EPUB("epub", "EPUB", "application/epub+zip", true),
    TXT("txt", "TXT", "text/plain", true),
    MOBI(
        "mobi", "MOBI", "application/x-mobipocket-ebook", true,
        aliasExts = listOf("azw", "azw3", "prc"),
        aliasMimes = listOf("application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook", "application/x-mobi8-ebook")
    ),
    FB2("fb2", "FB2", "application/x-fictionbook+xml", true, aliasMimes = listOf("application/x-fictionbook")),
    DOCX("docx", "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true),
    RTF("rtf", "RTF", "application/rtf", true, aliasMimes = listOf("text/rtf")),
    HTML("html", "HTML", "text/html", true, aliasExts = listOf("htm", "xhtml"), aliasMimes = listOf("application/xhtml+xml")),
    MD("md", "Markdown", "text/markdown", true, aliasExts = listOf("markdown"), aliasMimes = listOf("text/x-markdown")),
    CBZ("cbz", "CBZ", "application/vnd.comicbook+zip", true, aliasMimes = listOf("application/x-cbz", "application/x-cbr"), hasText = false);

    val allExts: List<String> get() = listOf(ext) + aliasExts
    val allMimes: List<String> get() = listOf(mimeType) + aliasMimes

    companion object {
        fun fromStored(name: String?): DocFormat? = entries.firstOrNull { it.name == name }
    }
}

object FormatSupport {
    /** MIME types offered to the system picker. octet-stream is included because providers label many ebook files that way. */
    val pickerMimeTypes: Array<String> =
        (DocFormat.entries.flatMap { it.allMimes } + "application/octet-stream" + "text/xml" + "application/zip").distinct().toTypedArray()

    fun fromFileName(name: String?): DocFormat? {
        if (name.isNullOrBlank()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return DocFormat.entries.firstOrNull { ext in it.allExts }
    }

    fun fromMime(mime: String?): DocFormat? {
        if (mime.isNullOrBlank()) return null
        return DocFormat.entries.firstOrNull { f -> f.allMimes.any { it.equals(mime, ignoreCase = true) } }
    }

    /** The file name wins because providers frequently report generic MIME types. */
    fun detect(name: String?, mime: String?): DocFormat? = fromFileName(name) ?: fromMime(mime)

    fun isSupportedName(name: String): Boolean = fromFileName(name) != null

    fun supportedLabel(): String = DocFormat.entries.joinToString(", ") { it.label }
}
