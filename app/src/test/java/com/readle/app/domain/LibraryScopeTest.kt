package com.readle.app.domain

import com.readle.app.data.db.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScopeTest {

    private fun item(
        id: Long, title: String, available: Boolean = true, author: String? = null, series: String? = null,
        opened: Long? = null, favorite: Boolean = false, progress: Float? = null, dup: Boolean = false,
        collections: Set<Long> = emptySet(), location: Long? = null
    ) = LibraryItem(
        doc = DocumentEntity(
            id = id, uri = "content://x/$id", displayName = "$title.epub", title = title, author = author, series = series,
            format = DocFormat.EPUB.name, lastOpenedAt = opened, isFavorite = favorite, locationId = location,
            availabilityStatus = if (available) DocumentEntity.AVAILABLE else DocumentEntity.UNAVAILABLE
        ),
        progressFraction = progress ?: 0f, hasProgress = progress != null, collectionIds = collections, isDuplicate = dup
    )

    private fun titles(l: List<LibraryItem>) = l.map { it.doc.title }

    @Test
    fun scopesRoundTripThroughTheirEncoding() {
        val scopes = listOf(
            LibraryScope.All, LibraryScope.Recent, LibraryScope.Collection(42),
            LibraryScope.Author("Jane: Austen"), LibraryScope.Author(""), LibraryScope.Series("Saga"),
            LibraryScope.Smart(SmartList.FINISHED), LibraryScope.Folder(3)
        )
        for (s in scopes) assertEquals(s, LibraryScope.decode(s.encode()))
    }

    @Test
    fun badEncodingsFallBackToAll() {
        assertEquals(LibraryScope.All, LibraryScope.decode(null))
        assertEquals(LibraryScope.All, LibraryScope.decode(""))
        assertEquals(LibraryScope.All, LibraryScope.decode("collection:abc"))
        assertEquals(LibraryScope.All, LibraryScope.decode("smart:nope"))
        assertEquals(LibraryScope.All, LibraryScope.decode("bogus:1"))
    }

    @Test
    fun theLibraryNeverListsFilesTheAppCannotOpen() {
        val items = listOf(item(1, "ok"), item(2, "gone", available = false))
        assertEquals(listOf("ok"), titles(ScopeLogic.apply(items, LibraryScope.All, LibraryQuery())))
        assertEquals(listOf("gone"), titles(ScopeLogic.apply(items, LibraryScope.Smart(SmartList.UNAVAILABLE), LibraryQuery())))
        assertEquals(1, ScopeLogic.countInScope(items, LibraryScope.All))
    }

    @Test
    fun recentShowsOnlyOpenedBooksNewestFirstRegardlessOfUserSort() {
        val items = listOf(item(1, "a", opened = 10), item(2, "b", opened = 30), item(3, "c"), item(4, "d", opened = 20))
        val out = ScopeLogic.apply(items, LibraryScope.Recent, LibraryQuery(sortKey = SortKey.TITLE, ascending = true))
        assertEquals(listOf("b", "d", "a"), titles(out))
    }

    @Test
    fun collectionAuthorSeriesAndFolderScopes() {
        val items = listOf(
            item(1, "a", author = "X", series = "S", collections = setOf(9L), location = 5),
            item(2, "b", author = "Y", collections = setOf(9L, 8L)),
            item(3, "c", author = null, location = 5)
        )
        assertEquals(listOf("a", "b"), titles(ScopeLogic.apply(items, LibraryScope.Collection(9), LibraryQuery())))
        assertEquals(listOf("b"), titles(ScopeLogic.apply(items, LibraryScope.Collection(8), LibraryQuery())))
        assertEquals(listOf("a"), titles(ScopeLogic.apply(items, LibraryScope.Author("x"), LibraryQuery())))
        assertEquals(listOf("c"), titles(ScopeLogic.apply(items, LibraryScope.Author(""), LibraryQuery())))
        assertEquals(listOf("a"), titles(ScopeLogic.apply(items, LibraryScope.Series("s"), LibraryQuery())))
        assertEquals(listOf("a", "c"), titles(ScopeLogic.apply(items, LibraryScope.Folder(5), LibraryQuery())))
    }

    @Test
    fun smartListsFilterByStatusAndFlags() {
        val items = listOf(
            item(1, "fav", favorite = true),
            item(2, "reading", progress = 0.4f),
            item(3, "done", progress = 1f),
            item(4, "new"),
            item(5, "dupe", dup = true)
        )
        fun smart(l: SmartList) = titles(ScopeLogic.apply(items, LibraryScope.Smart(l), LibraryQuery()))
        assertEquals(listOf("fav"), smart(SmartList.FAVORITES))
        assertEquals(listOf("reading"), smart(SmartList.READING))
        assertEquals(listOf("done"), smart(SmartList.FINISHED))
        assertEquals(listOf("dupe", "fav", "new"), smart(SmartList.UNREAD))
        assertEquals(listOf("dupe"), smart(SmartList.DUPLICATES))
    }

    @Test
    fun userSearchAndFiltersStillApplyInsideAScope() {
        val items = listOf(item(1, "Dune", collections = setOf(1L)), item(2, "Emma", collections = setOf(1L)))
        val out = ScopeLogic.apply(items, LibraryScope.Collection(1), LibraryQuery(text = "emm"))
        assertEquals(listOf("Emma"), titles(out))
        assertTrue(ScopeLogic.apply(items, LibraryScope.Collection(1), LibraryQuery(text = "zzz")).isEmpty())
    }
}
