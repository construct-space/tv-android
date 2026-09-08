package al.basecode.jarvistv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jarvis TV — full-screen WebView kiosk for Android TV / Fire TV.
 *
 * Voice INPUT: the mic lives on the Bluetooth voice remote, which only the system
 * SpeechRecognizer (Google) can read — raw AudioRecord/MediaRecorder gets a phantom
 * built-in mic (silence). So press the remote mic (keycode 186) -> Google STT
 * (single-shot) -> window.jarvisAsk(). MediaRecorder->ElevenLabs is a fallback only
 * when no recognizer exists (e.g. a device WITH a real local mic).
 * Voice OUTPUT: ElevenLabs TTS, played in the WebView.
 *
 * Remote: mic button = talk · MENU = set backend URL · BACK = web back.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_RECORD_AUDIO = 10
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val GOOGLE_TV_SEARCH = "com.google.android.katniss"
    }

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var voiceAfterPermission = false
    private var micPermissionRequestActive = false
    private var permissionDialogShowing = false
    private var webLoaded = false
    private var pendingAsk: String? = null

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recording = false        // ElevenLabs (MediaRecorder) fallback path active

    private var sr: SpeechRecognizer? = null
    private var gListening = false        // Google SpeechRecognizer (remote mic) active

    private fun backendUrl(): String =
        prefs.getString("backend_url", null) ?: BuildConfig.DEFAULT_BACKEND_URL

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // let ElevenLabs TTS autoplay
            cacheMode = WebSettings.LOAD_NO_CACHE       // kiosk: always fetch the latest client
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.clearCache(true)
        web.addJavascriptInterface(VoiceBridge(), "AndroidVoice")
        initTts()

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webLoaded = true
                flushPendingAsk()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

        hideSystemUi()
        web.loadUrl(backendUrl())
        handleSearchIntent(intent)
        ui.post { ensureRecordAudioPermission(startAfterGrant = false) }
    }

    // ---- native Text-To-Speech (Google TTS engine) ----
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault().takeIf { (tts?.isLanguageAvailable(it) ?: -2) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
                ttsReady = true
                // tell the web native TTS is available so it can skip the (dead) Web Speech API
                runOnUiThread { web.evaluateJavascript("window.__tvNativeTTS = true", null) }
                Log.w("JarvisTTS", "native TTS ready")
            } else {
                Log.w("JarvisTTS", "native TTS init failed: $status")
            }
        }
    }

    /** Exposed to the web page as `window.AndroidVoice` (SPEAK button → these). */
    inner class VoiceBridge {
        @JavascriptInterface fun toggle() = runOnUiThread { if (busy()) stopVoice() else startVoice() }
        @JavascriptInterface fun start() = runOnUiThread { if (!busy()) startVoice() }
        @JavascriptInterface fun stop() = runOnUiThread { if (busy()) stopVoice() }
        @JavascriptInterface fun ttsAvailable(): Boolean = ttsReady
        @JavascriptInterface fun speak(text: String) = runOnUiThread {
            if (ttsReady && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tv-tts")
        }
        @JavascriptInterface fun stopSpeak() = runOnUiThread { tts?.stop() }
    }

    private fun busy() = recording || gListening

    private fun toggleVoice() {
        if (busy()) stopVoice() else startVoice()
    }

    private fun askJarvis(text: String?) {
        val query = text?.trim().orEmpty()
        if (query.isEmpty()) return
        pendingAsk = query
        flushPendingAsk()
    }

    private fun flushPendingAsk() {
        if (!webLoaded) return
        val query = pendingAsk ?: return
        pendingAsk = null
        web.evaluateJavascript("window.jarvisAsk && window.jarvisAsk(${JSONObject.quote(query)})", null)
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)
        Log.w("JarvisVoice", "android tv search query: $query")
        askJarvis(query)
    }

    private fun hasRecordAudioPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureRecordAudioPermission(startAfterGrant: Boolean): Boolean {
        if (hasRecordAudioPermission()) return true
        voiceAfterPermission = voiceAfterPermission || startAfterGrant
        jsListening(false, "permission")
        if (!micPermissionRequestActive && Build.VERSION.SDK_INT >= 23) {
            micPermissionRequestActive = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
        return false
    }

    private fun openAppSettings(packageName: String = this.packageName) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun recognizerPackages(): List<String> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, 0)
        }
        return services.mapNotNull { it.serviceInfo?.packageName }.distinct()
    }

    private fun defaultRecognizerPackage(): String? {
        val configured = try {
            Settings.Secure.getString(contentResolver, "voice_recognition_service")
        } catch (_: Exception) {
            null
        }
        val configuredPackage = configured
            ?.let { ComponentName.unflattenFromString(it) }
            ?.packageName
        if (!configuredPackage.isNullOrBlank()) return configuredPackage

        val packages = recognizerPackages()
        return packages.firstOrNull { it == GOOGLE_APP || it == GOOGLE_TV_SEARCH }
            ?: packages.firstOrNull()
    }

    private fun recognizerHasMicPermission(): Boolean {
        val pkg = defaultRecognizerPackage() ?: return true
        val granted = packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, pkg) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) showExternalMicPermissionDialog(pkg)
        return granted
    }

    private fun showJarvisMicPermissionDialog() {
        if (permissionDialogShowing) return
        permissionDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Turn on Jarvis microphone")
            .setMessage(
                "Jarvis needs Microphone permission for voice input. The MiBox remote " +
                    "also needs Microphone enabled for Google/Speech Services."
            )
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { permissionDialogShowing = false }
            .show()
    }

    private fun showExternalMicPermissionDialog(packageName: String) {
        if (permissionDialogShowing) return
        permissionDialogShowing = true
        voiceAfterPermission = true
        jsListening(false, "permission")
        AlertDialog.Builder(this)
            .setTitle("Turn on Google microphone")
            .setMessage(
                "The MiBox remote mic is read by Android TV speech recognition. Turn on " +
                    "Microphone for Google/Speech Services, then press the remote mic again."
            )
            .setPositiveButton("Open settings") { _, _ -> openAppSettings(packageName) }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { permissionDialogShowing = false }
            .show()
    }

    // Google SpeechRecognizer is the ONLY API that can read the BT voice-remote mic.
    private fun googleAvailable() =
        try { SpeechRecognizer.isRecognitionAvailable(this) } catch (_: Exception) { false }

    private fun startVoice() {
        if (busy()) return
        if (!ensureRecordAudioPermission(startAfterGrant = true)) return
        if (googleAvailable() && !recognizerHasMicPermission()) return
        if (googleAvailable()) startGoogle() else startRec()   // remote mic via Google; EL only if no recognizer
    }

    private fun stopVoice() {
        if (gListening) { try { sr?.stopListening() } catch (_: Exception) {} }
        else if (recording) stopAndSend()
    }

    // ---- Google single-shot STT (reads the remote mic; free) ----
    private fun startGoogle() {
        if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(gRec) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        gListening = true; jsListening(true)
        try { sr?.startListening(intent) } catch (e: Exception) {
            Log.w("JarvisVoice", "google start failed: $e"); gListening = false; startRec()
        }
    }

    private val gRec = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { Log.w("JarvisVoice", "google ready (remote mic open)") }
        override fun onResults(b: Bundle?) {
            gListening = false; jsListening(false)
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            Log.w("JarvisVoice", "google result: $t")
            if (!t.isNullOrBlank()) askJarvis(t)
            else web.evaluateJavascript("window.jarvisListening && window.jarvisListening(false,'nospeech')", null)
        }
        override fun onPartialResults(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.let { web.evaluateJavascript("window.jarvisPartial && window.jarvisPartial(${JSONObject.quote(it)})", null) }
        }
        override fun onError(error: Int) {
            Log.w("JarvisVoice", "google error: $error"); gListening = false
            web.evaluateJavascript("window.jarvisListening && window.jarvisListening(false,${if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "'nospeech'" else "'error'"})", null)
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ---- ElevenLabs fallback (devices WITH a real local mic): MediaRecorder -> /stt ----
    private fun startRec() {
        try {
            val f = File(cacheDir, "rec.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; recFile = f; recording = true
            jsListening(true)
        } catch (e: Exception) {
            recording = false; jsListening(false)
        }
    }

    private fun stopAndSend() {
        recording = false
        val f = recFile
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        jsListening(false)
        if (f == null || !f.exists() || f.length() < 800L) return  // too short / empty
        Thread { uploadForTranscript(f) }.start()
    }

    private fun uploadForTranscript(f: File) {
        try {
            val bytes = f.readBytes()
            val conn = (URL(backendUrl().trimEnd('/') + "/stt").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "audio/mp4")
                connectTimeout = 10000; readTimeout = 60000
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            val text = JSONObject(body).optString("text", "").trim()
            ui.post {
                if (text.isNotEmpty())
                    askJarvis(text)
                else
                    web.evaluateJavascript("window.jarvisListening && window.jarvisListening(false)", null)
            }
        } catch (_: Exception) {
            ui.post { web.evaluateJavascript("window.jarvisListening && window.jarvisListening(false)", null) }
        }
    }

    private fun jsListening(on: Boolean, reason: String? = null) {
        val suffix = reason?.let { ",${JSONObject.quote(it)}" } ?: ""
        web.evaluateJavascript("window.jarvisListening && window.jarvisListening($on$suffix)", null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RECORD_AUDIO) return
        micPermissionRequestActive = false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (voiceAfterPermission) {
                voiceAfterPermission = false
                startVoice()
            }
        } else {
            jsListening(false, "permission")
            showJarvisMicPermissionDialog()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Dedicated voice/search buttons → trigger voice immediately.
    // NOTE: the remote MIC button (PROG_BLUE/186) is owned by Google Assistant at the
    // system level and CANNOT be intercepted while Google is enabled; kept here for
    // remotes where it does reach the app (Google disabled).
    private val voiceKeys = setOf(
        KeyEvent.KEYCODE_PROG_BLUE,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_ASSIST
    )

    // OK / select keys: short-press activates the focused web element (so the sidebar
    // and cards are clickable with OK), long-press triggers voice. This resolves the
    // conflict where OK could only do one of "select" or "talk".
    private val selectKeys = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BUTTON_A
    )

    private val LONG_PRESS_MS = 400L
    private var selectDownTime = 0L
    private var selectLongFired = false
    private var menuDownTime = 0L
    private var menuLongFired = false

    // ALL key routing happens here, BEFORE the WebView sees the event. This is
    // essential: if the WebView got OK first it would translate it to its own
    // activation/keydown (which previously hijacked every press), so OK could
    // never be both "select" and "talk". We intercept and decide ourselves.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP  "
            else -> "A${event.action}"
        }
        Log.w(
            "JarvisKey",
            "$action code=${event.keyCode} scan=${event.scanCode} source=${event.source}"
        )

        // BACK / MENU must be handled BEFORE the WebView — an open space (mail)
        // otherwise swallows BACK internally and traps the user, unable to
        // switch spaces. jarvisHome() closes the launcher / leaves the space.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                web.evaluateJavascript("window.jarvisHome && window.jarvisHome()", null)
            }
            return true
        }
        // MENU: short-press opens the in-app Settings screen; long-press opens the
        // low-level backend-URL dialog (dev escape hatch).
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) { menuDownTime = event.eventTime; menuLongFired = false }
                    else if (!menuLongFired && event.eventTime - menuDownTime >= LONG_PRESS_MS) {
                        menuLongFired = true; showUrlDialog()
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (!menuLongFired) web.evaluateJavascript("window.jarvisSettings && window.jarvisSettings()", null)
                    menuLongFired = false
                }
            }
            return true
        }

        // Dedicated voice/search buttons → talk immediately.
        if (event.keyCode in voiceKeys) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleVoice()
            return true
        }

        // OK / select: short press = activate the focused web element (navigation),
        // long hold = voice. We consume it so the WebView never double-handles.
        if (event.keyCode in selectKeys) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        selectDownTime = event.eventTime
                        selectLongFired = false
                    } else if (!selectLongFired && event.eventTime - selectDownTime >= LONG_PRESS_MS) {
                        selectLongFired = true
                        toggleVoice()   // held long enough → talk
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (!selectLongFired) {
                        // short press → OK: enter/activate the focused widget (drills
                        // through shadow DOM); falls back to clicking the focused element.
                        web.evaluateJavascript(
                            "window.__tvOk ? window.__tvOk() : (document.activeElement&&document.activeElement.click&&document.activeElement.click())",
                            null
                        )
                    }
                    selectLongFired = false
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onSearchRequested(): Boolean { toggleVoice(); return true }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply { setText(backendUrl()); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Backend URL")
            .setMessage("Address of the Jarvis backend on your network")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) { prefs.edit().putString("backend_url", u).apply(); web.loadUrl(u) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (voiceAfterPermission && hasRecordAudioPermission() && recognizerHasMicPermission()) {
            voiceAfterPermission = false
            ui.post { startVoice() }
        }
    }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() {
        try { recorder?.release() } catch (_: Exception) {}
        try { sr?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
