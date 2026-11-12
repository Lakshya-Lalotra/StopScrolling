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

class ColorsTest {
    @Test fun borderPointsStayInsideTheBox() {
        val pts = Colors.borderPoints(100, 200, 300, 300, inset = 4, perEdge = 8)
        assertEquals(32, pts.size)
        assertTrue(pts.all { (x, y) -> x in 104..295 && y in 204..295 })
        assertTrue(pts.any { it.first == 104 } && pts.any { it.first == 295 })
        assertTrue(Colors.borderPoints(0, 0, 6, 6, inset = 4, perEdge = 8).isEmpty())
    }

    @Test fun dominantColourWinsOverIconPixels() {
        val bar = 0xFF1C1C1F.toInt()
        val icon = 0xFFFFFFFF.toInt()
        val px = IntArray(30) { if (it % 5 == 0) icon else bar }
        assertEquals(bar, Colors.dominant(px))
        assertNull(Colors.dominant(IntArray(4) { 0x00000000 }))
    }

    @Test fun darkness() {
        assertTrue(Colors.isDark(0xFF101010.toInt()))
        assertFalse(Colors.isDark(0xFFF5F5F5.toInt()))
    }
}

class WebRulesTest {
    private val cfg = UrlRules.Config(igExplore = true)
    private val ig = WebSite.INSTAGRAM
    private val yt = WebSite.YOUTUBE

    @Test fun instagramPages() {
        assertEquals(WebRules.Decision.Allow, WebRules.decide(ig, "https://www.instagram.com/", null, cfg, false))
        assertEquals(WebRules.Decision.Allow, WebRules.decide(ig, "https://www.instagram.com/direct/inbox/", null, cfg, false))
        assertEquals(WebRules.Decision.Block("Instagram Reels"),
            WebRules.decide(ig, "https://www.instagram.com/reels/", null, cfg, false))
        assertEquals(WebRules.Decision.Block("Instagram Explore"),
            WebRules.decide(ig, "https://www.instagram.com/explore/", null, cfg, false))
        // Facebook login is allowed, other sites open outside, app links are ignored
        assertEquals(WebRules.Decision.Allow, WebRules.decide(ig, "https://m.facebook.com/login/", null, cfg, false))
        assertEquals(WebRules.Decision.External, WebRules.decide(ig, "https://example.com/x", null, cfg, false))
        assertEquals(WebRules.Decision.Ignore, WebRules.decide(ig, "intent://reels#Intent;scheme=instagram;end", null, cfg, false))
        assertEquals(WebRules.Decision.Ignore, WebRules.decide(ig, "instagram://reels", null, cfg, false))
    }

    @Test fun reelFromDms() {
        val dm = "https://www.instagram.com/direct/t/123/"
        val reel = "https://www.instagram.com/reel/AbC123/"
        assertEquals(WebRules.Decision.Allow, WebRules.decide(ig, reel, dm, cfg, true))
        assertEquals(WebRules.Decision.Block("Instagram Reels"), WebRules.decide(ig, reel, dm, cfg, false))
        assertEquals(WebRules.Decision.Block("Instagram Reels"),
            WebRules.decide(ig, reel, "https://www.instagram.com/", cfg, true))
        assertEquals(WebRules.Decision.Block("Instagram Reels"),
            WebRules.decide(ig, "https://www.instagram.com/reels/", dm, cfg, true))
    }

    @Test fun youtube() {
        assertEquals(WebRules.Decision.Block("YouTube Shorts"),
            WebRules.decide(yt, "https://m.youtube.com/shorts/xyz", null, cfg, false))
        assertEquals(WebRules.Decision.Allow, WebRules.decide(yt, "https://m.youtube.com/watch?v=1", null, cfg, false))
        assertEquals(WebRules.Decision.Allow, WebRules.decide(yt, "https://accounts.google.com/signin", null, cfg, false))
        assertEquals(WebRules.Decision.Ignore, WebRules.decide(yt, "vnd.youtube:abc", null, cfg, false))
        assertEquals(WebRules.Decision.Allow, WebRules.decide(yt, "about:blank", null, cfg, false))
    }

