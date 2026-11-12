package com.scrollstop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceTest {
    @Test fun noneBlocksImmediately() {
        assertFalse(Allowance.NONE.hasRoom(0, 0))
        assertFalse(Allowance.NONE.hasRoom(0, 1))
    }

    @Test fun timeOnly() {
        val a = Allowance(10, 0)
        assertTrue(a.hasRoom(9 * 60_000L, 500))
        assertFalse(a.hasRoom(10 * 60_000L, 1))
        assertEquals(60_000L, a.msLeft(9 * 60_000L))
        assertNull(a.videosLeft(3))
    }

    @Test fun countOnly() {
        val a = Allowance(0, 5)
        assertTrue(a.hasRoom(99 * 60_000L, 5)) // 5th video is allowed
        assertFalse(a.hasRoom(0, 6))           // 6th is not
        assertEquals(2L, a.videosLeft(3))
    }

    @Test fun whicheverComesFirst() {
        val a = Allowance(10, 5)
        assertFalse(a.hasRoom(10 * 60_000L, 1))
        assertFalse(a.hasRoom(0, 6))
        assertTrue(a.hasRoom(60_000L, 2))
    }

    @Test fun weakening() {
        assertTrue(Allowance(5, 0).allowsMoreThan(Allowance.NONE))
        assertFalse(Allowance.NONE.allowsMoreThan(Allowance(5, 0)))
        assertTrue(Allowance(10, 0).allowsMoreThan(Allowance(5, 0)))
        assertFalse(Allowance(3, 0).allowsMoreThan(Allowance(5, 0)))
        // Dropping the time cap while a video cap remains lets you watch longer.
        assertTrue(Allowance(0, 20).allowsMoreThan(Allowance(10, 20)))
        // Adding a second cap only makes it stricter.
        assertFalse(Allowance(10, 20).allowsMoreThan(Allowance(10, 0)))
    }
}

class ScheduleTest {
    private val mon = 1
    private val tue = 2
    private val sun = 7

    @Test fun disabledAlwaysBlocks() {
        assertTrue(Schedule(false, 600, 700, 0).isBlockingAt(mon, 0))
    }

    @Test fun dayWindow() {
        val s = Schedule(true, 9 * 60, 17 * 60, Schedule.ALL_DAYS)
        assertTrue(s.isBlockingAt(mon, 9 * 60))
        assertTrue(s.isBlockingAt(mon, 16 * 60 + 59))
        assertFalse(s.isBlockingAt(mon, 17 * 60))
        assertFalse(s.isBlockingAt(mon, 8 * 60))
    }

    @Test fun overnightBelongsToStartDay() {
        // Monday only, 22:00 -> 07:00
        val s = Schedule(true, 22 * 60, 7 * 60, 1 shl (mon - 1))
        assertTrue(s.isBlockingAt(mon, 23 * 60))
        assertTrue(s.isBlockingAt(tue, 6 * 60))   // Monday night spilling into Tuesday
        assertFalse(s.isBlockingAt(tue, 23 * 60)) // Tuesday not selected
        assertFalse(s.isBlockingAt(mon, 6 * 60))  // Sunday night not selected
    }

    @Test fun sundayToMondayWrap() {
        val s = Schedule(true, 22 * 60, 7 * 60, 1 shl (sun - 1))
        assertTrue(s.isBlockingAt(mon, 60))
    }

    @Test fun sameStartEndMeansAllDay() {
        val s = Schedule(true, 0, 0, 1 shl (mon - 1))
        assertTrue(s.isBlockingAt(mon, 720))
        assertFalse(s.isBlockingAt(tue, 720))
    }

    @Test fun weakening() {
        val always = Schedule(false, 0, 0, Schedule.ALL_DAYS)
        val work = Schedule(true, 9 * 60, 17 * 60, Schedule.ALL_DAYS)
        assertTrue(work.weakerThan(always))
        assertFalse(always.weakerThan(work))
        val longer = Schedule(true, 8 * 60, 18 * 60, Schedule.ALL_DAYS)
        assertFalse(longer.weakerThan(work))
        assertTrue(work.weakerThan(longer))
    }
}

class UrlRulesTest {
    private val c = UrlRules.Config(igExplore = true, igStories = true, fbWatch = true, tiktok = true)
    private val off = UrlRules.Config(false, false, false, false, false, false, false, false)

