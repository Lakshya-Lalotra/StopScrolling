package com.scrollstop.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Something to cover with an overlay, e.g. the Reels tab button or the Explore grid.
 * [color] is the cover's colour (null = default for light/dark mode, transparent while it's being measured).
 */
data class Hide(val rect: Rect, val text: String? = null, val color: Int? = null)

/** What ScrollStop found on the current screen of a supported app. */
data class Analysis(
    /** A short-video viewer/feed of this app is open. */
    val shortVideo: Boolean = false,
    /** Something to close right away with Back (Stories viewer, Facebook Video tab...). */
    val closeNow: String? = null,
    /** Regions to cover. */
    val hides: List<Hide> = emptyList(),
    /** An Instagram direct-message thread is on screen. */
    val dmThread: Boolean = false,
)

/**
 * Detection rules for each app. Apps rename their internal view IDs from time to time;
 * if a block stops working, add the new ID to the matching list below (find IDs with the
 * "Developer Assistant" app or Android Studio's Layout Inspector) and rebuild.
 */
object Rules {

    // ---------- Instagram ----------
    private val IG_REEL_IDS = listOf(
        "clips_viewer_view_pager",
        "clips_viewer_container",
        "clips_video_container",
        "root_clips_layout",
        "clips_ufi_component",
        "clips_single_media_component",
    )
    private val IG_REEL_PAGER_IDS = setOf("clips_viewer_view_pager", "clips_viewer_container")
    private val IG_DM_IDS = listOf(
        "direct_thread_toolbar",
        "row_thread_composer_edittext",
        "thread_title",
        "direct_thread_composer",
    )
    private val IG_STORY_IDS = listOf(
        "reel_viewer_root",
        "reel_viewer_container",
        "reel_viewer_media_container",
        "reel_viewer_image_view",
    )
    private val IG_SEARCH_IDS = listOf(
        "action_bar_search_edit_text",
        "action_bar_search_hint_text",
        "search_edit_text",
    )

    // ---------- YouTube ----------
    private val YT_SHORTS_IDS = listOf(
        "reel_recycler",
        "reel_player_page_container",
        "reel_watch_player",
        "reel_time_bar",
        "shorts_container",
        "reel_watch_fragment_root",
    )
    private val YT_SHORTS_PAGER_IDS = setOf("reel_recycler")
    private val YT_PLAYER_IDS = listOf("watch_player", "player_view", "watch_while_layout", "mini_player")

    // ---------- Facebook / Snapchat (few stable IDs, so labels are used) ----------
    private val FB_REELS_TAB = Regex("(?i)^reels?(,|\\s+tab|$).*")
    private val FB_VIDEO_TAB = Regex("(?i)^(video|watch)(,|\\s+tab|$).*")
    private val FB_REEL_VIEWER = Regex("(?i)^(reel by .+|reels? (viewer|player).*)$")
    private val SNAP_SPOTLIGHT = Regex("(?i)^spotlight.*")
    private val YT_SHORTS_LABEL = Regex("(?i)^shorts$")
    private val YT_HOME_LABEL = Regex("(?i)^home$")

    /** IDs of full-screen pagers used to count how many videos were swiped through. */
    fun isVideoPager(viewId: String?): Boolean {
        val id = viewId?.substringAfter(":id/") ?: return false
        return id in IG_REEL_PAGER_IDS || id in YT_SHORTS_PAGER_IDS
    }

    fun analyze(p: Platform, pkg: String, root: AccessibilityNodeInfo, prefs: Prefs, screen: Rect): Analysis =
        when (p) {
            Platform.INSTAGRAM -> instagram(pkg, root, prefs, screen)
            Platform.YOUTUBE -> youtube(pkg, root, prefs, screen)
            Platform.FACEBOOK -> facebook(root, prefs, screen)
            Platform.SNAPCHAT -> snapchat(root, prefs, screen)
        }

