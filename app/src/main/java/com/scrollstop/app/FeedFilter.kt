package com.scrollstop.app

import java.util.Locale

/** Words Instagram puts on ads and on suggested posts (the app and the website use the same ones). */
object FeedLabels {
    private val AD = setOf(
        "sponsored", "sponsorisé", "sponsorisée", "gesponsert", "patrocinado", "patrocinada", "publicidad",
        "sponsorizzato", "gesponsord", "sponsorowane", "реклама", "sponsorlu", "sponzorováno", "प्रायोजित",
        "प्रायोजित पोस्ट", "স্পনসর করা", "ஸ்பான்சர் செய்யப்பட்டது", "ప్రాయోజితం", "スポンサー", "광고", "赞助内容", "贊助",
        "được tài trợ", "bersponsor", "disponsori", "ممول", "χορηγούμενη", "sponsrad", "sponsoreret",
    )
    private val AD_STARTS = listOf("paid partnership", "sponsored ·", "sponsored •", "ad ·", "ad •")
    /** Instagram's newer label is just "Ad" (and in Hindi "विज्ञापन"). */
    private val AD_SHORT = setOf("ad", "ads", "विज्ञापन")
    /** The coloured button bar under an ad's photo. Ordinary posts don't have one. */
    private val AD_BUTTONS = setOf(
        "order now", "shop now", "buy now", "learn more", "install now", "install", "download", "sign up",
        "book now", "apply now", "get offer", "get quote", "contact us", "send message", "send whatsapp message",
        "watch more", "listen now", "play game", "use app", "see menu", "get directions", "donate now", "call now",
        "get started", "subscribe", "open link", "visit website", "abhi order karein", "अभी ऑर्डर करें", "और जानें",
    )
    private val SUGGESTED = setOf(
        "suggested for you", "suggested posts", "suggested post", "suggested reels", "suggested threads",
        "suggested", "recommended for you", "popular on instagram", "based on your activity",
        "posts you might like", "more posts you might like", "suggestions for you", "सुझाए गए पोस्ट", "आपके लिए सुझाव",
    )
    private val SUGGESTED_STARTS = listOf("because you liked", "because you follow", "because you watched", "based on your")

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    fun isAd(text: String?): Boolean {
        val t = norm(text ?: return false)
        return t in AD || t in AD_SHORT || AD_STARTS.any { t.startsWith(it) }
    }

    /** Options in a post's ⋯ menu that make Instagram itself remove the post from the feed. */
    private val NOT_INTERESTED = setOf(
        "not interested", "दिलचस्पी नहीं है", "रुचि नहीं है", "no me interesa", "não tenho interesse", "pas intéressé",
        "pas intéressé(e)", "nicht interessiert", "non mi interessa", "غير مهتم", "tidak tertarik", "ilgilenmiyorum",
    )
    private val HIDE_AD = setOf(
        "hide ad", "विज्ञापन छुपाएँ", "विज्ञापन छिपाएँ", "विज्ञापन छुपाएं", "ocultar anuncio", "ocultar anúncio",
        "masquer la publicité", "werbeanzeige verbergen", "nascondi inserzione", "إخفاء الإعلان", "sembunyikan iklan",
        "reklamı gizle",
    )
    /** After "Hide ad" Instagram may ask why; any of these answers is fine. */
    private val AD_REASONS = setOf(
        "it's irrelevant", "it’s irrelevant", "not relevant", "irrelevant", "it's repetitive", "it’s repetitive",
        "i keep seeing this", "seen it too many times",
    )
    /** Things that are only in a post's ⋯ menu: if we see them, the menu is open. */
    private val MENU_HINTS = setOf(
        "about this account", "why you're seeing this post", "why you’re seeing this post", "why you're seeing this ad",
        "why you’re seeing this ad", "report", "report ad", "unfollow", "add to favorites", "copy link", "interested",
    )

    fun isRemoveOption(text: String?, kind: FeedKind): Boolean {
        val t = norm(text ?: return false)
        return t in NOT_INTERESTED || (kind == FeedKind.AD && t in HIDE_AD)
    }

    fun isAdReason(text: String?): Boolean = text != null && norm(text) in AD_REASONS

    fun isMenuHint(text: String?): Boolean = text != null && norm(text) in MENU_HINTS

    /** A call-to-action button such as "Order now", which only ads carry. */
    fun isAdButton(text: String?): Boolean = text != null && norm(text) in AD_BUTTONS

    fun isSuggested(text: String?): Boolean {
        val t = norm(text ?: return false)
        return t in SUGGESTED || SUGGESTED_STARTS.any { t.startsWith(it) }
    }
}

enum class FeedKind(val label: String) { AD("Ad hidden"), SUGGESTED("Suggested post hidden") }

/**
 * One row of Instagram's feed list as ScrollStop saw it. A post is several rows (header, photo,
 * buttons, caption...). [header] is the account name when the row is a post header, "" for a header
 * whose name couldn't be read, or null for other rows.
 */
data class FeedRow(
    val top: Int,
    val bottom: Int,
    val header: String? = null,
    val ad: Boolean = false,
    val suggestedLabel: Boolean = false,
    /** A "Follow" button in the post header: the account isn't one you follow. */
    val follow: Boolean = false,
)

data class FeedCover(val top: Int, val bottom: Int, val kind: FeedKind, val headerTop: Int?, val headerBottom: Int?)

data class FeedResult(
    val covers: List<FeedCover>,
    /** How far to scroll down to get past the hidden post on screen, or null to leave it. */
    val skipBy: Int?,
)

