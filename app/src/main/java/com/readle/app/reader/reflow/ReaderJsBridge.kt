package com.readle.app.reader.reflow

import android.webkit.JavascriptInterface

/**
 * The only object exposed to page JavaScript (as "ReadleBridge"). Only the nonce'd Readle script can
 * call it, because the Content-Security-Policy blocks every other script. All inputs are treated as
 * untrusted and clamped before being forwarded.
 */
class ReaderJsBridge(private val listener: ReflowListener) {

    @JavascriptInterface
    fun onScroll(fraction: Double, scrollY: Double, scrollHeight: Double, viewportHeight: Double) {
        if (fraction.isNaN()) return
        listener.onScroll(fraction.toFloat().coerceIn(0f, 1f))
    }

    @JavascriptInterface
    fun onPages(page: Int, total: Int) {
        if (total < 1 || page < 1 || page > total) return
        listener.onPages(page, total)
    }

    @JavascriptInterface
    fun onTap(x: Double) {
        if (x.isNaN()) return
        listener.onTap(x.toFloat().coerceIn(0f, 1f))
    }

    @JavascriptInterface
    fun onSelection(text: String?, start: Int, end: Int) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || start < 0 || end < start) return
        listener.onSelection(t.take(MAX_SELECTION), start, end)
    }

    @JavascriptInterface
    fun onSelectionCleared() = listener.onSelectionCleared()

    @JavascriptInterface
    fun onNavigate(dir: String?) {
        when (dir) {
            "next" -> listener.onNavigate(true)
            "prev" -> listener.onNavigate(false)
        }
    }

    @JavascriptInterface
    fun onInternalJump(fractionBefore: Double) {
        listener.onInternalJump(if (fractionBefore.isNaN()) 0f else fractionBefore.toFloat().coerceIn(0f, 1f))
    }

    @JavascriptInterface
    fun onHighlightTap(id: Int) {
        if (id > 0) listener.onHighlightTap(id.toLong())
    }

    @JavascriptInterface
    fun onReady() = listener.onReady()

    companion object {
        const val NAME = "ReadleBridge"
        private const val MAX_SELECTION = 5000
    }
}
