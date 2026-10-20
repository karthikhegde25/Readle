package com.readle.app.reader.pdf

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Anything made of fixed pages that the page viewer can show: a PDF, or a comic archive of page images.
 * Sizes are in the source's own units (PDF points, or image pixels); only their proportions matter to the viewer.
 */
interface PageSource : Closeable {
    val pageCount: Int

    /** Page size (width, height). */
    suspend fun pageSize(index: Int): Pair<Int, Int>

    /** Height/width of a page if it has already been measured, else null (no I/O). */
    fun cachedPageRatio(index: Int): Float?

    /**
     * A [viewW] x [viewH] bitmap showing the page scaled by [scale] (pixels per size unit) with the page's
     * top-left corner at ([tx], [ty]) in bitmap pixels. Areas outside the page are transparent.
     */
    suspend fun renderRegion(index: Int, viewW: Int, viewH: Int, scale: Float, tx: Float, ty: Float): Bitmap

    /** The whole page as a bitmap [widthPx] wide, on a white background. */
    suspend fun render(index: Int, widthPx: Int): Bitmap
}
