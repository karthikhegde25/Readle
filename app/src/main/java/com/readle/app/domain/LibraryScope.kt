package com.readle.app.domain

enum class SmartList(val key: String, val label: String) {
    FAVORITES("favorites", "Favorites"),
    READING("reading", "Reading"),
    FINISHED("finished", "Finished"),
    UNREAD("unread", "To read"),
    DUPLICATES("duplicates", "Possible duplicates"),
    UNAVAILABLE("unavailable", "Unavailable files")
}

/** What a library list is showing. Encoded as a string so it can travel in a navigation route. */
sealed interface LibraryScope {
    data object All : LibraryScope
    data object Recent : LibraryScope
    data class Collection(val id: Long) : LibraryScope
    /** An empty name addresses the "Unknown author" group. */
    data class Author(val name: String) : LibraryScope
    data class Series(val name: String) : LibraryScope
    data class Smart(val list: SmartList) : LibraryScope
    data class Folder(val locationId: Long) : LibraryScope

    fun encode(): String = when (this) {
        All -> "all"
        Recent -> "recent"
        is Collection -> "collection:$id"
        is Author -> "author:$name"
        is Series -> "series:$name"
        is Smart -> "smart:${list.key}"
        is Folder -> "folder:$locationId"
    }

    companion object {
        fun decode(value: String?): LibraryScope {
            if (value.isNullOrBlank()) return All
            val kind = value.substringBefore(':')
            val arg = if (value.contains(':')) value.substringAfter(':') else ""
            return when (kind) {
                "all" -> All
                "recent" -> Recent
                "collection" -> arg.toLongOrNull()?.let { Collection(it) } ?: All
                "author" -> Author(arg)
                "series" -> Series(arg)
                "smart" -> SmartList.entries.firstOrNull { it.key == arg }?.let { Smart(it) } ?: All
                "folder" -> arg.toLongOrNull()?.let { Folder(it) } ?: All
                else -> All
            }
        }
    }
}

object ScopeLogic {
    /**
     * The library only lists files the app can actually open. Unavailable files live in their own
     * "Unavailable files" list, where they can be relinked or removed.
     */
    fun apply(items: List<LibraryItem>, scope: LibraryScope, base: LibraryQuery): List<LibraryItem> {
        val inScope = when (scope) {
            is LibraryScope.Smart -> when (scope.list) {
                SmartList.UNAVAILABLE -> items.filter { !it.isAvailable }
                SmartList.FAVORITES -> items.filter { it.isAvailable && it.doc.isFavorite }
                SmartList.READING -> items.filter { it.isAvailable && it.status == ReadStatus.READING }
                SmartList.FINISHED -> items.filter { it.isAvailable && it.status == ReadStatus.FINISHED }
                SmartList.UNREAD -> items.filter { it.isAvailable && it.status == ReadStatus.UNREAD }
                SmartList.DUPLICATES -> items.filter { it.isAvailable && it.isDuplicate }
            }
            LibraryScope.Recent -> items.filter { it.isAvailable && it.doc.lastOpenedAt != null }
            is LibraryScope.Folder -> items.filter { it.isAvailable && it.doc.locationId == scope.locationId }
            else -> items.filter { it.isAvailable }
        }
        val query = when (scope) {
            LibraryScope.Recent -> base.copy(sortKey = SortKey.RECENTLY_OPENED, ascending = false)
            is LibraryScope.Collection -> base.copy(collectionId = scope.id)
            is LibraryScope.Author -> base.copy(author = scope.name)
            is LibraryScope.Series -> base.copy(series = scope.name)
            else -> base
        }
        return LibraryLogic.apply(inScope, query)
    }

    /** Items in the scope before the user's search/filters: used to tell "empty" from "no results". */
    fun countInScope(items: List<LibraryItem>, scope: LibraryScope): Int =
        apply(items, scope, LibraryQuery()).size
}
