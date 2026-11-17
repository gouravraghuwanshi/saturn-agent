package io.github.pimobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One WebView per task, created on demand when pi first uses the browser.
 * WebViews stay attached (behind the UI) while not on screen so pages keep
 * running and screenshots keep working.
 */
object BrowserManager {
    class Page(val taskId: String, val view: WebView) {
        var url by mutableStateOf("about:blank")
        var title by mutableStateOf("")
        var progress by mutableStateOf(100)
        internal var targetId: String? = null
    }

    val pages = mutableStateMapOf<String, Page>()
    /** Task whose browser is on screen (the rest render in the hidden host). */
    var shown by mutableStateOf<String?>(null)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Must happen before the first WebView is created: exposes DevTools
        // on this app's private abstract socket (bridged by BrowserBridge).
        WebView.setWebContentsDebuggingEnabled(true)
        BrowserBridge.start()
    }

    /** Called from the bridge thread: ensures the task's WebView and returns its DevTools target id. */
    fun targetFor(taskId: String): String {
        val page = onMain { pages[taskId] ?: create(taskId) }
        page.targetId?.let { return it }
        val marker = "pimobile-$taskId"
        // The marker title lets us match this WebView among DevTools targets.
        repeat(50) {
            val targets = BrowserBridge.listTargets()
            for (i in 0 until targets.length()) {
                val t = targets.getJSONObject(i)
                if (t.optString("title") == marker || t.optString("url").contains(marker)) {
                    page.targetId = t.getString("id")
                    return t.getString("id")
                }
            }
            Thread.sleep(100)
        }
        error("browser target for $taskId not found")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(taskId: String): Page {
        val view = WebView(MutableContextWrapper(app)).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val page = Page(taskId, view)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { page.url = url; page.title = v.title.orEmpty() }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { page.url = url }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { page.progress = p }
            override fun onReceivedTitle(v: WebView, t: String?) { page.title = t.orEmpty() }
        }
        view.loadDataWithBaseURL(null, "<title>pimobile-$taskId</title>", "text/html", "utf-8", null)
        pages[taskId] = page
        return page
    }

    fun close(taskId: String) = main.post {
        pages.remove(taskId)?.view?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post { result = runCatching(block); latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "main thread busy" }
        return result!!.getOrThrow()
    }
}
