package com.scrollstop.app

import java.util.Locale

// Plain-Kotlin rules with no Android dependencies, so they can be unit tested on the JVM.

/** The short-video feeds ScrollStop knows how to find inside each app. */
enum class Platform(
    val key: String,
    val app: String,
    val feed: String,
    val packages: Set<String>,
) {
    INSTAGRAM("ig", "Instagram", "Reels", setOf("com.instagram.android")),
    YOUTUBE("yt", "YouTube", "Shorts", setOf("com.google.android.youtube")),
    FACEBOOK("fb", "Facebook", "Reels", setOf("com.facebook.katana", "com.facebook.lite")),
    SNAPCHAT("snap", "Snapchat", "Spotlight", setOf("com.snapchat.android"));

    val label get() = "$app $feed"

    companion object {
        private val byPkg = entries.flatMap { p -> p.packages.map { it to p } }.toMap()
        fun of(pkg: String?): Platform? = byPkg[pkg]
        fun byKey(key: String): Platform? = entries.firstOrNull { it.key == key }
    }
}

object Apps {
    val TIKTOK = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")

    /** View IDs of the address bar in common browsers. */
    val BROWSER_URL_BARS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.chrome.dev" to listOf("url_bar"),
        "com.chrome.canary" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.vivaldi.browser" to listOf("url_bar"),
        "com.kiwibrowser.browser" to listOf("url_bar"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view"),
        "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view", "display_url"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.mi.globalbrowser" to listOf("url"),
        "com.UCMobile.intl" to listOf("titlebar_url"),
    )

    /** Settings screens that can switch ScrollStop off or remove it. Watched only in strict mode. */
    val SETTINGS = setOf(
        "com.android.settings",
        "com.samsung.accessibility",
        "com.samsung.android.app.galaxyfinder",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.google.android.permissioncontroller",
    )
}

/**
 * Daily allowance for one feed. A value of 0 means "no limit of this kind";
 * when both are 0 there is no allowance at all and the feed is blocked right away.
 * Watching stops as soon as either limit is used up.
 */
data class Allowance(val minutes: Int, val videos: Int) {
    val isNone get() = minutes <= 0 && videos <= 0

    /** [usedVideos] includes the video currently on screen. */
    fun hasRoom(usedMs: Long, usedVideos: Long): Boolean {
        if (isNone) return false
        if (minutes > 0 && usedMs >= minutes * 60_000L) return false
        if (videos > 0 && usedVideos > videos) return false
        return true
    }

    fun msLeft(usedMs: Long): Long? =
        if (minutes > 0) (minutes * 60_000L - usedMs).coerceAtLeast(0) else null

    fun videosLeft(usedVideos: Long): Long? =
        if (videos > 0) (videos - usedVideos).coerceAtLeast(0) else null

    /** True if this allowance lets you watch more than [old] in some situation (a weakening). */
    fun allowsMoreThan(old: Allowance): Boolean = when {
        isNone -> false
        old.isNone -> true
        else -> cap(minutes) > cap(old.minutes) || cap(videos) > cap(old.videos)
    }

    fun describe(): String = when {
        isNone -> "None, blocked right away"
        minutes > 0 && videos > 0 -> "$minutes min or $videos videos a day, whichever comes first"
        minutes > 0 -> "$minutes min a day"
        else -> "$videos videos a day"
    }

    private fun cap(v: Int) = if (v <= 0) Int.MAX_VALUE else v

    companion object {
        val NONE = Allowance(0, 0)
    }
}

/**
 * Optional blocking hours. [days] is a bitmask, bit 0 = Monday ... bit 6 = Sunday.
 * A window whose end is before its start runs overnight; the part after midnight
 * belongs to the day the window started on.
 */
data class Schedule(val enabled: Boolean, val startMin: Int, val endMin: Int, val days: Int) {

    /** [isoDay] 1 = Monday ... 7 = Sunday. */
    fun isBlockingAt(isoDay: Int, minuteOfDay: Int): Boolean {
        if (!enabled) return true
        val prev = if (isoDay == 1) 7 else isoDay - 1
        return when {
            startMin == endMin -> dayOn(isoDay)
            startMin < endMin -> dayOn(isoDay) && minuteOfDay >= startMin && minuteOfDay < endMin
            else -> (dayOn(isoDay) && minuteOfDay >= startMin) || (dayOn(prev) && minuteOfDay < endMin)
        }
    }

    fun dayOn(isoDay: Int) = days and (1 shl (isoDay - 1)) != 0

