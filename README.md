# ScrollStop: Reels & Shorts blocker for Android

Blocks the short-video feeds and keeps the rest of each app usable (DMs, posts, search, normal YouTube videos).
Works offline: no internet permission, nothing is collected or sent.

## Features

**Feeds it blocks inside the real apps**
- **Instagram**: Reels tab + any reel opened from feed, profile or Explore. Optional: allow single reels friends send in DMs (swiping on is still blocked), hide Explore (search keeps working), block Stories.
- **YouTube**: Shorts tab + Shorts player (normal videos still work). Optional **search-only mode** that hides the Home feed.
- **Facebook**: Reels tab + reel player. Optional: block the Video/Watch tab.
- **Snapchat**: Spotlight.
- **Hide the tab buttons**: the Reels / Shorts / Spotlight buttons are covered so they can't be tapped.
- **Web browsers**: instagram.com/reels, youtube.com/shorts, facebook.com/reel, snapchat.com/spotlight (and TikTok if blocked) in Chrome, Firefox, Samsung Internet, Edge, Brave, Opera, DuckDuckGo, Vivaldi and more.

**Limits**
- **Daily allowance per feed**: by time (1–60 min) and/or by number of videos (1–100). The feed closes when either runs out, until tomorrow. Warning when 1 minute / 1 video is left.
- **Whole-app blocks and daily time limits** for any app (e.g. "TikTok: blocked", "Instagram: 30 min a day").
- **Blocking hours**: only block at certain times, on chosen days (overnight windows work).
- **Breaks**: pause everything for 5 / 15 / 30 minutes.

**Strict mode** (can't loosen anything while it's on: no breaks, no turning blocks off, no bigger allowances)
- Unlocks by **timer** (1 hour to 1 week), **PIN** (for parents or an accountability friend) or **typing a sentence**.
- **Protection**: the Settings screens that would switch ScrollStop off, force-stop it or uninstall it close right away.

**Other**
- Full-screen reminder when something is blocked (with your own message, or random nudges), or just a short toast.
- **Stats**: blocks, videos watched and time in feeds today; time per app; 7-day chart; all-time totals and time saved.
- Setup checklist (accessibility, battery, notifications) and a **notification if Android switches the blocker off**.
- Light and dark theme.

## Get the APK without a computer (GitHub, works from your phone)
1. Push this repository to GitHub (it's already set up in `.github/workflows/build.yml`).
2. Open the **Actions** tab. The "Build APK" job runs on every push (or tap **Run workflow**).
3. After ~5 minutes, open the finished run → **Artifacts** → download **ScrollStop-apk**, unzip it, install `app-release.apk`.

## Or build it yourself
Android Studio: open this folder → wait for Gradle sync → **Build → Build APK(s)**.
Command line (JDK 17+, Android SDK): `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
Tests: `./gradlew testDebugUnitTest` (set `SCROLLSTOP_SCREENSHOTS=/some/dir` to also save a PNG of every screen).

### About signing
Every build is signed with the same key (`app/scrollstop.jks`), so new versions install **over** old ones and keep your
settings and stats. (Version 1 builds from GitHub Actions were signed with a random key each time, so **uninstall the old
ScrollStop once** before installing this version.) If your repository is public and you'd rather use a private key, set the
`SCROLLSTOP_KEYSTORE`, `SCROLLSTOP_PASSWORD` and `SCROLLSTOP_ALIAS` environment variables (or GitHub secrets) when building.

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

## Limits of this approach
ScrollStop reads the screen through Android's accessibility service, like Sociallite and similar blockers. That means:
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
| `System.kt` | Service checks and the "blocker is off" reminder |