    @Test fun urlParts() {
        assertEquals("www.instagram.com", WebRules.hostOf("https://www.instagram.com:443/p/1"))
        assertEquals("/p/1", WebRules.pathOf("https://www.instagram.com/p/1"))
        assertEquals("/", WebRules.pathOf("https://www.instagram.com?x=1"))
    }
}

class SleepTest {
    @Test fun sleepWindow() {
        val w = Sleep.window(true, 23 * 60, 7 * 60)
        assertTrue(Sleep.isSleeping(w, 3, 23 * 60 + 30))
        assertTrue(Sleep.isSleeping(w, 4, 6 * 60))
        assertFalse(Sleep.isSleeping(w, 4, 12 * 60))
        assertFalse(Sleep.isSleeping(Sleep.window(false, 23 * 60, 7 * 60), 4, 6 * 60))
    }

    @Test fun weakening() {
        val w = Sleep.window(true, 23 * 60, 7 * 60)
        assertTrue(Sleep.weakens(w, null))                                  // switching it off
        assertTrue(Sleep.weakens(w, Sleep.window(true, 0, 7 * 60)))         // later bedtime
        assertFalse(Sleep.weakens(w, Sleep.window(true, 22 * 60, 8 * 60)))  // longer sleep
        assertFalse(Sleep.weakens(null, w))                                 // switching it on
    }
}

class MoreWebRulesTest {
    private val cfg = UrlRules.Config()

    @Test fun sharedLinkPlaysJustThatOne() {
        val shared = WebRules.cleanPath("https://www.instagram.com/reel/AbC/?igsh=xyz")
        assertEquals("/reel/AbC", shared)
        assertEquals(WebRules.Decision.Allow, WebRules.decide(WebSite.INSTAGRAM,
            "https://www.instagram.com/reel/AbC/", null, cfg, false, sharedPath = shared))
        assertEquals(WebRules.Decision.Block("Instagram Reels"), WebRules.decide(WebSite.INSTAGRAM,
            "https://www.instagram.com/reel/Other/", null, cfg, false, sharedPath = shared))
        val short = WebRules.cleanPath("https://youtube.com/shorts/q1w2e3?si=abc")
        assertEquals(WebRules.Decision.Allow, WebRules.decide(WebSite.YOUTUBE,
            "https://m.youtube.com/shorts/q1w2e3", null, cfg, false, sharedPath = short))
        assertEquals(WebRules.Decision.Block("YouTube Shorts"), WebRules.decide(WebSite.YOUTUBE,
            "https://m.youtube.com/shorts/next1", null, cfg, false, sharedPath = short))
    }

    @Test fun discoverPages() {
        assertEquals(WebRules.Decision.Block("Reddit Popular"),
            WebRules.decide(WebSite.REDDIT, "https://www.reddit.com/r/popular/", null, cfg, false, blockDiscover = true))
        assertEquals(WebRules.Decision.Allow,
            WebRules.decide(WebSite.REDDIT, "https://www.reddit.com/r/popular/", null, cfg, false, blockDiscover = false))
        assertEquals(WebRules.Decision.Allow,
            WebRules.decide(WebSite.REDDIT, "https://www.reddit.com/r/android/", null, cfg, false, blockDiscover = true))
        assertEquals(WebRules.Decision.Block("X Explore"),
            WebRules.decide(WebSite.X, "https://x.com/explore", null, cfg, false, blockDiscover = true))
        assertEquals(WebRules.Decision.Allow,
            WebRules.decide(WebSite.X, "https://x.com/explorer_guy", null, cfg, false, blockDiscover = true))
        assertEquals(WebRules.Decision.Block("Facebook Reels"),
            WebRules.decide(WebSite.FACEBOOK, "https://m.facebook.com/reel/123", null, cfg, false))
        assertEquals(WebRules.Decision.External,
            WebRules.decide(WebSite.LINKEDIN, "https://www.instagram.com/", null, cfg, false))
    }

