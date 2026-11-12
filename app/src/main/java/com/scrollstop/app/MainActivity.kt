package com.scrollstop.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Four simple tabs, like Sociallite: Home (status and today), Apps (one row per app, tap for its
 * switches), Stats and Settings. Details live in bottom sheets instead of long pages.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private lateinit var ui: Ui
    private lateinit var page: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: BottomNavigationView
    private var tab = R.id.nav_home

    private val handler = Handler(Looper.getMainLooper())
    /** Updates the changing bits of the current page (countdowns, status) without rebuilding it. */
    private val refreshers = ArrayList<() -> Unit>()
    private var lastStructure = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (structure() != lastStructure) render() else refreshers.forEach { it() }
            handler.postDelayed(this, 1000)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private var afterVpn: (() -> Unit)? = null
    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            prefs.adultFilter = true
            FilterVpnService.start(this)
        } else {
            toast("The filter needs the VPN permission to work")
        }
        handler.postDelayed({ afterVpn?.invoke() ?: render() }, 600)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        stats = Stats.get(this)
        ui = Ui(this)
        page = findViewById(R.id.page)
        scroll = findViewById(R.id.scroll)
        nav = findViewById(R.id.bottomNav)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        tab = savedInstanceState?.getInt("tab", R.id.nav_home) ?: R.id.nav_home
        nav.selectedItemId = tab
        nav.setOnItemSelectedListener {
            tab = it.itemId
            render()
            scroll.scrollTo(0, 0)
            true
        }
        ServiceCheckWorker.schedule(this)
        publishShortcuts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        ScrollStopWidget.refresh(this)
    }

    /** Things that change which cards are shown; when they change the page is rebuilt. */
    private fun structure(): String = listOf(
        tab, ServiceState.isEnabled(this), BlockerService.isRunning, prefs.isLocked(), prefs.isPaused(),
        prefs.isSleeping(), ServiceState.batteryUnrestricted(this), ServiceState.notificationsAllowed(this),
    ).joinToString()

    private fun render() {
        val y = scroll.scrollY
        page.removeAllViews()
        refreshers.clear()
        lastStructure = structure()
        when (tab) {
            R.id.nav_block -> buildApps()
            R.id.nav_stats -> buildStats()
            R.id.nav_settings -> buildSettings()
            else -> buildHome()
        }
        refreshers.forEach { it() }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // =====================================================================
    // Home
    // =====================================================================

    private fun buildHome() {
        ui.header(page, getString(R.string.app_name), null)
        val enabled = ServiceState.isEnabled(this)
        val running = BlockerService.isRunning

        // ---- status ----
        val status = ui.card(page)
        val tvStatus = ui.text(status, "", com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        tvStatus.setTypeface(tvStatus.typeface, android.graphics.Typeface.BOLD)
        val tvSub = ui.body(status, "", muted = true)
        when {
            !enabled -> ui.button(status, "Turn on") { showEnableDialog() }.also { setTop(it, 10) }
            !running -> ui.button(status, "Fix it") { open(ServiceState.accessibilitySettings()) }.also { setTop(it, 10) }
        }
        refreshers += {
            val (t, s) = when {
                !enabled -> "Not protected" to "Turn on ScrollStop to block Reels, Shorts, ads and suggested posts."
                !running -> "Paused by Android" to "Switch ScrollStop off and on again in Accessibility settings."
                prefs.isSleeping() -> "😴 Sleep mode" to "Social apps are closed until ${Fmt.clock(prefs.sleepEnd)}."
                prefs.isPaused() -> "⏸ On a break" to
                    "Back on in ${Fmt.countdown(prefs.pauseUntil - System.currentTimeMillis())}"
                !prefs.inSchedule() -> "🌙 Outside blocking hours" to "Starts at ${Fmt.clock(prefs.schedule.startMin)}."
                else -> "🛡️ Protected" to "Reels, Shorts, ads and suggested posts are blocked."
            }
            tvStatus.text = t
            tvSub.text = if (prefs.isLocked()) "$s\n🔒 ${lockText()}" else s
        }
        val left = listOf(enabled, ServiceState.batteryUnrestricted(this), ServiceState.notificationsAllowed(this))
            .count { !it }
        if (left > 0 && enabled) {
            ui.divider(status)
            ui.navRow(status, "Finish setup", "$left left") { setupSheet() }
        }

        // ---- today ----
        val today = ui.card(page)
        val tvTime = ui.text(today, "", com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall)
        tvTime.setTypeface(tvTime.typeface, android.graphics.Typeface.BOLD)
        tvTime.setTextColor(ui.primaryColor())
        ui.body(today, "in social apps today", muted = true)
        val tvLine = ui.small(today, "")
        setTop(tvLine, 6)
        refreshers += {
            val appTimes = stats.appTimeOn(stats.day())
            val total = appTimes.values.sum()
            tvTime.text = Fmt.duration(total)
            val inside = WebSite.entries.map { it.pkg }.distinct().sumOf { stats.get(Stats.webMs(it)) }
            val pct = if (total > 0) (inside * 100 / total) else 0
            tvLine.text = "${stats.get(Stats.BLOCKS)} blocked · " +
                "${Platform.entries.sumOf { stats.get(Stats.shortCount(it)) }} short videos · $pct% locked in"
        }

        // ---- feed-free apps ----
        ui.section(page, "Without the feeds")
        val grid = ui.card(page)
        WebSite.entries.chunked(3).forEach { rowSites ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (site in rowSites) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ui.dp(8), 0, ui.dp(8))
                    setOnClickListener { openWeb(site) }
                }
                cell.addView(siteIcon(site, 48))
                ui.text(cell, site.title, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    .apply { gravity = Gravity.CENTER; setTop(this, 4) }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row)
        }

        // ---- break ----
        val brk = ui.card(page)
        when {
            prefs.isLocked() -> ui.body(brk, "Breaks are off while strict mode is on.", muted = true)
            prefs.isPaused() -> {
                val tv = ui.body(brk, "")
                refreshers += { tv.text = "On a break · ${Fmt.countdown(prefs.pauseUntil - System.currentTimeMillis())} left" }
                ui.buttonRow(brk, Triple("Resume now", Ui.Btn.FILLED) { prefs.pauseUntil = 0L; render() })
            }
            else -> {
                ui.title(brk, "Take a break")
                ui.buttonRow(
                    brk,
                    Triple("5 min", Ui.Btn.TONAL) { pause(5) },
                    Triple("15 min", Ui.Btn.TONAL) { pause(15) },
                    Triple("30 min", Ui.Btn.TONAL) { pause(30) },
                )
            }
        }
    }

    private fun setupSheet() {
        Sheet("Finish setup") { box, sheet ->
            step(box, ServiceState.isEnabled(this), "Accessibility service") { showEnableDialog() }
            step(box, ServiceState.batteryUnrestricted(this), "Run in the background") {
                if (!open(ServiceState.batteryRequest(this))) open(ServiceState.batterySettings())
            }
            step(box, ServiceState.notificationsAllowed(this), "Reminders") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                }
                handler.postDelayed({ sheet.refresh() }, 1500)
            }
            ui.small(box, "Xiaomi, Oppo, Vivo, Realme, Samsung: also set battery use to Unrestricted, and on Xiaomi " +
                "turn on Autostart.").also { setTop(it, 10) }
        }
    }

    private fun step(parent: LinearLayout, done: Boolean, label: String, onClick: () -> Unit) {
        ui.navRow(parent, (if (done) "✅  " else "⬜  ") + label, if (done) "Done" else "Allow") {
            if (!done) onClick()
        }
    }

    private fun pause(minutes: Int) {
        if (prefs.isLocked()) {
            showLockedDialog()
            return
        }
        prefs.pauseUntil = System.currentTimeMillis() + minutes * 60_000L
        render()
    }

    // =====================================================================
    // Apps
    // =====================================================================

    private fun buildApps() {
        ui.header(page, "Apps", "Tap an app to choose what to block.")
        val list = ui.group(page)
        Platform.entries.forEachIndexed { i, p ->
            if (i > 0) ui.divider(list)
            ui.navRow(list, p.app, summary(p), platformIcon(p)) { appSheet(p) }
        }
        ui.divider(list)
        ui.switchRow(list, "TikTok", if (prefs.blockTiktok) "Blocked completely" else "Not blocked",
            prefs.blockTiktok) { on, revert ->
            if (!on && prefs.isLocked()) {
                revert()
                showLockedDialog()
            } else {
                prefs.blockTiktok = on
                render()
            }
        }
        ui.divider(list)
        ui.navRow(list, "Other apps", prefs.appLimits.size.let { if (it == 0) "No limits" else "$it with limits" },
            ui.icon(null, "+", ui.mutedColor())) { limitsSheet() }

        ui.section(page, "More")
        val more = ui.group(page)
        toggle(more, "Block in web browsers", "Reels, Shorts and the rest in Chrome, Firefox and others",
            { prefs.blockBrowsers }, { prefs.blockBrowsers = it })
        ui.divider(more)
        ui.navRow(more, "Feed-free apps in ScrollStop", "Options") { webSheet() }
    }

    /** One line about what's blocked in an app. */
    private fun summary(p: Platform): String {
        if (!prefs.blocked(p)) return "Allowed"
        val a = prefs.allowance(p)
        val parts = mutableListOf(p.feed)
        if (p == Platform.INSTAGRAM) {
            if (prefs.webHideSuggested) parts += "suggested"
            if (prefs.webHideAds) parts += "ads"
        }
        return parts.joinToString(", ") + if (a.isNone) "" else " · allowance"
    }

    private fun appSheet(p: Platform) {
        Sheet(p.app) { box, sheet ->
            val on = prefs.blocked(p)
            ui.section(box, "Block")
            toggle(box, "Block ${p.feed}", null, { prefs.blocked(p) }, { prefs.setBlocked(p, it); sheet.refresh() })
            toggle(box, "Hide the ${p.feed} button", null, { prefs.hideTab(p) }, { prefs.setHideTab(p, it) }, enabled = on)
            when (p) {
                Platform.INSTAGRAM -> {
                    toggle(box, "Block suggested posts", "Posts from accounts you don't follow",
                        { prefs.webHideSuggested }, { prefs.webHideSuggested = it; sheet.refresh() })
                    toggle(box, "Block ads", "Sponsored posts and paid partnerships",
                        { prefs.webHideAds }, { prefs.webHideAds = it; sheet.refresh() })
                    toggle(box, "Skip past them", "Scrolls on by itself when you reach one",
                        { prefs.igSkipHidden }, { prefs.igSkipHidden = it },
                        enabled = prefs.webHideSuggested || prefs.webHideAds)
                    toggle(box, "Block Explore", null, { prefs.blockIgExplore }, { prefs.blockIgExplore = it })
                    toggle(box, "Block Stories", null, { prefs.blockIgStories }, { prefs.blockIgStories = it })
                    toggle(box, "Allow reels sent in DMs", "Just that one, no swiping on",
                        { prefs.allowInstagramDm }, { prefs.allowInstagramDm = it }, weakensWhen = true, enabled = on)
                }
                Platform.YOUTUBE -> toggle(box, "Search-only mode", "Hides the Home feed",
                    { prefs.blockYtHome }, { prefs.blockYtHome = it })
                Platform.FACEBOOK -> toggle(box, "Block the Video tab", null,
                    { prefs.blockFbWatch }, { prefs.blockFbWatch = it })
                Platform.SNAPCHAT -> Unit
            }

            if (on) {
                ui.section(box, "Daily allowance")
                val a = prefs.allowance(p)
                val value = if (a.isNone) "None" else a.describe().substringBefore(",")
                ui.navRow(box, "Free ${p.feed} each day", value) { allowanceDialog(p) { sheet.refresh() } }
                if (!a.isNone) {
                    ui.small(box, "Today: ${Fmt.duration(stats.get(Stats.shortMs(p)))} · " +
                        "${stats.get(Stats.shortCount(p))} videos")
                }
            }

            ui.section(box, "More")
            if (p == Platform.INSTAGRAM) {
                toggle(box, "Use ScrollStop's Instagram instead", "Opening Instagram takes you there",
                    { prefs.igUseScrollStop }, { prefs.igUseScrollStop = it })
                ui.navRow(box, "Snooze in Instagram's settings", snoozeValue()) { snoozeDialog { sheet.refresh() } }
            }
            WebSite.entries.firstOrNull { it.platform == p }?.let { site ->
                ui.navRow(box, "Open feed-free ${site.title}", null) { openWeb(site) }
            }
        }
    }

    private fun snoozeValue() = when {
        prefs.igSnoozedAt == 0L -> "Off"
        prefs.igSnoozeDue() -> "Ended"
        else -> "Until " + SimpleDateFormat("d MMM", Locale.getDefault())
            .format(Date(prefs.igSnoozedAt + Prefs.SNOOZE_DAYS * Prefs.DAY_MS))
    }

    private fun webSheet() {
        Sheet("Feed-free apps") { box, sheet ->
            ui.body(box, "Instagram, YouTube, Facebook, X, Reddit and LinkedIn inside ScrollStop, with the Reels, " +
                "Explore and Shorts buttons removed.", muted = true)
            ui.section(box, "Instagram")
            val feedNames = mapOf("following" to "Following", "favorites" to "Favorites", "home" to "Home")
            ui.navRow(box, "Feed", feedNames[prefs.webIgFeed]) {
                val keys = feedNames.keys.toList()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Instagram feed")
                    .setSingleChoiceItems(arrayOf("Following: people you follow, newest first", "Favorites only",
                        "Home: Instagram's mix"), keys.indexOf(prefs.webIgFeed)) { d, i ->
                        if (keys[i] == "home" && prefs.webIgFeed != "home" && prefs.isLocked()) showLockedDialog()
                        else prefs.webIgFeed = keys[i]
                        d.dismiss()
                        sheet.refresh()
                    }
                    .show()
            }
            toggle(box, "Hide reels in the feed", null, { prefs.webHideFeedReels }, { prefs.webHideFeedReels = it })
            toggle(box, "Messages only", "Hides the feed too", { prefs.webIgMessagesOnly }, { prefs.webIgMessagesOnly = it })
            ui.section(box, "All of them")
            toggle(box, "Block suggested posts", null, { prefs.webHideSuggested }, { prefs.webHideSuggested = it })
            toggle(box, "Block ads", null, { prefs.webHideAds }, { prefs.webHideAds = it })
            toggle(box, "Block Explore and Popular", "YouTube, X and Reddit",
                { prefs.webBlockDiscover }, { prefs.webBlockDiscover = it })
            ui.section(box, "Shortcuts")
            ui.navRow(box, "Open links in ScrollStop", null) { openByDefaultSettings() }
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                ui.navRow(box, "Add to home screen", null) { pinDialog() }
            }
        }
    }

    private fun limitsSheet() {
        Sheet("Other apps") { box, sheet ->
            ui.body(box, "Block an app completely, or close it after a while each day.", muted = true)
            val limits = prefs.appLimits
            if (limits.isNotEmpty()) ui.section(box, "Limits")
            val today = stats.appTimeOn(stats.day())
            for ((pkg, min) in limits.entries.sortedBy { appLabel(it.key).lowercase() }) {
                val value = if (min == 0) "Blocked" else "${Fmt.duration(min * 60_000L)} · ${Fmt.duration(today[pkg] ?: 0L)} used"
                ui.navRow(box, appLabel(pkg), value, ui.icon(appIcon(pkg), appLabel(pkg).take(1), ui.mutedColor())) {
                    limitDialog(pkg) { sheet.refresh() }
                }
            }
            ui.space(box, 8)
            ui.button(box, "Add an app", Ui.Btn.TONAL) { pickAppDialog { sheet.refresh() } }
        }
    }

    // =====================================================================
    // Stats
    // =====================================================================

    private fun buildStats() {
        ui.header(page, "Stats", null)
        val appTimes = stats.appTimeOn(stats.day())
        val total = appTimes.values.sum()

        val today = ui.card(page)
        val big = ui.text(today, Fmt.duration(total), com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall)
        big.setTypeface(big.typeface, android.graphics.Typeface.BOLD)
        big.setTextColor(ui.primaryColor())
        ui.body(today, "in social apps today", muted = true)
        val inside = WebSite.entries.map { it.pkg }.distinct().sumOf { stats.get(Stats.webMs(it)) }
        val nums = ui.stats(today, listOf("blocked", "short videos", "locked in"))
        nums[0].text = stats.get(Stats.BLOCKS).toString()
        nums[1].text = Platform.entries.sumOf { stats.get(Stats.shortCount(it)) }.toString()
        nums[2].text = "${if (total > 0) inside * 100 / total else 0}%"

        // ---- last 7 days ----
        val week = ui.card(page, "Last 7 days")
        val chart = BarChartView(this)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        chart.bars = (6 downTo 0).map { ago ->
            val key = stats.day(ago)
            val ms = stats.appTimeOn(key).values.sum()
            val label = runCatching { dayFmt.format(keyFmt.parse(key)!!) }.getOrDefault(key.takeLast(2))
            BarChartView.Bar(ms / 60_000f, label, Fmt.duration(ms), ago == 0)
        }
        week.addView(chart)
        val appWeek = (0..6).sumOf { stats.appTimeOn(stats.day(it)).values.sum() }
        ui.small(week, "Daily average ${Fmt.duration(appWeek / 7)}")

        // ---- by app ----
        val rows = appTimes.entries.filter { it.value >= 60_000 }.sortedByDescending { it.value }
        if (rows.isNotEmpty()) {
            ui.section(page, "Today by app")
            val apps = ui.group(page)
            rows.forEachIndexed { i, (pkg, ms) ->
                if (i > 0) ui.divider(apps)
                val p = Platform.of(pkg)
                val detail = p?.let { " · ${stats.get(Stats.shortCount(it))} ${it.feed.lowercase()}" } ?: ""
                ui.navRow(apps, appLabel(pkg), Fmt.duration(ms) + detail,
                    ui.icon(appIcon(pkg), appLabel(pkg).take(1), ui.mutedColor()), null)
            }
        }

        // ---- all time ----
        val all = ui.card(page, "All time")
        val totalBlocks = stats.get(Stats.BLOCKS, Stats.ALL)
        val nums2 = ui.stats(all, listOf("blocked", "saved (estimate)"))
        nums2[0].text = totalBlocks.toString()
        nums2[1].text = Fmt.duration(totalBlocks * Stats.MINUTES_SAVED_PER_BLOCK * 60_000L)

        ui.button(page, "Reset stats", Ui.Btn.TEXT) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset all stats?")
                .setMessage("Today's allowance counters are reset too.")
                .setPositiveButton("Reset") { _, _ ->
                    if (prefs.isLocked()) showLockedDialog() else {
                        stats.resetAll()
                        render()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // =====================================================================
    // Settings
    // =====================================================================

    private fun buildSettings() {
        ui.header(page, "Settings", null)
        val main = ui.group(page)
        ui.navRow(main, "Strict mode", if (prefs.isLocked()) "On 🔒" else "Off") { strictSheet() }
        ui.divider(main)
        ui.navRow(main, "Sleep mode",
            if (prefs.sleepOn) "${Fmt.clock(prefs.sleepStart)} – ${Fmt.clock(prefs.sleepEnd)}" else "Off") { sleepSheet() }
        ui.divider(main)
        val s = prefs.schedule
        ui.navRow(main, "Blocking hours", if (s.enabled) "${Fmt.clock(s.startMin)} – ${Fmt.clock(s.endMin)}" else "Always") {
            hoursSheet()
        }
        ui.divider(main)
        filterSwitch(main) { render() }

        val look = ui.group(page)
        toggle(look, "Full-screen reminder", "When something is blocked",
            { prefs.showBlockScreen }, { prefs.showBlockScreen = it })
        ui.divider(look)
        ui.navRow(look, "Reminder text", prefs.customMessage.ifBlank { "Random" }) { messageDialog() }

        val other = ui.group(page)
        ui.navRow(other, "Help", null) { helpSheet() }
        ui.divider(other)
        ui.navRow(other, "About", BuildConfig.VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle("ScrollStop ${BuildConfig.VERSION_NAME}")
                .setMessage("The blocker works offline and never sends anything anywhere. Internet is only used to " +
                    "load the social sites you open inside ScrollStop, and by the optional adult filter.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun filterSwitch(parent: LinearLayout, after: () -> Unit) {
        val sub = if (!prefs.adultFilter) "Blocks adult sites everywhere"
        else if (FilterVpnService.running) "On" else "Not running: switch off and on"
        toggle(parent, "Adult content filter", sub, { prefs.adultFilter }, { on ->
            if (on) {
                val ask = VpnService.prepare(this)
                if (ask == null) {
                    prefs.adultFilter = true
                    FilterVpnService.start(this)
                    handler.postDelayed(after, 600)
                } else {
                    afterVpn = after
                    askVpn.launch(ask)
                }
            } else {
                prefs.adultFilter = false
                FilterVpnService.stop(this)
                handler.postDelayed(after, 600)
            }
        })
    }

    private fun strictSheet() {
        Sheet("Strict mode") { box, sheet ->
            ui.body(box, "While it's on: no breaks, no turning blocks off, no bigger allowances, and ScrollStop can't " +
                "be switched off or uninstalled.", muted = true)
            ui.space(box, 10)
            if (prefs.isLocked()) {
                ui.title(box, "🔒 " + lockText()).setPadding(0, 0, 0, ui.dp(8))
                when (prefs.lockType) {
                    LockType.PIN -> ui.button(box, "Unlock with PIN", Ui.Btn.TONAL) { unlockDialog { sheet.refresh() } }
                    LockType.PHRASE -> ui.button(box, "Unlock", Ui.Btn.TONAL) { unlockDialog { sheet.refresh() } }
                    else -> Unit
                }
            } else {
                ui.button(box, "Turn on strict mode") { strictDialog { sheet.refresh() } }
            }
            ui.space(box, 6)
            toggle(box, "Protect ScrollStop", "Closes the settings screens that could turn it off",
                { prefs.guardSettings }, { prefs.guardSettings = it })
        }
    }

    private fun sleepSheet() {
        Sheet("Sleep mode") { box, sheet ->
            ui.body(box, "Social apps close at bedtime and open again when you wake up.", muted = true)
            toggle(box, "Sleep mode", null, { prefs.sleepOn }, { prefs.sleepOn = it; sheet.refresh() })
            if (!prefs.sleepOn) return@Sheet
            ui.buttonRow(box,
                Triple("Bedtime ${Fmt.clock(prefs.sleepStart)}", Ui.Btn.OUTLINED) { pickSleepTime(true) { sheet.refresh() } },
                Triple("Wake ${Fmt.clock(prefs.sleepEnd)}", Ui.Btn.OUTLINED) { pickSleepTime(false) { sheet.refresh() } },
            )
            dayChips(box, prefs.sleepDays) { days ->
                val new = Sleep.window(true, prefs.sleepStart, prefs.sleepEnd, days)
                if (prefs.isLocked() && Sleep.weakens(prefs.sleepWindow(), new)) false
                else {
                    prefs.sleepDays = days
                    true
                }
            }
            val reminders = listOf(0, 15, 30, 60)
            ui.navRow(box, "Heads-up before bedtime", if (prefs.sleepReminder == 0) "Off" else "${prefs.sleepReminder} min") {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Heads-up before bedtime")
                    .setItems(arrayOf("Off", "15 minutes before", "30 minutes before", "1 hour before")) { _, i ->
                        prefs.sleepReminder = reminders[i]
                        sheet.refresh()
                    }
                    .show()
            }
        }
    }

    private fun hoursSheet() {
        Sheet("Blocking hours") { box, sheet ->
            val s = prefs.schedule
            toggle(box, "Only block at certain times", "Outside these hours everything is allowed",
                { prefs.schedule.enabled }, { prefs.schedule = prefs.schedule.copy(enabled = it); sheet.refresh() },
                weakensWhen = true)
            if (!s.enabled) return@Sheet
            ui.buttonRow(box,
                Triple("From ${Fmt.clock(s.startMin)}", Ui.Btn.OUTLINED) { pickTime(true) { sheet.refresh() } },
                Triple("To ${Fmt.clock(s.endMin)}", Ui.Btn.OUTLINED) { pickTime(false) { sheet.refresh() } },
            )
            dayChips(box, s.days) { days ->
                val cur = prefs.schedule
                val next = cur.copy(days = days)
                if (prefs.isLocked() && next.weakerThan(cur)) false
                else {
                    prefs.schedule = next
                    true
                }
            }
        }
    }

    private fun helpSheet() {
        Sheet("Help") { box, _ ->
            ui.body(box,
                "• Android can turn accessibility apps off after updates. Turn ScrollStop on again.\n" +
                    "• Android 13+: switch greyed out? App info → ⋮ → \"Allow restricted settings\".\n" +
                    "• Xiaomi, Oppo, Vivo, Realme, Samsung: set battery use to Unrestricted.\n" +
                    "• After an update, switch ScrollStop off and on once so it can skip past hidden posts.",
                muted = true)
            ui.space(box, 6)
            ui.navRow(box, "Accessibility settings", null) { open(ServiceState.accessibilitySettings()) }
            ui.navRow(box, "App info", null) { open(ServiceState.appInfo(this)) }
            ui.navRow(box, "Battery settings", null) {
                if (!open(ServiceState.batteryRequest(this))) open(ServiceState.batterySettings())
            }
            ui.navRow(box, "Something isn't blocked?", "Capture") { captureDialog() }
        }
    }

    /** Asks the service to save the layout of the next social-app screen, to send to the developer. */
    private fun captureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Capture a screen layout")
            .setMessage("Open Instagram (or YouTube, Facebook, Snapchat) on the screen where something isn't blocked " +
                "and wait 3 seconds. ScrollStop saves that screen's layout and shows a notification to share it.\n\n" +
                "The file lists the screen's buttons and labels, including names and captions on screen. Only " +
                "share it with someone you trust.")
            .setPositiveButton("Start") { _, _ ->
                if (!BlockerService.isRunning) toast("Turn on ScrollStop first")
                else {
                    BlockerService.requestCapture()
                    toast("Now open the app and wait 3 seconds")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dayChips(parent: LinearLayout, days: Int, onChange: (Int) -> Boolean) {
        val group = ChipGroup(this)
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, n ->
            val chip = Chip(android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_ScrollStop_FilterChip)).apply {
                text = n
                isCheckable = true
                isChecked = days and (1 shl i) != 0
            }
            chip.setOnCheckedChangeListener { v, checked ->
                val cur = (0 until group.childCount).fold(0) { acc, k ->
                    if ((group.getChildAt(k) as Chip).isChecked) acc or (1 shl k) else acc
                }
                if (!onChange(cur)) {
                    v.isChecked = !checked
                    showLockedDialog()
                }
            }
            group.addView(chip)
        }
        parent.addView(group)
    }

    // =====================================================================
    // Bottom sheets
    // =====================================================================

    /** A bottom sheet whose content is rebuilt by [build] each time [refresh] is called. */
    private inner class Sheet(private val title: String, private val build: (LinearLayout, Sheet) -> Unit) {
        val dialog = BottomSheetDialog(this@MainActivity)
        private val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(28))
        }

        init {
            dialog.setContentView(ScrollView(this@MainActivity).apply { addView(box) })
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.setOnDismissListener { render() }
            refresh()
            dialog.show()
        }

        fun refresh() {
            box.removeAllViews()
            val head = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val t = ui.text(null, title, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            head.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ui.button(head, "Done", Ui.Btn.TEXT) { dialog.dismiss() }
            box.addView(head)
            build(box, this)
        }
    }

    /**
     * A switch bound to a setting. [weakensWhen] is the value that makes blocking weaker;
     * that direction is refused while strict mode is on.
     */
    private fun toggle(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        weakensWhen: Boolean = false,
        enabled: Boolean = true,
    ) {
        ui.switchRow(parent, title, subtitle, get(), enabled) { checked, revert ->
            if (checked == weakensWhen && prefs.isLocked()) {
                revert()
                showLockedDialog()
            } else {
                set(checked)
            }
        }
    }

    // =====================================================================
    // Dialogs
    // =====================================================================

    private fun allowanceDialog(p: Platform, then: () -> Unit) {
        val old = prefs.allowance(p)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        }
        ui.body(box, "Watch a little each day, then ${p.feed} closes until tomorrow. Both Off = blocked right away.",
            muted = true)
        val tvMin = ui.text(box, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        setTop(tvMin, 16)
        val sMin = Slider(this).apply { valueFrom = 0f; valueTo = 60f; stepSize = 1f; value = old.minutes.coerceIn(0, 60).toFloat() }
        box.addView(sMin)
        val tvN = ui.text(box, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        val sN = Slider(this).apply { valueFrom = 0f; valueTo = 100f; stepSize = 1f; value = old.videos.coerceIn(0, 100).toFloat() }
        box.addView(sN)
        fun label() {
            tvMin.text = "Time: " + if (sMin.value == 0f) "Off" else "${sMin.value.toInt()} min a day"
            tvN.text = "Videos: " + if (sN.value == 0f) "Off" else "${sN.value.toInt()} a day"
        }
        label()
        sMin.addOnChangeListener { _, _, _ -> label() }
        sN.addOnChangeListener { _, _, _ -> label() }
        MaterialAlertDialogBuilder(this)
            .setTitle("${p.label} allowance")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val new = Allowance(sMin.value.toInt(), sN.value.toInt())
                if (prefs.isLocked() && new.allowsMoreThan(old)) showLockedDialog()
                else {
                    prefs.setAllowance(p, new)
                    then()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

    private fun pickAppDialog(then: () -> Unit) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in prefs.appLimits }
            .map { AppEntry(it, appLabel(it), appIcon(it)) }
            .sortedBy { it.label.lowercase() }
        val adapter = object : ArrayAdapter<AppEntry>(this, android.R.layout.simple_list_item_1, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val e = getItem(position)!!
                v.text = e.label
                e.icon?.let { d ->
                    val s = ui.dp(32)
                    d.setBounds(0, 0, s, s)
                    v.setCompoundDrawables(d, null, null, null)
                    v.compoundDrawablePadding = ui.dp(16)
                }
                return v
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose an app")
            .setAdapter(adapter) { _, which -> limitDialog(apps[which].pkg, then) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun limitDialog(pkg: String, then: () -> Unit) {
        val current = prefs.appLimits[pkg]
        val values = listOf(0, 5, 15, 30, 45, 60, 90, 120, 180)
        val labels = values.map { if (it == 0) "Block completely" else "${Fmt.duration(it * 60_000L)} a day" }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(appLabel(pkg))
            .setItems(labels.toTypedArray()) { _, which ->
                val new = values[which]
                if (prefs.isLocked() && AppLimits.weakens(current, new)) showLockedDialog()
                else {
                    prefs.appLimits = prefs.appLimits + (pkg to new)
                    then()
                }
            }
            .setNegativeButton("Cancel", null)
        if (current != null) b.setNeutralButton("Remove limit") { _, _ ->
            if (prefs.isLocked()) showLockedDialog()
            else {
                prefs.appLimits = prefs.appLimits - pkg
                then()
            }
        }
        b.show()
    }

    private fun lockText(): String = when (prefs.lockType) {
        LockType.TIMER -> {
            val left = prefs.lockUntil - System.currentTimeMillis()
            val at = SimpleDateFormat(if (left < 20 * 3_600_000L) "h:mm a" else "EEE d MMM, h:mm a", Locale.getDefault())
                .format(Date(prefs.lockUntil))
            "Until $at (${Fmt.duration(left)} left)"
        }
        LockType.PIN -> "On until the PIN is entered"
        LockType.PHRASE -> "On until you type the sentence"
        LockType.NONE -> "Off"
    }

    private fun strictDialog(then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("How should it unlock?")
            .setItems(arrayOf("After a set time", "With a PIN someone else keeps", "By typing a sentence")) { _, which ->
                when (which) {
                    0 -> timerDialog(then)
                    1 -> pinSetupDialog(then)
                    else -> confirm("Turn on strict mode?", "To unlock you'll type:\n\n\"${Lock.PHRASE}\"") {
                        prefs.lockType = LockType.PHRASE
                        afterLock(then)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun timerDialog(then: () -> Unit) {
        val hours = listOf(1, 4, 8, 24, 72, 168)
        val labels = arrayOf("1 hour", "4 hours", "8 hours", "1 day", "3 days", "1 week")
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock for")
            .setItems(labels) { _, which ->
                confirm("Lock for ${labels[which]}?", "There's no way to unlock early.") {
                    prefs.lockUntil = System.currentTimeMillis() + hours[which] * 3_600_000L
                    prefs.lockType = LockType.TIMER
                    afterLock(then)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinSetupDialog(then: () -> Unit) {
        val input = pinInput("New PIN (4–8 digits)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Set a PIN")
            .setMessage("Ask a friend or parent to type a PIN you don't know.")
            .setView(padded(input))
            .setPositiveButton("Next") { _, _ ->
                val pin = input.text.toString()
                if (pin.length !in 4..8) toast("The PIN needs 4 to 8 digits")
                else {
                    val again = pinInput("Same PIN again")
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Confirm PIN")
                        .setView(padded(again))
                        .setPositiveButton("Lock") { _, _ ->
                            if (again.text.toString() != pin) toast("PINs didn't match")
                            else {
                                prefs.lockPinHash = Lock.hashPin(pin, prefs.salt)
                                prefs.lockType = LockType.PIN
                                afterLock(then)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun afterLock(then: () -> Unit) {
        prefs.pauseUntil = 0L
        toast("Strict mode is on 🔒")
        then()
    }

    private fun unlockDialog(then: () -> Unit) {
        val isPin = prefs.lockType == LockType.PIN
        val input = if (isPin) pinInput("PIN") else EditText(this).apply {
            hint = Lock.PHRASE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Unlock strict mode")
            .setMessage(if (isPin) "Enter the PIN." else "Type exactly:\n\n\"${Lock.PHRASE}\"")
            .setView(padded(input))
            .setPositiveButton("Unlock") { _, _ ->
                val ok = if (isPin) Lock.hashPin(input.text.toString(), prefs.salt) == prefs.lockPinHash
                else Lock.phraseMatches(input.text.toString())
                if (ok) {
                    prefs.unlock()
                    toast("Strict mode is off")
                    then()
                } else {
                    toast(if (isPin) "Wrong PIN" else "That didn't match. Staying focused 💪")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLockedDialog() {
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("🔒 Strict mode is on")
            .setMessage(lockText() + ".\n\nYou can make blocking stricter, but not looser.")
            .setPositiveButton("OK", null)
        if (prefs.lockType == LockType.PIN || prefs.lockType == LockType.PHRASE) {
            b.setNeutralButton("Unlock") { _, _ -> unlockDialog { render() } }
        }
        b.show()
    }

    private fun pickTime(start: Boolean, then: () -> Unit) {
        val cur = prefs.schedule
        val m = if (start) cur.startMin else cur.endMin
        TimePickerDialog(this, { _, h, min ->
            val v = h * 60 + min
            val next = if (start) cur.copy(startMin = v) else cur.copy(endMin = v)
            if (prefs.isLocked() && next.weakerThan(cur)) showLockedDialog()
            else {
                prefs.schedule = next
                then()
            }
        }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun pickSleepTime(bedtime: Boolean, then: () -> Unit) {
        val cur = if (bedtime) prefs.sleepStart else prefs.sleepEnd
        TimePickerDialog(this, { _, h, min ->
            val v = h * 60 + min
            val old = prefs.sleepWindow()
            val new = Sleep.window(true, if (bedtime) v else prefs.sleepStart, if (bedtime) prefs.sleepEnd else v,
                prefs.sleepDays)
            if (prefs.isLocked() && Sleep.weakens(old, new)) showLockedDialog()
            else {
                if (bedtime) prefs.sleepStart = v else prefs.sleepEnd = v
                then()
            }
        }, cur / 60, cur % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun messageDialog() {
        val input = EditText(this).apply {
            setText(prefs.customMessage)
            hint = "e.g. You said you'd read tonight."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Reminder text")
            .setMessage("Shown when something is blocked. Leave empty for random nudges.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                prefs.customMessage = input.text.toString()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun snoozeDialog(then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Snooze in Instagram's settings")
            .setMessage(
                "Instagram also has its own switch. It lasts 30 days, so ScrollStop reminds you to renew it.\n\n" +
                    "1. In Instagram, open your profile and tap ☰\n" +
                    "2. Tap \"Suggested content\"\n" +
                    "3. Turn on \"Snooze suggested posts in feed\"\n\n" +
                    "Can't find it? Search \"suggested\" in Instagram's settings."
            )
            .setPositiveButton("Done, remind me") { _, _ ->
                prefs.igSnoozedAt = System.currentTimeMillis()
                prefs.igSnoozeRemindedAt = 0L
                then()
            }
            .setNeutralButton("Open Instagram") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                if (launch == null || !open(launch)) toast("Instagram isn't installed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEnableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on ScrollStop")
            .setMessage(
                "1. Tap \"Open settings\"\n" +
                    "2. Find ScrollStop under Installed / Downloaded apps\n" +
                    "3. Switch it on and allow\n\n" +
                    "Greyed out? (Android 13+) Open App info → ⋮ → \"Allow restricted settings\", then try again.\n\n" +
                    "ScrollStop only looks for Reels, Shorts, ads and suggested posts. It never stores or sends " +
                    "what's on your screen."
            )
            .setPositiveButton("Open settings") { _, _ -> open(ServiceState.accessibilitySettings()) }
            .setNeutralButton("App info") { _, _ -> open(ServiceState.appInfo(this)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, msg: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Turn on") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================================
    // Feed-free apps and shortcuts
    // =====================================================================

    private fun openWeb(site: WebSite) {
        if (prefs.isSleeping()) {
            toast("Sleep mode is on until ${Fmt.clock(prefs.sleepEnd)}")
            return
        }
        startActivity(WebAppActivity.intent(this, site))
    }

    private fun shortcutLabel(site: WebSite) = when (site) {
        WebSite.INSTAGRAM, WebSite.FACEBOOK -> "${site.title} (no Reels)"
        WebSite.YOUTUBE -> "YouTube (no Shorts)"
        else -> "${site.title} (focus)"
    }

    private fun pinDialog() {
        val sites = WebSite.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Add to home screen")
            .setItems(sites.map { shortcutLabel(it) }.toTypedArray()) { _, i ->
                ShortcutManagerCompat.requestPinShortcut(this, shortcut(sites[i]), null)
            }
            .show()
    }

    private fun openByDefaultSettings() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName"))
        } else ServiceState.appInfo(this)
        if (!open(i)) open(ServiceState.appInfo(this))
    }

    private fun shortcut(site: WebSite): ShortcutInfoCompat {
        val label = shortcutLabel(site)
        val icon = when (site) {
            WebSite.INSTAGRAM -> R.mipmap.ic_web_instagram
            WebSite.YOUTUBE -> R.mipmap.ic_web_youtube
            else -> R.mipmap.ic_launcher
        }
        return ShortcutInfoCompat.Builder(this, "web_${site.key}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(this, icon))
            .setIntent(WebAppActivity.intent(this, site))
            .build()
    }

    /** Long-press ScrollStop's icon to jump straight into the feed-free apps. */
    private fun publishShortcuts() {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, WebSite.entries.take(4).map { shortcut(it) })
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private val brandColors = mapOf(
        "instagram" to 0xFFC13584.toInt(), "youtube" to 0xFFE53935.toInt(), "facebook" to 0xFF1877F2.toInt(),
        "x" to 0xFF222222.toInt(), "reddit" to 0xFFFF4500.toInt(), "linkedin" to 0xFF0A66C2.toInt(),
        "snap" to 0xFFFFC400.toInt(), "ig" to 0xFFC13584.toInt(), "yt" to 0xFFE53935.toInt(), "fb" to 0xFF1877F2.toInt(),
    )

    private fun platformIcon(p: Platform): View {
        val installed = p.packages.firstOrNull { ServiceState.isInstalled(this, it) }
        return ui.icon(installed?.let { appIcon(it) }, p.app.take(1), brandColors[p.key] ?: ui.primaryColor())
    }

    private fun siteIcon(site: WebSite, size: Int): View {
        val d = if (ServiceState.isInstalled(this, site.pkg)) appIcon(site.pkg) else null
        return ui.icon(d, site.title.take(1), brandColors[site.key] ?: ui.primaryColor(), size)
    }

    private fun setTop(v: View, dp: Int) {
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = ui.dp(dp)
    }

    private fun pinInput(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun padded(v: View): View = LinearLayout(this).apply {
        setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun open(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun appLabel(pkg: String): String = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        WebSite.entries.firstOrNull { it.pkg == pkg }?.title ?: pkg
    }

    private fun appIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
