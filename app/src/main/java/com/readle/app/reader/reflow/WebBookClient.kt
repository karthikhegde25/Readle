package com.readle.app.reader.reflow

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * Serves a [ReflowBook] to a WebView under a private origin (https://readle.local/book/...) and keeps
 * the page sandboxed:
 *  - every chapter is rewritten to carry Readle's CSS/script and a strict Content-Security-Policy,
 *  - requests to any other host are blocked (the page can never reach the network),
 *  - link clicks are routed back to native code instead of being followed by the WebView.
 */
class WebBookClient(
    private val book: ReflowBook,
    private val listener: ReflowListener,
    /** Resolves ("theme" | "font", generated file name) to a stored file; only the user's own imports resolve. */
    private val userFiles: ((String, String) -> java.io.File?)? = null
) : WebViewClient() {

    /** The chapter that is (about to be) shown. Written on the main thread, read on the IO thread. */
    @Volatile
    var spec: PageSpec? = null

    /** Document-like formats (DOCX, RTF) can be pinch-zoomed and turned by dragging past a chapter end. */
    @Volatile
    var zoomable: Boolean = false

    @Volatile
    private var currentPath: String = ""

    private val random = SecureRandom()

    fun urlFor(spine: Int): String {
        val path = book.spine.getOrNull(spine)?.path ?: book.spine.first().path
        return BASE + path.split('/').joinToString("/") { Uri.encode(it) }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != HOST || url.scheme != "https") return blocked()
        val encoded = url.encodedPath.orEmpty()
        if (encoded.startsWith(ReaderCss.USER_PREFIX)) return userFile(encoded.removePrefix(ReaderCss.USER_PREFIX))
        if (!encoded.startsWith(PREFIX)) return notFound()
        val path = PathUtil.normalize(PathUtil.decode(encoded.removePrefix(PREFIX)))
        val resource = try {
            book.open(path)
        } catch (e: Exception) {
            null
        } ?: return notFound()

        val isChapter = book.spineIndexOf(path) >= 0 && (Mime.isHtml(path) || resource.mime == "text/html")
        if (!isChapter) {
            val mime = resource.mime
            // Documents that are not spine chapters (e.g. a stray HTML file) are served inert, as plain text.
            val safeMime = if (mime == "text/html") "text/plain" else mime
            return WebResourceResponse(safeMime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), resource.stream)
        }

        val page = spec ?: return notFound()
        return try {
            val raw = resource.stream.use { it.readBounded(MAX_CHAPTER_BYTES) }
            val nonce = newNonce()
            val html = HtmlInjector.inject(
                rawHtml = XmlUtil.decodeText(raw),
                css = page.css,
                script = ReaderScript.JS,
                initJson = HtmlInjector.initJson(
                    target = page.target,
                    isFirst = book.spineIndexOf(path) == 0,
                    isLast = book.spineIndexOf(path) == book.spine.lastIndex,
                    highlightsJson = page.highlightsJson,
                    zoomable = zoomable
                ),
                nonce = nonce,
                zoomable = zoomable
            )
            currentPath = path
            WebResourceResponse(
                "text/html", "utf-8", 200, "OK",
                mapOf(
                    "Content-Security-Policy" to HtmlInjector.contentSecurityPolicy(nonce, HOST),
                    "Cache-Control" to "no-store"
                ),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        } catch (e: BookOpenException) {
            WebResourceResponse("text/plain", "utf-8", 413, "Too large", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } catch (e: Exception) {
            notFound()
        }
    }

    /** Serves one imported font or theme picture. Anything that isn't a generated, listed file name is a 404. */
    private fun userFile(rest: String): WebResourceResponse {
        val parts = rest.split('/')
        if (parts.size != 2) return notFound()
        val file = try { userFiles?.invoke(parts[0], parts[1]) } catch (e: Exception) { null } ?: return notFound()
        val mime = if (parts[0] == "theme") "image/jpeg" else com.readle.app.domain.CustomRules.fontMime(file.extension.lowercase()) ?: return notFound()
        return try {
            WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "private, max-age=86400"), file.inputStream())
        } catch (e: Exception) {
            notFound()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == HOST && url.scheme == "https") {
            val encoded = url.encodedPath.orEmpty()
            if (!encoded.startsWith(PREFIX)) return true
            val path = PathUtil.normalize(PathUtil.decode(encoded.removePrefix(PREFIX)))
            if (path == currentPath) return false // same-chapter anchor: let the browser scroll
            val idx = book.spineIndexOf(path)
            if (idx >= 0) listener.onChapterLink(idx, url.fragment?.let { PathUtil.decode(it) })
            return true
        }
        val scheme = url.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "mailto") listener.onExternalLink(url.toString())
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "readle.local"
        const val PREFIX = "/book/"
        const val BASE = "https://$HOST$PREFIX"
        private const val MAX_CHAPTER_BYTES = 24 * 1024 * 1024
    }
}
