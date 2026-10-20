package com.readle.app.reader.reflow

/** What the WebView should load. A new [token] means "(re)load this chapter". */
data class PageSpec(
    val target: ChapterTarget,
    val css: String,
    val highlightsJson: String,
    val background: Int,
    val token: Long
)

/** Commands sent from the reader logic to the page that is already loaded. */
sealed interface ReflowCommand {
    data class PageBy(val dir: Int) : ReflowCommand
    data class ScrollFraction(val fraction: Float) : ReflowCommand
    data class SetStyle(val css: String, val background: Int) : ReflowCommand
    data class Highlights(val json: String) : ReflowCommand
    data class Find(val query: String, val nth: Int) : ReflowCommand
    data object ClearFind : ReflowCommand
    data object ClearSelection : ReflowCommand

    /** Asks the page for its speech text and where the visible page starts. [lastOffset] is the word last spoken, or -1. */
    data class TtsPrepare(val lastOffset: Int) : ReflowCommand
    /** Highlights speech-text range [start, end) and keeps it on screen. */
    data class TtsMark(val start: Int, val end: Int) : ReflowCommand
    data object TtsClear : ReflowCommand
}

/** Events coming out of the page. Called on WebView/JS threads, so implementations must be thread-safe. */
interface ReflowListener {
    fun onScroll(fraction: Float)

    /** Screen-sized pages in the current section: the page being shown (1-based) and how many there are. */
    fun onPages(page: Int, total: Int)
    fun onTap(x: Float)
    fun onSelection(text: String, start: Int, end: Int)
    fun onSelectionCleared()
    fun onNavigate(next: Boolean)
    fun onInternalJump(fractionBefore: Float)
    fun onHighlightTap(id: Long)
    fun onReady()

    /** Answer to [ReflowCommand.TtsPrepare]: [text] is what to speak, [visibleStart] where the visible page begins, [lastVisible] whether the last spoken word is still on screen. */
    fun onTtsText(text: String, visibleStart: Int, lastVisible: Boolean)

    /** Result of [ReflowCommand.PageBy]: "ok", "start" (already at top) or "end" (already at bottom). */
    fun onPageResult(dir: Int, result: String)

    /** The user followed a link to another chapter of the book. */
    fun onChapterLink(spine: Int, fragment: String?)

    /** The user followed a link that leaves the book; the UI should confirm before opening it. */
    fun onExternalLink(url: String)
}
