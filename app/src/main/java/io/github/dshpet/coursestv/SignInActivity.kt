package io.github.dshpet.coursestv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.isVisible
import java.io.ByteArrayInputStream

/**
 * The website with its sign-in dialog opened, usable with the remote through `site.js`. Credentials
 * and cookies stay in the WebView. Closes once the site reports the customer signed in.
 */
class SignInActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var page: SitePage
    private lateinit var loading: View
    private lateinit var error: MessageOverlay

    private var isActive = false
    private var requestedUrl: String? = null
    private var refusedUrl: String? = null
    private var rendererGone = false
    private var tokenDeadline = 0L
    private val backCallback = OnBackInvokedCallback { handleBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = createWebView()
        page = SitePage(webView) { isActive && !error.isOpen && Hosts.isSite(webView.url) }
        loading = loadingOverlay(this, "Opening the sign-in page…")
        error = MessageOverlay(this, "The sign-in page couldn’t load", focusBehind = webView, "Retry" to ::retry, "Return to home" to ::loadHome)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            listOf(webView, loading, error.view).forEach { addView(it, MATCH_PARENT, MATCH_PARENT) }
        })
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        webView.loadUrl(HOME_URL)
    }

    override fun onResume() {
        super.onResume()
        if (rendererGone) return
        isActive = true
        webView.onResume()
        if (!error.isOpen) webView.requestFocus()
        page.startPrompting()
    }

    override fun onPause() {
        isActive = false
        page.stopPrompting()
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onDestroy() {
        page.stopPrompting()
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        webView.apply {
            removeCallbacks(tokenCheck)
            if (!rendererGone) stopLoading()
            webViewClient = WebViewClient()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.setNeedInitialFocus(false)

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = blockTracker(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (request.url.scheme.equals("https", ignoreCase = true)) {
                    requestedUrl = request.url.toString()
                    return false
                }
                showError("This link can’t open on the TV.")
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                page.stopPrompting()
                if (!error.isOpen) loading.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView, url: String) = pageShown(url)

            override fun onPageFinished(view: WebView, url: String) = pageShown(url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError(LOAD_ERROR)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == view.url || error.url == requestedUrl) {
                    refusedUrl = error.url
                    showError("A secure connection couldn’t be made. Check that the TV’s date and time are right and the network is working, then try again.")
                }
            }

            // The renderer was ended, usually to free memory: offer to start over instead of crashing.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone = true
                page.stopPrompting()
                (view.parent as? ViewGroup)?.removeView(view)
                showError(LOAD_ERROR)
                return true
            }
        }
    }

    private fun pageShown(url: String) {
        loading.visibility = View.GONE
        page.install(url)
        page.startPrompting()
        page.signedIn { signedIn -> if (signedIn) awaitToken() }
    }

    // The site fetches its API token just after signing in; close once it is stored (or after a
    // few seconds without it), or the screen behind would find no session.
    private fun awaitToken() {
        if (tokenDeadline == 0L) tokenDeadline = SystemClock.uptimeMillis() + TOKEN_WAIT_MILLIS
        webView.removeCallbacks(tokenCheck)
        tokenCheck.run()
    }

    private val tokenCheck = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (WebViewCookies.token != null || SystemClock.uptimeMillis() >= tokenDeadline) done() else webView.postDelayed(this, TOKEN_CHECK_MILLIS)
        }
    }

    private fun done() {
        CookieManager.getInstance().flush()
        finish()
    }

    // Back closes the sign-in dialog first (an extra Back meant for the keyboard must not lose what
    // was typed), then leaves.
    private fun handleBack() {
        if (rendererGone) return finish()
        if (error.isOpen) {
            refusedUrl = null
            error.hide()
            page.startPrompting()
            return
        }
        page.back { closed ->
            if (isDestroyed) return@back
            if (closed) page.dismiss() else finish()
        }
    }

    private fun showError(message: String) {
        page.stopPrompting()
        loading.visibility = View.GONE
        error.show(message)
    }

    private fun retry() {
        if (rendererGone) return recreate()
        // A refused page never loaded, so a reload would repeat the page before it.
        val refused = refusedUrl
        refusedUrl = null
        error.hide()
        loading.visibility = View.VISIBLE
        if (refused != null) webView.loadUrl(refused) else webView.reload()
    }

    private fun loadHome() {
        if (rendererGone) return recreate()
        refusedUrl = null
        error.hide()
        loading.visibility = View.VISIBLE
        webView.loadUrl(HOME_URL)
    }

    private companion object {
        const val HOME_URL = "${Hosts.SITE}/home"
        const val LOAD_ERROR = "Check your connection, then try again."
        const val TOKEN_CHECK_MILLIS = 250L
        const val TOKEN_WAIT_MILLIS = 8_000L
    }
}

/**
 * Installs `site.js` and opens the sign-in dialog whenever the page shows its Sign In button, until
 * the user closes it. Checks every 0.5 s; pauses after 10 s without a button until the next page or resume.
 */
private class SitePage(private val webView: WebView, private val canPrompt: () -> Boolean) {

    private val script by lazy { webView.context.assets.open("site.js").bufferedReader().use { it.readText() } }
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { check() }
    private var prompting = true
    private var misses = 0
    private var generation = 0

