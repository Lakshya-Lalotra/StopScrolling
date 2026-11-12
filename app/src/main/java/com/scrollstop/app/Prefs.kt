package com.scrollstop.app

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.UUID

/** All settings, stored in SharedPreferences. One shared instance per process. */
class Prefs private constructor(context: Context) {

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }

        private const val SCHEMA = 2
        const val SNOOZE_DAYS = 30L
        const val DAY_MS = 86_400_000L
    }

    val sp: SharedPreferences = context.getSharedPreferences("scrollstop", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun setInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun long(key: String, def: Long) = sp.getLong(key, def)
    private fun setLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun setStr(key: String, v: String) = sp.edit().putString(key, v).apply()

    // ---- per-feed blocking (keys "ig", "yt", "fb", "snap" are the same as version 1) ----
    fun blocked(p: Platform) = bool(p.key, true)
    fun setBlocked(p: Platform, v: Boolean) = setBool(p.key, v)

    fun hideTab(p: Platform) = bool("hide_${p.key}", true)
    fun setHideTab(p: Platform, v: Boolean) = setBool("hide_${p.key}", v)

    fun allowance(p: Platform) = Allowance(int("allow_min_${p.key}", 0), int("allow_n_${p.key}", 0))
    fun setAllowance(p: Platform, a: Allowance) = sp.edit()
        .putInt("allow_min_${p.key}", a.minutes.coerceAtLeast(0))
        .putInt("allow_n_${p.key}", a.videos.coerceAtLeast(0))
        .apply()

    var allowInstagramDm: Boolean get() = bool("ig_dm", false); set(v) = setBool("ig_dm", v)
    /** Opening the Instagram app takes you to ScrollStop's Instagram (no ads, suggestions or Reels). */
    var igUseScrollStop: Boolean get() = bool("ig_redirect", false); set(v) = setBool("ig_redirect", v)
    /** In the Instagram app, scroll past a hidden ad or suggested post once you stop on it. */
    var igSkipHidden: Boolean get() = bool("ig_skip", true); set(v) = setBool("ig_skip", v)
    /** When the user last snoozed suggested posts in the Instagram app (Instagram undoes it after 30 days). */
    var igSnoozedAt: Long get() = long("ig_snoozed", 0L); set(v) = setLong("ig_snoozed", v)
    var igSnoozeRemindedAt: Long get() = long("ig_snooze_remind", 0L); set(v) = setLong("ig_snooze_remind", v)
    fun igSnoozeDue(now: Long = System.currentTimeMillis()) = igSnoozedAt > 0 && now - igSnoozedAt >= SNOOZE_DAYS * DAY_MS
    var blockIgExplore: Boolean get() = bool("ig_explore", false); set(v) = setBool("ig_explore", v)
    var blockIgStories: Boolean get() = bool("ig_stories", false); set(v) = setBool("ig_stories", v)
    var blockYtHome: Boolean get() = bool("yt_home", false); set(v) = setBool("yt_home", v)
    var blockFbWatch: Boolean get() = bool("fb_watch", false); set(v) = setBool("fb_watch", v)
    var blockBrowsers: Boolean get() = bool("browsers", true); set(v) = setBool("browsers", v)
    var blockTiktok: Boolean get() = bool("tiktok", false); set(v) = setBool("tiktok", v)

    var appLimits: Map<String, Int>
        get() = AppLimits.parse(sp.getString("app_limits", ""))
        set(v) = setStr("app_limits", AppLimits.format(v))

    fun urlConfig() = UrlRules.Config(
        igReels = blocked(Platform.INSTAGRAM),
        igExplore = blockIgExplore,
        igStories = blockIgStories,
        ytShorts = blocked(Platform.YOUTUBE),
        fbReels = blocked(Platform.FACEBOOK),
        fbWatch = blockFbWatch,
        snapSpotlight = blocked(Platform.SNAPCHAT),
        tiktok = blockTiktok,
    )

    // ---- schedule ----
    var schedule: Schedule
        get() = Schedule(
            bool("sched", false), int("start", 9 * 60), int("end", 22 * 60), int("days", Schedule.ALL_DAYS)
        )
        set(v) = sp.edit().putBoolean("sched", v.enabled).putInt("start", v.startMin)
            .putInt("end", v.endMin).putInt("days", v.days).apply()

    fun inSchedule(): Boolean {
        val c = Calendar.getInstance()
        val iso = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Calendar: Sunday = 1 -> ISO Monday = 1
        return schedule.isBlockingAt(iso, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    // ---- sleep mode ----
    var sleepOn: Boolean get() = bool("sleep", false); set(v) = setBool("sleep", v)
    var sleepStart: Int get() = int("sleep_start", 23 * 60); set(v) = setInt("sleep_start", v)
    var sleepEnd: Int get() = int("sleep_end", 7 * 60); set(v) = setInt("sleep_end", v)
    var sleepDays: Int get() = int("sleep_days", Schedule.ALL_DAYS); set(v) = setInt("sleep_days", v)
    /** Minutes before bedtime to give a heads-up (0 = none). */
    var sleepReminder: Int get() = int("sleep_remind", 30); set(v) = setInt("sleep_remind", v)
    fun sleepWindow(): Schedule? = Sleep.window(sleepOn, sleepStart, sleepEnd, sleepDays)

    fun isoToday(): Int = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    fun minuteNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** True during sleep hours (breaks still pause it, unless strict mode has breaks turned off). */
    fun isSleeping(): Boolean {
        if (isPaused()) return false
        val c = Calendar.getInstance()
        val iso = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        return Sleep.isSleeping(sleepWindow(), iso, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    // ---- ScrollStop's own browser ----
    var webHideAds: Boolean get() = bool("web_ads", true); set(v) = setBool("web_ads", v)
    var webHideSuggested: Boolean get() = bool("web_suggested", true); set(v) = setBool("web_suggested", v)
    var webHideFeedReels: Boolean get() = bool("web_feed_reels", true); set(v) = setBool("web_feed_reels", v)
    var webIgMessagesOnly: Boolean get() = bool("web_ig_home", false); set(v) = setBool("web_ig_home", v)
    /** Instagram feed inside ScrollStop: "home", "following" (people you follow, newest first) or "favorites". */
    var webIgFeed: String get() = str("web_ig_feed", "following"); set(v) = setStr("web_ig_feed", v)
    /** Blocks endless discover pages: YouTube Explore, X Explore, Reddit Popular/All, Facebook Gaming. */
    var webBlockDiscover: Boolean get() = bool("web_discover", true); set(v) = setBool("web_discover", v)

    // ---- adult content filter ----
    var adultFilter: Boolean get() = bool("adult_filter", false); set(v) = setBool("adult_filter", v)

    // ---- breaks ----
    var pauseUntil: Long get() = long("pause", 0L); set(v) = setLong("pause", v)
    fun isPaused() = System.currentTimeMillis() < pauseUntil

    /** True when blocking should happen right now. */
    fun isActiveNow() = !isPaused() && inSchedule()

    // ---- strict mode ----
    var lockType: LockType
        get() = runCatching { LockType.valueOf(str("lock_type", "NONE")) }.getOrDefault(LockType.NONE)
        set(v) = setStr("lock_type", v.name)
    var lockUntil: Long get() = long("lock_until", 0L); set(v) = setLong("lock_until", v)
    var lockPinHash: String get() = str("lock_pin", ""); set(v) = setStr("lock_pin", v)
    val salt: String
        get() = sp.getString("salt", null) ?: UUID.randomUUID().toString().also { setStr("salt", it) }

    fun isLocked(): Boolean {
        val t = lockType
        if (t == LockType.TIMER && System.currentTimeMillis() >= lockUntil) {
            lockType = LockType.NONE // timer ran out
            return false
        }
        return Lock.isLocked(t, lockUntil, System.currentTimeMillis())
    }

    fun unlock() {
        sp.edit().putString("lock_type", LockType.NONE.name).putLong("lock_until", 0L).putString("lock_pin", "").apply()
    }

    var guardSettings: Boolean get() = bool("guard", true); set(v) = setBool("guard", v)

    // ---- block screen ----
    var showBlockScreen: Boolean get() = bool("block_screen", true); set(v) = setBool("block_screen", v)
    var customMessage: String get() = str("message", ""); set(v) = setStr("message", v.trim())

    // ---- housekeeping ----
    var everEnabled: Boolean get() = bool("ever_enabled", false); set(v) = setBool("ever_enabled", v)
    var lastOffWarning: Long get() = long("off_warned", 0L); set(v) = setLong("off_warned", v)

    /** Carries settings over from version 1 (single global allowance, phrase-only strict mode). */
    private fun migrate() {
        if (sp.getInt("schema", 1) >= SCHEMA) return
        val e = sp.edit()
        val oldAllowance = sp.getInt("allow", 0)
        if (oldAllowance > 0) {
            for (p in Platform.entries) e.putInt("allow_min_${p.key}", oldAllowance)
        }
        if (sp.getBoolean("strict", false)) e.putString("lock_type", LockType.PHRASE.name)
        e.remove("allow").remove("strict").remove("day").remove("blocks_today").remove("used_ms")
        e.putInt("schema", SCHEMA).apply()
    }
}