    @Test fun instagram() {
        assertEquals("Instagram Reels", UrlRules.match("instagram.com/reels/abc123", c))
        assertEquals("Instagram Reels", UrlRules.match("https://www.instagram.com/reel/abc/", c))
        assertEquals("Instagram Reels", UrlRules.match("instagram.com/someone/reels", c))
        assertEquals("Instagram Explore", UrlRules.match("instagram.com/explore/", c))
        assertEquals("Instagram Stories", UrlRules.match("instagram.com/stories/someone/123", c))
        assertNull(UrlRules.match("instagram.com/reelfan", c)) // a username, not reels
        assertNull(UrlRules.match("instagram.com/p/xyz", c))
        assertNull(UrlRules.match("instagram.com/", c))
    }

    @Test fun youtube() {
        assertEquals("YouTube Shorts", UrlRules.match("m.youtube.com/shorts/abcd", c))
        assertEquals("YouTube Shorts", UrlRules.match("https://youtube.com/shorts", c))
        assertNull(UrlRules.match("youtube.com/watch?v=abcd", c))
        assertNull(UrlRules.match("youtube.com/@shortsguy", c))
    }

    @Test fun facebookSnapTiktok() {
        assertEquals("Facebook Reels", UrlRules.match("facebook.com/reel/123", c))
        assertEquals("Facebook Reels", UrlRules.match("m.facebook.com/reels/", c))
        assertEquals("Facebook Reels", UrlRules.match("fb.watch/abc", c))
        assertEquals("Facebook Watch", UrlRules.match("facebook.com/watch/?v=1", c))
        assertEquals("Snapchat Spotlight", UrlRules.match("snapchat.com/spotlight/xyz", c))
        assertEquals("TikTok", UrlRules.match("www.tiktok.com/@someone", c))
        assertEquals("TikTok", UrlRules.match("vm.tiktok.com/abc", c))
    }

    @Test fun ignoresSearchesAndDisabledRules() {
        assertNull(UrlRules.match("instagram reels", c))
        assertNull(UrlRules.match("", c))
        assertNull(UrlRules.match(null, c))
        assertNull(UrlRules.match("instagram.com/reels/abc", off))
        assertNull(UrlRules.match("notinstagram.com/reels/abc", c))
    }
}

class LockAndLimitsTest {
    @Test fun lockTypes() {
        assertFalse(Lock.isLocked(LockType.NONE, Long.MAX_VALUE, 0))
        assertTrue(Lock.isLocked(LockType.TIMER, 100, 99))
        assertFalse(Lock.isLocked(LockType.TIMER, 100, 100))
        assertTrue(Lock.isLocked(LockType.PIN, 0, 1))
        assertTrue(Lock.isLocked(LockType.PHRASE, 0, 1))
    }

    @Test fun phrase() {
        assertTrue(Lock.phraseMatches("  i choose focus   over scrolling. "))
        assertFalse(Lock.phraseMatches("I choose scrolling"))
    }

    @Test fun pinHashIsStable() {
        assertEquals(Lock.hashPin("1234", "s"), Lock.hashPin("1234", "s"))
        assertFalse(Lock.hashPin("1234", "s") == Lock.hashPin("1235", "s"))
    }

    @Test fun appLimitsRoundTrip() {
        val m = mapOf("com.a" to 30, "com.b" to 0)
        assertEquals(m, AppLimits.parse(AppLimits.format(m)))
        assertEquals(emptyMap<String, Int>(), AppLimits.parse(""))
        assertEquals(mapOf("x" to 5), AppLimits.parse("x=5;broken;=3;y=z"))
    }

    @Test fun appLimitWeakening() {
        assertTrue(AppLimits.weakens(30, null))
        assertTrue(AppLimits.weakens(0, 15))
        assertFalse(AppLimits.weakens(30, 15))
        assertFalse(AppLimits.weakens(null, 15))
    }

    @Test fun formatting() {
        assertEquals("1h 5m", Fmt.duration(65 * 60_000L))
        assertEquals("2h", Fmt.duration(120 * 60_000L))
        assertEquals("12m", Fmt.duration(12 * 60_000L + 5_000))
        assertEquals("45s", Fmt.duration(45_000))
        assertEquals("12:00 AM", Fmt.clock(0))
        assertEquals("1:30 PM", Fmt.clock(13 * 60 + 30))
        assertEquals("4:05", Fmt.countdown(245_000))
        assertEquals("1:00:00", Fmt.countdown(3_600_000))
    }

    @Test fun platformLookup() {
        assertEquals(Platform.FACEBOOK, Platform.of("com.facebook.lite"))
        assertNull(Platform.of("com.example"))
        assertEquals(Platform.YOUTUBE, Platform.byKey("yt"))
    }
}
