package com.readle.app.reader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import kotlinx.coroutines.Job
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.readle.app.domain.ReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zoom and scroll state of the PDF view, hoisted so the reader's toolbar can drive it.
 *
 * Two modes:
 *  - continuous (zoomPage == null): all pages in a vertical list at fit-width / fit-page;
 *  - zoomed (zoomPage != null): one page, panned and zoomed around the user's fingers. Only the visible
 *    region is rendered, so it stays sharp at any zoom and memory use does not grow with the zoom level.
 */
@Stable
class PdfViewState(initialPage: Int) {
    val listState = LazyListState(firstVisibleItemIndex = initialPage.coerceAtLeast(0))

    var fitPage by mutableStateOf(false)
        private set

    /** Page shown in zoomed mode, or null in continuous mode. */
    var zoomPage by mutableStateOf<Int?>(null)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    /** Position of the zoomed page's top-left corner in viewport pixels. */
    var offset by mutableStateOf(Offset.Zero)
        private set
    /** Continuous mode scrolls here once the list is shown again after leaving zoomed mode. */
    var pendingScroll by mutableStateOf<Int?>(null)
        internal set
    /** How far (in pixels) the [pendingScroll] page's top edge should sit above the top of the screen. */
    internal var pendingScrollOffset: Int = 0

    internal var viewport: IntSize = IntSize.Zero
    /** Space between pages in the zoomed scrolling view, in pixels. */
    internal var gap: Float = 16f
    internal var pageCount: Int = 0
    internal var ratioOf: (Int) -> Float = { DEFAULT_RATIO }
    var ratio: Float = DEFAULT_RATIO
        private set

    /**
     * Pages mode: exactly one page is shown at a time (always in the single-page view, never the scrolling list),
     * fitted so the whole page is visible; tap or swipe turns pages.
     */
    var paged by mutableStateOf(false)
        private set

    val zoomed: Boolean get() = zoomPage != null

    /** Smallest zoom: 1 (page width) when scrolling, or the zoom at which the whole page fits the screen in pages mode. */
    internal fun minZoom(): Float =
        if (paged && viewport.width > 0) minOf(1f, viewport.height / (viewport.width * ratio)).coerceIn(0.2f, 1f) else 1f

    private fun pageW() = viewport.width * zoom
    private fun pageH() = pageW() * ratio

    /** Keeps the page covering the viewport (or centred when smaller than it). */
    private fun clamp(o: Offset): Offset {
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        val w = pageW()
        val h = pageH()
        val x = if (w <= vw) (vw - w) / 2f else o.x.coerceIn(vw - w, 0f)
        val y = if (h <= vh) (vh - h) / 2f else o.y.coerceIn(vh - h, 0f)
        return Offset(x, y)
    }

    /**
     * Puts the zoomed view at [o] (where the anchor page's top-left corner is).
     * Pages mode keeps the one page covering the screen. Scrolling mode lets the view run on into the pages above and
     * below, like one long sheet: the anchor page is whichever one is at the middle of the screen.
     */
    private fun place(o: Offset) {
        if (paged || pageCount == 0) {
            offset = clamp(o)
            return
        }
        var p = zoomPage ?: return
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        val w = pageW()
        val x = if (w <= vw) (vw - w) / 2f else o.x.coerceIn(vw - w, 0f)
        var y = o.y
        var h = w * ratioOf(p)
        var guard = 0
        while (guard++ < 200) {
            if (p < pageCount - 1 && y + h + gap / 2f < vh / 2f) {
                y += h + gap
                p += 1
                h = w * ratioOf(p)
            } else if (p > 0 && y - gap / 2f > vh / 2f) {
                p -= 1
                h = w * ratioOf(p)
                y -= h + gap
            } else break
        }
        if (p == 0) y = minOf(y, if (h <= vh) (vh - h) / 2f else 0f)
        if (p == pageCount - 1) y = maxOf(y, if (h <= vh) (vh - h) / 2f else vh - h)
        zoomPage = p
        ratio = ratioOf(p)
        offset = Offset(x, y)
    }

    /** Scrolling mode, zoomed: moves the view freely, flowing from one page into the next. */
    internal fun scrollBy(delta: Offset) {
        if (zoomPage != null) place(offset + delta)
    }

    /** Re-checks the layout after a page's real proportions became known. */
    internal fun relayout() {
        if (zoomPage != null) place(offset)
    }