/**
 * Remembers which posts were hidden between looks at the screen, so the bottom half of an ad stays
 * hidden after its header (the only part that says "Sponsored") has scrolled off the top.
 */
class FeedMemory {
    /** Accounts of the posts seen last time, top to bottom, with what was hidden. */
    var order: List<Pair<String, FeedKind?>> = emptyList()
    /** What the post at the very top (its header already scrolled away) was. */
    var leading: FeedKind? = null
    /** Last scroll direction: 1 = down the feed, -1 = back up, 0 = unknown. */
    var direction = 0

    fun reset() {
        order = emptyList()
        leading = null
        direction = 0
    }
}

/** Works out which parts of the feed to cover. Plain Kotlin so it can be unit tested. */
object FeedPlan {
    private class Group(val rows: MutableList<FeedRow>, var header: FeedRow?, val labelOnly: Boolean, val leading: Boolean)

    fun plan(
        rows: List<FeedRow>,
        listTop: Int,
        listBottom: Int,
        memory: FeedMemory,
        hideAds: Boolean,
        hideSuggested: Boolean,
    ): FeedResult {
        val sorted = rows.filter { it.bottom > listTop && it.top < listBottom }.sortedBy { it.top }
        if (sorted.isEmpty() || (!hideAds && !hideSuggested)) return FeedResult(emptyList(), null)

        // 1) Split the rows into posts.
        val groups = ArrayList<Group>()
        for (r in sorted) {
            val cur = groups.lastOrNull()
            when {
                r.header != null -> {
                    // "Suggested for you" banner right above a post header belongs to that post.
                    if (cur != null && cur.labelOnly && cur.header == null) {
                        cur.rows += r
                        cur.header = r
                    } else groups += Group(mutableListOf(r), r, labelOnly = false, leading = false)
                }
                r.suggestedLabel -> groups += Group(mutableListOf(r), null, labelOnly = true, leading = false)
                cur == null -> groups += Group(mutableListOf(r), null, labelOnly = false, leading = true)
                else -> cur.rows += r
            }
        }

        // 2) Decide what each post is.
        val firstHeaderName = groups.firstOrNull { it.header != null }?.header?.header
        val kinds = groups.map { g ->
            when {
                g.leading -> leadingKind(firstHeaderName, groups.any { it.header != null }, memory)
                hideAds && g.rows.any { it.ad } -> FeedKind.AD
                hideSuggested && (g.labelOnly || g.rows.any { it.suggestedLabel } || g.header?.follow == true) ->
                    FeedKind.SUGGESTED
                else -> null
            }
        }.map { k -> if ((k == FeedKind.AD && !hideAds) || (k == FeedKind.SUGGESTED && !hideSuggested)) null else k }

        // 3) Cover hidden posts from their first row down to where the next post starts.
        val covers = ArrayList<FeedCover>()
        groups.forEachIndexed { i, g ->
            val kind = kinds[i] ?: return@forEachIndexed
            val top = if (g.leading) listTop else maxOf(listTop, g.rows.first().top)
            val bottom = minOf(listBottom, groups.getOrNull(i + 1)?.rows?.first()?.top ?: maxOf(g.rows.last().bottom, listBottom))
            if (bottom - top < 8) return@forEachIndexed
            val prev = covers.lastOrNull()
            if (prev != null && prev.bottom >= top - 2 && prev.kind == kind) {
                covers[covers.size - 1] = prev.copy(bottom = bottom)
            } else {
                covers += FeedCover(top, bottom, kind, g.header?.top, g.header?.bottom)
            }
        }

        // 4) Remember for next time.
        if (groups.any { it.header != null }) {
            memory.order = groups.indices.filter { groups[it].header != null }
                .map { (groups[it].header!!.header ?: "") to kinds[it] }
            memory.leading = if (groups.first().leading) kinds.first() else null
        } else {
            // Only the middle of one post is on screen: keep the order, remember what this post is.
            memory.leading = kinds.firstOrNull()
        }

        // 5) If a hidden post has reached the top and fills most of the screen, scroll past it.
        val height = listBottom - listTop
        var skip: Int? = null
        val first = covers.firstOrNull()
        if (first != null && first.top <= listTop + height / 4 && first.bottom - first.top >= height * 45 / 100) {
            val nextStart = groups.firstOrNull { g -> g.rows.first().top >= first.bottom - 2 && !g.leading }?.rows?.first()?.top
            val by = (nextStart?.takeIf { it < listBottom } ?: first.bottom) - listTop
            if (by > height / 10) skip = minOf(by, height * 8 / 10)
        }
        return FeedResult(covers, skip)
    }

    /** What the post at the top of the screen is, when its header has already scrolled away. */
    private fun leadingKind(firstHeaderName: String?, anyHeader: Boolean, memory: FeedMemory): FeedKind? {
        if (anyHeader && !firstHeaderName.isNullOrEmpty()) {
            val i = memory.order.indexOfFirst { it.first == firstHeaderName }
            if (i > 0) return memory.order[i - 1].second
            if (i == 0) return memory.leading
            // A post we haven't seen yet: scrolling down, the one above it is the last one we saw.
            if (memory.direction >= 0 && memory.order.isNotEmpty()) return memory.order.last().second
            return memory.leading
        }
        if (!anyHeader && memory.direction >= 0 && memory.order.isNotEmpty()) return memory.order.last().second
        return memory.leading
    }
}
