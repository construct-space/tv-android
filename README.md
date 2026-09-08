# Jarvis TV — Android TV / Fire TV client

A thin **full-screen WebView kiosk** that displays the Jarvis web client on your TV.
The backend (FastAPI) keeps running on your Mac/server; this app just points a WebView
at it over the LAN and grants mic + audio so ElevenLabs STT/TTS work in WebView.

```
TV (this APK, WebView)  ──HTTP/WebSocket──▶  Mac/server  (uvicorn backend.server:app, :8000)
```

## Configure the backend address

Default is baked into `app/build.gradle.kts`:

```
buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://192.168.1.137:8000\"")
```

Change it there before building, **or** at runtime: press the **MENU** button on the TV
remote to open a dialog and type the URL (saved on the device). Make sure the backend
is started bound to your LAN:

```
HOST=0.0.0.0 ./run.sh        # from the Jarvis project root
```

## Build the APK

**Option A — Android Studio (easiest):** open the `androidtv/` folder, let it sync
(this generates the Gradle wrapper), then **Build ▸ Build APK(s)** or press Run with the
TV/emulator selected.

**Option B — command line:** needs the Gradle wrapper. If `gradlew` isn't present yet,
generate it once (`gradle wrapper --gradle-version 8.7`) or open the project in Android
Studio once, then:

```
./build.sh
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## Install on the TV

1. Enable **Developer options ▸ USB/Network debugging** on the Android TV / Fire TV.
2. Connect over the network:
   ```
   adb connect <TV_IP>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   (or `adb install` over USB.) The app then appears in the TV launcher row as **JARVIS**.

## Permissions & notes

- **Mic (STT):** the app requests `RECORD_AUDIO` and grants WebView's `getUserMedia` so
  ElevenLabs Scribe works. TTS audio autoplays (`mediaPlaybackRequiresUserGesture=false`).
- **Cleartext HTTP** is allowed (`usesCleartextTraffic=true`) so the LAN `http://…:8000`
  backend loads. For a public deployment, serve the backend over HTTPS and drop this.
- `minSdk 21`, `compileSdk/targetSdk 34`. Plain `Activity` + `WebView` — no extra deps.
- Remote keys: **MENU** = set backend URL, **BACK** = web back.
