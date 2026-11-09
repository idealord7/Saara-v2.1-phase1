package com.saara.assistant

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground Saara voice assistant service.
 *
 * Wake/idle + continuous flow:
 * - Continuously listens in the background with a single, stable [SpeechRecognizer] instance.
 * - While !isSaraActive, all speech is ignored unless it contains the wake word ("Hey Sara" / "সারা" / "سارا").
 * - On wake, [isSaraActive]=true. During the active session, follow-up utterances are processed directly
 *   without repeating the wake word.
 * - A session timeout (8–10s, default 9s) resets [isSaraActive]=false after silence/inactivity; wake word
 *   is required again. Listening continues.
 *
 * Mic/privacy/beep stability:
 * - Keeps ONE SpeechRecognizer alive (created lazily, destroyed only in [onDestroy]).
 * - Does NOT call cancel()/stopListening() between normal utterances; after [onResults]/[onError] it schedules
 *   the next [SpeechRecognizer.startListening] via [Handler] to avoid rapid hardware toggling/beeps/privacy flicker.
 * - [onEndOfSpeech] does not interrupt the loop; it only tracks speech state.
 */
class VoiceService : Service() {
  companion object {
    private const val TAG = "VoiceService"
    var instance: VoiceService? = null
    const val ASSISTANT_COMMAND = "saara_command"
    const val PREF_WAKE_PHRASES = "wake_phrases"
    const val PREF_SESSION_TIMEOUT_SEC = "session_timeout_sec"
    const val DEFAULT_SESSION_TIMEOUT_SEC = 9
    val DEFAULT_WAKE_PHRASES = listOf(
      "hey sara", "hey saara", "ok saara", "okay saara", "oye saara", "oye sara", "saara", "sara", "সারা", "سارا"
    )
    private const val RESTART_AFTER_RESULT_MS = 120L
    private const val RESTART_AFTER_NO_MATCH_MS = 220L
    private const val RESTART_AFTER_ERROR_MS = 450L
    private const val RESTART_AFTER_BUSY_MS = 900L

    fun loadWakePhrases(p: android.content.SharedPreferences): MutableList<String> {
      val raw = p.getString(PREF_WAKE_PHRASES, null)
      val list = if (raw.isNullOrBlank()) DEFAULT_WAKE_PHRASES.toMutableList()
      else raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
      if (list.isEmpty()) list.addAll(DEFAULT_WAKE_PHRASES)
      return list
    }

    fun saveWakePhrases(e: android.content.SharedPreferences.Editor, phrases: List<String>) {
      val cleaned = phrases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
      e.putString(PREF_WAKE_PHRASES, (if (cleaned.isEmpty()) DEFAULT_WAKE_PHRASES else cleaned).joinToString("\n"))
    }
  }

  // --- Gemini / app state ---
  private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null
  @Volatile private var running = false
  @Volatile private var ready = false
  @Volatile private var speakingUntil = 0L
  @Volatile var muted = false
  @Volatile var isSaraActive = false
  @Volatile private var listeningEnabled = false
  @Volatile private var recognizerActive = false
  @Volatile private var micEnabled = true
  private var retries = 0
  private var pendingCommand: String? = null
  private var track: AudioTrack? = null
  private val audioQ = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var bubble: Bubble? = null
  private var overlay: Overlay? = null
  private var cam: CamShare? = null
  private var screen: ScreenShare? = null
  @Volatile private var screenAuto = false
  private val screenAutoStop = Runnable { if (Bus.screenOn && screenAuto) stopScreen(true) }
  private var calls: CallWatcher? = null
  @Volatile private var callActive = false
  @Volatile private var enrolling = false
  private val micLock = Any()
  private var lastMicToggle = 0L
  private val main = Handler(Looper.getMainLooper())
  private val busListener: () -> Unit = { updateOverlay() }

  // --- Speech recognition (single stable instance) ---
  private var speechRecognizer: SpeechRecognizer? = null
  private var recognizerIntent: Intent? = null
  private val sessionTimer = Runnable { onSessionTimeout() }
  private val restartRunnable = Runnable { startListeningInternal() }
  @Volatile private var wakePatterns: List<Regex> = emptyList()

  private fun sessionTimeoutMs(): Long =
    Prefs.plain(this).getInt(PREF_SESSION_TIMEOUT_SEC, DEFAULT_SESSION_TIMEOUT_SEC).coerceIn(3, 60) * 1000L