    /** The pages on screen in the zoomed view, each with the y of its top edge. Pages mode shows only one. */
    internal fun slots(): List<Pair<Int, Float>> {
        val p = zoomPage ?: return emptyList()
        if (paged) return listOf(p to offset.y)
        val w = pageW()
        val vh = viewport.height.toFloat()
        val out = ArrayList<Pair<Int, Float>>(4)
        out.add(p to offset.y)
        var q = p
        var top = offset.y
        var n = 0
        while (q > 0 && top - gap > 0f && n++ < 4) {
            q -= 1
            top -= gap + w * ratioOf(q)
            out.add(0, q to top)
        }
        q = p
        var bottom = offset.y + w * ratioOf(p)
        n = 0
        while (q < pageCount - 1 && bottom + gap < vh && n++ < 4) {
            q += 1
            val t = bottom + gap
            out.add(q to t)
            bottom = t + w * ratioOf(q)
        }
        return out
    }

    /** The list item under viewport y, with the fraction down that page. */
    internal fun pageAt(y: Float): Pair<Int, Float>? {
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return null
        val hit = items.firstOrNull { y >= it.offset && y <= it.offset + it.size }
            ?: items.minByOrNull { abs(it.offset + it.size / 2f - y) }
            ?: return null
        return hit.index to ((y - hit.offset) / hit.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** Starts zoomed mode so the point (fx, fy) of [page] (fractions of its width/height) stays under [focal]. */
    internal fun enterZoom(page: Int, targetZoom: Float, focal: Offset, fx: Float, fy: Float) {
        if (viewport.width == 0 || pageCount == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        zoomPage = p
        ratio = ratioOf(p)
        zoom = targetZoom.coerceIn(minZoom(), MAX_ZOOM)
        place(Offset(focal.x - fx * pageW(), focal.y - fy * pageH()))
    }

    /** Zooms by [factor] keeping the page point under [focal] fixed. */
    internal fun zoomAround(factor: Float, focal: Offset) {
        val z2 = (zoom * factor).coerceIn(minZoom(), MAX_ZOOM)
        val k = z2 / zoom
        val moved = Offset(focal.x - (focal.x - offset.x) * k, focal.y - (focal.y - offset.y) * k)
        zoom = z2
        place(moved)
    }

    /** Pans; returns the vertical movement that was blocked by the page edge (used to flip pages). */
    internal fun panBy(delta: Offset): Float = panByXY(delta).y

    /** Pans; returns how far the movement was blocked by the page edges on each axis. */
    internal fun panByXY(delta: Offset): Offset {
        val target = offset + delta
        val c = clamp(target)
        offset = c
        return target - c
    }

    /** Switches the zoomed page, keeping zoom and horizontal position. */
    internal fun showPage(page: Int, atTop: Boolean) {
        if (pageCount == 0) return
        val p = page.coerceIn(0, pageCount - 1)
        val wasFitted = paged && abs(zoom - minZoom()) < 0.03f
        zoomPage = p
        ratio = ratioOf(p)
        if (paged && wasFitted) zoom = minZoom()
        val y = if (atTop) 0f else viewport.height - pageH()
        place(Offset(offset.x, y))
    }

    internal fun updateRatio(page: Int, newRatio: Float) {
        if (page != zoomPage || newRatio <= 0f) return
        val wasFitted = paged && abs(zoom - minZoom()) < 0.03f
        ratio = newRatio
        if (paged && wasFitted) zoom = minZoom()
        place(offset)
    }

    /** Leaves the zoomed view: back to the scrolling list, or (in pages mode) back to the whole page fitted on screen. */
    internal fun exitZoom() {
        if (paged) {
            zoom = minZoom()
            offset = clamp(Offset.Zero)
            return
        }
        val p = zoomPage
        if (p != null && viewport.height > 0) {
            // Land on the same spot of the page that was in the middle of the screen, not at the top of the page.
            val vh = viewport.height.toFloat()
            val z = zoom.coerceAtLeast(1f)
            var idx = p
            var top = vh / 2f + (offset.y - vh / 2f) / z
            if (top > 0f && idx > 0) {
                idx -= 1
                top -= viewport.width * ratioOf(idx) + gap
            }
            pendingScrollOffset = if (top < 0f) (-top).roundToInt() else 0
            pendingScroll = idx
        }
        zoomPage = null
        zoom = 1f
        offset = Offset.Zero
    }

    /** Switches between the scrolling list and one-page-at-a-time, keeping the page being read. */
    internal fun setPaged(on: Boolean) {
        if (viewport.width == 0 || pageCount == 0) return
        if (on) {
            val page = zoomPage ?: listState.layoutInfo.let { info ->
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: listState.firstVisibleItemIndex
            }
            paged = true
            zoomPage = page.coerceIn(0, pageCount - 1)
            ratio = ratioOf(zoomPage!!)
            zoom = minZoom()
            offset = clamp(Offset.Zero)
        } else if (paged) {
            paged = false
            exitZoom()
        }
    }

    // ---- toolbar buttons ----

    fun zoomBy(factor: Float) {
        fitPage = false
        if (viewport.width == 0) return
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        if (!zoomed) {
            if (factor <= 1f) return
            val (page, fy) = pageAt(center.y) ?: return
            enterZoom(page, factor, center, 0.5f, fy)
        } else {
            zoomAround(factor, center)
            if (zoom <= minZoom() * 1.05f) exitZoom()
        }
    }

    /** Scrolling: page width. Pages mode: the whole page fitted on the screen. */
    fun fitWidth() {
        fitPage = false
        if (zoomed) exitZoom()
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val DEFAULT_RATIO = 1.4142f
    }
}

@Composable
fun rememberPdfViewState(initialPage: Int): PdfViewState = remember { PdfViewState(initialPage) }

private object PdfBitmaps {
    /** Memory-bounded: roughly one eighth of the heap, measured in KB. */
    val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Any already-rendered version of a page, used as a stand-in so a page never shows blank while the right one renders. */
    fun anyFor(index: Int): Bitmap? =
        cache.snapshot().entries.firstOrNull { it.key.startsWith("$index@") && !it.value.isRecycled }?.value
}

private fun themeFilter(theme: ReaderTheme, highContrast: Boolean, tint: Color? = null): ColorFilter? = when (theme) {
    ReaderTheme.DAY -> tint?.let { ColorFilter.tint(it, BlendMode.Multiply) }
    ReaderTheme.SEPIA -> if (highContrast) null else ColorFilter.tint(Color(0xFFF4ECD8), BlendMode.Multiply)
    ReaderTheme.SEPIA_CONTRAST -> ColorFilter.tint(Color(0xFFF0DEC6), BlendMode.Multiply)
    // Night inverts the page (white paper becomes black); images are inverted too, which is the usual trade-off.
    ReaderTheme.NIGHT, ReaderTheme.NIGHT_CONTRAST -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

private class RegionBitmap(val bitmap: Bitmap, val page: Int, val zoom: Float, val offset: Offset) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * PDF viewer rendered with Android's PdfRenderer (pages are drawn exactly as authored).
 * Continuous vertical scrolling at fit-width; pinch or double-tap zooms around the touched point into a
 * pan-and-zoom page view (flip pages by dragging past an edge); pinch back out or double-tap to return.
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfReaderView(
    handle: PageSource,
    state: PdfViewState,
    theme: ReaderTheme,
    highContrast: Boolean,
    tapToTurn: Boolean,
    tint: Color? = null,
    reducedMotion: Boolean,
    goTo: Flow<Int>,
    onPageChanged: (Int) -> Unit,
    onTapCenter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pageGapColor = when (theme) {
        ReaderTheme.NIGHT, ReaderTheme.NIGHT_CONTRAST -> Color(0xFF0B0B0B)
        ReaderTheme.SEPIA -> Color(0xFFD9CDB2)
        ReaderTheme.SEPIA_CONTRAST -> Color(0xFFCDB99B)
        ReaderTheme.DAY -> if (tint != null) tint.copy(alpha = 1f).let { Color(it.red * 0.88f, it.green * 0.88f, it.blue * 0.88f) } else Color(0xFFE3E3E3)
    }
    val filter = remember(theme, highContrast, tint) { themeFilter(theme, highContrast, tint) }
    val currentTapToTurn by rememberUpdatedState(tapToTurn)
    val currentOnTapCenter by rememberUpdatedState(onTapCenter)
    val currentOnPage by rememberUpdatedState(onPageChanged)
    val currentReduced by rememberUpdatedState(reducedMotion)
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    SideEffect { state.gap = gapPx }
    // Rendered pages belong to this document only, so they are dropped when the viewer closes.
    DisposableEffect(handle) { onDispose { PdfBitmaps.cache.evictAll() } }

    state.pageCount = handle.pageCount
    state.ratioOf = { handle.cachedPageRatio(it) ?: PdfViewState.DEFAULT_RATIO }

    LaunchedEffect(handle, goTo) {
        goTo.collect { page ->
            val target = page.coerceIn(0, handle.pageCount - 1)
            when {
                state.zoomed -> state.showPage(target, atTop = true)
                currentReduced -> state.listState.scrollToItem(target)
                else -> state.listState.animateScrollToItem(target)
            }
        }
    }

    // Report the page that occupies most of the screen.
    LaunchedEffect(handle, state) {
        snapshotFlow {
            state.zoomPage ?: run {
                val info = state.listState.layoutInfo
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }?.index ?: 0
            }
        }.distinctUntilChanged().collect { currentOnPage(it) }
    }

    fun turnPage(dir: Int) {
        val zp = state.zoomPage
        if (zp != null) state.showPage(zp + dir, atTop = dir > 0)
        else scope.launch {
            val target = (state.listState.firstVisibleItemIndex + dir).coerceIn(0, handle.pageCount - 1)
            if (currentReduced) state.listState.scrollToItem(target) else state.listState.animateScrollToItem(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(pageGapColor)
            .clipToBounds()
            // Gestures: pinch anywhere; one-finger pan only when zoomed (otherwise the list scrolls).
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    flingJob?.cancel()
                    flingJob = null
                    val tracker = VelocityTracker()
                    var pinch = 1f
                    var moved = 0f
                    var over = 0f
                    var overX = 0f
                    var flipped = false
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) multiTouch = true
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (state.zoomed) {
                            moved += pan.getDistance()
                            if (pressed >= 2 || moved > slop) {
                                if (zoomChange != 1f) state.zoomAround(zoomChange, centroid)
                                if (pan != Offset.Zero) {
                                    if (state.paged) {
                                        val blocked = state.panByXY(pan)
                                        val left = blocked.y
                                        over = if (left != 0f && (over == 0f || (left > 0f) == (over > 0f))) over + left else 0f
                                        val leftX = blocked.x
                                        overX = if (leftX != 0f && (overX == 0f || (leftX > 0f) == (overX > 0f))) overX + leftX else 0f
                                        val zp = state.zoomPage
                                        if (!flipped && zp != null && abs(over) > 220f) {
                                            flipped = true
                                            if (over < 0f) state.showPage(zp + 1, atTop = true) else state.showPage(zp - 1, atTop = false)
                                            over = 0f
                                        } else if (!flipped && zp != null && abs(overX) > 110f) {
                                            // Pages mode: a sideways swipe past the page edge turns the page.
                                            flipped = true
                                            if (overX < 0f) state.showPage(zp + 1, atTop = true) else state.showPage(zp - 1, atTop = true)
                                            overX = 0f
                                        }
                                    } else {
                                        // Scrolling mode: the zoomed view flows from page to page like one long sheet.
                                        state.scrollBy(pan)
                                    }
                                }
                                if (pressed == 1) event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressed >= 2) {
                            pinch *= zoomChange
                            if (pinch > 1.04f && state.viewport.width > 0) {
                                state.pageAt(centroid.y)?.let { (page, fy) ->
                                    state.enterZoom(page, pinch, centroid, centroid.x / state.viewport.width, fy)
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (state.zoomed && state.zoom < state.minZoom() * 1.08f) {
                        state.exitZoom()
                    } else if (state.zoomed && !state.paged && !multiTouch && moved > slop && !currentReduced) {
                        // Let go with speed: keep scrolling and slow down smoothly.
                        val v = tracker.calculateVelocity()
                        flingJob = scope.launch {
                            val vx = v.x
                            val vy = v.y
                            launch {
                                var last = 0f
                                AnimationState(0f, vx).animateDecay(exponentialDecay()) {
                                    val d = value - last
                                    last = value
                                    state.scrollBy(Offset(d, 0f))
                                }
                            }
                            var last = 0f
                            AnimationState(0f, vy).animateDecay(exponentialDecay()) {
                                val d = value - last
                                last = value
                                state.scrollBy(Offset(0f, d))
                            }
                        }
                    }
                }
            }
            .pointerInput(handle) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        if (state.paged && state.zoom <= state.minZoom() * 1.05f) state.zoomAround(2.5f / state.zoom, pos)
                        else if (state.zoomed) state.exitZoom()
                        else state.pageAt(pos.y)?.let { (page, fy) ->
                            state.enterZoom(page, 2.5f, pos, pos.x / size.width, fy)
                        }
                    },
                    onTap = { pos ->
                        val x = pos.x / size.width
                        // A tap-to-turn mode still needs a way to reach the toolbar: the middle of the screen.
                        if (currentTapToTurn && x < 0.28f) turnPage(-1)
                        else if (currentTapToTurn && x > 0.72f) turnPage(1)
                        else currentOnTapCenter()
                    }
                )
            }
    ) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        SideEffect { state.viewport = IntSize(viewportW, viewportH) }
        val density = LocalDensity.current

        // Pages mode shows one fitted page at a time; scrolling mode shows the list. Follows the reader's setting.
        LaunchedEffect(tapToTurn, viewportW, viewportH, handle.pageCount) {
            state.viewport = IntSize(viewportW, viewportH)
            state.pageCount = handle.pageCount
            state.setPaged(tapToTurn)
        }

        if (state.zoomPage == null) {
            LaunchedEffect(state.pendingScroll) {
                state.pendingScroll?.let {
                    state.listState.scrollToItem(it.coerceIn(0, handle.pageCount - 1), state.pendingScrollOffset)
                    state.pendingScroll = null
                    state.pendingScrollOffset = 0
                }
            }
            LazyColumn(
                state = state.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(handle.pageCount, key = { it }) { index ->
                    PdfPageItem(
                        handle = handle,
                        index = index,
                        viewportW = viewportW,
                        viewportH = viewportH,
                        fitPage = state.fitPage,
                        paper = if (theme == ReaderTheme.NIGHT || theme == ReaderTheme.NIGHT_CONTRAST) Color.Black else Color.White,
                        filter = filter,
                        density = density.density
                    )
                }
            }
        } else {
            ZoomedPage(handle, state, filter, viewportW, viewportH)
        }
    }
}

/**
 * The zoomed view: one page in pages mode, or the pages around the middle of the screen in scrolling mode so they
 * scroll into each other. Each draws a stand-in immediately and swaps in the sharp render of the visible part when ready.
 */
@OptIn(FlowPreview::class)
@Composable
private fun ZoomedPage(
    handle: PageSource,
    state: PdfViewState,
    filter: ColorFilter?,
    viewportW: Int,
    viewportH: Int
) {
    val sharps = remember(handle) { mutableStateMapOf<Int, RegionBitmap>() }
    val standIns = remember(handle) { mutableStateMapOf<Int, Bitmap>() }
    val visible by remember(state) { derivedStateOf { state.slots().map { it.first } } }

    // Learn the real page proportions (a page may never have been shown in the list) and load the stand-ins.
    LaunchedEffect(handle, visible) {
        val wanted = (visible + visible.minOrNull()?.minus(1) + visible.maxOrNull()?.plus(1))
            .filterNotNull().filter { it in 0 until handle.pageCount }.distinct()
        for (p in wanted) {
            try {
                val (w, h) = handle.pageSize(p)
                if (p == state.zoomPage) state.updateRatio(p, h.toFloat() / w.coerceAtLeast(1)) else state.relayout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
            if (standIns[p] == null) {
                val bmp = PdfBitmaps.anyFor(p) ?: try {
                    handle.render(p, 720).also { PdfBitmaps.cache.put("$p@720", it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                if (bmp != null) standIns[p] = bmp
            }
        }
        // Keep a handful around the current pages so scrolling back never shows a blank page.
        val centre = visible.firstOrNull() ?: 0
        while (standIns.size > 8) {
            val far = standIns.keys.maxByOrNull { abs(it - centre) } ?: break
            standIns.remove(far)
        }
    }

    // Re-render the visible region shortly after the gesture settles; the old image stays on screen meanwhile.
    LaunchedEffect(handle, viewportW, viewportH) {
        snapshotFlow { Triple(state.zoomPage, state.zoom, state.offset) }
            .debounce(90)
            .collectLatest { (current, z, off) ->
                if (current == null) return@collectLatest
                val slots = state.slots()
                val mid = viewportH / 2f
                for ((p, y) in slots.sortedBy { (pg, top) -> abs(top + viewportW * z * state.ratioOf(pg) / 2f - mid) }) {
                    try {
                        val (pointsW, _) = handle.pageSize(p)
                        val scale = viewportW.toFloat() / pointsW.coerceAtLeast(1) * z
                        val bmp = handle.renderRegion(p, viewportW, viewportH, scale, off.x, y)
                        sharps[p] = RegionBitmap(bmp, p, z, Offset(off.x, y))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Out of memory or a renderer hiccup: keep showing the previous image.
                    }
                }
                val keep = slots.map { it.first }.toSet()
                sharps.keys.retainAll(keep)
            }
    }

    Canvas(Modifier.fillMaxSize()) {
        val z = state.zoom
        val off = state.offset
        val pageW = viewportW * z
        for ((p, y) in state.slots()) {
            val pageH = pageW * state.ratioOf(p)
            (standIns[p] ?: PdfBitmaps.anyFor(p))?.takeIf { !it.isRecycled }?.let { bmp ->
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(off.x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(pageW.roundToInt().coerceAtLeast(1), pageH.roundToInt().coerceAtLeast(1)),
                    colorFilter = filter
                )
            }
            sharps[p]?.let { r ->
                val k = z / r.zoom
                // A rendered region is a screen-sized picture; only the part that lies on its own page may be drawn,
                // otherwise its blank margin would paint over the neighbouring page.
                clipRect(off.x, y, off.x + pageW, y + pageH) {
                    drawImage(
                        image = r.image,
                        dstOffset = IntOffset((off.x - r.offset.x * k).roundToInt(), (y - r.offset.y * k).roundToInt()),
                        dstSize = IntSize((viewportW * k).roundToInt().coerceAtLeast(1), (viewportH * k).roundToInt().coerceAtLeast(1)),
                        colorFilter = filter
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    handle: PageSource,
    index: Int,
    viewportW: Int,
    viewportH: Int,
    fitPage: Boolean,
    paper: Color,
    filter: ColorFilter?,
    density: Float
) {
    val ratio by produceState(initialValue = handle.cachedPageRatio(index) ?: PdfViewState.DEFAULT_RATIO, handle, index) {
        try {
            val (w, h) = handle.pageSize(index)
            value = h.toFloat() / w.coerceAtLeast(1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
        }
    }
    val widthPx = if (fitPage) minOf(viewportW.toFloat(), viewportH / ratio).toInt() else viewportW
    // Render a little above screen resolution, rounded to 32px buckets so layout changes don't re-render.
    val renderWidth = (((widthPx * 1.3f).toInt() + 31) / 32) * 32
    val bitmap by produceState<Bitmap?>(null, handle, index, renderWidth) {
        val key = "$index@$renderWidth"
        val cached = PdfBitmaps.cache.get(key)
        if (cached != null && !cached.isRecycled) {
            value = cached
        } else {
            val rendered = try {
                handle.render(index, renderWidth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (rendered != null) {
                PdfBitmaps.cache.put(key, rendered)
                value = rendered
            }
        }
    }
    // Never leave a page blank: fall back to any other render of the same page while the right one is made.
    val shown = bitmap ?: PdfBitmaps.anyFor(index)
    val widthDp = (widthPx / density).dp
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(widthDp)
                .aspectRatio(1f / ratio)
                .background(paper)
                .semantics { contentDescription = "Page ${index + 1} of ${handle.pageCount}" }
        ) {
            shown?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = filter
                )
            }
        }
    }
}

/** Thumbnail page grid used to jump around a document. */
@Composable
fun PdfPagesGrid(
    handle: PageSource,
    currentPage: Int,
    bookmarkedPages: Set<Int>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items((0 until handle.pageCount).toList(), key = { it }) { index ->
            val thumb by produceState<Bitmap?>(null, handle, index) {
                val key = "t$index"
                val cached = PdfBitmaps.cache.get(key)
                if (cached != null) value = cached else {
                    val bmp = try {
                        handle.render(index, 240)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) PdfBitmaps.cache.put(key, bmp)
                    value = bmp
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val selected = index == currentPage
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .background(Color.White)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                        .clickable(onClickLabel = "Go to page ${index + 1}") { onPick(index) }
                ) {
                    thumb?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Text(
                    text = if (index in bookmarkedPages) "${index + 1} ★" else "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
