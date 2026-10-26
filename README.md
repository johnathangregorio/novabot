# NovaBot

An Android app that watches the DoorDash Dasher app and automatically claims catering offers that
match your filters. It uses Android's Accessibility Service to read the screen and tap for you.
Everything runs on your phone, with no account and no server.

> **Disclaimer:** This project isn't affiliated with or endorsed by DoorDash. Automating the Dasher
> app may break DoorDash's terms of service and could get your account deactivated. Use it at your
> own risk.

## Features

- **Filters:** minimum payout, maximum miles, allowed and blocked starting points (regions).
- **Scan schedule:** pick which days to scan, and which pickup-time windows to accept on each day.
- **Filter profiles:** save several sets of filters and switch between them.
- **Run schedule:** only run the bot during set hours, with optional per-day overrides.
- **Speed and Slow modes:** Speed Mode cuts scan delays. Slow Mode checks at random intervals,
  with optional extra randomness.
- **Floating button:** a 🤖 overlay over Dasher to start and stop the bot without switching apps.
- **Screen lock and dim:** block accidental taps and save battery while the bot runs.
- **Notifications:** Android alerts, plus optional Telegram alerts through your own bot
  (claims, skipped offers, the bot stopping, and a daily summary).
- **Recent Claims:** history of claimed orders, stored on the phone.

## Requirements

- Android 8.0 (API 26) or newer.
- The DoorDash Dasher app (`com.doordash.driverapp`).

## Install

1. Build the APK (see below), or grab one from Releases if one is published.
2. Install it on the phone that runs Dasher.
3. Open the app and follow the setup wizard.

**Android 13 and newer:** sideloaded apps can't turn on an Accessibility Service straight away.
If Android shows a "Restricted setting" message, go to **Settings → Apps → NovaBot → ⋮ → Allow
restricted settings**, then turn the service on again.

## Telegram alerts (optional)

1. In Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot`, and copy the token
   it gives you.
2. Send any message to your new bot from the account that should get the alerts.
3. In the app, go to **Settings → Alerts**, paste the token, tap **Detect** to fill in your chat
   ID, then tap **Send Test Message**.

The app talks to Telegram's Bot API directly. Your token stays on your phone.

## Build

You need **JDK 17** and the Android SDK (API 35). Much newer JDKs (for example the Java 25 bundled
with recent Android Studio releases) break the release build's lint step on this Android Gradle
plugin version. Point `JAVA_HOME` at a JDK 17, or set Android Studio's Gradle JDK to 17.

Gradle also needs to know where the Android SDK is. Opening the project in Android Studio handles
this. From the command line, set `ANDROID_HOME`, or create a `local.properties` file with
`sdk.dir=/path/to/Android/Sdk`.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified build, signed with your local debug keystore
```

On Windows, use `gradlew.bat`. Release builds are signed with `~/.android/debug.keystore` so they
can be installed straight away. Use your own signing key if you distribute builds.

## How it works

`DasherAccessibilityService` reads the Dasher app's screen through the accessibility tree,
works out which screen it's on, scans the offer list, and opens and claims offers that pass the
active filter profile (`FilterSettings` / `FilterProfileStore`). The onboarding and settings
screens are plain Android Views.

## Contributing

Issues and pull requests are welcome. DoorDash changes its app often, so screen-detection fixes
are especially useful. Include the Dasher app version you tested against.

## License

MIT. See [LICENSE](LICENSE).