  /** Call after changing wake phrases in Settings. */
  fun refreshWakeConfig() { main.post { wakePatterns = buildWakePatterns(loadWakePhrases(Prefs.plain(this))) } }

  private fun normalizePhrase(s: String): String =
    s.lowercase(Locale.ROOT).replace(Regex("[,!?।]+"), " ").replace(Regex("\\s+"), " ").trim()

  private fun buildWakePatterns(phrases: List<String>): List<Regex> {
    val out = ArrayList<Regex>()
    for (ph in phrases) {
      val t = ph.trim(); if (t.isEmpty()) continue
      val hasLatin = t.any { it in 'A'..'Z' || it in 'a'..'z' }
      if (hasLatin) {
        val words = t.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
          val body = words.joinToString("\\s+") { Regex.escape(it) }
          out.add(Regex("(?i)(?:^|\\b)" + body + "\\b[,!?. ]*(.*)$"))
        }
      } else {
        out.add(Regex(Regex.escape(t) + "[,!?.\\s]*(.*)$"))
      }
    }
    out.sortByDescending { it.pattern.length }
    return out
  }

  // Best-effort beep suppression around startListening (recognizer tones are system/OEM-dependent).
  private var beepMuted = false
  private var savedMusicVol = -1
  private var savedNotifVol = -1
  private var savedSystemVol = -1
  private val unmuteBeeps = Runnable { restoreBeepStreams() }

  private fun muteBeepStreams() {
    try {
      val am = getSystemService(AudioManager::class.java) ?: return
      if (!beepMuted) {
        try { savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { savedMusicVol = -1 }
        try { savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (_: Exception) { savedNotifVol = -1 }
        try { savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM) } catch (_: Exception) { savedSystemVol = -1 }
        beepMuted = true
      }
      try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) } catch (_: Exception) {}
      try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0) } catch (_: Exception) {}
      // Do not touch RING/ALARM/VOICE_CALL.
    } catch (_: Exception) {}
  }

  private fun restoreBeepStreams() {
    if (!beepMuted) return
    try {
      val am = getSystemService(AudioManager::class.java) ?: return
      if (savedMusicVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0) } catch (_: Exception) {}
      if (savedNotifVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0) } catch (_: Exception) {}
    } catch (_: Exception) {}
    beepMuted = false
    savedMusicVol = -1; savedNotifVol = -1; savedSystemVol = -1
  }

  private val recognitionListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      recognizerActive = true
      // Restore after the recognizer's start cue window; keep this short to avoid silencing app audio long.
      main.removeCallbacks(unmuteBeeps)
      main.postDelayed(unmuteBeeps, 350L)
    }

    override fun onBeginningOfSpeech() {
      recognizerActive = true
      // User is speaking; if a session is active, keep it alive.
      resetSessionTimerIfActive()
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
      resetSessionTimerIfActive()
    }

    override fun onEndOfSpeech() {
      // Do not stop/cancel here; let the system deliver onResults/onError and we restart smoothly.
      recognizerActive = false
    }

    override fun onResults(results: Bundle?) {
      recognizerActive = false
      main.removeCallbacks(unmuteBeeps); restoreBeepStreams()
      if (running && listeningEnabled) handleRecognitionResults(results)
      if (running && listeningEnabled) restartListening(if (isSaraActive) 250L else 600L)
    }

    override fun onError(error: Int) {
      recognizerActive = false
      main.removeCallbacks(unmuteBeeps); restoreBeepStreams()
      if (!running) return
      when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
          Bus.add("system", "Microphone permission is missing.", true)
          listeningEnabled = false
          return
        }
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
          restartListening(RESTART_AFTER_BUSY_MS)
          return
        }
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
          // Longer backoff reduces start/stop session churn (mic indicator/beep toggling).
          restartListening(if (isSaraActive) 700L else 1400L)
          return
        }
        SpeechRecognizer.ERROR_CLIENT -> {
          restartListening(900L)
          return
        }
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> {
          restartListening(1200L)
          return
        }
        SpeechRecognizer.ERROR_AUDIO -> {
          restartListening(1000L)
          return
        }
        else -> {
          Log.w(TAG, "SpeechRecognizer error: $error")
          restartListening(900L)
          return
        }
      }
    }
  }

  // ---------- Public API used by the app ----------
  fun isMicOn(): Boolean = micEnabled && listeningEnabled

  fun toggleMic() {
    synchronized(micLock) {
      val now = SystemClock.elapsedRealtime()
      if (now - lastMicToggle < 800L) return
      lastMicToggle = now
      if (callActive) {
        Bus.add("system", "A call is in progress. I will keep the microphone off until it ends.", true)
        return
      }
      micEnabled = !micEnabled
      Prefs.plain(this).edit().putBoolean("mic_enabled", micEnabled).apply()
      if (micEnabled) {
        Bus.add("system", "Microphone ON. Say \"Hey Sara\" or \"সারা\" to start.", true)
        startListening()
      } else {
        isSaraActive = false
        main.removeCallbacks(sessionTimer)
        stopListening()
        Bus.add("system", "Microphone muted.", true)
      }
      Bus.ping()
    }
  }

  fun startSession() {
    isSaraActive = true
    resetSessionTimer()
  }

  private fun resetSessionTimer() {
    main.removeCallbacks(sessionTimer)
    if (isSaraActive && running) {
      main.postDelayed(sessionTimer, sessionTimeoutMs())
    }
  }

  private fun resetSessionTimerIfActive() {
    if (isSaraActive) resetSessionTimer()
  }

  fun endSession() {
    if (!isSaraActive) return
    isSaraActive = false
    main.removeCallbacks(sessionTimer)
    Bus.add("system", "Session ended. Say \"Hey Sara\" or \"সারা\" to start again.", true)
  }

  private fun onSessionTimeout() {
    if (!running) return
    if (!isSaraActive) return
    isSaraActive = false
    Bus.add("system", "Session ended. Say \"Hey Sara\" or \"সারা\" to start again.", true)
  }

  fun suspendWakeForEnrollment() {
    main.post {
      enrolling = true
      stopListening()
      if (running) Bus.add("system", "Voice recognition paused for enrollment.", true)
    }
  }

  fun resumeWakeAfterEnrollment() {
    main.post {
      enrolling = false
      if (running && ready && !callActive && micEnabled) startListening()
    }
  }

  fun refreshCalls() {
    if (!running) return
    if (!Prefs.on(this, "f_calls", true) || !has(Manifest.permission.READ_PHONE_STATE)) {
      calls?.stop(); calls = null
      return
    }
    if (calls == null) {
      calls = CallWatcher(this, ::onCallStarted, ::onCallAnswered, ::onCallEnded).also { it.start() }
    }
  }

  private fun onCallStarted(caller: String) {
    callActive = true
    stopListening()
    val who = if (caller.isBlank()) "an unknown number" else caller
    Bus.setStatus("Incoming call" + if (caller.isBlank()) "" else " · $caller")
    Bus.add("system", "Incoming call from $who. Say \"answer\" or \"reject\" — the caller details stay on this phone.", true)
  }

  private fun onCallAnswered() {
    callActive = true
    Bus.setStatus("Call active")
    Bus.add("system", "Call answered. Saara will resume listening after the call ends.", true)
  }

  private fun onCallEnded() {
    callActive = false
    Bus.setStatus(if (ready) "Connected · listening" else Bus.status)
    Bus.add("system", "Call ended.", true)
    main.postDelayed({
      if (running && ready && !callActive && micEnabled) startListening()
    }, 900L)
    Bus.ping()
  }

  fun refreshBubble() {
    if (Prefs.on(this, "f_bubble", false)) {
      if (bubble == null) bubble = Bubble(this) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      bubble?.show()
    } else bubble?.hide()
    if (!Prefs.on(this, "f_overlay", true)) overlay?.hide()
  }

  fun sendText(t: String) {
    // Typed input from UI: activate session and process directly.
    startSession()
    processUtterance(t)
  }

  fun runScheduled(text: String): Boolean {
    Bus.add("system", "Scheduled task fired at ${SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())}: $text", true)
    return false
  }

  fun onNotification(key: String, pkg: String, title: String, text: String) {
    Bus.add("system", "A notification arrived from $pkg. Its contents were kept on this device.", true)
  }

  // ---------- Wake/idle + continuous conversation ----------
  private fun handleRecognitionResults(bundle: Bundle?) {
    val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val heard = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (heard.isBlank()) return

    if (!isSaraActive) {
      // Idle state: ignore everything except the wake word.
      val afterWake = extractWakeCommand(heard)
      if (afterWake == null) return
      isSaraActive = true
      resetSessionTimer()
      Bus.add("system", "Wake word heard.", true)
      if (afterWake.isNotBlank()) processUtterance(afterWake)
      return
    }

    // Active conversation flow: process directly, no wake word required.
    resetSessionTimer()
    processUtterance(heard)
  }

  private fun processUtterance(text: String) {
    if (text.isBlank()) return
    // Strip an accidental repeated wake phrase during an active session.
    val cleaned = extractWakeCommand(text)?.takeIf { it.isNotBlank() } ?: text.trim()
    if (cleaned.isBlank()) { resetSessionTimer(); return }
    armScreenAuto(300_000L)
    Bus.add("you", cleaned, true)
    if (TaskControl.isStop(cleaned)) {
      TaskControl.clear()
      stopSpeaking()
      Bus.add("system", "Stopped the current task.", true)
      resetSessionTimer()
      return
    }
    try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
    if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", cleaned)).toString())
    else Bus.add("saara", "Gemini is not connected yet. Check the API key and connection in Settings.", true)
  }

  /**
   * Returns:
   * - command text after a wake phrase (may be empty if only wake phrase was said),
   * - or null if no wake phrase is present.
   * Supported: "Hey Sara/hi/hello/ok Sara", Bengali "সারা", Arabic-script "سارا" (and bare "sara" fallback).
   */
  private fun extractWakeCommand(raw: String): String? {
    val patterns = listOf(
      Regex("""(?i)\bhey\s+sara\b[,!\s]*(.*)$"""),
      Regex("""(?i)\bhi\s+sara\b[,!\s]*(.*)$"""),
      Regex("""(?i)\bhello\s+sara\b[,!\s]*(.*)$"""),
      Regex("""(?i)\bok\s+sara\b[,!\s]*(.*)$"""),
      Regex("""সারা[,!\s]*(.*)$"""),
      Regex("""سارا[,!\s]*(.*)$"""),
      Regex("""(?i)\bsara\b[,!\s]*(.*)$""")
    )
    for (p in patterns) {
      val m = p.find(raw) ?: continue
      return m.groupValues.getOrNull(1)?.trim().orEmpty()
    }
    return null
  }

  // ---------- Speech recognizer lifecycle (stable instance) ----------
  fun startListening() { main.post { startListeningInternal() } }

  fun restartListening(delayMs: Long = 0L) {
    if (!running) return
    main.removeCallbacks(restartRunnable)
    main.postDelayed(restartRunnable, delayMs.coerceAtLeast(0L))
  }

  private fun ensureRecognizer(): SpeechRecognizer? {
    speechRecognizer?.let { return it }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
    val sr = SpeechRecognizer.createSpeechRecognizer(applicationContext)
    sr.setRecognitionListener(recognitionListener)
    speechRecognizer = sr
    return sr
  }

  private fun buildRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    // Longer silence windows reduce end-of-utterance cycles => fewer startListening() restarts (less mic toggling/beep churn).
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
  }

  private fun startListeningInternal() {
    if (!running || !micEnabled || callActive || enrolling) return
    val sr = ensureRecognizer()
    if (sr == null) {
      Bus.add("system", "Speech recognition is not available on this device.", true)
      return
    }
    listeningEnabled = true
    refreshNotif()
    val intent = recognizerIntent ?: buildRecognizerIntent().also { recognizerIntent = it }
    try {
      // Important: do NOT call cancel()/stopListening() here in the normal loop.
      // Reusing the same instance and only calling startListening() after results/errors
      // minimizes system mic toggles, privacy-indicator flicker, and beep sounds.
      sr.startListening(intent)
      recognizerActive = true
      Bus.setStatus(if (isSaraActive) "Connected · listening" else "Connected · say \"Hey Sara\"")
    } catch (e: Exception) {
      Log.w(TAG, "startListening failed", e)
      recognizerActive = false
      // Busy/illegal-state recovery: back off and retry; keep the instance.
      restartListening(RESTART_AFTER_ERROR_MS)
    }
  }

  fun stopListening() {
    listeningEnabled = false
    recognizerActive = false
    main.removeCallbacks(restartRunnable)
    main.removeCallbacks(unmuteBeeps); restoreBeepStreams()
    // Only stop when the user/service explicitly pauses listening (mute/call/enroll/destroy).
    try { speechRecognizer?.stopListening() } catch (_: Exception) {}
    try { speechRecognizer?.cancel() } catch (_: Exception) {}
    refreshNotif()
  }

  private fun destroyRecognizer() {
    main.removeCallbacks(restartRunnable)
    main.removeCallbacks(unmuteBeeps); restoreBeepStreams()
    try { speechRecognizer?.cancel() } catch (_: Exception) {}
    try { speechRecognizer?.destroy() } catch (_: Exception) {}
    speechRecognizer = null
    recognizerIntent = null
    recognizerActive = false
    listeningEnabled = false
  }

  // ---------- Foreground service / UI ----------
  override fun onBind(i: Intent?): IBinder? = null

  private fun notif(): Notification {
    val stop = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val sharing = listOfNotNull(if (Bus.camOn) "camera" else null, if (Bus.screenOn) "screen" else null)
    val state = when {
      !micEnabled -> "Saara · microphone muted"
      listeningEnabled && isSaraActive -> "Saara · listening (active)"
      listeningEnabled -> "Saara · say \"Hey Sara\""
      ready -> "Saara · starting…"
      else -> "Saara · connecting…"
    }
    val title = if (sharing.isEmpty()) state else "$state • sharing ${sharing.joinToString(" & ")}"
    return Notification.Builder(this, "saara").setContentTitle(title).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(open).addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build()).build()
  }

  private fun refreshNotif() {
    if (!running) return
    try { getSystemService(NotificationManager::class.java).notify(1, notif()) } catch (_: Exception) {}
  }

  private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun fg() {
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        var t = 0
        if (has(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Bus.camOn && has(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Bus.screenOn) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (t == 0) {
          Bus.add("system", "Microphone permission is missing, so Saara cannot listen in the background.", true)
          stopSelf(); return
        }
        startForeground(1, notif(), t)
      } else startForeground(1, notif())
    } catch (e: Exception) { Bus.add("system", "Foreground service: ${e.message}", true) }
  }

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    Bus.init(this)
    when (i?.action) {
      "STOP" -> { stopSelf(); return START_NOT_STICKY }
      "WAKE" -> { if (running && ready && micEnabled && !callActive) startListening(); return START_STICKY }
      "CAM_START", "CAM_STOP", "SCREEN_START", "SCREEN_STOP" -> { if (!running) { stopSelf(); return START_NOT_STICKY }; share(i!!); return START_STICKY }
    }
    val cmd = i?.getStringExtra(ASSISTANT_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
    if (cmd != null) pendingCommand = cmd
    if (running) return START_STICKY
    if (!has(Manifest.permission.RECORD_AUDIO)) {
      Bus.add("system", "Microphone permission is missing. Allow Microphone in Android app permissions.", true)
      stopSelf(); return START_NOT_STICKY
    }
    running = true; instance = this; Bus.running = true
    micEnabled = Prefs.plain(this).getBoolean("mic_enabled", true)
    wakePatterns = buildWakePatterns(loadWakePhrases(Prefs.plain(this)))
    migrateFacts(this)
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("saara", "Saara", NotificationManager.IMPORTANCE_LOW))
    fg()
    val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    track = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(maxOf(48000, minBuf)).build().also { it.play() }
    Thread {
      while (running) {
        val b: ByteArray = (try { audioQ.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }) ?: continue
        try { track?.write(b, 0, b.size) } catch (_: Exception) {}
      }
    }.start()
    refreshBubble()
    Bus.listen(busListener)
    Bus.add("system", "Connecting…", true)
    refreshCalls()
    connect()
    return START_STICKY
  }

  private fun updateOverlay() {
    if (!running) return
    if (!Prefs.on(this, "f_overlay", true) || !Settings.canDrawOverlays(this)) { overlay?.hide(); return }
    if (overlay == null) overlay = Overlay(this)
    if (Bus.camOn) { overlay?.show("📷 LIVE CAMERA SHARING ON\nLive camera frames are being sent to Gemini.", true); return }
    if (MainActivity.visible) { overlay?.hide(); return }
    val m = synchronized(Bus) { Bus.msgs.lastOrNull() } ?: return
    val icon = when (m.role) { "you" -> "🧑 "; "saara" -> "✨ "; "tool" -> "⚙️ "; else -> "ℹ️ " }
    overlay?.show(icon + m.text.takeLast(220))
  }

  // ---------- camera / screen sharing ----------
  private fun share(i: Intent) {
    when (i.action) {
      "CAM_START" -> startCam(i.getBooleanExtra("front", false))
      "CAM_STOP" -> stopCam(true)
      "SCREEN_START" -> startScreen(i.getIntExtra("code", 0), projData(i), i.getBooleanExtra("auto", false))
      "SCREEN_STOP" -> stopScreen(true)
    }
  }
  @Suppress("DEPRECATION")
  private fun projData(i: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java) else i.getParcelableExtra("data")
  private fun tellModel(t: String) { if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()) }

  private fun startCam(front: Boolean) {
    if (!has(Manifest.permission.CAMERA)) { Bus.add("system", "Camera permission missing. Allow it in the Saara app.", true); return }
    val was = Bus.camOn
    Bus.camOn = true; fg()
    val c = cam ?: CamShare(this, { sendFrame(it) }, { m -> Bus.add("system", m, true); main.post { stopCam(false) } }).also { cam = it }
    val err = c.start(front)
    if (err != null) { Bus.add("system", err, true); stopCam(false); return }
    Bus.ping()
    if (!was) { Bus.add("system", "Camera sharing is ON (${if (front) "front" else "back"}).", true); Bus.add("system", "Live camera frames are being sent to Gemini.", true); tellModel("[System] Camera sharing is now ON. Live camera frames are being sent to you. Use them to help the user with the task.") }
  }
  private fun stopCam(say: Boolean) {
    cam?.stop(); cam = null
    if (Bus.camOn) { Bus.camOn = false; fg(); if (say) { Bus.add("system", "Camera sharing stopped.", true); tellModel("[System] Camera sharing stopped. Do not reply.") } }
    Bus.ping()
  }
  private fun startScreen(code: Int, data: Intent?, auto: Boolean) {
    if (data == null || code == 0) { Bus.add("system", "Screen sharing was not allowed.", true); return }
    main.removeCallbacks(screenAutoStop)
    screenAuto = auto; Bus.screenAuto = auto
    Bus.screenOn = true; fg()
    val s = screen ?: ScreenShare(this, { sendFrame(it) }, { main.post { stopScreen(true) } }).also { screen = it }
    val err = s.start(code, data)
    if (err != null) { Bus.add("system", err, true); stopScreen(false); return }
    Bus.add("system", if (auto) "Screen sharing is ON for this task. It will stop automatically when the task ends." else "Screen sharing is ON. Tap the Screen chip to stop it.", true)
    Bus.add("system", "Live screen frames are being sent to Gemini.", true)
    if (auto) armScreenAuto(300_000L)
    tellModel("[System] Screen sharing is now ON. Live screen frames are being sent to you. Use them to complete the user's mobile task." + if (auto) " This sharing was requested for the current task; it will be turned off automatically when the task is done." else "")
  }
  private fun stopScreen(say: Boolean) {
    main.removeCallbacks(screenAutoStop)
    screenAuto = false; Bus.screenAuto = false
    screen?.stop(); screen = null
    if (Bus.screenOn) { Bus.screenOn = false; fg(); if (say) { Bus.add("system", "Screen sharing stopped.", true); tellModel("[System] Screen sharing stopped. Do not reply.") } }
    Bus.ping()
  }
  private fun armScreenAuto(delayMs: Long) {
    if (!screenAuto || !Bus.screenOn) return
    main.removeCallbacks(screenAutoStop)
    main.postDelayed(screenAutoStop, delayMs)
  }

  fun sendFrame(jpeg: ByteArray) {
    if (!running || !ready) return
    val socket = ws ?: return
    try {
      val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
      val blob = JSONObject().put("data", data).put("mimeType", "image/jpeg")
      socket.send(JSONObject().put("realtimeInput", JSONObject().put("video", blob)).toString())
    } catch (_: Exception) {}
  }

  // ---------- Gemini Live connection ----------
  private fun connect() {
    if (!running) return
    ready = false
    Bus.setStatus("Connecting…")
    val key = Prefs.secure(this).getString("apiKey", "") ?: ""
    if (key.isBlank()) { Bus.add("system", "API key missing. Add it in Settings.", true); stopSelf(); return }
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
    ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onOpen(w: WebSocket, r: Response) { w.send(setup()) }
      override fun onMessage(w: WebSocket, text: String) { handle(text) }
      override fun onMessage(w: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
      override fun onClosing(w: WebSocket, code: Int, reason: String) { w.close(1000, null) }
      override fun onClosed(w: WebSocket, code: Int, reason: String) { lost("Disconnected ($code) $reason") }
      override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { lost("Connection failed: ${t.message} ${r?.code ?: ""}") }
    })
  }

  private fun lost(msg: String) {
    ready = false
    if (!running) return
    Bus.add("system", msg, true)
    if (++retries > 3) { Bus.add("system", "Stopped after repeated failures. Check API key / model name in Settings.", true); main.post { stopSelf() }; return }
    Bus.setStatus("Reconnecting…")
    main.postDelayed({ connect() }, 3000)
  }

  private fun setup(): String {
    val p = Prefs.plain(this)
    val model = p.getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
    val voice = p.getString("voice", "Aoede") ?: "Aoede"
    val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date())
    val taskMemory = if (Prefs.on(this, "learn_tasks", true)) try { TaskLearning.prompt(this) } catch (_: Exception) { "" } else ""
    val ins = "You are Saara, an elegant, proactive, warm FEMALE voice assistant (use feminine forms when speaking Hindi, e.g. 'main kar rahi hoon'). Be concise. " + langRule(this) + " " +
      "Current local time: $now. " +
      "You can use device_control and local task tools for permitted phone actions. Do not request or reveal contacts, SMS, call logs, notifications, local knowledge, learning, chat history, or sheet contents. URL imports are handled locally by the app. Never ask for private data to be pasted into Gemini. " +
      "If a tool result says a feature is OFF or an Android permission is missing, tell the user in one short sentence exactly what to switch on or grant in the Saara app; do not just say you cannot. " +
      "AUTONOMY (very important): for any task with several steps (open an app, search, type, send, post, take a photo, answer a call...), complete the WHOLE task yourself by chaining tool calls one after another. " +
      "When camera or screen sharing is ON, you receive live frames and can see the screen. Use them to understand the screen, ground taps and complete mobile tasks. Private database contents are never sent. " +
      "SCREEN SHARING: manual screen sharing stays ON until the user turns it off. If YOU request it with request_screen_share, it is for the current task and the app turns it off automatically when the task ends; do not tell the user to stop it manually. " +
      "SPEED & SELF-HEALING: prefer the shortest direct path. After every tool result, read the returned screen and continue. If an element is missing or a tap did nothing, do not repeat the same call blindly: try a different label/id, a shorter label, tap_xy with bounds, scroll, wait_text, back, reopen the app, or another route. Try at most twice per exact action, then report one short sentence. Use maximum intelligence and keep going until the task is genuinely done. " +
      "CONFIRMATION (be smart and low-friction): do NOT ask for confirmation for clear, short, reversible actions: open app, tap, scroll, type, search, copy/paste, take photo, volume, torch, calls or messages when the recipient and content are already clear. Only ask ONCE when the action is destructive or irreversible (delete, erase, remove, cancel, pay, order, book, reset, submit, logout, block, report) or when the target/content is genuinely ambiguous. If the user already told you clearly, just do it; never ask again just to repeat the same yes. After a required confirmation, finish the whole task without asking again. Read-only questions are answered immediately. " +
      "If the user says stop, wait, hold on, ruko, bas or changes their mind at any time, stop immediately, call no more action tools, and briefly acknowledge. If a tool really fails after alternatives, say briefly what failed and ask how to proceed; never pretend it worked. " +
      "CLIPBOARD: use ui_action copy_text to copy given text (or the selected text), select_all, and paste. Use copy/paste for fields that reject typing. " +
      "CALLS: incoming calls are announced locally by the app; caller details are never sent to Gemini. Answer or reject when the user's instruction is clear. " +
      "Ask only when information is truly missing (which of two contacts, what text to write). " +
      "Do not narrate each step; when everything is done, say one short sentence with the result. " +
      "Follow the user's command in the simplest way: never make it complicated, never lecture, find a way to get it done. " +
      "Photos: call device_control take_photo (it presses the shutter by itself; text=front for selfie). Calls: answer_call / end_call. " +
      "Notifications and scheduled-task text are private local data and are never forwarded to Gemini. " +
      "Do not store personal facts in Gemini. The app stores Learning and Knowledge locally on the phone. " +
      "SELF-LEARNING: before repeating a multi-step task, call recall_task to check which steps worked or failed. After solving a new task or discovering a better method, call remember_task with concise working steps. Never put contacts, messages, ledger data, URLs, or screen text into remember_task. " +
      "Do not ask for private shop ledger details. Local ledger data is not sent to Gemini. " +
      "Live camera and screen frames are sent to Gemini only while the user turns camera or screen sharing ON; the user can turn them off at any time. While Screen automation is ON, on-device tool results may include screen text so you can complete the task; private local database contents (Learning, Knowledge, contacts, messages, tasks, fetched pages) are withheld. Links and spreadsheet data are fetched and stored locally by the app, not by Gemini. " +
      if (taskMemory.isBlank()) "" else "The short task-memory hints below contain goal names, tool names and outcomes with numbers/emails redacted; they do not contain contacts, messages, ledger data, or screen text.\n$taskMemory"
    val s = JSONObject()
      .put("model", "models/$model")
      .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ins))))
      .put("tools", Tools.declarations())
      .put("outputAudioTranscription", JSONObject())
    return JSONObject().put("setup", s).toString()
  }

  private fun handle(s: String) {
    val m = try { JSONObject(s) } catch (_: Exception) { return }
    if (m.has("setupComplete")) {
      ready = true; retries = 0; Bus.setStatus("Connected · say \"Hey Sara\"")
      val cmd = pendingCommand
      pendingCommand = null
      if (cmd != null) processUtterance(cmd)
      if (micEnabled) startListening() else Bus.add("system", "Gemini connected. Microphone is muted.", true)
    }
    if (m.has("error")) Bus.add("system", "Gemini error: " + m.optString("error"), true)
    val sc = m.optJSONObject("serverContent")
    if (sc != null) {
      val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
      if (parts != null) for (i in 0 until parts.length()) {
        val inl = parts.getJSONObject(i).optJSONObject("inlineData")
        if (inl != null) play(Base64.decode(inl.getString("data"), Base64.DEFAULT))
      }
      sc.optJSONObject("outputTranscription")?.let { Bus.add("saara", it.optString("text")) }
      if (sc.optBoolean("interrupted")) { stopSpeaking() }
      if (sc.optBoolean("turnComplete")) {
        Bus.endTurn()
        armScreenAuto(6000L)
        try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
      }
    }
    val calls = m.optJSONObject("toolCall")?.optJSONArray("functionCalls")
    if (calls != null) Thread {
      armScreenAuto(300_000L)
      resetSessionTimerIfActive()
      val out = JSONArray()
      for (i in 0 until calls.length()) {
        val c = calls.getJSONObject(i)
        val name = c.getString("name")
        val args = c.optJSONObject("args") ?: JSONObject()
        Bus.add("tool", "$name $args", true)
        val r = Tools.run(this, name, args)
        Bus.add("tool", "→ $r", true)
        out.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", JSONObject().put("result", Tools.modelResult(name, r))))
      }
      ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
    }.start()
  }

  private fun play(pcm: ByteArray) {
    if (track == null) return
    speakingUntil = maxOf(speakingUntil, System.currentTimeMillis()) + pcm.size / 48
    audioQ.offer(pcm)
  }

  private fun stopSpeaking() {
    audioQ.clear()
    try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    speakingUntil = 0
  }

  override fun onDestroy() {
    running = false; ready = false
    isSaraActive = false
    main.removeCallbacks(sessionTimer)
    main.removeCallbacks(screenAutoStop)
    screenAuto = false; Bus.screenAuto = false
    destroyRecognizer()
    Bus.unlisten(busListener)
    calls?.stop(); calls = null; callActive = false
    TaskControl.clear()
    audioQ.clear()
    cam?.stop(); cam = null; Bus.camOn = false
    screen?.stop(); screen = null; Bus.screenOn = false
    ws?.close(1000, null)
    try { track?.release() } catch (_: Exception) {}
    bubble?.hide(); overlay?.hide()
    instance = null; Bus.running = false
    Bus.add("system", "Stopped.", true); Bus.setStatus("Stopped")
    super.onDestroy()
  }
}
