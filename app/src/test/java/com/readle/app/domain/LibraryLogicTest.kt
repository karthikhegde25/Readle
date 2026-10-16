package com.readle.app.domain

import com.readle.app.data.db.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    private fun doc(
        id: Long,
        title: String,
        author: String? = null,
        series: String? = null,
        format: DocFormat = DocFormat.EPUB,
        size: Long = 100,
        modified: Long = 0,
        opened: Long? = null,
        favorite: Boolean = false,
        fingerprint: String? = null,
        name: String = "$title.${format.ext}"
    ) = DocumentEntity(
        id = id, uri = "content://x/$id", displayName = name, title = title, author = author,
        series = series, format = format.name, sizeBytes = size, lastModified = modified,
        lastOpenedAt = opened, isFavorite = favorite, fingerprint = fingerprint
    )

    private fun item(
        d: DocumentEntity,
        progress: Float = 0f,
        hasProgress: Boolean = progress > 0f,
        collections: Set<Long> = emptySet(),
        dup: Boolean = false
    ) = LibraryItem(d, progress, hasProgress, collections, dup)

    private fun titles(items: List<LibraryItem>) = items.map { it.doc.title }

    @Test
    fun sortByTitleIsCaseInsensitiveAndReversible() {
        val items = listOf(item(doc(1, "banana")), item(doc(2, "Apple")), item(doc(3, "cherry")))
        assertEquals(listOf("Apple", "banana", "cherry"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.TITLE))))
        assertEquals(
            listOf("cherry", "banana", "Apple"),
            titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.TITLE, ascending = false)))
        )
    }

    @Test
    fun equalKeysFallBackToIdSoOrderIsDeterministic() {
        val a = item(doc(5, "Same", size = 10))
        val b = item(doc(2, "Same", size = 10))
        val c = item(doc(9, "Same", size = 10))
        val sorted = LibraryLogic.apply(listOf(a, b, c), LibraryQuery(sortKey = SortKey.FILE_SIZE))
        assertEquals(listOf(2L, 5L, 9L), sorted.map { it.doc.id })
        val again = LibraryLogic.apply(listOf(c, a, b), LibraryQuery(sortKey = SortKey.FILE_SIZE))
        assertEquals(sorted.map { it.doc.id }, again.map { it.doc.id })
    }

    @Test
    fun unknownAuthorsSortLastInBothDirections() {
        val items = listOf(
            item(doc(1, "A", author = null)),
            item(doc(2, "B", author = "Zed")),
            item(doc(3, "C", author = "Abe"))
        )
        assertEquals(listOf("C", "B", "A"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.AUTHOR))))
        assertEquals(listOf("B", "C", "A"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.AUTHOR, ascending = false))))
    }

    @Test
    fun neverOpenedSortsLastWhenSortingByRecentlyOpened() {
        val items = listOf(
            item(doc(1, "never")),
            item(doc(2, "old", opened = 100)),
            item(doc(3, "new", opened = 900))
        )
        assertEquals(listOf("new", "old", "never"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.RECENTLY_OPENED, ascending = false))))
        assertEquals(listOf("old", "new", "never"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.RECENTLY_OPENED, ascending = true))))
    }

    @Test
    fun sortByProgressAndFormat() {
        val items = listOf(
            item(doc(1, "p", format = DocFormat.PDF), progress = 0.5f),
            item(doc(2, "e", format = DocFormat.EPUB), progress = 0.9f),
            item(doc(3, "t", format = DocFormat.TXT), progress = 0.1f)
        )
        assertEquals(listOf("t", "p", "e"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.PROGRESS))))
        assertEquals(listOf("e", "p", "t"), titles(LibraryLogic.apply(items, LibraryQuery(sortKey = SortKey.FORMAT))))
    }

    @Test
    fun statusThresholds() {
        assertEquals(ReadStatus.UNREAD, LibraryLogic.statusOf(false, 0f))
        assertEquals(ReadStatus.UNREAD, LibraryLogic.statusOf(true, 0.0005f))
        assertEquals(ReadStatus.READING, LibraryLogic.statusOf(true, 0.4f))
        assertEquals(ReadStatus.FINISHED, LibraryLogic.statusOf(true, 0.98f))
        assertEquals(ReadStatus.FINISHED, LibraryLogic.statusOf(true, 1f))
    }

    @Test
    fun filtersCombineWithAnd() {
        val items = listOf(
            item(doc(1, "Dune", author = "Frank Herbert", format = DocFormat.EPUB, favorite = true), progress = 0.5f),
            item(doc(2, "Dune Messiah", author = "Frank Herbert", format = DocFormat.PDF, favorite = true), progress = 0f),
            item(doc(3, "Emma", author = "Jane Austen", format = DocFormat.EPUB), progress = 1f)
        )
        val q = LibraryQuery(formats = setOf(DocFormat.EPUB), favoritesOnly = true)
        assertEquals(listOf("Dune"), titles(LibraryLogic.apply(items, q)))
        assertEquals(listOf("Emma"), titles(LibraryLogic.apply(items, LibraryQuery(status = ReadStatus.FINISHED))))
        assertEquals(listOf("Dune Messiah"), titles(LibraryLogic.apply(items, LibraryQuery(status = ReadStatus.UNREAD, author = "frank  herbert"))))
        assertTrue(LibraryQuery(favoritesOnly = true).hasActiveFilters)
        assertFalse(LibraryQuery().hasActiveFilters)
    }

    @Test
    fun searchMatchesTitleAuthorSeriesFilenameAndRequiresAllTokens() {
        val items = listOf(
            item(doc(1, "Dune", author = "Frank Herbert", series = "Dune Chronicles", name = "dune_final.epub")),
            item(doc(2, "Emma", author = "Jane Austen"))
        )
        fun hits(text: String) = titles(LibraryLogic.apply(items, LibraryQuery(text = text)))
        assertEquals(listOf("Dune"), hits("herbert"))
        assertEquals(listOf("Dune"), hits("chronicles"))
        assertEquals(listOf("Dune"), hits("DUNE_FINAL"))
        assertEquals(listOf("Dune"), hits("frank dune"))
        assertEquals(emptyList<String>(), hits("frank austen"))
        assertEquals(listOf("Dune", "Emma"), hits("  "))
    }

    @Test
    fun authorAndSeriesFiltersIncludingUnknownAuthorGroup() {
        val items = listOf(
            item(doc(1, "A", author = "X", series = "S1")),
            item(doc(2, "B", author = null, series = "s1")),
            item(doc(3, "C", author = "  ", series = null))
        )
        assertEquals(listOf("B", "C"), titles(LibraryLogic.apply(items, LibraryQuery(author = ""))))
        assertEquals(listOf("A", "B"), titles(LibraryLogic.apply(items, LibraryQuery(series = "S1"))))
    }

    @Test
    fun collectionFilter() {
        val items = listOf(
            item(doc(1, "A"), collections = setOf(10L, 11L)),
            item(doc(2, "B"), collections = setOf(11L)),
            item(doc(3, "C"))
        )
        assertEquals(listOf("A"), titles(LibraryLogic.apply(items, LibraryQuery(collectionId = 10L))))
        assertEquals(listOf("A", "B"), titles(LibraryLogic.apply(items, LibraryQuery(collectionId = 11L))))
    }

    @Test
    fun recentOnlyHidesNeverOpened() {
        val items = listOf(item(doc(1, "A", opened = 5)), item(doc(2, "B")))
        assertEquals(listOf("A"), titles(LibraryLogic.apply(items, LibraryQuery(recentOnly = true))))
    }

    @Test
    fun duplicateDetectionUsesFingerprintsAndIgnoresMissingOnes() {
        val docs = listOf(
            doc(1, "a", fingerprint = "f1"),
            doc(2, "b", fingerprint = "f1"),
            doc(3, "c", fingerprint = "f2"),
            doc(4, "d", fingerprint = null),
            doc(5, "e", fingerprint = null),
            doc(6, "f", fingerprint = "")
        )
        assertEquals(setOf(1L, 2L), LibraryLogic.duplicateIds(docs))
        val items = docs.map { item(it, dup = it.id in setOf(1L, 2L)) }
        assertEquals(listOf("a", "b"), titles(LibraryLogic.apply(items, LibraryQuery(duplicatesOnly = true))))
    }

    @Test
    fun groupsAreBuiltFromMetadataWithoutGuessing() {
        val docs = listOf(
            doc(1, "a", author = "Jane Austen", series = "Saga"),
            doc(2, "b", author = "jane  austen", series = "saga"),
            doc(3, "c", author = null),
            doc(4, "d", author = "Bob")
        )
        val authors = LibraryLogic.authorGroups(docs)
        assertEquals(listOf("Bob", "Jane Austen", ""), authors.map { it.name })
        assertEquals(2, authors.first { it.name == "Jane Austen" }.count)
        val series = LibraryLogic.seriesGroups(docs)
        assertEquals(1, series.size)
        assertEquals(2, series[0].count)
    }
}