    /** True if [other] blocks in fewer situations than this one (a weakening). */
    fun weakerThan(other: Schedule): Boolean {
        for (d in 1..7) for (m in 0 until 24 * 60 step 5) {
            if (other.isBlockingAt(d, m) && !isBlockingAt(d, m)) return true
        }
        return false
    }

    companion object {
        const val ALL_DAYS = 0b1111111
    }
}

/** Matches browser addresses that lead to short-video feeds. */
object UrlRules {
    data class Config(
        val igReels: Boolean = true,
        val igExplore: Boolean = false,
        val igStories: Boolean = false,
        val ytShorts: Boolean = true,
        val fbReels: Boolean = true,
        val fbWatch: Boolean = false,
        val snapSpotlight: Boolean = true,
        val tiktok: Boolean = false,
    )

    private val END = "(/|\\?|#|$)"
    private val IG_REELS = Regex("^/([^/?#]+/)?reels?$END")
    private val IG_EXPLORE = Regex("^/explore$END")
    private val IG_STORIES = Regex("^/stories/")
    private val YT_SHORTS = Regex("^/(shorts|feed/shorts)$END")
    private val FB_REELS = Regex("^/(.+/)?reels?$END")
    private val FB_WATCH = Regex("^/watch$END")
    private val SNAP_SPOTLIGHT = Regex("^/spotlight$END")

    /** Returns a label for the blocked feed, or null if the address is fine. */
    fun match(raw: String?, c: Config): String? {
        val s = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (s.isEmpty() || s.contains(' ')) return null // a search query, not an address
        val noScheme = s.substringAfter("://", s)
        val cut = noScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPort = if (cut < 0) noScheme else noScheme.substring(0, cut)
        val rest = if (cut < 0) "/" else noScheme.substring(cut)
        val path = if (rest.startsWith("/")) rest else "/$rest"
        var host = hostPort.substringBefore(':')
        for (prefix in listOf("www.", "m.", "mobile.", "web.", "touch.", "mbasic.")) host = host.removePrefix(prefix)
        if (!host.contains('.')) return null

        fun on(domain: String) = host == domain || host.endsWith(".$domain")

        return when {
            on("instagram.com") && c.igReels && IG_REELS.containsMatchIn(path) -> "Instagram Reels"
            on("instagram.com") && c.igExplore && IG_EXPLORE.containsMatchIn(path) -> "Instagram Explore"
            on("instagram.com") && c.igStories && IG_STORIES.containsMatchIn(path) -> "Instagram Stories"
            on("youtube.com") && c.ytShorts && YT_SHORTS.containsMatchIn(path) -> "YouTube Shorts"
            on("facebook.com") && c.fbReels && FB_REELS.containsMatchIn(path) -> "Facebook Reels"
            on("fb.watch") && c.fbReels -> "Facebook Reels"
            on("facebook.com") && c.fbWatch && FB_WATCH.containsMatchIn(path) -> "Facebook Watch"
            on("snapchat.com") && c.snapSpotlight && SNAP_SPOTLIGHT.containsMatchIn(path) -> "Snapchat Spotlight"
            on("tiktok.com") && c.tiktok -> "TikTok"
            else -> null
        }
    }
}

enum class LockType { NONE, TIMER, PIN, PHRASE }

object Lock {
    const val PHRASE = "I choose focus over scrolling"

    fun isLocked(type: LockType, until: Long, now: Long): Boolean = when (type) {
        LockType.NONE -> false
        LockType.TIMER -> now < until
        LockType.PIN, LockType.PHRASE -> true
    }

    fun phraseMatches(input: String) =
        input.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!').equals(PHRASE, ignoreCase = true)

