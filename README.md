# FIMFiction Android WebApp (Capacitor)

A lightweight Android wrapper around https://www.fimfiction.net using Capacitor.

This personal-use app embeds the site in a secure WebView with quality-of-life features:

- In-app navigation (including links that would normally open a new window)
- Back button navigates web history before exiting
- Camera, microphone, and geolocation prompts are supported
- File downloads via Android DownloadManager (saved to Downloads)
- Offline fallback page when connectivity fails
- WebView debugging in debug builds

Note: This app simply wraps the public site for personal use and is not affiliated with fimfiction.net.

## Project layout

- capacitor.config.json — Capacitor configuration (remote server URL, navigation)
- www/ — minimal local assets (e.g., offline.html)
- android/ — native Android project (Gradle)
- android/app/src/main/java/com/desmond/fimfiction/MainActivity.java — WebView customizations

## Requirements

- Linux / macOS / Windows with WSL
- Node.js 18+ (this repo used Node 22)
- Java 17 (OpenJDK 17)
- Android SDK (platform-tools, platforms;android-34/35, build-tools)
- Gradle wrapper is included in android/

## Setup

1) Install dependencies
- Node: https://nodejs.org/
- Java 17: e.g., jdk17-openjdk on Arch
- Android SDK: install cmdline-tools, accept licenses, ensure `platform-tools`, `platforms;android-35` (or `34`), and `build-tools` are installed.

2) Install npm deps
```
npm ci || npm i
```

3) Sync Capacitor to native
```
npx cap sync android
```

## Build

Debug APK:
```
cd android
./gradlew assembleDebug
```

Output:
- `android/app/build/outputs/apk/debug/app-debug.apk`

Install to device (USB debugging required):
```
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.desmond.fimfiction/.MainActivity
```

## Behavior notes

- External links: All http(s) links are kept in-app; non-http(s) schemes (e.g., `mailto:`, `tel:`) are handed to the system.
- Permissions: CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION are declared and requested at startup for convenience in this personal build.
- Downloads: Clicking downloadable links uses Android DownloadManager and shows a notification on completion.
- Offline: If the main frame fails to load (network error or HTTP 4xx/5xx), an offline page is shown (www/offline.html). Use the Retry button to reload.

## Customizing

- App name & ID: Change in `capacitor.config.json` and Android `app/build.gradle` if needed.
- Icons & Splash: Assets were generated from a user-supplied PNG; to regenerate, use @capacitor/assets or cordova-res.
- Orientation & UI: Adjust Android Manifest or WebView settings in `MainActivity.java`.

## License

No license specified. For personal use. If publishing publicly, ensure you have permission to wrap and redistribute the target site, and add an appropriate license.

