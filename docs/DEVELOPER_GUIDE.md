# Developer Guide

A walkthrough of the NovaBot source for people who want to build it, understand it, or fix it.
You don't need to be an Android expert. If you can read Kotlin (or Java) and use a terminal, you
can follow along.

- [1. Get it building](#1-get-it-building)
- [2. Run it](#2-run-it)
- [3. Project map](#3-project-map)
- [4. How the bot works](#4-how-the-bot-works)
- [5. Fixing it when DoorDash changes the app](#5-fixing-it-when-doordash-changes-the-app)
- [6. Common changes](#6-common-changes)
- [7. Shipping your own build](#7-shipping-your-own-build)
- [8. Contributing](#8-contributing)

---

## 1. Get it building

**You need:**

- [Android Studio](https://developer.android.com/studio) (any recent version). It brings the
  Android SDK with it.
- **JDK 17.** This matters. Newer JDKs (like the Java 25 bundled with recent Android Studio
  releases) break the release build on this Android Gradle plugin version.

**Easiest path (Android Studio):**

1. **File → Open** and pick the project folder.
2. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**: choose a
   JDK 17. If you don't have one, the dropdown can download one for you.
3. Wait for Gradle sync to finish, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

**Command line:**

```sh
# Point Gradle at JDK 17 and your Android SDK (or put sdk.dir=... in local.properties)
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

On Windows use `gradlew.bat`. You may see `Daemon compilation failed: null` in the output.
That's Kotlin falling back to a slower compiler, and it's harmless if the build ends in
`BUILD SUCCESSFUL`.

## 2. Run it

**On a real phone** (the only way to test claiming):

1. Turn on **Developer options → USB debugging**, plug the phone in, and run
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Or just press Run in Android Studio.
2. Open the app and follow the setup wizard.
3. On Android 13+, if turning on the Accessibility Service shows **"Restricted setting"**, go to
   **Settings → Apps → NovaBot → ⋮ → Allow restricted settings** and try again.

**On an emulator** (good for UI work, not for claiming): the wizard, settings, filters, Telegram
and the floating button all work. Without the Dasher app and a real account, the bot just loops
pressing Back looking for Dasher.

> Heads-up: the package name is `com.coralreef`. If you already have another NovaBot build on
> your phone, installing yours replaces it (or fails if it was signed with a different key).
> See [Shipping your own build](#7-shipping-your-own-build) to change it.

**Watching what the bot does:**

```sh
adb logcat -s CateringBot     # live bot log: every cycle, screen detection, tap, and decision
```

## 3. Project map

All the code is in `app/src/main/java/com/coralreef/`.

| File | What it does |
|---|---|
| `DasherAccessibilityService.kt` | **The bot.** Reads Dasher's screen, decides what to do, taps. Also draws the floating button, screen lock and banners. Most changes happen here. |
| `FilterSettings.kt` | Every user setting (payout, miles, zones, schedule, modes, Telegram). Loads and saves `SharedPreferences`. |
| `FilterProfile.kt` / `FilterProfileStore.kt` | Saved filter profiles ("Default", "Weekend", ...) and switching between them. |
| `MainActivity.kt` | Launcher. Sends you to the setup wizard if anything is missing, otherwise to Settings. |
| `OnboardingWizardActivity.kt` | First-run wizard: Welcome → Accessibility → Overlay → Notifications → Telegram → Done. |
| `FilterSetupWizardActivity.kt` | The five filter questions shown at the end of setup. |
| `SettingsActivity.kt` | Main screen with Home / Alerts / Bot / Filters tabs. |
| `ClaimHistoryStore.kt` / `ClaimHistoryView.kt` | Saves the last 100 claims on the phone and draws the Recent Claims list. |
| `NotificationHelper.kt` | Android notifications. |
| `TelegramHelper.kt` | Sends Telegram alerts through the user's own bot, and finds their chat ID. |
| `DebugOverlayReceiver.kt` | Developer tool that draws a box on screen to check tap targets line up. |

Other places worth knowing:

- `app/src/main/res/layout/` holds screen layouts (`activity_settings.xml` is the big one).
  `overlay_bot.xml` is the floating control panel.
- `app/src/main/res/xml/accessibility_service_config.xml` declares what the service is allowed to
  do: read window content, perform gestures, take screenshots.
- `app/src/main/res/raw/` holds the animations (Lottie `.json`) and sounds.

## 4. How the bot works

The bot is an Android **Accessibility Service**. Android hands it the Dasher app's on-screen
layout as a tree of nodes (text, descriptions, positions), and lets it perform taps and swipes.
There's no DoorDash API involved; it reads and taps the screen like a person would.

### The loop

When you tap **Start Bot**, `startAutoLoop()` kicks off `runLoopCycle()`, which runs over and
over (each cycle schedules the next with `scheduleNext()`):

1. **Is Dasher in front?** If another app is open, press Back and try again shortly.
2. **Where are we?** `captureAndDetect()` reads the screen and decides which `ScreenState` it is:
   `HOME`, `MENU`, `SCHEDULE`, `OFFER_DETAIL`, `DASHING`, `ERROR`, and so on. Detection is a chain
   of checks for text that only appears on that screen ("Home" + "Account" + "Ratings" means the
   side menu is open, a row of date tabs means the Schedule screen, ...).
3. **Act on that screen.** From Home it opens the menu and goes to **Schedule → Offers**. Error
   screens get "Try again". Popups get dismissed.
4. **Scan.** On the Offers tab, `scanAllDatesFromFirst()` goes through each date tab the user
   wants scanned and reads the offer list (`findOffersOnListScreen()`).
5. **Check each offer.** Matching offers are opened. `readDetailScreen()` pulls out payout, miles,
   pickup time, restaurant and zone (with an OCR fallback in `readDetailScreenViaOCR()` when the
   text isn't in the tree). The offer is compared against the active filter profile.
6. **Claim.** `claimWithDetails()` → `claimCurrentOffer()` taps the claim button and confirms.
   On success it saves the claim with `ClaimHistoryStore.record()`, plays a sound, and sends
   notifications. Offers it skips are cached so the bot doesn't re-open them every cycle.

Timings (how long to wait after each tap) come from `Timings.normal()`, `speed()` and `slow()`
depending on the mode.

### Safety rules

- `isDangerousTap()` blocks taps on anything like **Dash**, **Unassign**, **End now**, **Pause
  orders** or **Preview route**, so the bot can never start or end a dash or drop an order.
- On `DASHING` and `UPCOMING_ORDER` screens the bot only touches known-safe navigation.
- Outside the run schedule (`isWithinActiveHours()`) the bot pauses. It only scans the days the
  user picked (`shouldScanDay()`) and only claims inside their pickup-time windows.

If you change claiming or navigation, keep these guards intact.

## 5. Fixing it when DoorDash changes the app

This is the most common job. DoorDash updates the Dasher app, a screen looks slightly different,
and the bot stops recognizing it. The fix is usually small.

1. **Reproduce it** on a phone with the bot running and `adb logcat -s CateringBot` open. Look for
   the cycle where it goes wrong: `UNKNOWN` screens, "not found" messages, or the same action
   repeating.
2. **Look at what the bot sees.** Each time the screen settles, the full node tree is appended to
   a file on the phone:

   ```sh
   adb pull /sdcard/Android/data/com.coralreef/files/ui_dump.log
   ```

   Find the dump for the broken screen (each starts with `=== UI TREE <time> | State: ... ===`).
   It lists every node's text, description and bounds. You can also run
   `adb shell uiautomator dump` and pull `/sdcard/window_dump.xml`.
3. **Update the matching code.** Usually one of:
   - A screen-detection rule in `captureAndDetect()` (new wording, a removed label).
   - A button lookup: `tapByText()`, `tapByDesc()`, `tapById()`, or `findClaimButtonNode()`.
   - Text parsing in `parseDetailText()` if the offer details layout changed.
4. **Test on a real phone**, and say in your PR which Dasher app version you tested
   (**Settings → Apps → Dasher** on the phone).

Prefer matching on text and content descriptions over screen coordinates. Coordinates break on
different phone sizes.

## 6. Common changes

**Add a setting:**

1. Add a field to `FilterSettings` and read and write it in `load()` / `save()` with a new
   preferences key. Give it a default that keeps current behavior.
2. Add the control to `activity_settings.xml` and wire it up in `SettingsActivity` where the
   other controls on that tab are loaded and saved.
3. Read it in `DasherAccessibilityService` with `FilterSettings.load(this)`.

If the setting belongs to a filter profile (per-profile, not app-wide), add it to
`FilterProfile` and `FilterProfileStore` instead.

**Change a Telegram message:** search `DasherAccessibilityService.kt` for `TelegramHelper.send(`.
Messages are plain text. `send()` quietly does nothing if Telegram isn't set up.

**Change an Android notification:** see `NotificationHelper.kt`.

## 7. Shipping your own build

If you share APKs you built yourself:

- **Change the package name** (`applicationId` in `app/build.gradle.kts`) to something of your
  own, like `com.yourname.novabot`, so it doesn't clash with other builds on people's phones.
- **Sign with your own key.** Release builds are signed with your local debug keystore by default,
  which is fine for yourself but not for distribution. Create a keystore in **Build → Generate
  Signed App Bundle / APK** and point `signingConfigs` at it. Keep the key and its passwords out
  of git.
- **Bump `versionCode` and `versionName`** each release, and add a `CHANGELOG.md` entry.

## 8. Contributing

1. Fork the repo and make a branch.
2. Keep changes focused. A screen-detection fix and a new feature should be separate PRs.
3. Make sure `./gradlew assembleRelease` passes (it runs lint).
4. In the PR, describe what you changed, how you tested it, and the Dasher app version.

Never commit tokens, keystores, `local.properties`, screenshots showing your location or
earnings, or log files. The bot logs can contain offer and address details.
