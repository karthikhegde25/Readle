package com.readle.app.domain

import com.readle.app.data.db.DocumentEntity

/** A library row: the document plus the derived data the list needs. */
data class LibraryItem(
    val doc: DocumentEntity,
    val progressFraction: Float = 0f,
    val hasProgress: Boolean = false,
    val collectionIds: Set<Long> = emptySet(),
    val isDuplicate: Boolean = false
) {
    val status: ReadStatus get() = LibraryLogic.statusOf(hasProgress, progressFraction)
    val format: DocFormat? get() = DocFormat.fromStored(doc.format)
    val isAvailable: Boolean get() = doc.availabilityStatus == DocumentEntity.AVAILABLE
}

/** Filters are combined with AND; an empty/null filter means "no restriction". */
data class LibraryQuery(
    val text: String = "",
    val sortKey: SortKey = SortKey.TITLE,
    val ascending: Boolean = true,
    val formats: Set<DocFormat> = emptySet(),
    val author: String? = null,
    val series: String? = null,
    val collectionId: Long? = null,
    val status: ReadStatus? = null,
    val favoritesOnly: Boolean = false,
    val duplicatesOnly: Boolean = false,
    val recentOnly: Boolean = false
) {
    val hasActiveFilters: Boolean
        get() = text.isNotBlank() || formats.isNotEmpty() || status != null || favoritesOnly || duplicatesOnly
}

object LibraryLogic {
    const val FINISHED_THRESHOLD = 0.98f

    fun statusOf(hasProgress: Boolean, fraction: Float): ReadStatus = when {
        !hasProgress || fraction <= 0.001f -> ReadStatus.UNREAD
        fraction >= FINISHED_THRESHOLD -> ReadStatus.FINISHED
        else -> ReadStatus.READING
    }

    /** IDs of documents that share a fingerprint with at least one other document. */
    fun duplicateIds(docs: List<DocumentEntity>): Set<Long> =
        docs.filter { !it.fingerprint.isNullOrEmpty() }
            .groupBy { it.fingerprint }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.map { it.id } }
            .toSet()

    fun matchesText(item: LibraryItem, text: String): Boolean {
        val tokens = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(item.doc.title).append(' ')
            append(item.doc.displayName).append(' ')
            item.doc.author?.let { append(it).append(' ') }
            item.doc.series?.let { append(it).append(' ') }
            append(item.doc.format).append(' ')
            item.doc.language?.let { append(it) }
        }.lowercase()
        return tokens.all { haystack.contains(it) }
    }

    fun filter(items: List<LibraryItem>, q: LibraryQuery): List<LibraryItem> = items.filter { item ->
        (q.formats.isEmpty() || item.format in q.formats) &&
            (q.author == null || authorMatches(item.doc.author, q.author)) &&
            (q.series == null || MetadataNormalizer.groupKey(item.doc.series) == MetadataNormalizer.groupKey(q.series)) &&
            (q.collectionId == null || q.collectionId in item.collectionIds) &&
            (q.status == null || item.status == q.status) &&
            (!q.favoritesOnly || item.doc.isFavorite) &&
            (!q.duplicatesOnly || item.isDuplicate) &&
            (!q.recentOnly || item.doc.lastOpenedAt != null) &&
            matchesText(item, q.text)
    }

    /** The "Unknown author" group is addressed with an empty string. */
    fun authorMatches(docAuthor: String?, wanted: String): Boolean =
        if (wanted.isEmpty()) docAuthor.isNullOrBlank()
        else MetadataNormalizer.groupKey(docAuthor) == MetadataNormalizer.groupKey(wanted)

    private val ci = String.CASE_INSENSITIVE_ORDER

    /** Deterministic: every comparator ends with a stable tie-break on name then id. */
    fun comparator(key: SortKey, ascending: Boolean): Comparator<LibraryItem> {
        val primary: Comparator<LibraryItem> = when (key) {
            SortKey.TITLE -> compareBy<LibraryItem, String>(ci) { it.doc.title }
            SortKey.FILENAME -> compareBy<LibraryItem, String>(ci) { it.doc.displayName }
            SortKey.AUTHOR -> Comparator { a, b -> compareNullable(a.doc.author, b.doc.author, ascending) }
            SortKey.RECENTLY_OPENED -> Comparator { a, b ->
                // Never-opened documents always sort last, regardless of direction.
                val x = a.doc.lastOpenedAt
                val y = b.doc.lastOpenedAt
                when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> if (ascending) x.compareTo(y) else y.compareTo(x)
                }
            }
            SortKey.LAST_MODIFIED -> compareBy<LibraryItem> { it.doc.lastModified }
            SortKey.FILE_SIZE -> compareBy<LibraryItem> { it.doc.sizeBytes }
            SortKey.FORMAT -> compareBy<LibraryItem, String>(ci) { it.doc.format }
            SortKey.PROGRESS -> compareBy<LibraryItem> { it.progressFraction }
        }
        val directional: Comparator<LibraryItem> = when (key) {
            // These two handle direction themselves (nulls-last behaviour).
            SortKey.AUTHOR, SortKey.RECENTLY_OPENED -> primary
            else -> if (ascending) primary else primary.reversed()
        }
        val byTitle: Comparator<LibraryItem> = compareBy<LibraryItem, String>(ci) { it.doc.title }
        val byId: Comparator<LibraryItem> = compareBy<LibraryItem> { it.doc.id }
        return directional.thenComparing(byTitle).thenComparing(byId)
    }

    private fun compareNullable(a: String?, b: String?, ascending: Boolean): Int {
        val x = a?.takeIf { it.isNotBlank() }
        val y = b?.takeIf { it.isNotBlank() }
        return when {
            x == null && y == null -> 0
            x == null -> 1 // unknown authors always last
            y == null -> -1
            else -> if (ascending) ci.compare(x, y) else ci.compare(y, x)
        }
    }

    fun apply(items: List<LibraryItem>, q: LibraryQuery): List<LibraryItem> =
        filter(items, q).sortedWith(comparator(q.sortKey, q.ascending))

    data class Group(val name: String, val count: Int)

    /** Authors with a count; documents without an author fall into the "" (unknown) group, listed last. */
    fun authorGroups(docs: List<DocumentEntity>): List<Group> {
        val groups = docs.groupBy { MetadataNormalizer.groupKey(it.author) }
        return groups.map { (key, list) ->
            val display = if (key.isEmpty()) "" else list.mapNotNull { it.author }.first()
            Group(display, list.size)
        }.sortedWith(
            compareBy<Group> { it.name.isEmpty() }.thenBy(ci) { it.name }
        )
    }

    /** Series with a count; documents without a series are not listed (nothing is guessed). */
    fun seriesGroups(docs: List<DocumentEntity>): List<Group> =
        docs.filter { !it.series.isNullOrBlank() }
            .groupBy { MetadataNormalizer.groupKey(it.series) }
            .map { (_, list) -> Group(list.mapNotNull { it.series }.first(), list.size) }
            .sortedWith(compareBy<Group, String>(ci) { it.name })
}