    @Test fun followingFeed() {
        assertEquals("https://www.instagram.com/?variant=following",
            WebRules.feedRedirect(WebSite.INSTAGRAM, "https://www.instagram.com/", "following"))
        assertNull(WebRules.feedRedirect(WebSite.INSTAGRAM, "https://www.instagram.com/?variant=following", "following"))
        assertNull(WebRules.feedRedirect(WebSite.INSTAGRAM, "https://www.instagram.com/direct/inbox/", "following"))
        assertNull(WebRules.feedRedirect(WebSite.INSTAGRAM, "https://www.instagram.com/", "home"))
        assertNull(WebRules.feedRedirect(WebSite.YOUTUBE, "https://m.youtube.com/", "following"))
    }

    @Test fun sharedText() {
        assertEquals("https://www.instagram.com/reel/AbC/",
            WebRules.firstUrl("Look at this! https://www.instagram.com/reel/AbC/ so funny"))
        assertNull(WebRules.firstUrl("no link here"))
        assertEquals(WebSite.YOUTUBE, WebSite.forUrl("https://youtu.be/abc"))
        assertEquals(WebSite.X, WebSite.forUrl("https://twitter.com/someone/status/1"))
        assertNull(WebSite.forUrl("https://example.com/"))
    }
}

class SleepDaysTest {
    @Test fun daysAndHeadsUp() {
        val weeknights = Sleep.window(true, 23 * 60, 7 * 60, 0b0011111) // Mon-Fri nights
        assertTrue(Sleep.isSleeping(weeknights, 5, 23 * 60 + 10))   // Friday night
        assertTrue(Sleep.isSleeping(weeknights, 6, 6 * 60))         // early Saturday, from Friday night
        assertFalse(Sleep.isSleeping(weeknights, 6, 23 * 60 + 10))  // Saturday night is free
        assertEquals(30, Sleep.minutesToBedtime(weeknights, 1, 22 * 60 + 30))
        assertNull(Sleep.minutesToBedtime(weeknights, 6, 22 * 60 + 30))
        assertNull(Sleep.minutesToBedtime(weeknights, 1, 23 * 60 + 5))
    }
}

class DnsPacketTest {
    /** A DNS question for example.com from 10.111.222.1:40000 to 10.111.222.2:53. */
    private fun query(): ByteArray {
        val dns = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(),
            'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0, 1, 0, 1)
        val total = 28 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte(); p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        val c = DnsPacket.checksum(p, 0, 20); p[10] = (c shr 8).toByte(); p[11] = c.toByte()
        p[20] = (40000 shr 8).toByte(); p[21] = 40000.toByte(); p[23] = 53
        p[24] = ((8 + dns.size) shr 8).toByte(); p[25] = (8 + dns.size).toByte()
        dns.copyInto(p, 28)
        return p
    }

    @Test fun parsesQuery() {
        val p = query()
        val q = DnsPacket.parse(p, p.size)!!
        assertEquals(40000, q.srcPort)
        assertEquals(53, q.dstPort)
        assertEquals(29, q.payload.size)
        assertEquals(0x12, q.payload[0].toInt())
        assertNull(DnsPacket.parse(p, 20))
        val tcp = p.copyOf().also { it[9] = 6 }
        assertNull(DnsPacket.parse(tcp, tcp.size))
    }

    @Test fun buildsAnswerBackToTheAsker() {
        val p = query()
        val q = DnsPacket.parse(p, p.size)!!
        val answer = ByteArray(45) { it.toByte() }
        val r = DnsPacket.response(q, answer, answer.size)
        assertEquals(28 + 45, r.size)
        assertEquals(0, DnsPacket.checksum(r, 0, 20)) // a valid header sums to zero
        assertEquals(listOf<Byte>(10, 111, 222.toByte(), 2), r.copyOfRange(12, 16).toList()) // from the fake DNS
        assertEquals(listOf<Byte>(10, 111, 222.toByte(), 1), r.copyOfRange(16, 20).toList()) // to the asker
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        assertEquals(40000, ((r[22].toInt() and 0xFF) shl 8) or (r[23].toInt() and 0xFF))
        assertEquals(answer.toList(), r.copyOfRange(28, r.size).toList())
    }
}
