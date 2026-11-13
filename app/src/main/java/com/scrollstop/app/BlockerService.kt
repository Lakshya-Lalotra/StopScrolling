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

        /** Set from Help → "Something isn't blocked?": save the next social-app screen's layout. */
        @Volatile private var captureAskedAt = 0L
        fun requestCapture() {
            captureAskedAt = SystemClock.elapsedRealtime()
        }
        private const val SYSTEM_UI = "com.android.systemui"
    }

    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private lateinit var hides: HideOverlays
    private lateinit var sampler: ColorSampler
    private lateinit var blockScreen: BlockScreen
    private lateinit var curtain: Curtain
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
    private var captureSeconds = 0

    /** Removing a post through Instagram's ⋯ menu: 0 = waiting for the menu, 1 = ad reason, 2 = done. */
    private class Removal(val kind: FeedKind, val startedAt: Long) {
        var step = 0
        var stepAt = startedAt
    }
    private var removal: Removal? = null
    private val removalTimes = ArrayDeque<Long>()
    private val handled = HashMap<String, Long>()
    private var dmAuthor: String? = null
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
        curtain = Curtain(this)
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
        curtain.hide()
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
        if (platform == Platform.INSTAGRAM && removal != null) {
            continueRemoval(root)
            return
        }
        val a = Rules.analyze(platform, pkg, root, prefs, screen, feed)
        if (platform == Platform.INSTAGRAM && prefs.igUseScrollStop) {
            openScrollStopInstagram(if (a.dmThread) "https://www.instagram.com/direct/inbox/" else null)
            return
        }
        // Best: ask the app itself to remove the ad / suggested post, like tapping "Not interested" by hand.
        if (platform == Platform.INSTAGRAM && prefs.igRemovePosts && !blockScreen.isShowing) {
            a.feedTargets.firstOrNull { canRemove(it.kind, it.account) }?.let {
                startRemoval(pkg, it)
                return
            }
        }
        if (platform == Platform.FACEBOOK) a.dismissButton?.let { dismissFacebook(it) }
        hides.show(pkg, if (blockScreen.isShowing || imeVisible()) emptyList() else colorize(pkg, a))
        if (a.feedSkip != null && a.feedList != null) skipFeedPost(a.feedSkip, a.feedList, a.feedNode)
        if (a.dmThread) lastDmSeenAt = SystemClock.elapsedRealtime()

        a.closeNow?.let { what ->
            endWatching()
            block("$what blocked", null, platform.key, pkg, GLOBAL_ACTION_BACK)
            return
        }
        if (a.shortVideo) onShortVideo(platform, pkg, a) else endWatching()
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
            val spot = h.sample ?: if (h.text == null && !h.scrolls) h.rect else null
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
    private fun skipFeedPost(by: Int, list: Rect, listNode: AccessibilityNodeInfo?) {
        if (!prefs.igSkipHidden) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFeedScrollAt < 400 || now - lastSkipAt < 1_200 || feed.direction < 0 || imeVisible()) return
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
        val sent = try {
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
            false
        }
        // No permission to swipe yet (needs the service switched off and on once after updating):
        // ask the feed itself to scroll a screen down instead.
        if (!sent) runCatching { listNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
    }

    // ---------------- removing posts the way a person would ----------------

    private fun canRemove(kind: FeedKind, account: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFeedScrollAt < 350) return false // wait until the feed is still
        while (removalTimes.isNotEmpty() && now - removalTimes.first() > 60_000) removalTimes.removeFirst()
        if (removalTimes.size >= 8) return false // at most 8 a minute, like a person
        handled["$kind|$account"]?.let { if (now - it < 10 * 60_000) return false }
        return true
    }

    /** Opens the post's ⋯ menu behind a plain curtain; [continueRemoval] picks the option. */
    private fun startRemoval(pkg: String, t: FeedTarget) {
        val now = SystemClock.elapsedRealtime()
        removalTimes.addLast(now)
        handled["${t.kind}|${t.account}"] = now
        removal = Removal(t.kind, now)
        hides.clear()
        curtain.show(sampler.colorForWidth(pkg, screenRect().width()) ?: sampler.appColor(pkg)
            ?: ContextCompat.getColor(this, R.color.hide_bg))
        if (!click(t.more)) tap(t.more)
        for (d in listOf(250L, 600L, 1_000L, 1_500L, 2_200L, 3_100L)) {
            h.postDelayed({ if (removal != null) evaluate() }, d)
        }
    }

    private fun continueRemoval(root: AccessibilityNodeInfo) {
        val r = removal ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - r.startedAt > 3_000) {
            endRemoval(false, root)
            return
        }
        if (r.step == 0) {
            Rules.findLabel(root) { FeedLabels.isRemoveOption(it, r.kind) }?.let { option ->
                if (!click(option)) tap(option)
                r.step = if (r.kind == FeedKind.AD) 1 else 2
                r.stepAt = now
            }
        } else if (r.step == 1) {
            val reason = Rules.findLabel(root) { FeedLabels.isAdReason(it) }
            if (reason != null) {
                if (!click(reason)) tap(reason)
                r.step = 2
            } else if (now - r.stepAt > 900) {
                r.step = 2 // Instagram didn't ask why: the ad is already gone
            }
        }
        if (r.step == 2) endRemoval(true, root)
    }

    private fun endRemoval(ok: Boolean, root: AccessibilityNodeInfo?) {
        removal = null
        // If the menu is still open (the option wasn't found), close it without touching anything else.
        if (!ok && root != null && Rules.findLabel(root) { FeedLabels.isMenuHint(it) } != null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (ok) stats.add(Stats.FEED_REMOVED, 1)
        h.postDelayed({
            curtain.hide()
            scheduleEval()
        }, if (ok) 350 else 100)
    }

    /** Facebook: taps the ✕ / Hide button on a sponsored or suggested post, so Facebook removes it. */
    private fun dismissFacebook(button: AccessibilityNodeInfo) {
        val now = SystemClock.elapsedRealtime()
        if (!canRemove(FeedKind.SUGGESTED, "fb:${Rules.bounds(button).top / 50}")) return
        removalTimes.addLast(now)
        if (click(button) || tap(button)) stats.add(Stats.FEED_REMOVED, 1)
    }

    /** Clicks [n] or the nearest clickable element around it, the way a tap would. */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops < 6) {
            if (c.isClickable) return runCatching { c.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            c = c.parent
            hops++
        }
        return false
    }

    /** A real tap on the middle of [n], for buttons that ignore [click]. */
    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val b = Rules.bounds(n)
        if (b.isEmpty) return false
        val p = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        return runCatching {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
        }.getOrDefault(false)
    }

    private fun onAppChanged(pkg: String) {
        fgPkg = pkg
        feed.reset()
        if (removal != null) {
            removal = null
            curtain.hide()
        }
        hides.clear()
        endWatching()
        if (blockScreen.isShowing && pkg != blockScreen.forPkg && pkg !in launchers &&
            pkg != packageName && pkg != SYSTEM_UI
        ) blockScreen.dismiss()
    }

    // ---------------- short videos ----------------

    private fun onShortVideo(p: Platform, pkg: String, a: Analysis) {
        val enforce = prefs.blocked(p)
        if (watching == p && dmSession && dmAuthor != null && a.reelAuthor != null && a.reelAuthor != dmAuthor) {
            // Swiped from the friend's reel on to someone else's: that's the Reels feed again.
            newVideo()
            return
        }
        if (watching != p) {
            endWatching()
            // A reel opened from a DM shows a reply box; it stays allowed until the author changes.
            val fromDm = p == Platform.INSTAGRAM && prefs.allowInstagramDm &&
                (a.reelFromDm || SystemClock.elapsedRealtime() - lastDmSeenAt < 6_000)
            dmAuthor = if (fromDm) a.reelAuthor else null
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
        dmAuthor = null
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
        maybeCapture(pkg, now)
        watching?.let { if (!dmSession) stats.add(Stats.shortMs(it), dt) }
        // Re-check limits and allowances, and make sure covers never stay on top of another app.
        if ((pkg != null && isInteresting(pkg)) || hides.isShowing) evaluate()
        maybeFlush(now)
        if (now - lastMinuteAt >= 60_000) {
            lastMinuteAt = now
            everyMinute()
        }
    }

    /** After the user asks for it, saves the layout of a social app screen 3 seconds after it opens. */
    private fun maybeCapture(pkg: String?, now: Long) {
        if (captureAskedAt == 0L) return
        if (now - captureAskedAt > 10 * 60_000L) {
            captureAskedAt = 0L
            return
        }
        if (pkg == null || Platform.of(pkg) == null) {
            captureSeconds = 0
            return
        }
        if (++captureSeconds < 3) return
        captureAskedAt = 0L
        captureSeconds = 0
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val file = runCatching { LayoutDump.write(this, root, screenRect()) }.getOrNull()
        if (file == null) {
            toast("Couldn't save the layout")
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "ScrollStop screen layout")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("layout", uri)
        val chooser = Intent.createChooser(send, "Share the screen layout")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Notifier.layoutSaved(this, chooser)
        toast("Screen layout saved. Check your notifications to share it.")
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
