# Aurelune for Android

A tiny Android app that opens **https://aurelune.unfazed.ir/** full screen. It is a plain WebView: no Capacitor, no
Cordova, no AndroidX, no dependencies at all. Two Java files, one manifest.

It adds the few things a bare WebView is missing:

| | |
|---|---|
| Uploads | `<input type="file">` opens the system file picker (artists uploading music, covers…) |
| Back button | goes back inside the site, then sends the app to the background (music keeps playing) |
| Links | other websites open in the browser; links to your site stay in the app; links to your site tapped in other apps can open in Aurelune |
| Background music + controls | a foreground service keeps the app alive and the CPU awake while audio plays. The notification (and lock screen) shows the song's title, artist and cover with **shuffle / previous / play-pause / next / repeat** buttons (shuffle and repeat show their on/off/one state) and a seek bar; headset and Bluetooth buttons work too. Pausing keeps the notification so you can resume from it; swiping it away stops the music |
| Offline | the site's own service worker + IndexedDB offline downloads work as in a browser; if the site can't be reached at all you get a "Try again" screen |
| File downloads | files the site offers (e.g. your data export) are saved to Downloads |
| Look | dark from the first frame, status/navigation bars match, notch and keyboard handled, Aurelune icon |

Minimum Android 7.0 (API 24). Release APK is signed and ready to install or upload to Myket / Bazaar.

## Get the APK (easiest: GitHub builds it for you)

1. Create a new **private** GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab → **Build Android APK** → **Run workflow** (it also runs on every push).
3. When it finishes (about 3-5 minutes), open the run and download the **Aurelune-apk** artifact. Unzip it: inside is
   `Aurelune-1.0.0-release.apk`.
4. Copy it to the phone and install it (Android asks you to allow "install unknown apps" for your file manager or browser).

## Or build on your computer

- **Android Studio** (Ladybug 2024.2.1 or newer): *Open* this folder, wait for the sync, then *Build → Build Bundle(s) / APK(s) → Build APK(s)*
  with the **release** variant. Output: `app/build/outputs/apk/release/`.
- **Command line** (JDK 17 and the Android SDK with platform 35 installed, `ANDROID_HOME` set):
  `./gradlew assembleRelease` (Windows: `gradlew.bat assembleRelease`).

## Things you may want to change

- **Site address**: `START_URL` at the top of `app/build.gradle`. If the *host* changes, also change it in
  `app/src/main/AndroidManifest.xml` (the `android:host` line) and `app/src/main/assets/offline.html` (the "Try again" link).
  The app only keeps pages from that host inside the window.
- **Version**: raise `versionCode` (and `versionName`) in `app/build.gradle` for every update you give to people; Android
  refuses to install an update with the same or lower `versionCode`.
- **App id**: `app.aurelune.mobile` (same as your old Capacitor config).

## About the signing key

`aurelune-release.jks` + `signing.properties` is the key every release is signed with. Updates only install over an
older version if signed with the **same** key, so **back both files up**. They are included so the GitHub build works
with zero setup, which is fine for a private repository. If you ever make the repository public, create your own key
and replace those two files:

```
keytool -genkeypair -keystore aurelune-release.jks -alias aurelune -keyalg RSA -keysize 4096 -validity 36500
```

(then put the passwords in `signing.properties`). For a CI that keeps the key out of the repo, the build also reads
`AURELUNE_KEYSTORE`, `AURELUNE_STORE_PASSWORD`, `AURELUNE_KEY_ALIAS` and `AURELUNE_KEY_PASSWORD` from the environment.

## If Vercel (or any firewall) blocks the app

The app sends the same user-agent as normal mobile Chrome (no `wv`, no custom name), so a bot check has nothing odd to
react to. To see why a request was blocked, open the project on Vercel → **Firewall** (or **Security**) → traffic/logs:
it names the rule. Things worth checking there: *Attack Challenge Mode* (turn it off, or add a bypass rule), *Bot
Protection* rules, and rate limits. In the app, `window.AurAndroid` exists only inside the Android app, if the site ever
needs to know.

## Known limits

- The notification controls talk to your site's player through `import('/js/player.js')` (the `player` object exported by
  `public/js/player.js`, see `app/src/main/assets/bridge.js`). If you ever rename or restructure that module, update
  `bridge.js`; until then the music still plays and a plain "Music is playing" notification is shown, just without the
  song details and buttons.
- Offline downloads live in the WebView's storage. Android's "Clear storage" for the app, or uninstalling it, removes them
  (same as clearing a browser's site data).
- Blob/data downloads started by the page are not supported (normal `https` downloads are).
- Debug the page from a computer: install a debug build, open `chrome://inspect` in desktop Chrome.