    /** Idempotent: the script installs once per document. */
    fun install(url: String?) {
        if (Hosts.isSite(url)) webView.evaluateJavascript(script, null)
    }

    fun startPrompting() {
        misses = 0
        if (prompting) schedule(0)
    }

    fun stopPrompting() {
        generation++
        handler.removeCallbacks(check)
    }

    /** The user closed the dialog: leave the page alone. */
    fun dismiss() {
        prompting = false
        stopPrompting()
    }

    /** Closes the site's sign-in dialog; reports whether there was one. */
    fun back(onResult: (closed: Boolean) -> Unit) = call("back()") { onResult(it == "\"handled\"") }

    /** The site sets `isCustomerLoggedIn` on every page. */
    fun signedIn(onResult: (Boolean) -> Unit) {
        if (!Hosts.isSite(webView.url)) return onResult(false)
        webView.evaluateJavascript("Number(window.isCustomerLoggedIn || 0)") { onResult(it == "1") }
    }

    private fun check() {
        if (!prompting || !canPrompt()) return
        val expected = generation
        call("status()") { result ->
            if (expected != generation || !prompting || !canPrompt()) return@call
            when (result.removeSurrounding("\"")) {
                "ready" -> {
                    misses = 0
                    call("openSignIn()") {}
                }
                "consent", "open", "opening" -> misses = 0 // the cookie banner and the dialog come first
                else -> misses++
            }
            if (misses < MAX_MISSES) schedule(CHECK_MILLIS)
        }
    }

    private fun schedule(delayMillis: Long) {
        stopPrompting()
        handler.postDelayed(check, delayMillis)
    }

    /** Calls a `site.js` method; the result is its JSON value, "null" when there is none. */
    private fun call(method: String, onResult: (String) -> Unit) {
        if (!Hosts.isSite(webView.url)) return onResult("null")
        webView.evaluateJavascript("window.__coursesTvSite?.$method ?? null") { onResult(it ?: "null") }
    }

    private companion object {
        const val CHECK_MILLIS = 500L
        const val MAX_MISSES = 20
    }
}

// Analytics, advertising and widget hosts the site loads on every page. None is needed to sign in,
// and on a Chromecast they cost CPU and memory.
private val TRACKERS = listOf(
    "adobedtm.com", "omtrdc.net", "demdex.net", "go-mpulse.net", "akstat.io",
    "acsbap.com", "acsbapp.com", "tiktok.com", "pinterest.com", "pinimg.com",
    "googletagmanager.com", "google-analytics.com", "googleadservices.com", "doubleclick.net",
    "facebook.net", "facebook.com", "bazaarvoice.com", "nr-data.net", "newrelic.com",
    "tealiumiq.com", "amazon-adsystem.com", "slgnt.us", "emsecure.net",
)

private fun blockTracker(request: WebResourceRequest): WebResourceResponse? {
    val host = request.url.host
    if (request.isForMainFrame || host == null || TRACKERS.none { host == it || host.endsWith(".$it") }) return null
    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

private val SCRIM = Palette.background.copy(alpha = 0.9f).toArgb()

private fun loadingOverlay(context: Context, message: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setBackgroundColor(SCRIM)
    addView(ProgressBar(context).apply { isIndeterminate = true })
    addView(TextView(context).apply {
        text = message
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(0, context.dp(18), 0, 0)
    })
}

/** A card with a title, a message and buttons over a scrim; on hiding, the focus returns to [focusBehind]. */
private class MessageOverlay(context: Context, title: String, private val focusBehind: View, vararg actions: Pair<String, () -> Unit>) {

    val view = FrameLayout(context)
    val isOpen: Boolean get() = view.isVisible

    private val body = TextView(context).apply {
        setTextColor(Palette.dim.toArgb())
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, context.dp(12), 0, context.dp(28))
    }

    private val buttons = actions.map { (label, action) ->
        val focused = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(ColorStateList(focused, intArrayOf(Palette.onFocus.toArgb(), Color.WHITE)))
            backgroundTintList = ColorStateList(focused, intArrayOf(Palette.focus.toArgb(), 0xFF264760.toInt()))
            setOnClickListener { action() }
        }
    }

    init {
        view.isVisible = false
        view.setBackgroundColor(SCRIM)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(36), context.dp(32), context.dp(36), context.dp(28))
            background = GradientDrawable().apply {
                setColor(Palette.surface.toArgb())
                cornerRadius = context.dp(10).toFloat()
            }
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            })
            addView(body)
            buttons.forEachIndexed { index, button ->
                addView(button, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(54)).apply { if (index > 0) topMargin = context.dp(12) })
            }
        }
        view.addView(card, FrameLayout.LayoutParams(context.dp(600), WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            marginStart = context.dp(48)
            marginEnd = context.dp(48)
        })
    }

    fun show(message: String) {
        body.text = message
        if (isOpen) return
        view.isVisible = true
        buttons.firstOrNull()?.requestFocus()
    }

    fun hide() {
        if (!isOpen) return
        val hadFocus = view.hasFocus()
        view.isVisible = false
        if (hadFocus) focusBehind.requestFocus()
    }
}
