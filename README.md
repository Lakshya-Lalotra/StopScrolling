# ScrollStop: Reels & Shorts blocker for Android

Blocks the short-video feeds and keeps the rest of each app usable (DMs, posts, search, normal YouTube videos).
The blocker works offline and never sends anything anywhere.

## Features

**Social apps without the feeds, inside ScrollStop** (like SocialLite's focused browser)
- **Instagram, YouTube, Facebook, X, Reddit and LinkedIn** open inside ScrollStop. The Reels, Explore and Shorts
  buttons are **removed completely**, and the pages behind them can't be opened.
- Reels, **ads** and **suggested posts** are taken out of the feed. Instagram can show your **Following** feed
  (people you follow, newest first) or **Favorites** instead of the algorithm's mix, or messages only.
- **Explore / Popular pages blocked**: YouTube Explore, X Explore, Reddit Popular and All.
- **Links from friends**: share a reel or video link to ScrollStop (or make links open in it) and you can watch
  that one, but not swipe on to the next.
- Home-screen shortcuts ("Instagram (no Reels)", "YouTube (no Shorts)"...) and a long-press menu on the app icon.
- Time inside counts towards each app's stats and daily limit; a top bar shows today's time and what was hidden.

**Inside the real apps** (via the accessibility service)
- **Instagram**: Reels tab + any reel opened from feed, profile or Explore. Optional: allow single reels friends send
  in DMs (swiping on is still blocked), hide Explore, block Stories.
- **YouTube**: Shorts tab + Shorts player (normal videos still work). Optional **search-only mode** that hides Home.
- **Facebook**: Reels tab + reel player. Optional: block the Video/Watch tab.
- **Snapchat**: Spotlight.
- **Tab buttons covered** in the app's own colour so they look removed (Android doesn't allow truly deleting
  another app's buttons; for that, use the inside-ScrollStop versions above).
- **Web browsers**: the same feeds are blocked in Chrome, Firefox, Samsung Internet, Edge, Brave, Opera and more.

**Limits**
- **Daily allowance per feed**: by time (1–60 min) and/or number of videos (1–100), then closed until tomorrow.
- **Whole-app blocks and daily time limits** for any app (e.g. "TikTok: blocked", "Instagram: 30 min a day").
- **Sleep mode**: social apps close between bedtime and wake-up on the nights you pick, with a heads-up beforehand.
- **Blocking hours** on chosen days, and **breaks** of 5 / 15 / 30 minutes.

**Strict mode** (can't loosen anything while it's on)
- Unlocks by **timer** (1 hour to 1 week), **PIN** (for parents or a friend) or **typing a sentence**.
- **Protection**: the Settings screens that would switch ScrollStop off, force-stop it, disconnect its filter or
  uninstall it close right away.

**Adult content filter** (optional)
- Blocks adult and harmful sites in every app using Cloudflare's free family DNS (1.1.1.3), through a VPN that only
  carries DNS lookups. Turns itself back on after a restart.

**Stats & more**
- Blocks, videos watched, time in feeds, time per app, 7-day chart, all-time totals, time saved, and
  **"locked in"**: how much of your social time happened inside ScrollStop's feed-free apps.
- **Home-screen widget** with today's time and quick buttons.
- Full-screen reminder when something is blocked (your own message or random nudges), light and dark theme,
  setup checklist, and a notification if Android switches the blocker off.

## If Play Protect says "App blocked to protect your device"
In some countries (India included) Google Play Protect blocks any app installed from a browser, chat app or file manager
if it uses an accessibility service. ScrollStop needs one to see Reels and Shorts, so it gets caught. It's your own app, so
either:
- **On the phone:** Play Store → your profile picture → **Play Protect** → ⚙ (top right) → turn off
  **Scan apps with Play Protect**. Install the APK, then turn the setting back on. If Play Protect later asks about
  ScrollStop, choose to keep it.
- **From a computer:** turn on USB debugging (Settings → About phone → tap Build number 7 times → Developer options →
  USB debugging), connect the phone and run `adb install app-release.apk` (or press Run in Android Studio). Installs over
  USB aren't covered by this block.

## First-time setup on the phone
1. Install the APK (allow "Install unknown apps" for your browser/file manager if asked).
2. Open ScrollStop → **Turn on blocker** → Accessibility → ScrollStop → On.
3. **Android 13+**: if the switch is greyed out, go to Settings → Apps → ScrollStop → ⋮ (top right) → **Allow restricted settings**, then try step 2 again.
4. Follow the **Finish setup** card: allow running in the background (battery: Unrestricted) and notifications.
   On Xiaomi also enable **Autostart**.

## Privacy
The blocker reads the screen only to find the feeds it blocks, and keeps nothing. Internet access is used only to load
the social sites you open inside ScrollStop, and by the optional adult filter to ask Cloudflare's family DNS for
addresses. Stats stay on the phone.

## Limits of this approach
For the real apps ScrollStop works through Android's accessibility service. That means:
- Reels that autoplay *inside* the Instagram home feed are not hidden; opening them is blocked.
- Facebook Lite and Snapchat expose little screen information, so blocking there is best-effort.
- Apps rename their internal screen IDs now and then. If a block stops working after an app update, add the new ID to the
  lists in `app/src/main/java/com/scrollstop/app/Rules.kt` (find IDs with the "Developer Assistant" app or Android Studio's
  Layout Inspector) and rebuild. The fake-screen tests in `RulesTest.kt` show what each rule expects.

## Code map
| File | What it does |
|---|---|
| `BlockerService.kt` | Accessibility service: watches the screen, counts time/videos, blocks, strict-mode protection |
| `Rules.kt` | Detection rules per app (view IDs and labels) |
| `Overlays.kt` | Covers for tab buttons/feeds, and the full-screen block reminder |
| `Logic.kt` | Plain rules (allowance, schedule, browser URLs, lock, app limits), unit tested |
| `Prefs.kt` / `Stats.kt` | Settings and daily counters |
| `MainActivity.kt` / `Ui.kt` | The app's four tabs |
| `System.kt` | Service checks and reminders |
| `WebAppActivity.kt` + `res/raw/*.js` | Social sites inside ScrollStop with the feeds removed |
| `FilterVpnService.kt` | Optional adult-content DNS filter |
| `ScrollStopWidget.kt` | Home-screen widget |