    private fun instagram(pkg: String, root: AccessibilityNodeInfo, prefs: Prefs, screen: Rect): Analysis {
        val blocking = prefs.blocked(Platform.INSTAGRAM)
        val hides = ArrayList<Hide>()

        val clipsTab = visible(root, pkg, "clips_tab")
        val reels = anyVisibleId(root, pkg, IG_REEL_IDS) || clipsTab.any { it.isSelected }
        if (blocking && prefs.hideTab(Platform.INSTAGRAM)) {
            clipsTab.firstOrNull()?.let { hides += Hide(bounds(it)) }
        }

        var close: String? = null
        if (prefs.blockIgStories && anyVisibleId(root, pkg, IG_STORY_IDS)) close = "Instagram Stories"

        if (prefs.blockIgExplore && !reels) {
            val searchTabSelected = visible(root, pkg, "search_tab").any { it.isSelected }
            if (searchTabSelected && !isTyping(root)) {
                val bar = IG_SEARCH_IDS.firstNotNullOfOrNull { visible(root, pkg, it).firstOrNull() }
                    ?.let { bounds(it) }
                    ?.takeIf { it.top < screen.top + screen.height() * 0.3 }
                val tabBar = visible(root, pkg, "tab_bar").firstOrNull()?.let { bounds(it) }
                if (bar != null) {
                    val bottom = tabBar?.top ?: screen.bottom
                    val region = Rect(screen.left, bar.bottom + 8, screen.right, bottom)
                    if (region.height() > screen.height() / 4) {
                        hides += Hide(region, "Explore is hidden\n\nTap the search bar to look someone up")
                    }
                }
            }
        }

        return Analysis(
            shortVideo = reels,
            closeNow = close,
            hides = hides,
            dmThread = anyVisibleId(root, pkg, IG_DM_IDS),
        )
    }

    private fun youtube(pkg: String, root: AccessibilityNodeInfo, prefs: Prefs, screen: Rect): Analysis {
        val blocking = prefs.blocked(Platform.YOUTUBE)
        val hides = ArrayList<Hide>()

        val pivotBar = visible(root, pkg, "pivot_bar").firstOrNull()
        val navRoot = pivotBar ?: root
        val bottomZone = screen.top + screen.height() * 0.8
        fun inNav(n: AccessibilityNodeInfo) = pivotBar != null || bounds(n).top >= bottomZone

        val shortsItem = findAll(navRoot, 400) { n ->
            n.isVisibleToUser && YT_SHORTS_LABEL.matches(label(n)) && inNav(n)
        }.firstOrNull()
        val shorts = anyVisibleId(root, pkg, YT_SHORTS_IDS) || (shortsItem?.let { selectedSelfOrParent(it) } == true)

        if (blocking && prefs.hideTab(Platform.YOUTUBE) && shortsItem != null) {
            hides += Hide(bounds(clickableSelfOrParent(shortsItem)))
        }

        // Search-only mode: cover the Home feed, keep Search, Subscriptions and the video player.
        if (prefs.blockYtHome && !shorts && pivotBar != null) {
            val homeItem = findAll(pivotBar, 200) { n -> YT_HOME_LABEL.matches(label(n)) }.firstOrNull()
            val playerOpen = YT_PLAYER_IDS.any { id ->
                visible(root, pkg, id).any { bounds(it).height() > screen.height() / 6 }
            }
            if (homeItem != null && selectedSelfOrParent(homeItem) && !playerOpen) {
                val topLimit = screen.top + screen.height() * 0.25
                val searchBtn = findAll(root, 600) { n ->
                    n.isVisibleToUser && label(n).equals("Search", ignoreCase = true) && bounds(n).top < topLimit
                }.firstOrNull()
                val top = searchBtn?.let { bounds(it).bottom } ?: (screen.top + (screen.height() * 0.12).toInt())
                val bottom = bounds(pivotBar).top
                if (bottom - top > screen.height() / 4) {
                    hides += Hide(
                        Rect(screen.left, top + 4, screen.right, bottom),
                        "Home feed is hidden\n\nSearch for what you came for,\nor open Subscriptions",
                    )
                }
            }
        }
        return Analysis(shortVideo = shorts, hides = hides)
    }

    private fun facebook(root: AccessibilityNodeInfo, prefs: Prefs, screen: Rect): Analysis {
        val blocking = prefs.blocked(Platform.FACEBOOK)
        val hides = ArrayList<Hide>()
        var reels = false
        var close: String? = null
        walk(root, 1500) { n ->
            if (!n.isVisibleToUser) return@walk
            val l = label(n)
            if (l.isEmpty()) return@walk
            if (FB_REELS_TAB.matches(l)) {
                if (n.isSelected) reels = true
                if (blocking && prefs.hideTab(Platform.FACEBOOK) && l.contains("tab", ignoreCase = true)) {
                    hides += Hide(bounds(n))
                }
            } else if (FB_VIDEO_TAB.matches(l) && n.isSelected && prefs.blockFbWatch) {
                close = "Facebook Video"
            } else if (FB_REEL_VIEWER.matches(l)) {
                reels = true
            }
        }
        if (hides.size > 2) hides.subList(2, hides.size).clear() // never cover more than the tab itself
        return Analysis(shortVideo = reels, closeNow = close, hides = hides.filter { it.rect.height() < screen.height() / 5 })
    }

