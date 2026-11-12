package com.scrollstop.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sign

/**
 * Watches the screen and:
 *  - closes Reels / Shorts / Spotlight (Back) once the daily allowance is used up,
 *  - covers the Reels/Shorts tab buttons, Instagram Explore and YouTube's Home feed,
 *  - closes the same feeds when opened in a web browser,
 *  - sends you Home from apps that are blocked or over their daily limit,
 *  - in strict mode, keeps you out of the settings screens that would switch it off or uninstall it,
 *  - counts time and videos for the Stats tab.
 */
class BlockerService : AccessibilityService() {

    companion object {
        @Volatile var instance: BlockerService? = null
            private set
        val isRunning get() = instance != null
        private const val SYSTEM_UI = "com.android.systemui"
    }

    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private lateinit var hides: HideOverlays
    private lateinit var sampler: ColorSampler
    private lateinit var blockScreen: BlockScreen
    private lateinit var power: PowerManager
    private val h = Handler(Looper.getMainLooper())

    private var appLabel = "ScrollStop"
    private var launchers: Set<String> = emptySet()
    @Volatile private var limits: Map<String, Int> = emptyMap()

    private var evalPending = false
    private val evalRunnable = Runnable { evalPending = false; evaluate() }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            h.postDelayed(this, 1000)
        }
    }

    /** App in front, as last seen. */
    private var fgPkg: String? = null

    // Short-video session state
    private var watching: Platform? = null
    private var dmSession = false
    private var lastDmSeenAt = 0L
    private var countMode = 0 // 0 = unknown, 1 = list index, 2 = scroll distance, 3 = time-based guess
    private var pagerMax = -1
    private var scrollAcc = 0
    private var lastGuessAt = 0L

    private var lastBlockAt = 0L
    private var lastGuardAt = 0L
    private var guardStrikes = 0
    private var lastTickAt = 0L
    private var lastFlushAt = 0L
    private var lastMinuteAt = 0L
    private var lastRedirectAt = 0L
    private val feed = FeedMemory()
    private var lastFeedScrollAt = 0L
    private var lastSkipAt = 0L
    private var sleepRemindedDay = ""
    private val warned = HashSet<String>()
    private var lastToast: Toast? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "app_limits") limits = prefs.appLimits
        scheduleEval()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            hides.clear()
            endWatching()
            stats.flush()
        }
    }

    // ---------------- lifecycle ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs.get(this)
        stats = Stats.get(this)
        hides = HideOverlays(this)
        sampler = ColorSampler(this) { scheduleEval() }
        blockScreen = BlockScreen(this)
        power = getSystemService(PowerManager::class.java)
        appLabel = applicationInfo.loadLabel(packageManager).toString()
        launchers = findLaunchers()
        limits = prefs.appLimits
        prefs.everEnabled = true
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        instance = this
        h.post(ticker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance == null) return
        instance = null
        h.removeCallbacksAndMessages(null)
        hides.clear()
        blockScreen.dismiss()
        runCatching { unregisterReceiver(screenReceiver) }
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        stats.flush()
    }

    override fun onInterrupt() {}

    // ---------------- events ----------------

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || instance == null) return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == SYSTEM_UI) {
                    hides.clear() // notification shade, volume panel, recents...
                    return
                }
                evaluate()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg == "com.instagram.android") onFeedScrolled(e)
                onScrolled(e, pkg)
                if (isInteresting(pkg)) scheduleEval()
            }
            else -> if (isInteresting(pkg)) scheduleEval()
        }
    }

    private fun isInteresting(pkg: String): Boolean =
        Platform.of(pkg) != null || pkg in Apps.TIKTOK || pkg in Apps.BROWSER_URL_BARS ||
            pkg in limits || pkg in Apps.SETTINGS

    private fun isSocialApp(pkg: String): Boolean =
        Platform.of(pkg) != null || pkg in Apps.TIKTOK || pkg in limits

    private fun isTracked(pkg: String): Boolean =
        Platform.of(pkg) != null || pkg in Apps.TIKTOK || pkg in limits

    /** Content changes arrive many times a second while a video plays; look at most every 150 ms. */
    private fun scheduleEval() {
        if (evalPending) return
        evalPending = true
        h.postDelayed(evalRunnable, 150)
    }

    private fun evaluate() {
        h.removeCallbacks(evalRunnable)
        evalPending = false
        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow
        } catch (e: RuntimeException) {
            null
        } ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != fgPkg) onAppChanged(pkg)
        if (pkg == packageName || pkg == SYSTEM_UI) {
            hides.clear()
            endWatching()
            return
        }
        val screen = screenRect()

        // Strict-mode protection works even during breaks and outside the schedule.
        if (pkg in Apps.SETTINGS) {
            hides.clear()
            endWatching()
            if (prefs.guardSettings && prefs.isLocked() &&
                Rules.isProtectedSettingsScreen(pkg, root, appLabel, screen)
            ) guard()
            return
        }

        // Sleep mode closes social apps whatever else is set (except during a break).
        if (prefs.isSleeping() && isSocialApp(pkg)) {
            hides.clear()
            endWatching()
            block("Sleep mode", "Social apps are closed until ${Fmt.clock(prefs.sleepEnd)}. Sleep well.",
                "sleep", pkg, GLOBAL_ACTION_HOME)
            return
        }

        if (!prefs.isActiveNow()) {
            hides.clear()
            endWatching()
            return
        }

        appBlockReason(pkg)?.let { (title, detail) ->
            hides.clear()
            endWatching()
            block(title, detail, "app", pkg, GLOBAL_ACTION_HOME)
            return
        }

        Apps.BROWSER_URL_BARS[pkg]?.let { ids ->
            hides.clear()
            endWatching()
            if (prefs.blockBrowsers) checkBrowser(pkg, root, ids)
            return
        }

        val platform = Platform.of(pkg) ?: run {
            hides.clear()
            endWatching()
            return
        }
        val a = Rules.analyze(platform, pkg, root, prefs, screen, feed)
        if (platform == Platform.INSTAGRAM && prefs.igUseScrollStop) {
            openScrollStopInstagram(if (a.dmThread) "https://www.instagram.com/direct/inbox/" else null)
            return
        }
        hides.show(pkg, if (blockScreen.isShowing || imeVisible()) emptyList() else colorize(pkg, a))
        if (a.feedSkip != null && a.feedList != null) skipFeedPost(a.feedSkip, a.feedList)
        if (a.dmThread) lastDmSeenAt = SystemClock.elapsedRealtime()

        a.closeNow?.let { what ->
            endWatching()
            block("$what blocked", null, platform.key, pkg, GLOBAL_ACTION_BACK)
            return
        }
        if (a.shortVideo) onShortVideo(platform, pkg) else endWatching()
    }

    /**
     * Paints each cover in the app's own background colour so a hidden tab looks removed.
     * Until that colour is known the tab cover is see-through (still blocks taps) for a moment
     * while one screenshot is taken.
     */
    private fun colorize(pkg: String, a: Analysis): List<Hide> {
        val missing = ArrayList<Rect>()
        val out = a.hides.map { h ->
            // Tab buttons are measured on themselves, feed covers on their post's header row.
            val spot = h.sample ?: if (h.text == null) h.rect else null
            if (spot != null) {
                val c = sampler.tabColor(pkg, spot)
                when {
                    c != null -> h.copy(color = c)
                    // Don't measure while the Reels/Shorts tab itself is open: it may be highlighted.
                    sampler.worthTrying(pkg, spot) && !a.shortVideo -> {
                        missing += spot
                        h.copy(color = Color.TRANSPARENT)
                    }
                    else -> h.copy(color = sampler.appColor(pkg))
                }
            } else if (h.scrolls) {
                h.copy(color = sampler.colorForWidth(pkg, h.rect.width()) ?: sampler.appColor(pkg))
            } else {
                h.copy(color = sampler.appColor(pkg))
            }
        }
        if (missing.isNotEmpty()) sampler.sample(pkg, missing)
        return out
    }

    /**
     * Swaps the Instagram app for ScrollStop's Instagram, where ads, suggested posts and Reels are
     * removed completely (the app's own feed can only be covered, never edited).
     */
    private fun openScrollStopInstagram(url: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < 3_000) return
        lastRedirectAt = now
        hides.clear()
        endWatching()
        // Send the app to the background first, so leaving ScrollStop's version doesn't land back in it.
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({
            if (instance == null) return@postDelayed
            runCatching {
                startActivity(WebAppActivity.intent(this, WebSite.INSTAGRAM, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, 250)
    }

    /**
     * Scrolls the Instagram feed past a hidden ad or suggested post once you've stopped on it, with a
     * slow drag that comes to rest (so the feed doesn't fling on further).
     */
    private fun skipFeedPost(by: Int, list: Rect) {
        if (!prefs.igSkipHidden) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFeedScrollAt < 700 || now - lastSkipAt < 1_500 || feed.direction < 0 || imeVisible()) return
        lastSkipAt = now
        val x = list.exactCenterX()
        val startY = list.top + list.height() * 0.85f
        val endY = maxOf(list.top + 20f, startY - by)
        val move = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val duration = (300 + (startY - endY) / 4).toLong().coerceAtMost(700)
        val drag = GestureDescription.StrokeDescription(move, 0, duration, true)
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(drag).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    val rest = Path().apply {
                        moveTo(x, endY)
                        lineTo(x, endY + 1)
                    }
                    runCatching {
                        dispatchGesture(GestureDescription.Builder()
                            .addStroke(drag.continueStroke(rest, 0, 150, false)).build(), null, null)
                    }
                }
            }, null)
        } catch (e: RuntimeException) {
            // gestures not allowed (switch the service off and on once after updating)
        }
    }

    private fun onAppChanged(pkg: String) {
        fgPkg = pkg
        feed.reset()
        hides.clear()
        endWatching()
        if (blockScreen.isShowing && pkg != blockScreen.forPkg && pkg !in launchers &&
            pkg != packageName && pkg != SYSTEM_UI
        ) blockScreen.dismiss()
    }

    // ---------------- short videos ----------------

    private fun onShortVideo(p: Platform, pkg: String) {
        val enforce = prefs.blocked(p)
        if (watching != p) {
            endWatching()
            val fromDm = p == Platform.INSTAGRAM && prefs.allowInstagramDm &&
                SystemClock.elapsedRealtime() - lastDmSeenAt < 6_000
            if (enforce && !fromDm && !hasRoom(p, extraVideo = 1)) {
                blockFeed(p, pkg)
                return
            }
            watching = p
            dmSession = fromDm
            if (!fromDm) stats.add(Stats.shortCount(p), 1)
        } else if (enforce && !dmSession && !hasRoom(p, extraVideo = 0)) {
            endWatching()
            blockFeed(p, pkg)
            return
        }
        if (enforce && !dmSession) warnIfLow(p)
    }

    /** Called when the user swipes on to the next video. */
    private fun newVideo() {
        val p = watching ?: return
        dmSession = false // the reel from a DM is over; normal rules for the next one
        if (prefs.blocked(p) && !hasRoom(p, extraVideo = 1)) {
            endWatching()
            blockFeed(p, fgPkg)
            return
        }
        stats.add(Stats.shortCount(p), 1)
        if (prefs.blocked(p)) warnIfLow(p)
    }

    private fun hasRoom(p: Platform, extraVideo: Int): Boolean =
        prefs.allowance(p).hasRoom(stats.get(Stats.shortMs(p)), stats.get(Stats.shortCount(p)) + extraVideo)

    private fun blockFeed(p: Platform, pkg: String?) {
        val a = prefs.allowance(p)
        val detail = if (a.isNone) null else "You've used today's ${p.feed} allowance (${a.describe()})."
        block("${p.label} blocked", detail, p.key, pkg, GLOBAL_ACTION_BACK)
    }

    private fun endWatching() {
        watching = null
        dmSession = false
        countMode = 0
        pagerMax = -1
        scrollAcc = 0
    }

    private fun onFeedScrolled(e: AccessibilityEvent) {
        lastFeedScrollAt = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dy = e.scrollDeltaY
        if (abs(dy) <= 1 || abs(dy) > 5_000) return
        feed.direction = if (dy > 0) 1 else -1
        if (hides.isShowing) hides.shift(dy)
    }

    private fun onScrolled(e: AccessibilityEvent, pkg: String) {
        val p = watching ?: return
        if (Platform.of(pkg) != p) return
        val src = try {
            e.source
        } catch (ex: RuntimeException) {
            null
        } ?: return
        val screenH = screenRect().height()
        val srcH = Rules.bounds(src).height()
        val id = src.viewIdResourceName
        val pager = when {
            Rules.isVideoPager(id) -> true
            id != null && (p == Platform.INSTAGRAM || p == Platform.YOUTUBE) -> false // known apps: IDs only
            else -> srcH >= screenH * 0.75
        }
        if (!pager) return

        // 1) Lists report which item is showing: count each new item reached.
        if (countMode <= 1 && e.fromIndex >= 0 && e.itemCount > 0) {
            countMode = 1
            if (pagerMax < 0) pagerMax = e.fromIndex
            else if (e.fromIndex > pagerMax) {
                val n = (e.fromIndex - pagerMax).coerceAtMost(5)
                pagerMax = e.fromIndex
                repeat(n) { if (watching != null) newVideo() }
            }
            return
        }
        if (countMode == 1) return
        // 2) Otherwise add up how far the pager moved: one page height = one video.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dy = e.scrollDeltaY
            if (abs(e.scrollDeltaX) > 1 && abs(dy) <= 1) return // sideways swipe
            if (abs(dy) > 1 && (countMode == 0 || countMode == 2)) {
                countMode = 2
                val page = srcH.coerceAtLeast(screenH / 2)
                scrollAcc += dy
                while (abs(scrollAcc) >= page / 2 && watching != null) {
                    scrollAcc -= scrollAcc.sign * page
                    newVideo()
                }
                return
            }
            if (countMode == 2) return
        }
        // 3) No details at all: assume one swipe per burst of scroll events.
        countMode = 3
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuessAt > 1_200) {
            lastGuessAt = now
            newVideo()
        }
    }

    private fun warnIfLow(p: Platform) {
        val a = prefs.allowance(p)
        val day = stats.day()
        a.msLeft(stats.get(Stats.shortMs(p)))?.let { left ->
            if (left in 1..60_000 && warned.add("$day|t|${p.key}")) toast("About 1 minute of ${p.label} left today")
        }
        a.videosLeft(stats.get(Stats.shortCount(p)))?.let { left ->
            if (left == 0L && warned.add("$day|n|${p.key}")) toast("That's your last ${p.feed} video for today")
        }
    }

    // ---------------- other blocks ----------------

    /** (title, detail) when [pkg] is blocked completely or over its daily limit. */
    private fun appBlockReason(pkg: String): Pair<String, String?>? {
        if (pkg in Apps.TIKTOK && prefs.blockTiktok) return "TikTok is blocked" to null
        val limit = limits[pkg] ?: return null
        if (limit == 0) return "${appName(pkg)} is blocked" to null
        return if (AppLimits.isOver(limit, stats.get(Stats.appMs(pkg))))
            "Time's up for ${appName(pkg)}" to "You've used your ${Fmt.duration(limit * 60_000L)} for today."
        else null
    }

    private fun checkBrowser(pkg: String, root: AccessibilityNodeInfo, ids: List<String>) {
        for (id in ids) {
            val bar = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
            } catch (e: RuntimeException) {
                null
            } ?: continue
            if (bar.isFocused) return // the user is typing an address
            val what = UrlRules.match(bar.text?.toString(), prefs.urlConfig()) ?: return
            block("$what blocked", "Blocked in the browser too.", "web", pkg, GLOBAL_ACTION_BACK)
            return
        }
    }

    private fun guard() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuardAt < 800) return
        guardStrikes = if (now - lastGuardAt < 4_000) guardStrikes + 1 else 1
        lastGuardAt = now
        performGlobalAction(if (guardStrikes >= 3) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        toast("Strict mode is on. $appLabel can't be switched off or removed right now.")
    }

    private fun block(title: String, detail: String?, statKey: String, pkg: String?, action: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1_500) return
        lastBlockAt = now
        performGlobalAction(action)
        stats.recordBlock(statKey)
        val today = stats.get(Stats.BLOCKS)
        if (prefs.showBlockScreen) {
            val msg = prefs.customMessage.ifBlank { BlockScreen.QUOTES.random() }
            val saved = stats.get(Stats.BLOCKS) * Stats.MINUTES_SAVED_PER_BLOCK * 60_000L
            val line = "$today blocked today · about ${Fmt.duration(saved)} saved"
            h.postDelayed({
                if (instance != null) {
                    hides.clear()
                    blockScreen.show(title, listOfNotNull(detail, msg).joinToString("\n\n"), line, pkg)
                }
            }, 300)
        } else {
            toast("$title · $today today")
        }
    }

    // ---------------- once a second ----------------

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTickAt == 0L) 1_000L else (now - lastTickAt).coerceIn(0L, 2_000L)
        lastTickAt = now
        if (!power.isInteractive) {
            endWatching()
            maybeFlush(now)
            return
        }
        val pkg = fgPkg
        if (pkg != null && isTracked(pkg)) stats.add(Stats.appMs(pkg), dt)
        watching?.let { if (!dmSession) stats.add(Stats.shortMs(it), dt) }
        // Re-check limits and allowances, and make sure covers never stay on top of another app.
        if ((pkg != null && isInteresting(pkg)) || hides.isShowing) evaluate()
        maybeFlush(now)
        if (now - lastMinuteAt >= 60_000) {
            lastMinuteAt = now
            everyMinute()
        }
    }

    /** Bedtime heads-up and widget refresh. */
    private fun everyMinute() {
        ScrollStopWidget.refresh(this)
        val remind = prefs.sleepReminder
        if (remind <= 0) return
        val left = Sleep.minutesToBedtime(prefs.sleepWindow(), prefs.isoToday(), prefs.minuteNow()) ?: return
        val day = stats.day()
        if (left <= remind && sleepRemindedDay != day) {
            sleepRemindedDay = day
            Notifier.sleepSoon(this, left)
            toast("Sleep mode starts in $left min")
        }
    }

    private fun maybeFlush(now: Long) {
        if (now - lastFlushAt >= 10_000) {
            lastFlushAt = now
            stats.flush()
        }
    }

    // ---------------- helpers ----------------

    private fun screenRect(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun imeVisible(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (e: RuntimeException) {
        false
    }

    private fun findLaunchers(): Set<String> = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    } catch (e: RuntimeException) {
        emptySet()
    }

    private fun appName(pkg: String): String = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun toast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
}
