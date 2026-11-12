package com.scrollstop.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
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
import android.widget.ImageView
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val askVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            prefs.adultFilter = true
            FilterVpnService.start(this)
        } else {
            toast("The filter needs the VPN permission to work")
        }
        handler.postDelayed({ render() }, 600)
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
        ServiceState.batteryUnrestricted(this), ServiceState.notificationsAllowed(this),
    ).joinToString()

    private fun render() {
        val y = scroll.scrollY
        page.removeAllViews()
        refreshers.clear()
        lastStructure = structure()
        when (tab) {
            R.id.nav_block -> buildBlock()
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
        ui.header(page, getString(R.string.app_name), "Block Reels, Shorts & endless scrolling")

        val enabled = ServiceState.isEnabled(this)
        val running = BlockerService.isRunning

        // ---- status ----
        val status = ui.card(page)
        val tvStatus = ui.title(status, "")
        val tvSub = ui.body(status, "", muted = true)
        when {
            !enabled -> ui.button(status, "Turn on blocker") { showEnableDialog() }
            !running -> ui.button(status, "Open Accessibility settings") { open(ServiceState.accessibilitySettings()) }
        }
        refreshers += {
            val (t, s) = when {
                !enabled -> "🔴  Blocker is off" to "Turn it on so ScrollStop can close Reels and Shorts for you."
                !running -> "🟠  Blocker isn't running" to
                    "Android stopped it. In Accessibility settings, switch ScrollStop off and on again."
                prefs.isSleeping() -> "😴  Sleep mode" to
                    "Social apps are closed until ${Fmt.clock(prefs.sleepEnd)}."
                prefs.isPaused() -> "⏸  On a break" to
                    "Blocking comes back in ${Fmt.countdown(prefs.pauseUntil - System.currentTimeMillis())}"
                !prefs.inSchedule() -> "🌙  Outside blocking hours" to
                    "Blocking starts again at ${Fmt.clock(prefs.schedule.startMin)}."
                else -> "🟢  Blocker is on" to "Reels, Shorts and Spotlight close automatically."
            }
            tvStatus.text = t
            tvSub.text = if (prefs.isLocked()) "$s\n🔒 ${lockText()}" else s
        }

        // ---- setup checklist ----
        val battery = ServiceState.batteryUnrestricted(this)
        val notes = ServiceState.notificationsAllowed(this)
        if (!enabled || !battery || !notes) {
            val c = ui.card(page, "Finish setup", "So blocking keeps working in the background.")
            setupStep(c, enabled, "Turn on the accessibility service", "Open") { showEnableDialog() }
            setupStep(c, battery, "Let ScrollStop run in the background", "Allow") {
                if (!open(ServiceState.batteryRequest(this))) open(ServiceState.batterySettings())
            }
            setupStep(c, notes, "Get a reminder if the blocker gets switched off", "Allow") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
            ui.small(c, "Xiaomi, Oppo, Vivo, Realme, Samsung: also set ScrollStop's battery use to " +
                "\"Unrestricted\" / \"No restrictions\", and on Xiaomi turn on Autostart.").setPadding(0, ui.dp(8), 0, 0)
        }

        // ---- focused browser ----
        val web = ui.card(page, "Social apps without the feeds",
            "Open them inside ScrollStop: the Reels, Explore and Shorts buttons are gone completely, " +
                "and reels, ads and suggested posts are taken out of your feed.")
        ui.buttonRow(web,
            Triple("Instagram", Ui.Btn.FILLED) { openWeb(WebSite.INSTAGRAM) },
            Triple("YouTube", Ui.Btn.FILLED) { openWeb(WebSite.YOUTUBE) },
        )
        ui.buttonRow(web,
            Triple("Facebook", Ui.Btn.TONAL) { openWeb(WebSite.FACEBOOK) },
            Triple("X", Ui.Btn.TONAL) { openWeb(WebSite.X) },
        )
        ui.buttonRow(web,
            Triple("Reddit", Ui.Btn.TONAL) { openWeb(WebSite.REDDIT) },
            Triple("LinkedIn", Ui.Btn.TONAL) { openWeb(WebSite.LINKEDIN) },
        )
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            ui.button(web, "Add one to your home screen", Ui.Btn.TEXT) { pinDialog() }
        }

        // ---- today ----
        val today = ui.card(page, "Today")
        val nums = ui.stats(today, listOf("blocked", "short videos", "in feeds"))
        val tvApps = ui.small(today, "")
        refreshers += {
            nums[0].text = stats.get(Stats.BLOCKS).toString()
            nums[1].text = Platform.entries.sumOf { stats.get(Stats.shortCount(it)) }.toString()
            nums[2].text = Fmt.duration(Platform.entries.sumOf { stats.get(Stats.shortMs(it)) })
            val appTime = stats.appTimeOn(stats.day()).values.sum()
            val saved = stats.get(Stats.BLOCKS) * Stats.MINUTES_SAVED_PER_BLOCK * 60_000L
            tvApps.text = "Time in social apps: ${Fmt.duration(appTime)} · about ${Fmt.duration(saved)} saved*"
        }
        ui.small(today, "*Estimate: each blocked opening ≈ ${Stats.MINUTES_SAVED_PER_BLOCK} minutes not scrolled.")

        // ---- break ----
        val brk = ui.card(page, "Take a break")
        val tvBreak = ui.body(brk, "", muted = true)
        if (prefs.isLocked()) {
            tvBreak.text = "Breaks are off while strict mode is on."
        } else if (prefs.isPaused()) {
            ui.buttonRow(brk, Triple("Resume blocking now", Ui.Btn.FILLED) { prefs.pauseUntil = 0L; render() })
            refreshers += {
                tvBreak.text = "Blocking is paused for ${Fmt.countdown(prefs.pauseUntil - System.currentTimeMillis())} more."
            }
        } else {
            tvBreak.text = "Pause all blocking for a little while. It comes back on by itself."
            ui.buttonRow(
                brk,
                Triple("5 min", Ui.Btn.TONAL) { pause(5) },
                Triple("15 min", Ui.Btn.TONAL) { pause(15) },
                Triple("30 min", Ui.Btn.TONAL) { pause(30) },
            )
        }
    }

    private fun setupStep(parent: LinearLayout, done: Boolean, label: String, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(48)
        }
        val tv = ui.text(null, (if (done) "✅  " else "⬜  ") + label,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!done) ui.button(row, action, Ui.Btn.TONAL, onClick)
        parent.addView(row)
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
    // Block
    // =====================================================================

    private fun buildBlock() {
        ui.header(page, "What to block", "Feeds stay closed unless you give yourself a daily allowance.")
        for (p in Platform.entries) platformCard(p)

        val web = ui.card(page, "Web browsers")
        toggle(web, "Block in browsers too",
            "instagram.com/reels, youtube.com/shorts, facebook.com/reel, snapchat.com/spotlight and the other " +
                "feeds you block, in Chrome, Firefox, Samsung Internet, Edge, Brave, Opera and more.",
            { prefs.blockBrowsers }, { prefs.blockBrowsers = it })

        val inApp = ui.card(page, "Inside ScrollStop's social apps",
            "Extra clean-up for Instagram, YouTube, Facebook, X, Reddit and LinkedIn opened from ScrollStop.")
        val feedNames = mapOf("home" to "Home (Instagram's mix)", "following" to "Following: people you follow, newest first",
            "favorites" to "Favorites only")
        val feedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val feedTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.text(feedTexts, "Instagram feed", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        ui.text(feedTexts, feedNames[prefs.webIgFeed] ?: prefs.webIgFeed,
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
        feedRow.addView(feedTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ui.button(feedRow, "Change", Ui.Btn.TONAL) {
            val keys = feedNames.keys.toList()
            MaterialAlertDialogBuilder(this)
                .setTitle("Instagram feed")
                .setSingleChoiceItems(keys.map { feedNames[it] }.toTypedArray(), keys.indexOf(prefs.webIgFeed)) { d, i ->
                    if (keys[i] == "home" && prefs.webIgFeed != "home" && prefs.isLocked()) showLockedDialog()
                    else prefs.webIgFeed = keys[i]
                    d.dismiss()
                    render()
                }
                .show()
        }
        inApp.addView(feedRow)
        toggle(inApp, "Block Explore and Popular pages", "YouTube Explore, X Explore, Reddit Popular and All.",
            { prefs.webBlockDiscover }, { prefs.webBlockDiscover = it })
        toggle(inApp, "Hide reels in the feed", "Reel posts are removed from your Instagram home feed.",
            { prefs.webHideFeedReels }, { prefs.webHideFeedReels = it })
        toggle(inApp, "Hide suggested posts", "Removes posts from accounts you don't follow, \"Suggested for " +
            "you\" rows and everything after \"You're all caught up\". Only people you follow are left.",
            { prefs.webHideSuggested }, { prefs.webHideSuggested = it })
        toggle(inApp, "Hide ads", "Sponsored posts, paid partnerships and promoted videos, in any language.",
            { prefs.webHideAds }, { prefs.webHideAds = it })
        toggle(inApp, "Instagram: messages only", "Hides the home feed too. Messages, search and profiles still work.",
            { prefs.webIgMessagesOnly }, { prefs.webIgMessagesOnly = it })
        ui.small(inApp, "Got a link from a friend? Share it to ScrollStop to watch just that one. To make links " +
            "always open in ScrollStop: App info → Open by default → Add links.").setPadding(0, ui.dp(6), 0, 0)
        ui.button(inApp, "Open by default settings", Ui.Btn.TEXT) { openByDefaultSettings() }

        appsCard()
    }

    private fun platformCard(p: Platform) {
        val installed = p.packages.any { ServiceState.isInstalled(this, it) }
        val c = ui.card(page, p.app + if (installed) "" else "  ·  not installed")
        val what = when (p) {
            Platform.INSTAGRAM -> "Reels tab and any reel you open from the feed, profiles or Explore."
            Platform.YOUTUBE -> "Shorts tab and the Shorts player. Normal videos still work."
            Platform.FACEBOOK -> "Reels tab and the reel player."
            Platform.SNAPCHAT -> "The Spotlight tab."
        }
        toggle(c, "Block ${p.feed}", what, { prefs.blocked(p) }, { prefs.setBlocked(p, it); render() })
        val on = prefs.blocked(p)
        toggle(c, "Hide the ${p.feed} button", "Covers the tab button so it can't be tapped.",
            { prefs.hideTab(p) }, { prefs.setHideTab(p, it) }, enabled = on)

        when (p) {
            Platform.INSTAGRAM -> {
                toggle(c, "Use ScrollStop's Instagram instead",
                    "Opening the Instagram app takes you to ScrollStop's version, where ads, suggested posts and " +
                        "Reels are removed completely. Messages open your inbox. Take a break to use the real app.",
                    { prefs.igUseScrollStop }, { prefs.igUseScrollStop = it })
                val snoozeText = when {
                    prefs.igSnoozedAt == 0L -> "Instagram's own setting turns them off for 30 days. ScrollStop shows " +
                        "you where it is and reminds you to renew it."
                    prefs.igSnoozeDue() -> "⚠️ Your 30 days are up: Instagram has turned them back on. Snooze them again."
                    else -> "✅ Snoozed. Renew on " + SimpleDateFormat("d MMM", Locale.getDefault())
                        .format(Date(prefs.igSnoozedAt + Prefs.SNOOZE_DAYS * Prefs.DAY_MS)) + " (you'll get a reminder)."
                }
                val snoozeRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(52)
                }
                val snoozeTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                ui.text(snoozeTexts, "Suggested posts in the Instagram app",
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                ui.text(snoozeTexts, snoozeText, com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                    muted = true)
                snoozeRow.addView(snoozeTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = ui.dp(8) })
                ui.button(snoozeRow, "Turn off", Ui.Btn.TONAL) { snoozeDialog() }
                c.addView(snoozeRow)
                toggle(c, "Allow reels sent in DMs",
                    "Watch a reel a friend sent you. Swiping on to more reels is still blocked.",
                    { prefs.allowInstagramDm }, { prefs.allowInstagramDm = it }, weakensWhen = true, enabled = on)
                toggle(c, "Hide Explore", "Covers the Explore grid. Search still works.",
                    { prefs.blockIgExplore }, { prefs.blockIgExplore = it })
                toggle(c, "Block Stories", "Closes the story viewer.",
                    { prefs.blockIgStories }, { prefs.blockIgStories = it })
            }
            Platform.YOUTUBE -> toggle(c, "Search-only mode",
                "Hides the Home feed. Search, Subscriptions and the videos you pick still work.",
                { prefs.blockYtHome }, { prefs.blockYtHome = it })
            Platform.FACEBOOK -> toggle(c, "Block the Video tab", "Closes Facebook Video / Watch.",
                { prefs.blockFbWatch }, { prefs.blockFbWatch = it })
            Platform.SNAPCHAT -> Unit
        }

        if (on) {
            ui.divider(c)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ui.text(texts, "Daily allowance", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            val a = prefs.allowance(p)
            val tvA = ui.text(texts, "", com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ui.button(row, "Change", Ui.Btn.TONAL) { allowanceDialog(p) }
            c.addView(row)
            refreshers += {
                val usedMs = stats.get(Stats.shortMs(p))
                val usedN = stats.get(Stats.shortCount(p))
                val left = buildList {
                    a.msLeft(usedMs)?.let { add("${Fmt.duration(it)} left") }
                    a.videosLeft(usedN)?.let { add("$it videos left") }
                }
                tvA.text = if (a.isNone) a.describe()
                else a.describe() + "\nToday: ${Fmt.duration(usedMs)} · $usedN videos" +
                    (if (left.isEmpty()) "" else " · " + left.joinToString(" · "))
            }
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

    private fun allowanceDialog(p: Platform) {
        val old = prefs.allowance(p)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
        }
        ui.body(box, "Watch a little each day, then ${p.feed} closes until tomorrow. " +
            "Leave both at Off to block right away.", muted = true)
        val tvMin = ui.text(box, "", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        tvMin.setPadding(0, ui.dp(16), 0, 0)
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
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- whole apps ----

    private fun appsCard() {
        val c = ui.card(page, "Whole apps & time limits",
            "Block an app completely, or close it once you've used it for a while each day.")
        toggle(c, "Block TikTok completely", null, { prefs.blockTiktok }, { prefs.blockTiktok = it })
        val limits = prefs.appLimits
        val today = stats.appTimeOn(stats.day())
        for ((pkg, min) in limits.entries.sortedBy { appLabel(it.key).lowercase() }) {
            ui.divider(c)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            appIcon(pkg)?.let { d ->
                row.addView(ImageView(this).apply { setImageDrawable(d) },
                    LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(12) })
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ui.text(texts, appLabel(pkg), com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            val used = today[pkg] ?: 0L
            ui.text(texts, if (min == 0) "Blocked completely"
            else "${Fmt.duration(min * 60_000L)} a day · ${Fmt.duration(used)} used today",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ui.button(row, "Edit", Ui.Btn.TEXT) { limitDialog(pkg) }
            c.addView(row)
        }
        ui.space(c, 6)
        ui.button(c, "Add an app", Ui.Btn.TONAL) { pickAppDialog() }
    }

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

    private fun pickAppDialog() {
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
            .setAdapter(adapter) { _, which -> limitDialog(apps[which].pkg) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun limitDialog(pkg: String) {
        val current = prefs.appLimits[pkg]
        val values = listOf(0, 5, 15, 30, 45, 60, 90, 120, 180)
        val labels = values.map { if (it == 0) "Block completely" else "${Fmt.duration(it * 60_000L)} a day" }.toMutableList()
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(appLabel(pkg))
            .setItems(labels.toTypedArray()) { _, which ->
                val new = values[which]
                if (prefs.isLocked() && AppLimits.weakens(current, new)) showLockedDialog()
                else {
                    prefs.appLimits = prefs.appLimits + (pkg to new)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
        if (current != null) b.setNeutralButton("Remove limit") { _, _ ->
            if (prefs.isLocked()) showLockedDialog()
            else {
                prefs.appLimits = prefs.appLimits - pkg
                render()
            }
        }
        b.show()
    }

    // =====================================================================
    // Stats
    // =====================================================================

    private fun buildStats() {
        ui.header(page, "Stats", "Kept on this phone only.")

        val today = ui.card(page, "Today")
        val nums = ui.stats(today, listOf("blocked", "short videos", "in feeds"))
        nums[0].text = stats.get(Stats.BLOCKS).toString()
        nums[1].text = Platform.entries.sumOf { stats.get(Stats.shortCount(it)) }.toString()
        nums[2].text = Fmt.duration(Platform.entries.sumOf { stats.get(Stats.shortMs(it)) })

        // ---- last 7 days ----
        val week = ui.card(page, "Last 7 days", "Time in social apps each day")
        val chart = BarChartView(this)
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val keyFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val bars = (6 downTo 0).map { ago ->
            val key = stats.day(ago)
            val ms = stats.appTimeOn(key).values.sum()
            val label = runCatching { dayFmt.format(keyFmt.parse(key)!!) }.getOrDefault(key.takeLast(2))
            BarChartView.Bar((ms / 60_000f), label, Fmt.duration(ms), ago == 0)
        }
        chart.bars = bars
        week.addView(chart)
        val blocksWeek = (6 downTo 0).sumOf { stats.get(Stats.BLOCKS, stats.day(it)) }
        val appWeek = (0..6).sumOf { stats.appTimeOn(stats.day(it)).values.sum() }
        ui.small(week, "This week: ${Fmt.duration(appWeek)} in social apps · $blocksWeek blocked · " +
            "daily average ${Fmt.duration(appWeek / 7)}")

        // ---- per app today ----
        val apps = ui.card(page, "By app, today")
        val appTimes = stats.appTimeOn(stats.day())
        var any = false
        for (p in Platform.entries) {
            val app = p.packages.sumOf { appTimes[it] ?: 0L }
            val feedMs = stats.get(Stats.shortMs(p))
            val n = stats.get(Stats.shortCount(p))
            val b = stats.get(Stats.blocks(p.key))
            if (app == 0L && feedMs == 0L && n == 0L && b == 0L) continue
            any = true
            ui.text(apps, "${p.app}  ·  ${Fmt.duration(app)}", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            ui.small(apps, "${p.feed}: ${Fmt.duration(feedMs)} · $n videos · $b blocked").setPadding(0, 0, 0, ui.dp(8))
        }
        val others = appTimes.filterKeys { Platform.of(it) == null }
        for ((pkg, ms) in others.entries.sortedByDescending { it.value }) {
            if (ms < 1000) continue
            any = true
            ui.text(apps, "${appLabel(pkg)}  ·  ${Fmt.duration(ms)}", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                .setPadding(0, 0, 0, ui.dp(8))
        }
        val web = stats.get(Stats.blocks("web"))
        if (web > 0) {
            any = true
            ui.small(apps, "Blocked in browsers: $web")
        }
        if (!any) ui.body(apps, "Nothing yet today. 🎉", muted = true)

        // ---- locked in: how much social time happened inside ScrollStop's focused apps ----
        val totalSocial = appTimes.values.sum()
        val inside = WebSite.entries.map { it.pkg }.distinct().sumOf { stats.get(Stats.webMs(it)) }
        val locked = ui.card(page, "Locked in today", "Share of your social time spent in ScrollStop's feed-free apps.")
        val pct = if (totalSocial > 0) (inside * 100 / totalSocial).toInt() else 0
        val lockedNums = ui.stats(locked, listOf("locked in", "inside ScrollStop"))
        lockedNums[0].text = "$pct%"
        lockedNums[1].text = Fmt.duration(inside)

        // ---- all time ----
        val all = ui.card(page, "All time")
        val total = stats.get(Stats.BLOCKS, Stats.ALL)
        val nums2 = ui.stats(all, listOf("blocked", "saved (estimate)"))
        nums2[0].text = total.toString()
        nums2[1].text = Fmt.duration(total * Stats.MINUTES_SAVED_PER_BLOCK * 60_000L)

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
        strictCard()
        sleepCard()
        filterCard()
        scheduleCard()

        val bs = ui.card(page, "When something is blocked")
        toggle(bs, "Show a full-screen reminder", "Otherwise a short message pops up at the bottom.",
            { prefs.showBlockScreen }, { prefs.showBlockScreen = it })
        val msg = prefs.customMessage
        ui.body(bs, if (msg.isBlank()) "Reminder text: a random nudge" else "Reminder text: \"$msg\"", muted = true)
            .setPadding(0, ui.dp(4), 0, 0)
        ui.button(bs, "Write your own reminder", Ui.Btn.TONAL) { messageDialog() }

        val help = ui.card(page, "If blocking stops working")
        ui.body(help,
            "• Android can switch accessibility services off after updates or restarts. Turn ScrollStop on again.\n" +
                "• Android 13+: if the switch is greyed out, open App info → ⋮ menu → \"Allow restricted settings\", then try again.\n" +
                "• Xiaomi, Oppo, Vivo, Realme, Samsung: set battery use to Unrestricted. On Xiaomi also turn on Autostart.\n" +
                "• After an Instagram or YouTube update a block can stop working. The IDs ScrollStop looks for are in Rules.kt.",
            muted = true)
        ui.space(help, 6)
        ui.button(help, "Accessibility settings", Ui.Btn.OUTLINED) { open(ServiceState.accessibilitySettings()) }
        ui.button(help, "App info", Ui.Btn.OUTLINED) { open(ServiceState.appInfo(this)) }
        ui.button(help, "Battery settings", Ui.Btn.OUTLINED) {
            if (!open(ServiceState.batteryRequest(this))) open(ServiceState.batterySettings())
        }

        val about = ui.card(page, "About")
        ui.body(about, "ScrollStop ${BuildConfig.VERSION_NAME}\nThe blocker works offline and never sends anything anywhere. " +
            "Internet is only used to load the social sites you open inside ScrollStop, and by the optional adult filter.",
            muted = true)
    }

    private fun strictCard() {
        val c = ui.card(page, "Strict mode",
            "Locks your settings so blocking can't be loosened on a whim: no breaks, no turning blocks off, " +
                "no bigger allowances, and ScrollStop can't be switched off or uninstalled.")
        if (prefs.isLocked()) {
            val tv = ui.title(c, "")
            refreshers += { tv.text = "🔒 " + lockText() }
            when (prefs.lockType) {
                LockType.PIN -> ui.button(c, "Unlock with PIN", Ui.Btn.TONAL) { unlockDialog() }
                LockType.PHRASE -> ui.button(c, "Unlock by typing the sentence", Ui.Btn.TONAL) { unlockDialog() }
                else -> Unit
            }
        } else {
            ui.button(c, "Turn on strict mode") { strictDialog() }
        }
        toggle(c, "Protect ScrollStop", "While strict mode is on, Settings screens that could switch " +
            "ScrollStop off or uninstall it close right away.",
            { prefs.guardSettings }, { prefs.guardSettings = it })
    }

    private fun lockText(): String = when (prefs.lockType) {
        LockType.TIMER -> {
            val left = prefs.lockUntil - System.currentTimeMillis()
            val at = SimpleDateFormat(if (left < 20 * 3_600_000L) "h:mm a" else "EEE d MMM, h:mm a", Locale.getDefault())
                .format(Date(prefs.lockUntil))
            "Strict mode until $at (${Fmt.duration(left)} left)"
        }
        LockType.PIN -> "Strict mode is on until the PIN is entered"
        LockType.PHRASE -> "Strict mode is on until you type the sentence"
        LockType.NONE -> "Strict mode is off"
    }

    private fun strictDialog() {
        val kinds = arrayOf(
            "Timer: locked for a set time",
            "PIN: someone else keeps the code",
            "Sentence: type it out to unlock",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("How should it unlock?")
            .setItems(kinds) { _, which ->
                when (which) {
                    0 -> timerDialog()
                    1 -> pinSetupDialog()
                    else -> confirm("Turn on strict mode?",
                        "To unlock you'll need to type:\n\n\"${Lock.PHRASE}\"") {
                        prefs.lockType = LockType.PHRASE
                        afterLock()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun timerDialog() {
        val hours = listOf(1, 4, 8, 24, 72, 168)
        val labels = arrayOf("1 hour", "4 hours", "8 hours", "1 day", "3 days", "1 week")
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock for")
            .setItems(labels) { _, which ->
                confirm("Lock for ${labels[which]}?",
                    "There is no way to unlock early, so be sure.") {
                    prefs.lockUntil = System.currentTimeMillis() + hours[which] * 3_600_000L
                    prefs.lockType = LockType.TIMER
                    afterLock()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinSetupDialog() {
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
                                afterLock()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun afterLock() {
        prefs.pauseUntil = 0L
        toast("Strict mode is on 🔒")
        render()
    }

    private fun unlockDialog() {
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
                    render()
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
            b.setNeutralButton("Unlock") { _, _ -> unlockDialog() }
        }
        b.show()
    }

    // ---- Instagram app: snooze suggested posts ----

    private fun snoozeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn off suggested posts in Instagram")
            .setMessage(
                "Instagram has its own switch for this. It lasts 30 days, then Instagram turns it back on, " +
                    "so ScrollStop will remind you.\n\n" +
                    "1. In Instagram, go to your profile and tap ☰ (top right)\n" +
                    "2. Under \"What you see\", tap \"Suggested content\"\n" +
                    "3. Turn on \"Snooze suggested posts in feed\"\n\n" +
                    "Can't find it? Type \"suggested\" in the search bar at the top of Instagram's settings.\n\n" +
                    "Ads can't be switched off in the Instagram app. For no ads and no suggestions at all, use " +
                    "ScrollStop's Instagram."
            )
            .setPositiveButton("I've snoozed them") { _, _ ->
                prefs.igSnoozedAt = System.currentTimeMillis()
                prefs.igSnoozeRemindedAt = 0L
                toast("ScrollStop will remind you in 30 days")
                render()
            }
            .setNeutralButton("Open Instagram") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                if (launch == null || !open(launch)) toast("Instagram isn't installed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- sleep mode ----

    private fun sleepCard() {
        val c = ui.card(page, "Sleep mode",
            "At bedtime Instagram, YouTube, Facebook, Snapchat, TikTok and apps with a limit close until you wake up.")
        toggle(c, "Sleep mode", null, { prefs.sleepOn }, { prefs.sleepOn = it; render() })
        if (!prefs.sleepOn) return
        ui.buttonRow(c,
            Triple("Bedtime ${Fmt.clock(prefs.sleepStart)}", Ui.Btn.OUTLINED) { pickSleepTime(true) },
            Triple("Wake ${Fmt.clock(prefs.sleepEnd)}", Ui.Btn.OUTLINED) { pickSleepTime(false) },
        )
        val group = ChipGroup(this)
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, n ->
            val chip = Chip(android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_ScrollStop_FilterChip)).apply {
                text = n
                isCheckable = true
                isChecked = prefs.sleepDays and (1 shl i) != 0
            }
            chip.setOnCheckedChangeListener { v, checked ->
                val days = if (checked) prefs.sleepDays or (1 shl i) else prefs.sleepDays and (1 shl i).inv()
                val new = Sleep.window(true, prefs.sleepStart, prefs.sleepEnd, days)
                if (prefs.isLocked() && Sleep.weakens(prefs.sleepWindow(), new)) {
                    v.isChecked = !checked
                    showLockedDialog()
                } else prefs.sleepDays = days
            }
            group.addView(chip)
        }
        c.addView(group)
        ui.small(c, "Nights start on the days ticked above.")
        val reminders = listOf(0, 15, 30, 60)
        ui.button(c, "Heads-up before bedtime: " +
            if (prefs.sleepReminder == 0) "off" else "${prefs.sleepReminder} min", Ui.Btn.TEXT) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Heads-up before bedtime")
                .setItems(arrayOf("Off", "15 minutes before", "30 minutes before", "1 hour before")) { _, i ->
                    prefs.sleepReminder = reminders[i]
                    render()
                }
                .show()
        }
    }

    // ---- adult content filter ----

    private fun filterCard() {
        val c = ui.card(page, "Adult content filter",
            "Blocks adult and harmful websites in every app and browser, using Cloudflare's free family DNS " +
                "(1.1.1.3). Works through a VPN that only carries web address lookups: the rest of your " +
                "internet goes out as normal, and nothing is logged.")
        toggle(c, "Block adult content", null, { prefs.adultFilter }, { on ->
            if (on) {
                val ask = android.net.VpnService.prepare(this)
                if (ask == null) {
                    prefs.adultFilter = true
                    FilterVpnService.start(this)
                    handler.postDelayed({ render() }, 600)
                } else {
                    askVpn.launch(ask)
                }
            } else {
                prefs.adultFilter = false
                FilterVpnService.stop(this)
                handler.postDelayed({ render() }, 600)
            }
        })
        if (prefs.adultFilter) {
            ui.small(c, if (FilterVpnService.running) "🟢 Filter is running."
            else "🟠 Filter isn't running. Another VPN may have taken over: switch it off and on again.")
        }
        ui.small(c, "Only one VPN can run at a time. If sites still load, set Settings → Network → Private DNS to " +
            "Off or Automatic.").setPadding(0, ui.dp(4), 0, 0)
    }

    private fun pickSleepTime(bedtime: Boolean) {
        val cur = if (bedtime) prefs.sleepStart else prefs.sleepEnd
        TimePickerDialog(this, { _, h, min ->
            val v = h * 60 + min
            val old = prefs.sleepWindow()
            val new = Sleep.window(true, if (bedtime) v else prefs.sleepStart, if (bedtime) prefs.sleepEnd else v,
                prefs.sleepDays)
            if (prefs.isLocked() && Sleep.weakens(old, new)) showLockedDialog()
            else {
                if (bedtime) prefs.sleepStart = v else prefs.sleepEnd = v
                render()
            }
        }, cur / 60, cur % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    // ---- focused browser ----

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
            Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                android.net.Uri.parse("package:$packageName"))
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

    /** Long-press ScrollStop's icon to jump straight into Instagram or YouTube without the feeds. */
    private fun publishShortcuts() {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(this, WebSite.entries.take(4).map { shortcut(it) })
        }
    }

    // ---- schedule ----

    private fun scheduleCard() {
        val s = prefs.schedule
        val c = ui.card(page, "Blocking hours")
        toggle(c, "Only block at certain times", "Outside these hours everything is allowed.",
            { prefs.schedule.enabled },
            { prefs.schedule = prefs.schedule.copy(enabled = it); render() },
            weakensWhen = true)
        if (!s.enabled) return
        ui.buttonRow(c,
            Triple("From ${Fmt.clock(s.startMin)}", Ui.Btn.OUTLINED) { pickTime(true) },
            Triple("To ${Fmt.clock(s.endMin)}", Ui.Btn.OUTLINED) { pickTime(false) },
        )
        val group = ChipGroup(this)
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        names.forEachIndexed { i, n ->
            val chip = Chip(android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_ScrollStop_FilterChip)).apply {
                text = n
                isCheckable = true
                isChecked = s.dayOn(i + 1)
            }
            chip.setOnCheckedChangeListener { v, checked ->
                val cur = prefs.schedule
                val next = cur.copy(days = if (checked) cur.days or (1 shl i) else cur.days and (1 shl i).inv())
                if (prefs.isLocked() && next.weakerThan(cur)) {
                    v.isChecked = !checked
                    showLockedDialog()
                } else prefs.schedule = next
            }
            group.addView(chip)
        }
        c.addView(group)
        ui.small(c, "If the end is earlier than the start, the hours run overnight.")
    }

    private fun pickTime(start: Boolean) {
        val cur = prefs.schedule
        val m = if (start) cur.startMin else cur.endMin
        TimePickerDialog(this, { _, h, min ->
            val v = h * 60 + min
            val next = if (start) cur.copy(startMin = v) else cur.copy(endMin = v)
            if (prefs.isLocked() && next.weakerThan(cur)) showLockedDialog()
            else {
                prefs.schedule = next
                render()
            }
        }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun messageDialog() {
        val input = EditText(this).apply {
            setText(prefs.customMessage)
            hint = "e.g. You said you'd read tonight."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Your reminder")
            .setMessage("Shown on the block screen. Leave empty for random nudges.")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                prefs.customMessage = input.text.toString()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private fun showEnableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on ScrollStop")
            .setMessage(
                "1. Tap \"Open settings\"\n" +
                    "2. Find ScrollStop under Installed / Downloaded apps\n" +
                    "3. Switch it on and allow\n\n" +
                    "Greyed out? (Android 13+) Open App info → ⋮ menu (top right) → \"Allow restricted settings\", " +
                    "then try again.\n\n" +
                    "ScrollStop only looks for Reels, Shorts and Spotlight screens. It never stores or sends what's on " +
                    "your screen."
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