    private fun snapchat(root: AccessibilityNodeInfo, prefs: Prefs, screen: Rect): Analysis {
        val blocking = prefs.blocked(Platform.SNAPCHAT)
        val hides = ArrayList<Hide>()
        var spotlight = false
        val bottomZone = screen.top + screen.height() * 0.8
        walk(root, 1200) { n ->
            if (!n.isVisibleToUser) return@walk
            if (!SNAP_SPOTLIGHT.matches(label(n))) return@walk
            if (n.isSelected) spotlight = true
            val b = bounds(n)
            if (blocking && prefs.hideTab(Platform.SNAPCHAT) && b.top >= bottomZone && b.height() < screen.height() / 6) {
                hides += Hide(b)
            }
        }
        return Analysis(shortVideo = spotlight, hides = hides.take(1))
    }

    // ---------- Strict-mode protection ----------

    private val DANGER = Regex(
        "(?i)^(uninstall|force stop|clear (storage|data|cache)|disable|deactivate|stop|" +
            "turn off|use .*|.*shortcut|app info|disconnect|forget( vpn)?|always-on vpn)$"
    )

    /**
     * True on screens that could switch ScrollStop off or remove it: its accessibility page,
     * its App info page, or the uninstall dialog.
     */
    fun isProtectedSettingsScreen(pkg: String, root: AccessibilityNodeInfo, appLabel: String, screen: Rect): Boolean {
        // Dialogs (uninstall, VPN disconnect) sit mid-screen, so any mention of the app counts there.
        val installer = pkg.contains("packageinstaller") || pkg == "com.android.vpndialogs"
        var nameNearTop = false
        var nameAnywhere = false
        var danger = false
        var toggle = false
        val topZone = screen.top + screen.height() * 0.35
        walk(root, 1500) { n ->
            if (!n.isVisibleToUser) return@walk
            val l = label(n)
            if (l.contains(appLabel, ignoreCase = true)) {
                nameAnywhere = true
                if (bounds(n).top < topZone) nameNearTop = true
                if (l.equals("Use $appLabel", ignoreCase = true)) danger = true
            }
            if (DANGER.matches(l)) danger = true
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("Switch") || cls.contains("ToggleButton")) toggle = true
        }
        return if (installer) nameAnywhere else nameNearTop && (danger || toggle)
    }

    // ---------- helpers ----------

    fun label(n: AccessibilityNodeInfo): String =
        (n.contentDescription?.takeIf { it.isNotBlank() } ?: n.text ?: "").toString().trim()

    fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    /** Looks nodes up by full view ID. Replaceable so tests can run without a real window. */
    internal var findById: (AccessibilityNodeInfo, String) -> List<AccessibilityNodeInfo> =
        { root, fullId -> root.findAccessibilityNodeInfosByViewId(fullId) }

    private fun visible(root: AccessibilityNodeInfo, pkg: String, id: String): List<AccessibilityNodeInfo> =
        try {
            findById(root, "$pkg:id/$id").filter { it.isVisibleToUser && !bounds(it).isEmpty }
        } catch (e: RuntimeException) {
            emptyList()
        }

    private fun anyVisibleId(root: AccessibilityNodeInfo, pkg: String, ids: List<String>): Boolean =
        ids.any { visible(root, pkg, it).isNotEmpty() }

    private fun isTyping(root: AccessibilityNodeInfo): Boolean = try {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
    } catch (e: RuntimeException) {
        false
    }

    /** Tab buttons mark either themselves, their container, or their icon/label child as selected. */
    private fun selectedSelfOrParent(n: AccessibilityNodeInfo): Boolean {
        if (n.isSelected) return true
        if (n.parent?.isSelected == true) return true
        return (0 until n.childCount).any { n.getChild(it)?.isSelected == true }
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (n.isClickable) return n
        val p = n.parent ?: return n
        return if (p.isClickable && bounds(p).height() < bounds(n).height() * 3) p else n
    }

    /** Breadth-first walk over the window, capped so it stays fast. */
    fun walk(root: AccessibilityNodeInfo, limit: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < limit) {
            val n = queue.removeFirst()
            seen++
            visit(n)
            for (i in 0 until n.childCount) {
                try {
                    n.getChild(i)?.let { queue.add(it) }
                } catch (e: RuntimeException) {
                    // the window changed while we were reading it
                }
            }
        }
    }

    private fun findAll(
        root: AccessibilityNodeInfo,
        limit: Int,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        walk(root, limit) { if (match(it)) out += it }
        return out
    }
}
