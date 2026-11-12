package com.scrollstop.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject

/**
 * Instagram, YouTube, Facebook, X, Reddit and LinkedIn inside ScrollStop, like Sociallite's focused
 * browser: the website is shown with the Reels / Explore / Shorts buttons removed completely, reels,
 * ads and suggested posts taken out of the feed, and the blocked pages can't be opened.
 *
 * Links shared to ScrollStop (or opened with it) show just that post or video: watching the one a
 * friend sent is fine, swiping on to the next one isn't.
 */
class WebAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_URL = "url"

        /** [startUrl] opens a page other than the site's home, e.g. Instagram messages. */
        fun intent(ctx: Context, site: WebSite, startUrl: String? = null): Intent =
            Intent(ctx, WebAppActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("scrollstop://${site.key}"))
                .putExtra(EXTRA_SITE, site.key)
                .apply { if (startUrl != null) putExtra(EXTRA_URL, startUrl) }
    }

    private lateinit var site: WebSite
    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private lateinit var web: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvInfo: TextView
    private lateinit var root: LinearLayout
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastUrl: String? = null
    private var hiddenAds = 0
    private var hiddenSuggested = 0
    private var hiddenReels = 0
    private var closed = false
    private var currentScript = ""
    private var earlyScript = false
    private var scriptHandler: ScriptHandler? = null
    /** Path of a link that was shared or opened in ScrollStop; that one item is allowed. */
    private var sharedPath: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            stats.add(Stats.appMs(site.pkg), 1_000)
            stats.add(Stats.webMs(site.pkg), 1_000)
            if (checkClosed()) return
            updateInfo()
            handler.postDelayed(this, 1_000)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        stats = Stats.get(this)
        val incoming = when (intent.action) {
            Intent.ACTION_SEND -> WebRules.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
            else -> null
        }
        val fromLink = incoming?.let { WebSite.forUrl(it) }
        if (incoming != null && fromLink == null) {
            Toast.makeText(this, "ScrollStop can open Instagram, YouTube, Facebook, X, Reddit and LinkedIn links",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        site = fromLink ?: WebSite.byKey(intent.getStringExtra(EXTRA_SITE) ?: intent.data?.host) ?: WebSite.INSTAGRAM
        if (fromLink != null) sharedPath = WebRules.cleanPath(incoming!!)
        title = site.title
        buildViews()
        if (checkClosed()) return
        setupWeb()
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) {
            val asked = intent.getStringExtra(EXTRA_URL)?.takeIf { WebSite.forUrl(it) == site }
            val start = incoming?.takeIf { fromLink != null } ?: asked ?: site.home
            web.loadUrl(WebRules.feedRedirect(site, start, prefs.webIgFeed) ?: start)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ---------------- layout ----------------

    private fun buildViews() {
        val ui = Ui(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                this@WebAppActivity, android.R.attr.colorBackground, 0))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        ui.button(bar, "✕", Ui.Btn.TEXT) { finish() }.minWidth = ui.dp(48)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.text(texts, "${site.title} · ScrollStop",
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        tvInfo = ui.text(texts, "", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
        tvInfo.maxLines = 1
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ui.button(bar, "↻", Ui.Btn.TEXT) { web.reload() }.minWidth = ui.dp(48)
        ui.button(bar, "⚙", Ui.Btn.TEXT) { showSiteSettings() }.minWidth = ui.dp(48)
        root.addView(bar)
        progress = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            trackThickness = ui.dp(2)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2)))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            insets
        }
    }

    private fun updateInfo() {
        val parts = mutableListOf("${Fmt.duration(stats.get(Stats.appMs(site.pkg)))} today")
        val limit = prefs.appLimits[site.pkg]
        if (limit != null && limit > 0) {
            parts += "${Fmt.duration((limit * 60_000L - stats.get(Stats.appMs(site.pkg))).coerceAtLeast(0))} left"
        }
        if (hiddenReels > 0) parts += "$hiddenReels reels"
        if (prefs.webHideAds || hiddenAds > 0) parts += "$hiddenAds ads"
        if ((prefs.webHideSuggested && site != WebSite.YOUTUBE) || hiddenSuggested > 0) parts += "$hiddenSuggested suggested"
        tvInfo.text = parts.joinToString(" · ") +
            if (prefs.webHideAds || prefs.webHideSuggested || hiddenReels > 0) " hidden" else ""
    }

    // ---------------- web view ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        // Look like normal mobile Chrome: some sites strip features from in-app browsers.
        s.userAgentString = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "ScrollStop")
        earlyScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        installScript()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url.toString(), fromHistory = false)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Instagram and YouTube change pages without reloading, so check every address change.
                if (handleNavigation(url, fromHistory = true)) return
                lastUrl = url
                if (!earlyScript) view.evaluateJavascript(currentScript, null)
                else view.evaluateJavascript("window.__scrollstop && window.__scrollstop.run()", null)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.INVISIBLE
                if (!earlyScript) view.evaluateJavascript(currentScript, null)
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page crashed; close this screen instead of taking ScrollStop down with it.
                finish()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFile.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    /** (Re)builds the page script from the current settings. */
    private fun installScript() {
        currentScript = focusScript()
        if (earlyScript) {
            // Runs before the page draws, so the Reels button never flashes up.
            scriptHandler?.remove()
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, currentScript, site.hosts.flatMap {
                listOf("https://$it", "https://*.$it")
            }.toSet())
        }
    }

    // ---------------- settings panel (like Sociallite's) ----------------

    private fun showSiteSettings() {
        val ui = Ui(this)
        val sheet = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(24))
        }
        ui.title(box, site.title).setPadding(0, ui.dp(4), 0, ui.dp(4))
        var changed = false
        fun sw(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit, weakensWhen: Boolean = false) {
            ui.switchRow(box, title, sub, get()) { on, revert ->
                if (on == weakensWhen && prefs.isLocked()) {
                    revert()
                    Toast.makeText(this, "Strict mode is on: blocking can't be loosened", Toast.LENGTH_SHORT).show()
                } else {
                    set(on)
                    changed = true
                }
            }
        }
        when (site) {
            WebSite.INSTAGRAM -> {
                sw("Block Reels", "Removes the Reels button and reel pages.",
                    { prefs.blocked(Platform.INSTAGRAM) }, { prefs.setBlocked(Platform.INSTAGRAM, it) })
                sw("Block suggested posts", "Only posts from people you follow. No \"Suggested for you\".",
                    { prefs.webHideSuggested }, { prefs.webHideSuggested = it })
                sw("Block ads", "Sponsored posts and paid partnerships.", { prefs.webHideAds }, { prefs.webHideAds = it })
                sw("Hide reels in the feed", null, { prefs.webHideFeedReels }, { prefs.webHideFeedReels = it })
                sw("Block Explore & post search", "Searching for people still works.",
                    { prefs.blockIgExplore }, { prefs.blockIgExplore = it })
                sw("Messages only", "Hides the home feed.", { prefs.webIgMessagesOnly }, { prefs.webIgMessagesOnly = it })
                val names = linkedMapOf("following" to "Following", "favorites" to "Favorites", "home" to "Home")
                ui.navRow(box, "Feed", names[prefs.webIgFeed]) {
                    val keys = names.keys.toList()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Instagram feed")
                        .setSingleChoiceItems(arrayOf("Following: people you follow, newest first", "Favorites only",
                            "Home: Instagram's mix"), keys.indexOf(prefs.webIgFeed)) { d, i ->
                            if (keys[i] == "home" && prefs.webIgFeed != "home" && prefs.isLocked()) {
                                Toast.makeText(this, "Strict mode is on: blocking can't be loosened", Toast.LENGTH_SHORT).show()
                            } else if (keys[i] != prefs.webIgFeed) {
                                prefs.webIgFeed = keys[i]
                                changed = true
                            }
                            d.dismiss()
                            sheet.dismiss()
                        }
                        .show()
                }
            }
            WebSite.YOUTUBE -> {
                sw("Block Shorts", null, { prefs.blocked(Platform.YOUTUBE) }, { prefs.setBlocked(Platform.YOUTUBE, it) })
                sw("Search-only mode", "Hides the Home feed.", { prefs.blockYtHome }, { prefs.blockYtHome = it })
                sw("Block ads", null, { prefs.webHideAds }, { prefs.webHideAds = it })
                sw("Block Explore & Trending", null, { prefs.webBlockDiscover }, { prefs.webBlockDiscover = it })
            }
            WebSite.FACEBOOK -> {
                sw("Block Reels", null, { prefs.blocked(Platform.FACEBOOK) }, { prefs.setBlocked(Platform.FACEBOOK, it) })
                sw("Block Video / Watch", null, { prefs.blockFbWatch }, { prefs.blockFbWatch = it })
                sw("Block suggested posts", null, { prefs.webHideSuggested }, { prefs.webHideSuggested = it })
                sw("Block ads", null, { prefs.webHideAds }, { prefs.webHideAds = it })
            }
            else -> {
                sw("Block suggested posts", null, { prefs.webHideSuggested }, { prefs.webHideSuggested = it })
                sw("Block ads", null, { prefs.webHideAds }, { prefs.webHideAds = it })
                sw("Block Explore / Popular", null, { prefs.webBlockDiscover }, { prefs.webBlockDiscover = it })
            }
        }
        ui.small(box, "These apply to ScrollStop's ${site.title}. Strict mode only lets you make them stricter.")
            .setPadding(0, ui.dp(8), 0, 0)
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        sheet.setOnDismissListener {
            if (changed) {
                hiddenAds = 0
                hiddenSuggested = 0
                hiddenReels = 0
                installScript()
                val url = web.url ?: site.home
                web.loadUrl(WebRules.feedRedirect(site, url, prefs.webIgFeed) ?: url)
                updateInfo()
            }
        }
        sheet.show()
    }

    /** Returns true if the navigation was stopped. */
    private fun handleNavigation(url: String, fromHistory: Boolean): Boolean {
        val active = prefs.isActiveNow()
        val cfg = if (active) prefs.urlConfig() else UrlRules.Config(
            false, false, false, false, false, false, false, false
        )
        WebRules.feedRedirect(site, url, prefs.webIgFeed)?.let { other ->
            handler.post { web.loadUrl(other) }
            return true
        }
        val d = WebRules.decide(
            site, url, lastUrl, cfg, prefs.allowInstagramDm,
            blockDiscover = active && prefs.webBlockDiscover, sharedPath = sharedPath,
        )
        // Once you leave the shared post, it's no longer special.
        if (sharedPath != null && d is WebRules.Decision.Allow && WebRules.cleanPath(url) != sharedPath &&
            WebSite.forUrl(url) == site
        ) sharedPath = null
        return when (d) {
            WebRules.Decision.Allow -> false
            WebRules.Decision.Ignore -> true
            WebRules.Decision.External -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    // nothing can open it
                }
                if (fromHistory) goBackOrHome()
                true
            }
            is WebRules.Decision.Block -> {
                stats.recordBlock(site.statKey)
                Toast.makeText(this, "${d.label} blocked", Toast.LENGTH_SHORT).show()
                if (fromHistory) goBackOrHome()
                true
            }
        }
    }

    private fun goBackOrHome() {
        handler.post {
            web.stopLoading()
            if (web.canGoBack()) web.goBack() else web.loadUrl(site.home)
        }
    }

    private fun focusScript(): String {
        val active = prefs.isActiveNow()
        val ads = prefs.webHideAds
        val suggested = prefs.webHideSuggested
        val discover = active && prefs.webBlockDiscover
        val cfg = JSONObject()
        val raw: Int
        when (site) {
            WebSite.INSTAGRAM -> {
                raw = R.raw.focus_instagram
                val reels = active && prefs.blocked(Platform.INSTAGRAM)
                cfg.put("reels", reels)
                cfg.put("explore", active && prefs.blockIgExplore)
                cfg.put("feedReels", reels && prefs.webHideFeedReels)
                cfg.put("ads", ads)
                cfg.put("suggested", suggested)
                cfg.put("home", active && prefs.webIgMessagesOnly)
            }
            WebSite.YOUTUBE -> {
                raw = R.raw.focus_youtube
                cfg.put("shorts", active && prefs.blocked(Platform.YOUTUBE))
                cfg.put("ads", ads)
                cfg.put("home", active && prefs.blockYtHome)
            }
            else -> {
                raw = R.raw.focus_generic
                val g = genericConfig(site, active, discover)
                cfg.put("hide", org.json.JSONArray(g.hide))
                cfg.put("item", g.item)
                if (ads) cfg.put("ad", g.ad)
                if (suggested) cfg.put("suggested", g.suggested)
            }
        }
        val body = resources.openRawResource(raw).bufferedReader().use { it.readText() }
        return "var SS_CFG = $cfg;\n$body"
    }

    private class Generic(val hide: List<String>, val item: String, val ad: String, val suggested: String)

    /** What to take out of Facebook, X, Reddit and LinkedIn. */
    private fun genericConfig(site: WebSite, active: Boolean, discover: Boolean): Generic = when (site) {
        WebSite.FACEBOOK -> Generic(
            hide = buildList {
                if (active && prefs.blocked(Platform.FACEBOOK)) {
                    addAll(listOf("a[href^=\"/reel\"]", "a[href*=\"facebook.com/reel\"]", "a[aria-label^=\"Reels\"]"))
                }
                if (active && prefs.blockFbWatch) addAll(listOf("a[href^=\"/watch\"]", "a[aria-label^=\"Video\"]"))
                if (discover) add("a[href^=\"/gaming\"]")
            },
            item = "article, div[role=\"article\"], div[data-tracking-duration-id]",
            ad = "^\\s*(Sponsored|Sponsorisé|Gesponsert|Patrocinado)\\s*$",
            suggested = "^\\s*(Suggested for you|Reels and short videos|Reels|People you may know|Follow)\\s*$",
        )
        WebSite.X -> Generic(
            hide = if (discover) listOf("a[href=\"/explore\"]", "a[data-testid=\"AppTabBar_Explore_Link\"]") else emptyList(),
            item = "div[data-testid=\"cellInnerDiv\"]",
            ad = "^\\s*(Ad|Promoted)\\s*$",
            suggested = "^\\s*(Who to follow|You might like|Trending now|What’s happening|What's happening)\\s*$",
        )
        WebSite.REDDIT -> Generic(
            hide = buildList {
                if (prefs.webHideAds) addAll(listOf("shreddit-ad-post", "shreddit-comments-page-ad", "shreddit-dynamic-ad-link"))
                if (discover) addAll(listOf("a[href^=\"/r/popular\"]", "a[href^=\"/r/all\"]", "a[href^=\"/explore\"]"))
            },
            item = "shreddit-post, article",
            ad = "^\\s*Promoted\\s*$",
            suggested = "^\\s*(Suggested for you|Because you visited.*|Popular on Reddit)\\s*$",
        )
        else -> Generic(
            hide = emptyList(),
            item = "div.feed-shared-update-v2, div[data-id^=\"urn:li:activity\"], div[data-urn^=\"urn:li:activity\"]",
            ad = "^\\s*Promoted( by .*)?\\s*$",
            suggested = "^\\s*(Suggested|Recommended for you)\\s*$",
        )
    }

    /** Called from the page script when it hides something. */
    private inner class Bridge {
        @JavascriptInterface
        fun hidden(kind: String) {
            handler.post {
                when (kind) {
                    "ad" -> hiddenAds++
                    "suggested" -> hiddenSuggested++
                    "reel" -> hiddenReels++
                }
                updateInfo()
            }
        }
    }

    // ---------------- limits ----------------

    /** Closes this screen during sleep hours or once the app's daily limit is used up. Returns true if closed. */
    private fun checkClosed(): Boolean {
        if (closed) return true
        val reason = when {
            prefs.isSleeping() -> "Sleep mode is on until ${Fmt.clock(prefs.sleepEnd)}."
            !prefs.isActiveNow() -> null
            prefs.appLimits[site.pkg] == 0 -> "${site.title} is blocked."
            prefs.appLimits[site.pkg]?.let { AppLimits.isOver(it, stats.get(Stats.appMs(site.pkg))) } == true ->
                "You've used your ${Fmt.duration(prefs.appLimits[site.pkg]!! * 60_000L)} of ${site.title} for today."
            else -> null
        } ?: return false
        closed = true
        handler.removeCallbacks(ticker)
        if (::web.isInitialized) web.onPause()
        if (!isFinishing) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Time's up")
                .setMessage(reason)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        }
        return true
    }

    // ---------------- fullscreen video ----------------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        (window.decorView as FrameLayout).removeView(v)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return
        web.onResume()
        if (!checkClosed()) {
            updateInfo()
            handler.postDelayed(ticker, 1_000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (::web.isInitialized) web.onPause()
        stats.flush()
        ScrollStopWidget.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileCallback?.onReceiveValue(null)
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
