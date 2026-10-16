package com.readle.app.reader.reflow

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.readle.app.BuildConfig
import kotlinx.coroutines.flow.Flow

/**
 * Hosts the chapter WebView. All reading logic lives in the ViewModel; this composable only
 * translates [PageSpec]s and [ReflowCommand]s into WebView calls and forwards page events.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReflowReaderView(
    book: ReflowBook,
    spec: PageSpec,
    commands: Flow<ReflowCommand>,
    listener: ReflowListener,
    modifier: Modifier = Modifier
) {
    val currentListener by rememberUpdatedState(listener)
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Delegates through the latest listener so recompositions never leave a stale callback behind.
    val proxy = remember(book) {
        object : ReflowListener {
            override fun onScroll(fraction: Float) = currentListener.onScroll(fraction)
            override fun onPages(page: Int, total: Int) = currentListener.onPages(page, total)
            override fun onTap(x: Float) = currentListener.onTap(x)
            override fun onSelection(text: String, start: Int, end: Int) = currentListener.onSelection(text, start, end)
            override fun onSelectionCleared() = currentListener.onSelectionCleared()
            override fun onNavigate(next: Boolean) = currentListener.onNavigate(next)
            override fun onInternalJump(fractionBefore: Float) = currentListener.onInternalJump(fractionBefore)
            override fun onHighlightTap(id: Long) = currentListener.onHighlightTap(id)
            override fun onReady() = currentListener.onReady()
            override fun onTtsText(text: String, visibleStart: Int, lastVisible: Boolean) = currentListener.onTtsText(text, visibleStart, lastVisible)
            override fun onPageResult(dir: Int, result: String) = currentListener.onPageResult(dir, result)
            override fun onChapterLink(spine: Int, fragment: String?) = currentListener.onChapterLink(spine, fragment)
            override fun onExternalLink(url: String) = currentListener.onExternalLink(url)
        }
    }
    val client = remember(book) { WebBookClient(book, proxy) }

    LaunchedEffect(webView, commands) {
        val view = webView ?: return@LaunchedEffect
        commands.collect { cmd ->
            when (cmd) {
                is ReflowCommand.PageBy -> view.evaluateJavascript(
                    "(window.readle?window.readle.pageBy(${cmd.dir}):'none')"
                ) { raw -> currentListener.onPageResult(cmd.dir, raw.trim('"')) }
                is ReflowCommand.ScrollFraction -> view.evaluateJavascript(
                    "if(window.readle)window.readle.scrollToFraction(${cmd.fraction});", null
                )
                is ReflowCommand.SetStyle -> {
                    view.setBackgroundColor(cmd.background)
                    view.evaluateJavascript("if(window.readle)window.readle.setStyle('${ReaderCss.forJsString(cmd.css)}');", null)
                }
                is ReflowCommand.Highlights -> view.evaluateJavascript(
                    "if(window.readle)window.readle.applyHighlights(${cmd.json});", null
                )
                is ReflowCommand.Find -> view.evaluateJavascript(
                    "if(window.readle)window.readle.findText(${HtmlInjector.jsonString(cmd.query)},${cmd.nth});", null
                )
                ReflowCommand.ClearFind -> view.evaluateJavascript("if(window.readle)window.readle.clearFind();", null)
                ReflowCommand.ClearSelection -> view.evaluateJavascript("if(window.readle)window.readle.clearSelection();", null)
                is ReflowCommand.TtsPrepare -> view.evaluateJavascript(
                    "(window.readle?window.readle.ttsPrepare(${cmd.lastOffset}):'')"
                ) { raw ->
                    try {
                        // The result is a JSON string literal that itself contains JSON.
                        val inner = org.json.JSONTokener(raw).nextValue() as? String
                        if (!inner.isNullOrEmpty()) {
                            val o = org.json.JSONObject(inner)
                            currentListener.onTtsText(o.optString("t"), o.optInt("o", 0), o.optBoolean("v", false))
                        } else currentListener.onTtsText("", 0, false)
                    } catch (_: Exception) {
                        currentListener.onTtsText("", 0, false)
                    }
                }
                is ReflowCommand.TtsMark -> view.evaluateJavascript("if(window.readle)window.readle.ttsMark(${cmd.start},${cmd.end});", null)
                ReflowCommand.TtsClear -> view.evaluateJavascript("if(window.readle)window.readle.ttsClear();", null)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Hardened settings: no file or content access, no DOM storage, no zoom, no mixed content.
                settings.apply {
                    javaScriptEnabled = true // required for the Readle page script; book scripts are blocked by CSP
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    textZoom = 100 // text size is controlled by Readle's own setting, not the system one
                    setGeolocationEnabled(false)
                }
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                webViewClient = client
                addJavascriptInterface(ReaderJsBridge(proxy), ReaderJsBridge.NAME)
                if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                webView = this
            }
        },
        update = { view ->
            view.setBackgroundColor(spec.background)
            // Always keep the latest css/highlights for any chapter request; only a new token reloads.
            client.spec = spec
            if (view.tag != spec.token) {
                view.tag = spec.token
                view.loadUrl(client.urlFor(spec.target.spine))
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.removeJavascriptInterface(ReaderJsBridge.NAME)
            view.destroy()
        }
    )
}
