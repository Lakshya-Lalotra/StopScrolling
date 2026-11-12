package com.scrollstop.app

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs the detection rules against fake screens built from accessibility nodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesTest {
    private val screen = Rect(0, 0, 1080, 2400)
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        prefs = Prefs.get(ctx)
        prefs.sp.edit().clear().putInt("schema", 2).commit()
        // Robolectric can't search a fake window by view ID, so walk the tree instead.
        Rules.findById = { root, id ->
            val out = ArrayList<AccessibilityNodeInfo>()
            Rules.walk(root, 5000) { if (it.viewIdResourceName == id) out += it }
            out
        }
    }

    private fun node(
        pkg: String,
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        bounds: Rect = Rect(0, 0, 100, 100),
        selected: Boolean = false,
        cls: String = "android.view.View",
        children: Array<AccessibilityNodeInfo> = emptyArray(),
    ): AccessibilityNodeInfo {
        val n = AccessibilityNodeInfo.obtain()
        n.packageName = pkg
        n.className = cls
        id?.let { n.viewIdResourceName = "$pkg:id/$it" }
        n.text = text
        n.contentDescription = desc
        n.setBoundsInScreen(bounds)
        n.isSelected = selected
        n.isVisibleToUser = true
        for (c in children) shadowOf(n).addChild(c)
        return n
    }

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    @Test fun instagramReelsTab() {
        val tab = node(ig, id = "clips_tab", bounds = Rect(432, 2250, 648, 2380), selected = true)
        val root = node(ig, bounds = screen, children = arrayOf(node(ig, id = "tab_bar", bounds = Rect(0, 2250, 1080, 2380), children = arrayOf(tab))))
        val a = Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen)
        assertTrue(a.shortVideo)
        assertEquals(listOf(Rect(432, 2250, 648, 2380)), a.hides.map { it.rect })
    }

    @Test fun instagramFeedIsFine() {
        val tab = node(ig, id = "clips_tab", bounds = Rect(432, 2250, 648, 2380), selected = false)
        val feed = node(ig, id = "feed_tab", bounds = Rect(0, 2250, 216, 2380), selected = true)
        val root = node(ig, bounds = screen, children = arrayOf(tab, feed))
        val a = Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen)
        assertFalse(a.shortVideo)
        assertEquals(1, a.hides.size) // the Reels button is still covered

        prefs.setHideTab(Platform.INSTAGRAM, false)
        assertTrue(Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).hides.isEmpty())
    }

    @Test fun instagramReelViewerAndDm() {
        val viewer = node(ig, id = "clips_viewer_view_pager", bounds = screen)
        val a = Rules.analyze(Platform.INSTAGRAM, ig, node(ig, bounds = screen, children = arrayOf(viewer)), prefs, screen)
        assertTrue(a.shortVideo)
        assertFalse(a.dmThread)

        val dm = node(ig, id = "row_thread_composer_edittext", bounds = Rect(0, 2200, 1080, 2300))
        val b = Rules.analyze(Platform.INSTAGRAM, ig, node(ig, bounds = screen, children = arrayOf(dm)), prefs, screen)
        assertTrue(b.dmThread)
        assertFalse(b.shortVideo)
    }

    @Test fun instagramExploreCoveredOnlyWhenEnabled() {
        val search = node(ig, id = "action_bar_search_edit_text", bounds = Rect(40, 120, 1040, 220))
        val searchTab = node(ig, id = "search_tab", bounds = Rect(216, 2250, 432, 2380), selected = true)
        val tabBar = node(ig, id = "tab_bar", bounds = Rect(0, 2250, 1080, 2380), children = arrayOf(searchTab))
        val root = node(ig, bounds = screen, children = arrayOf(search, tabBar))
        assertTrue(Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).hides.none { it.text != null })

        prefs.blockIgExplore = true
        val cover = Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).hides.single { it.text != null }
        assertEquals(228, cover.rect.top)
        assertEquals(2250, cover.rect.bottom)
    }

    @Test fun instagramStories() {
        val story = node(ig, id = "reel_viewer_root", bounds = screen)
        val root = node(ig, bounds = screen, children = arrayOf(story))
        assertNull(Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).closeNow)
        prefs.blockIgStories = true
        assertEquals("Instagram Stories", Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).closeNow)
    }

    @Test fun youtubeShortsTabAndHomeFeed() {
        val home = node(yt, desc = "Home", bounds = Rect(0, 2250, 216, 2380), selected = true)
        val shorts = node(yt, desc = "Shorts", bounds = Rect(216, 2250, 432, 2380))
        val pivot = node(yt, id = "pivot_bar", bounds = Rect(0, 2250, 1080, 2380), children = arrayOf(home, shorts))
        val searchBtn = node(yt, desc = "Search", bounds = Rect(900, 100, 1000, 200))
        val root = node(yt, bounds = screen, children = arrayOf(searchBtn, pivot))

        val a = Rules.analyze(Platform.YOUTUBE, yt, root, prefs, screen)
        assertFalse(a.shortVideo)
        assertEquals(listOf(Rect(216, 2250, 432, 2380)), a.hides.map { it.rect })

        prefs.blockYtHome = true
        val b = Rules.analyze(Platform.YOUTUBE, yt, root, prefs, screen)
        val feed = b.hides.single { it.text != null }.rect
        assertEquals(204, feed.top)
        assertEquals(2250, feed.bottom)
    }

    @Test fun youtubeShortsPlayer() {
        val player = node(yt, id = "reel_recycler", bounds = screen)
        val a = Rules.analyze(Platform.YOUTUBE, yt, node(yt, bounds = screen, children = arrayOf(player)), prefs, screen)
        assertTrue(a.shortVideo)
        assertTrue(Rules.isVideoPager("$yt:id/reel_recycler"))
        assertFalse(Rules.isVideoPager("$yt:id/results"))
    }

    @Test fun facebookAndSnapchatTabs() {
        val fb = "com.facebook.katana"
        val reelsTab = node(fb, desc = "Reels, tab 3 of 6", bounds = Rect(360, 200, 540, 320), selected = true)
        val header = node(fb, text = "Reels and short videos", bounds = Rect(0, 900, 1080, 980))
        val a = Rules.analyze(Platform.FACEBOOK, fb, node(fb, bounds = screen, children = arrayOf(reelsTab, header)), prefs, screen)
        assertTrue(a.shortVideo)
        assertEquals(listOf(Rect(360, 200, 540, 320)), a.hides.map { it.rect })

        val video = node(fb, desc = "Video, tab 4 of 6", bounds = Rect(540, 200, 720, 320), selected = true)
        prefs.blockFbWatch = true
        assertEquals("Facebook Video",
            Rules.analyze(Platform.FACEBOOK, fb, node(fb, bounds = screen, children = arrayOf(video)), prefs, screen).closeNow)

        val snap = "com.snapchat.android"
        val spot = node(snap, desc = "Spotlight", bounds = Rect(860, 2250, 1080, 2380), selected = true)
        val s = Rules.analyze(Platform.SNAPCHAT, snap, node(snap, bounds = screen, children = arrayOf(spot)), prefs, screen)
        assertTrue(s.shortVideo)
        assertEquals(1, s.hides.size)
    }

    @Test fun nothingWhenFeedNotBlocked() {
        prefs.setBlocked(Platform.INSTAGRAM, false)
        val tab = node(ig, id = "clips_tab", bounds = Rect(432, 2250, 648, 2380), selected = true)
        val a = Rules.analyze(Platform.INSTAGRAM, ig, node(ig, bounds = screen, children = arrayOf(tab)), prefs, screen)
        assertTrue(a.hides.isEmpty())
    }

    @Test fun strictModeProtectsTheRightScreens() {
        val s = "com.android.settings"
        // ScrollStop's accessibility page
        val title = node(s, text = "ScrollStop", bounds = Rect(40, 200, 1040, 300))
        val sw = node(s, text = "Use ScrollStop", bounds = Rect(40, 500, 1040, 600), cls = "android.widget.Switch")
        assertTrue(Rules.isProtectedSettingsScreen(s, node(s, bounds = screen, children = arrayOf(title, sw)), "ScrollStop", screen))
        // App info page
        val uninstall = node(s, text = "Uninstall", bounds = Rect(40, 700, 500, 800), cls = "android.widget.Button")
        assertTrue(Rules.isProtectedSettingsScreen(s, node(s, bounds = screen, children = arrayOf(title, uninstall)), "ScrollStop", screen))
        // A list that merely mentions ScrollStop further down
        val item = node(s, text = "ScrollStop", bounds = Rect(40, 1500, 1040, 1600))
        val other = node(s, text = "Some other app", bounds = Rect(40, 1600, 1040, 1700))
        assertFalse(Rules.isProtectedSettingsScreen(s, node(s, bounds = screen, children = arrayOf(item, other)), "ScrollStop", screen))
        // Another app's App info page
        val otherTitle = node(s, text = "Calculator", bounds = Rect(40, 200, 1040, 300))
        assertFalse(Rules.isProtectedSettingsScreen(s, node(s, bounds = screen, children = arrayOf(otherTitle, uninstall)), "ScrollStop", screen))
        // Uninstall dialog
        val p = "com.google.android.packageinstaller"
        val dlg = node(p, text = "ScrollStop", bounds = Rect(100, 1000, 900, 1100))
        assertTrue(Rules.isProtectedSettingsScreen(p, node(p, bounds = screen, children = arrayOf(dlg)), "ScrollStop", screen))
    }
}
