package com.readle.app.domain

import com.readle.app.data.db.AnnotationEntity
import com.readle.app.data.db.BookmarkEntity
import com.readle.app.data.db.DocumentEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds a plain Markdown backup of bookmarks, highlights, quotes and notes, grouped by book. */
object MarkdownExport {
    fun build(
        docs: List<DocumentEntity>,
        bookmarks: List<BookmarkEntity>,
        annotations: List<AnnotationEntity>,
        generatedAt: Long = System.currentTimeMillis()
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("# Readle annotations export\n\n")
        sb.append("Generated ").append(fmt.format(Date(generatedAt))).append(" UTC\n\n")

        val byDoc = docs.associateBy { it.id }
        val ids = (bookmarks.map { it.documentId } + annotations.map { it.documentId }).distinct()
            .filter { it in byDoc }
            .sortedBy { byDoc[it]?.title?.lowercase() }
        if (ids.isEmpty()) {
            sb.append("_Nothing to export yet._\n")
            return sb.toString()
        }
        for (id in ids) {
            val doc = byDoc.getValue(id)
            sb.append("## ").append(doc.title.ifBlank { doc.displayName }).append('\n')
            doc.author?.let { sb.append("*").append(it).append("*\n") }
            sb.append('\n')

            val bms = bookmarks.filter { it.documentId == id }.sortedBy { it.progressFraction }
            if (bms.isNotEmpty()) {
                sb.append("### Bookmarks\n\n")
                for (b in bms) {
                    sb.append("- ").append(b.label.ifBlank { "Bookmark" })
                        .append(" (").append((b.progressFraction * 100).toInt()).append("%)\n")
                }
                sb.append('\n')
            }
            val anns = annotations.filter { it.documentId == id }.sortedBy { it.createdAt }
            if (anns.isNotEmpty()) {
                sb.append("### Highlights, quotes and notes\n\n")
                for (a in anns) {
                    val label = when (a.type) {
                        AnnotationEntity.NOTE -> "Note"
                        AnnotationEntity.QUOTE -> "Quote"
                        else -> "Highlight"
                    }
                    sb.append("> ").append(a.selectedText.replace("\n", "\n> ")).append("\n\n")
                    sb.append("*").append(label).append("*")
                    if (!a.noteText.isNullOrBlank()) sb.append(": ").append(a.noteText)
                    sb.append("\n\n")
                }
            }
        }
        return sb.toString()
    }
}
