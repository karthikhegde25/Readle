package com.readle.app.domain

/** Pure helpers that clean up metadata before it reaches the database. */
object MetadataNormalizer {
    private val PLACEHOLDERS = setOf(
        "unknown", "unknown author", "n/a", "na", "none", "null", "untitled", "anonymous", "-", "--"
    )
    private val WHITESPACE = Regex("\\s+")

    /** Turns "my_great-book.v2.epub" into "my great-book v2". */
    fun titleFromFileName(fileName: String): String {
        val withoutExt = run {
            val dot = fileName.lastIndexOf('.')
            val ext = if (dot > 0) fileName.substring(dot + 1) else ""
            if (dot > 0 && ext.length in 1..8 && DocFormat.entries.any { f -> f.allExts.any { it.equals(ext, true) } }) {
                fileName.substring(0, dot)
            } else fileName
        }
        val cleaned = withoutExt.replace('_', ' ').replace('.', ' ')
        val collapsed = collapseWhitespace(cleaned)
        return collapsed.ifEmpty { fileName.trim().ifEmpty { "Untitled" } }
    }

    fun collapseWhitespace(value: String): String = WHITESPACE.replace(value.trim(), " ")

    /** Returns null for blank or placeholder values so the UI can show an honest "Unknown author". */
    fun cleanField(raw: String?): String? {
        if (raw == null) return null
        val v = collapseWhitespace(raw)
        if (v.isEmpty()) return null
        if (v.lowercase() in PLACEHOLDERS) return null
        return v
    }

    fun cleanTitle(raw: String?, fallbackFileName: String): String =
        cleanField(raw) ?: titleFromFileName(fallbackFileName)

    /** Language tags such as "en-US" or "EN" become lower-case primary subtags ("en"). */
    fun cleanLanguage(raw: String?): String? {
        val v = cleanField(raw) ?: return null
        return v.substringBefore('-').substringBefore('_').lowercase().takeIf { it.length in 2..3 }
    }

    /** Case-insensitive key used when grouping by author or series. */
    fun groupKey(value: String?): String = value?.let { collapseWhitespace(it).lowercase() } ?: ""
}
