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

    /** A post in Instagram's feed list: header row (name + optional label/Follow), photo row, buttons row. */
    private fun igPost(y: Int, name: String, label: String? = null, follow: Boolean = false): List<AccessibilityNodeInfo> {
        val headerKids = mutableListOf(node(ig, id = "row_feed_photo_profile_name", text = name, bounds = Rect(140, y + 10, 600, y + 50)))
        label?.let { headerKids += node(ig, id = "secondary_label", text = it, bounds = Rect(140, y + 50, 600, y + 80)) }
        if (follow) headerKids += node(ig, text = "Follow", bounds = Rect(620, y + 20, 760, y + 70)).also { it.isClickable = true }
        headerKids += node(ig, desc = "More options", bounds = Rect(980, y + 20, 1060, y + 80)).also { it.isClickable = true }
        return listOf(
            node(ig, id = "row_feed_profile_header", bounds = Rect(0, y, 1080, y + 100), children = headerKids.toTypedArray()),
            node(ig, id = "row_feed_media", bounds = Rect(0, y + 100, 1080, y + 1180)),
            node(ig, id = "row_feed_view_group_buttons", bounds = Rect(0, y + 1180, 1080, y + 1300)),
        )
    }

    private fun feedScreen(rows: List<AccessibilityNodeInfo>, homeSelected: Boolean = true): AccessibilityNodeInfo {
        val list = AccessibilityNodeInfo.obtain().apply {
            packageName = ig
            className = "androidx.recyclerview.widget.RecyclerView"
            viewIdResourceName = "android:id/list"
            setBoundsInScreen(Rect(0, 200, 1080, 2250))
            isVisibleToUser = true
            isScrollable = true
        }
        rows.forEach { shadowOf(list).addChild(it) }
        val homeTab = node(ig, id = "feed_tab", bounds = Rect(0, 2250, 216, 2380), selected = homeSelected)
        return node(ig, bounds = screen, children = arrayOf(list, homeTab))
    }

    @Test fun instagramAppFeedAdsAndSuggestedPosts() {
        val rows = igPost(200, "alice") + igPost(1500, "brandco", label = "Sponsored")
        val a = Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(rows), prefs, screen)
        val cover = a.hides.single { it.scrolls }
        assertEquals(Rect(0, 1500, 1080, 2250), cover.rect)
        assertNull(cover.text) // covers carry no text
        assertEquals(Rect(0, 1500, 1080, 1600), cover.sample) // colour comes from the ad's header row
        assertEquals(Rect(0, 200, 1080, 2250), a.feedList)

        // A post with a Follow button is from someone you don't follow.
        val b = Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(igPost(200, "stranger", follow = true) + igPost(1500, "bob")),
            prefs, screen)
        val s = b.hides.single { it.scrolls }
        assertEquals(Rect(0, 200, 1080, 1500), s.rect)
        assertEquals(1300, b.feedSkip) // it's at the top and fills the screen: skip to bob

        // Instagram's newer "Ad" label, and an ad found only by its "Order now" button bar.
        val c = Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(igPost(200, "alice") + igPost(1500, "otrivin", label = "Ad")),
            prefs, screen)
        assertEquals(Rect(0, 1500, 1080, 2250), c.hides.single { it.scrolls }.rect)
        val withButton = igPost(1500, "shop") + node(ig, text = "Order now", bounds = Rect(0, 2100, 1080, 2200))
            .also { it.isClickable = true }
        val d = Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(igPost(200, "alice") + withButton), prefs, screen)
        assertEquals(1, d.hides.count { it.scrolls })

        // Instagram can be asked to remove it: the ad's ⋯ button is handed over.
        val target = c.feedTargets.single()
        assertEquals(FeedKind.AD, target.kind)
        assertEquals("otrivin", target.account)
        assertEquals("More options", target.more.contentDescription.toString())

        // Switched off: nothing is covered.
        prefs.webHideAds = false
        prefs.webHideSuggested = false
        assertTrue(Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(rows), prefs, screen).hides.none { it.scrolls })
    }

    @Test fun postsInsideAWrapperAreCoveredOneByOne() {
        // Some Instagram versions put every post inside one wrapper that fills the list.
        val posts = igPost(200, "alice") + igPost(1500, "stranger", follow = true)
        val wrapper = node(ig, bounds = Rect(0, 200, 1080, 2800), children = posts.toTypedArray())
        val a = Rules.analyze(Platform.INSTAGRAM, ig, feedScreen(listOf(wrapper)), prefs, screen)
        val cover = a.hides.single { it.scrolls }
        assertEquals(1500, cover.rect.top) // only the stranger's post, not the whole screen
    }

    @Test fun reelFromDmKnowsItsAuthor() {
        val viewer = node(ig, id = "clips_viewer_view_pager", bounds = screen)
        val reply = node(ig, id = "reel_viewer_message_composer", bounds = Rect(0, 2200, 1080, 2300))
        val author = node(ig, id = "clips_author_username", text = "bestfriend", bounds = Rect(40, 1900, 500, 1950))
        val a = Rules.analyze(Platform.INSTAGRAM, ig, node(ig, bounds = screen, children = arrayOf(viewer, reply, author)),
            prefs, screen)
        assertTrue(a.shortVideo)
        assertTrue(a.reelFromDm)
        assertEquals("bestfriend", a.reelAuthor)
    }

    @Test fun facebookHideButtonOnSponsoredPost() {
        val fb = "com.facebook.katana"
        val label = node(fb, text = "Sponsored", bounds = Rect(140, 450, 400, 490))
        val close = node(fb, desc = "Hide post", bounds = Rect(980, 420, 1060, 500)).also { it.isClickable = true }
        val post = node(fb, bounds = Rect(0, 400, 1080, 1400), children = arrayOf(label, close))
        val a = Rules.analyze(Platform.FACEBOOK, fb, node(fb, bounds = screen, children = arrayOf(post)), prefs, screen)
        assertEquals("Hide post", a.dismissButton?.contentDescription?.toString())
        prefs.webHideAds = false
        prefs.webHideSuggested = false
        assertNull(Rules.analyze(Platform.FACEBOOK, fb, node(fb, bounds = screen, children = arrayOf(post)), prefs, screen)
            .dismissButton)
    }

    @Test fun instagramFeedLeftAloneOnOtherTabs() {
        val rows = igPost(200, "brandco", label = "Sponsored")
        val root = feedScreen(rows, homeSelected = false) // e.g. the profile tab is open
        assertTrue(Rules.analyze(Platform.INSTAGRAM, ig, root, prefs, screen).hides.none { it.scrolls })
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
