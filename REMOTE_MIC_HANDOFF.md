# Android TV remote-mic capture — problem handoff (for a second set of eyes / Codex)

## Goal
In a third-party Android TV app (full-screen WebView kiosk), let the user **press the
remote's mic button and speak**, get the transcript into the app, and act on it.
TTS replies are handled separately (ElevenLabs, works fine). We need **STT input**.

## Device / environment
- **Xiaomi Mi TV Stick** (`ro.product.model = MiTV-AFMU0`, `ro.product.device = twilight`), **Android 14**.
- `pm list features | grep microphone` → **NOTHING**. The box has **no built-in mic**.
- The mic is on the **Bluetooth voice remote** (dedicated Google-mic button).
- The **Google app (`com.google.android.googlequicksearchbox`) is DISABLED** by the user
  (so the mic button isn't hijacked by Assistant and its keycode reaches our app).
- `com.droidlogic.mictoggle/AssistantMicMuteService` runs at system level (mic is Assistant-owned).
- App is a native Kotlin `Activity` hosting a `WebView`. Backend is FastAPI on the LAN.

## What WORKS
- The remote **mic button delivers keycode `186`** to our `onKeyDown`/`dispatchKeyEvent`
  **when our app is foreground** (verified: `JarvisKey DOWN code=186`).
- ElevenLabs **TTS** out (WebView plays mp3 from backend `/tts`).
- Backend `/stt` (ElevenLabs Scribe) works when given real audio (verified with a file).

## What FAILS (the problem)
1. **MediaRecorder / AudioRecord (`AudioSource.MIC` or `VOICE_RECOGNITION`)** → opens a
   **phantom `IN_BUILTIN_MIC`** and records **silence**. Uploads to ElevenLabs succeed
   (HTTP 200) but transcript is **empty** every time. (Makes sense: no built-in mic.)
2. **`SpeechRecognizer` single-shot** (`createSpeechRecognizer` + `startListening`,
   FREE_FORM, en-US, partials): pressing 186 calls `startGoogle()` but **NO listener
   callbacks fire at all** — no `onReadyForSpeech`, no `onError`, no `onResults`
   (`JarvisVoice` log empty). Hypothesis: with the Google app disabled there's no
   recognition session for the remote to stream into, so it silently no-ops.
3. **`SpeechRecognizer` continuous (wake loop)**: earlier, with restart loop, it DID get
   `onReadyForSpeech` + SODA `start detection` but always `NO_SPEECH`/`ERROR_CLIENT`
   (and flaps). Inconsistent on-device vs online availability.

## The open question
**Can a third-party Android TV app capture the BT voice-remote mic at all while the
Google app is disabled?** Our evidence says no — the remote only streams during an
Assistant/recognition session that the Google app provides. Candidate answers we want
Codex to confirm/refute and give working code for:

- **A.** Re-enable Google, then use `startActivityForResult(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)`
  triggered by a NON-mic button (e.g. `DPAD_CENTER`/OK), since Google re-grabs the mic
  button. (Classic Android TV in-app voice search.) Does this use the remote mic reliably?
- **B.** Is there ANY way to keep Google disabled and still read the remote mic
  (e.g. a specific `AudioSource`, BluetoothHeadset SCO, an Android TV input API,
  Leanback `SearchSupportFragment` + `setSpeechRecognitionCallback`)?
- **C.** Is the only robust answer to NOT use the remote mic — instead use a phone/laptop
  as the mic (the web client has a real mic) and keep the TV as a display?

## Key code (current)
`androidtv/app/src/main/java/al/basecode/jarvistv/MainActivity.kt`:
- `micKeys = {186, SEARCH, VOICE_ASSIST, ASSIST}`; `onKeyDown` toggles `startVoice()/stopVoice()`.
- `startVoice()` → `if (googleAvailable()) startGoogle() else startRec()`.
- `startGoogle()` → `SpeechRecognizer.createSpeechRecognizer(this).startListening(RecognizerIntent…)`.
- `startRec()` → `MediaRecorder` (AAC/m4a) → POST bytes to backend `/stt` (ElevenLabs).
- Manifest: `RECORD_AUDIO`, `<uses-feature microphone required=false>`,
  `<queries><intent><action android:name="android.speech.RecognitionService"/></queries>`.

## Reproduce
1. `adb connect <tv-ip>:5555`
2. Launch app (LEANBACK_LAUNCHER), ensure it's foreground.
3. `adb logcat -s JarvisKey JarvisVoice` ; press remote mic + speak.
4. Observe: `JarvisKey DOWN code=186` arrives, but `JarvisVoice` shows no recognizer callbacks.

---

## ✅ SOLVED
Two things fixed it:
1. **The recognizer package needs mic permission** — `adb shell pm grant
   com.google.android.tts android.permission.RECORD_AUDIO`. Without it the in-app
   `SpeechRecognizer` produced ZERO callbacks. With it, it reaches `onReadyForSpeech`
   and returns transcripts. (The app also prompts the user to enable this in Settings
   via `recognizerHasMicPermission()` / `showExternalMicPermissionDialog()`.)
2. **The dedicated remote MIC button is owned by Google Assistant** at the system level
   and cannot be intercepted while Google is enabled. So the Jarvis voice trigger is the
   **OK / DPAD_CENTER button** (reaches the app), which starts the in-app recognizer that
   reads the remote mic.

Verified end-to-end: press OK → speak → e.g. "what's the weather today in Pristina" →
correct transcript → MiniMax composes the screen → ElevenLabs speaks. Google STT in,
ElevenLabs TTS out.
