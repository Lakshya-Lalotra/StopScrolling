package com.scrollstop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPlanTest {
    private val top = 200
    private val bottom = 2200 // list is 2000 px tall

    /** A post: header row (60 px), photo, buttons, caption. */
    private fun post(y: Int, name: String, ad: Boolean = false, follow: Boolean = false) = listOf(
        FeedRow(y, y + 60, header = name, ad = ad, follow = follow),
        FeedRow(y + 60, y + 1140),  // photo
        FeedRow(y + 1140, y + 1220), // like / comment / share
        FeedRow(y + 1220, y + 1300), // caption
    )

    @Test fun labels() {
        assertTrue(FeedLabels.isAd("Sponsored"))
        assertTrue(FeedLabels.isAd(" प्रायोजित "))
        assertTrue(FeedLabels.isAd("Paid partnership with brandco"))
        assertTrue(FeedLabels.isAd("Ad"))           // Instagram's newer label
        assertFalse(FeedLabels.isAd("Adidas"))
        assertTrue(FeedLabels.isAdButton("Order now"))
        assertTrue(FeedLabels.isRemoveOption("Not interested", FeedKind.SUGGESTED))
        assertTrue(FeedLabels.isRemoveOption("Hide ad", FeedKind.AD))
        assertFalse(FeedLabels.isRemoveOption("Hide ad", FeedKind.SUGGESTED))
        assertFalse(FeedLabels.isRemoveOption("Unfollow", FeedKind.SUGGESTED)) // never touches anything else
        assertFalse(FeedLabels.isRemoveOption("Report", FeedKind.AD))
        assertTrue(FeedLabels.isAdReason("It's irrelevant"))
        assertTrue(FeedLabels.isMenuHint("About this account"))
        assertFalse(FeedLabels.isAdButton("Follow"))
        assertFalse(FeedLabels.isAd("Sponsored by my mum, lol"))
        assertTrue(FeedLabels.isSuggested("Suggested for you"))
        assertTrue(FeedLabels.isSuggested("Because you liked a post from carol"))
        assertFalse(FeedLabels.isSuggested("alice"))
    }

    @Test fun normalFeedIsLeftAlone() {
        val m = FeedMemory()
        val r = FeedPlan.plan(post(top, "alice") + post(top + 1300, "bob"), top, bottom, m, true, true)
        assertTrue(r.covers.isEmpty())
        assertNull(r.skipBy)
        assertEquals(listOf("alice" to null, "bob" to null), m.order)
    }

    @Test fun adIsCoveredUntilTheNextPost() {
        val m = FeedMemory()
        val rows = post(top, "alice") + post(top + 1300, "brandco", ad = true)
        val r = FeedPlan.plan(rows, top, bottom, m, true, true)
        assertEquals(1, r.covers.size)
        val c = r.covers[0]
        assertEquals(FeedKind.AD, c.kind)
        assertEquals(top + 1300, c.top)
        assertEquals(bottom, c.bottom) // runs to the bottom of the list
        assertEquals(top + 1300, c.headerTop)
        assertNull(r.skipBy) // the ad is still low on screen: you're reading alice's post
    }

    @Test fun adBottomStaysHiddenAfterItsHeaderScrollsAway() {
        val m = FeedMemory()
        FeedPlan.plan(post(top, "alice") + post(top + 1300, "brandco", ad = true), top, bottom, m, true, true)
        // Scrolled down 1500 px: brandco's header is gone, its photo is at the top, bob's post below.
        m.direction = 1
        val rows = post(top + 1300 - 1500, "brandco", ad = true).drop(1) + post(top + 2600 - 1500, "bob")
        val r = FeedPlan.plan(rows, top, bottom, m, true, true)
        assertEquals(1, r.covers.size)
        assertEquals(top, r.covers[0].top)
        assertEquals(top + 1100, r.covers[0].bottom) // up to bob's header
        assertEquals(FeedKind.AD, r.covers[0].kind)
        // The ad fills the top half: scroll on to bob.
        assertEquals(1100, r.skipBy)
    }

    @Test fun onlyTheMiddleOfAnAdOnScreen() {
        val m = FeedMemory()
        FeedPlan.plan(post(top, "alice") + post(top + 1300, "brandco", ad = true), top, bottom, m, true, true)
        m.direction = 1
        val r = FeedPlan.plan(listOf(FeedRow(top - 300, top + 780), FeedRow(top + 780, top + 860)), top, bottom, m, true, true)
        assertEquals(FeedKind.AD, r.covers.single().kind)
    }

    @Test fun suggestedBannerAndFollowButton() {
        val m = FeedMemory()
        val banner = FeedRow(top, top + 50, suggestedLabel = true)
        val rows = listOf(banner) + post(top + 50, "stranger") + post(top + 1350, "notfollowed", follow = true)
        val r = FeedPlan.plan(rows, top, bottom, m, true, true)
        assertEquals(1, r.covers.size) // both suggested posts merge into one cover
        assertEquals(FeedKind.SUGGESTED, r.covers[0].kind)
        assertEquals(top, r.covers[0].top)
        assertEquals(bottom, r.covers[0].bottom)
        assertEquals(1600, r.skipBy) // capped at 80% of the list
    }

    @Test fun switchesAreRespected() {
        val m = FeedMemory()
        val rows = post(top, "brandco", ad = true) + post(top + 1300, "x", follow = true)
        assertTrue(FeedPlan.plan(rows, top, bottom, m, false, false).covers.isEmpty())
        assertEquals(listOf(FeedKind.SUGGESTED), FeedPlan.plan(rows, top, bottom, FeedMemory(), false, true).covers.map { it.kind })
        assertEquals(listOf(FeedKind.AD), FeedPlan.plan(rows, top, bottom, FeedMemory(), true, false).covers.map { it.kind })
    }
}