    fun hashPin(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Per-app daily limits. 0 minutes = the whole app is blocked. Stored as "pkg=min;pkg=min". */
object AppLimits {
    fun parse(s: String?): Map<String, Int> =
        s.orEmpty().split(';').mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(0, i).trim().takeIf { it.isNotEmpty() }
                ?.let { k -> e.substring(i + 1).trim().toIntOrNull()?.let { k to it.coerceAtLeast(0) } }
        }.toMap()

    fun format(m: Map<String, Int>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** True if [new] for one app is weaker than [old] (null = no limit). */
    fun weakens(old: Int?, new: Int?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> new > old
    }

    fun isOver(limitMin: Int, usedMs: Long) = usedMs >= limitMin * 60_000L
}

object Fmt {
    fun duration(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            totalMin > 0 -> "${m}m"
            ms >= 1000 -> "${ms / 1000}s"
            else -> "0m"
        }
    }

    fun countdown(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun clock(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 24
        val m = minuteOfDay % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }
}

/** Picks the background colour around a tab button so its cover blends in. */
object Colors {
    /** Points just inside the edges of a box: where a tab button shows plain background, not its icon. */
    fun borderPoints(left: Int, top: Int, right: Int, bottom: Int, inset: Int, perEdge: Int): List<Pair<Int, Int>> {
        val l = left + inset
        val t = top + inset
        val r = right - 1 - inset
        val b = bottom - 1 - inset
        if (r <= l || b <= t || perEdge < 2) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(perEdge * 4)
        for (i in 0 until perEdge) {
            val x = l + (r - l) * i / (perEdge - 1)
            val y = t + (b - t) * i / (perEdge - 1)
            out += x to t
            out += x to b
            out += l to y
            out += r to y
        }
        return out
    }

    /** The most common opaque colour, or null if there is none. */
    fun dominant(pixels: IntArray): Int? {
        val counts = HashMap<Int, Int>()
        for (p in pixels) {
            if ((p ushr 24) < 0xF0) continue
            counts[p] = (counts[p] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    fun isDark(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b < 128
    }
}

/** Social sites ScrollStop can open in its own focused browser, with the feeds taken out. */
enum class WebSite(
    val key: String,
    val title: String,
    val home: String,
    /** Time spent here counts towards this app's stats and daily limit. */
    val pkg: String,
    val platform: Platform,
    /** Pages that stay inside ScrollStop. */
    val hosts: Set<String>,
    /** Sign-in pages that are allowed too. */
    val loginHosts: Set<String>,
) {
    INSTAGRAM(
        "instagram", "Instagram", "https://www.instagram.com/", "com.instagram.android", Platform.INSTAGRAM,
        setOf("instagram.com"), setOf("facebook.com", "fb.com", "fbsbx.com"),
    ),
    YOUTUBE(
        "youtube", "YouTube", "https://m.youtube.com/", "com.google.android.youtube", Platform.YOUTUBE,
        setOf("youtube.com", "youtu.be"), setOf("accounts.google.com", "consent.google.com", "google.com"),
    );

    companion object {
        fun byKey(key: String?): WebSite? = entries.firstOrNull { it.key == key }
    }
}

/** Decides what happens when a page inside the focused browser tries to open an address. */
object WebRules {
    sealed class Decision {
        object Allow : Decision()
        /** Open in the phone's normal browser or app. */
        object External : Decision()
        /** Do nothing (e.g. "open in the Instagram app" links, which would get around the blocks). */
        object Ignore : Decision()
        data class Block(val label: String) : Decision()
    }

    private val DM_REEL = Regex("^/reels?/[^/?#]+")

    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').lowercase(Locale.ROOT)

    fun pathOf(url: String): String {
        val rest = url.substringAfter("://", "")
        val i = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (i < 0 || rest[i] != '/') "/" else rest.substring(i)
    }

    private fun onDomain(host: String, domains: Set<String>) =
        domains.any { host == it || host.endsWith(".$it") }

    /**
     * [prevUrl] is the page the user was on; a reel opened from Instagram DMs is allowed when
     * [allowDmReel] is on, but the Reels feed itself never is.
     */
    fun decide(site: WebSite, url: String, prevUrl: String?, cfg: UrlRules.Config, allowDmReel: Boolean): Decision {
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return Decision.Allow
        if (scheme == "mailto" || scheme == "tel" || scheme == "sms") return Decision.External
        if (scheme != "http" && scheme != "https") return Decision.Ignore
        val host = hostOf(url)
        if (onDomain(host, site.hosts)) {
            val label = UrlRules.match(url, cfg) ?: return Decision.Allow
            val fromDm = prevUrl != null && onDomain(hostOf(prevUrl), site.hosts) &&
                pathOf(prevUrl).startsWith("/direct/")
            if (site == WebSite.INSTAGRAM && allowDmReel && fromDm && DM_REEL.containsMatchIn(pathOf(url)) &&
                !pathOf(url).matches(Regex("^/reels/?([?#].*)?$"))
            ) return Decision.Allow
            return Decision.Block(label)
        }
        if (onDomain(host, site.loginHosts)) return Decision.Allow
        return Decision.External
    }
}

/** Sleep mode: social apps are closed between bedtime and wake-up, every day. */
object Sleep {
    fun window(on: Boolean, start: Int, end: Int): Schedule? =
        if (on) Schedule(true, start, end, Schedule.ALL_DAYS) else null

    fun isSleeping(window: Schedule?, isoDay: Int, minuteOfDay: Int): Boolean =
        window != null && window.isBlockingAt(isoDay, minuteOfDay)

    /** True if [new] lets you use your apps at a time [old] didn't (a weakening). */
    fun weakens(old: Schedule?, new: Schedule?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> new.weakerThan(old)
    }
}
