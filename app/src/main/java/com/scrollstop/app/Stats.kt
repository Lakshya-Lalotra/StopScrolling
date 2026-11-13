package com.scrollstop.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Daily counters (time in apps, time and videos in short-video feeds, blocks).
 * Changes are kept in memory and written to disk every few seconds, because the
 * service updates them once a second.
 *
 * Keys look like "20261112|app_ms:com.instagram.android"; all-time totals use the day "all".
 */
class Stats private constructor(context: Context) {

    companion object {
        @Volatile private var instance: Stats? = null
        fun get(context: Context): Stats = instance ?: synchronized(this) {
            instance ?: Stats(context.applicationContext).also { instance = it }
        }

        const val ALL = "all"
        const val BLOCKS = "blocks"
        /** Ads and suggested posts removed from a feed (by the app itself, at ScrollStop's request). */
        const val FEED_REMOVED = "feed_removed"
        fun blocks(key: String) = "blocks:$key"
        fun shortMs(p: Platform) = "sv_ms:${p.key}"
        fun shortCount(p: Platform) = "sv_n:${p.key}"
        fun appMs(pkg: String) = "app_ms:$pkg"
        /** Time spent in that app inside ScrollStop's own browser (also counted in [appMs]). */
        fun webMs(pkg: String) = "web_ms:$pkg"

        /** Rough guess used for "time saved": one blocked opening ≈ 3 minutes of scrolling avoided. */
        const val MINUTES_SAVED_PER_BLOCK = 3
        private const val KEEP_DAYS = 60
    }

    private val sp: SharedPreferences = context.getSharedPreferences("scrollstop_stats", Context.MODE_PRIVATE)
    private val pending = HashMap<String, Long>()
    private val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    init {
        // Version 1 kept an all-time block count in the settings file.
        val old = context.getSharedPreferences("scrollstop", Context.MODE_PRIVATE)
        val oldTotal = old.getInt("blocks_total", 0)
        if (oldTotal > 0) {
            sp.edit().putLong("$ALL|$BLOCKS", sp.getLong("$ALL|$BLOCKS", 0) + oldTotal).apply()
            old.edit().remove("blocks_total").apply()
        }
    }

    @Synchronized fun day(daysAgo: Int = 0): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return fmt.format(c.time)
    }

    @Synchronized fun add(metric: String, delta: Long, day: String = day()) {
        val k = "$day|$metric"
        pending[k] = (pending[k] ?: 0L) + delta
    }

    @Synchronized fun get(metric: String, day: String = day()): Long {
        val k = "$day|$metric"
        return sp.getLong(k, 0L) + (pending[k] ?: 0L)
    }

    fun recordBlock(key: String) {
        add(BLOCKS, 1)
        add(blocks(key), 1)
        add(BLOCKS, 1, ALL)
        flush()
    }

    /** Total time today in every app that ScrollStop keeps track of. */
    @Synchronized fun appTimeOn(day: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        val prefix = "$day|app_ms:"
        for ((k, v) in sp.all) if (k.startsWith(prefix) && v is Long) out[k.removePrefix(prefix)] = v
        for ((k, v) in pending) if (k.startsWith(prefix)) {
            val pkg = k.removePrefix(prefix)
            out[pkg] = (out[pkg] ?: 0L) + v
        }
        return out
    }

    @Synchronized fun flush() {
        if (pending.isEmpty()) return
        val e = sp.edit()
        for ((k, v) in pending) e.putLong(k, sp.getLong(k, 0L) + v)
        pending.clear()
        pruneInto(e)
        e.apply()
    }

    @Synchronized fun resetAll() {
        pending.clear()
        sp.edit().clear().apply()
    }

    private var lastPrunedDay = ""

    private fun pruneInto(e: SharedPreferences.Editor) {
        val today = day()
        if (today == lastPrunedDay) return
        lastPrunedDay = today
        val oldest = day(KEEP_DAYS)
        for (k in sp.all.keys) {
            val d = k.substringBefore('|')
            if (d != ALL && d.length == 8 && d < oldest) e.remove(k)
        }
    }
}
