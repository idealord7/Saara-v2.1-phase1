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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Saara voice service.
 *
 * Audio strategy (software-gated, hardware-stable):
 * - Microphone capture uses ONE continuous [AudioRecord] thread for the service lifetime while enabled
 *   (no SpeechRecognizer start/stop cycles => avoids recognizer beeps/session toggles).
 * - Software gating controls what Saara "hears"/acts on:
 *     - Idle (![isSaraActive]): ignore everything except configured wake phrases.
 *     - Active ([isSaraActive]=true): process follow-up utterances without repeating wake phrase.
 * - Session timeout (seconds, configurable, can be OFF) resets [isSaraActive]=false after inactivity.
 * - Wake phrase detection + transcription use Gemini Live input audio/transcription; [inputTranscription]
 *   is gated in software so Saara does not act on non-wake/background speech while idle.
 */
class VoiceService : Service() {
  companion object {
    private const val TAG = "VoiceService"
    var instance: VoiceService? = null
    const val ASSISTANT_COMMAND = "saara_command"
    const val PREF_WAKE_PHRASES = "wake_phrases"
    const val PREF_SESSION_TIMEOUT_SEC = "session_timeout_sec"
    const val PREF_SESSION_TIMEOUT_ENABLED = "session_timeout_enabled"
    const val DEFAULT_SESSION_TIMEOUT_SEC = 9
    private const val ECHO_GUARD_MS = 1800L
    val DEFAULT_WAKE_PHRASES = listOf(
      "hey sara", "hey saara", "ok saara", "okay saara", "oye saara", "oye sara", "saara", "sara", "সারা", "سارا"
    )

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
  @Volatile private var lastHeardNorm = ""
  @Volatile private var lastHeardAt = 0L
  @Volatile private var lastReplyNorm = ""
  @Volatile private var lastReplyAt = 0L
  @Volatile var muted = false
  @Volatile var isSaraActive = false
  @Volatile private var micEnabled = true
  @Volatile private var captureRunning = false
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

  // --- Continuous hardware capture (single thread) ---
  private var audioRecord: AudioRecord? = null
  private var captureThread: Thread? = null
  private val captureStarting = AtomicBoolean(false)

  // --- Session / wake (on-device OpenWakeWord/TFLite when idle) ---
  private val sessionTimer = Runnable { onSessionTimeout() }
  @Volatile private var wakePatterns: List<Regex> = emptyList()
  private var wakeEngine: WakeEngine? = null
  private val wakeLock = Any()
  @Volatile private var localWakeUnavailable = false
  @Volatile private var localWakeWarned = false

  private fun sessionTimeoutEnabled(): Boolean =
    Prefs.plain(this).getBoolean(PREF_SESSION_TIMEOUT_ENABLED, true)

  private fun sessionTimeoutMs(): Long {
    if (!sessionTimeoutEnabled()) return 0L
    val sec = Prefs.plain(this).getInt(PREF_SESSION_TIMEOUT_SEC, DEFAULT_SESSION_TIMEOUT_SEC).coerceIn(0, 999)
    return if (sec <= 0) 0L else sec * 1000L
  }

  fun refreshWakeConfig() {
    main.post {
      wakePatterns = buildWakePatterns(loadWakePhrases(Prefs.plain(this)))
    localWakeUnavailable = false; localWakeWarned = false
      if (isSaraActive) resetSessionTimer()
    }
  }

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

  private fun normForCompare(s: String): String =
    s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ").replace(Regex("\\s+"), " ").trim()

  private fun normalizePhrase(s: String): String =
    s.lowercase(Locale.ROOT).replace(Regex("[,!?।]+"), " ").replace(Regex("\\s+"), " ").trim()

  // ---------- Public API used by the app ----------
  fun isMicOn(): Boolean = micEnabled && captureRunning

  fun toggleMic() {
    synchronized(micLock) {
      val now = SystemClock.elapsedRealtime()
      if (now - lastMicToggle < 800L) return
      lastMicToggle = now
      if (callActive) {
        Bus.add("system", "A call is in progress. Microphone input stays off until it ends.", true)
        return
      }
      micEnabled = !micEnabled
      Prefs.plain(this).edit().putBoolean("mic_enabled", micEnabled).apply()
      if (micEnabled) {
        isSaraActive = false
        main.removeCallbacks(sessionTimer)
        Bus.add("system", "Microphone ON (software). Say a wake phrase to start; background speech/noise is ignored until wake.", true)
        ensureCaptureStarted()
        maybeStartIdleWakeEngine()
      } else {
        isSaraActive = false
        main.removeCallbacks(sessionTimer)
        stopIdleWakeEngine()
        stopCapture()
        Bus.add("system", "Microphone muted.", true)
      }
      refreshNotif(); Bus.ping()
    }
  }

  fun startSession() {
    isSaraActive = true
    resetSessionTimer()
    stopIdleWakeEngine()
    Bus.setStatus("Connected · listening")
  }

  private fun resetSessionTimer() {
    main.removeCallbacks(sessionTimer)
    if (!isSaraActive || !running) return
    val ms = sessionTimeoutMs()
    if (ms > 0L) main.postDelayed(sessionTimer, ms)
  }

  private fun resetSessionTimerIfActive() {
    if (isSaraActive) resetSessionTimer()
  }

  fun endSession() {
    if (!isSaraActive) return
    isSaraActive = false
    main.removeCallbacks(sessionTimer)
    Bus.add("system", "Session ended. Say a wake phrase to start again.", true)
    maybeStartIdleWakeEngine()
  }

  private fun onSessionTimeout() {
    if (!running || !isSaraActive) return
    isSaraActive = false
    Bus.add("system", "Session ended. Say a wake phrase to start again.", true)
    maybeStartIdleWakeEngine()
  }

  fun suspendWakeForEnrollment() {
    main.post { enrolling = true; stopIdleWakeEngine(); stopCapture(); if (running) Bus.add("system", "Voice capture paused for enrollment.", true) }
  }

  fun resumeWakeAfterEnrollment() {
    main.post {
      enrolling = false
      if (running && ready && !callActive && micEnabled) { ensureCaptureStarted(); maybeStartIdleWakeEngine() }
    }
  }

  fun refreshCalls() {
    if (!running) return
    if (!Prefs.on(this, "f_calls", true) || !has(Manifest.permission.READ_PHONE_STATE)) { calls?.stop(); calls = null; return }
    if (calls == null) calls = CallWatcher(this, ::onCallStarted, ::onCallAnswered, ::onCallEnded).also { it.start() }
  }

  private fun onCallStarted(caller: String) {
    callActive = true
    stopIdleWakeEngine()
    stopCapture()
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
    main.postDelayed({ if (running && ready && !callActive && micEnabled) { ensureCaptureStarted(); if (!isSaraActive) maybeStartIdleWakeEngine() else Bus.setStatus("Connected · listening") } }, 900L)
    Bus.ping()
  }

  fun refreshBubble() {
    if (Prefs.on(this, "f_bubble", false)) {
      if (bubble == null) bubble = Bubble(this) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      bubble?.show()
    } else bubble?.hide()
    if (!Prefs.on(this, "f_overlay", true)) overlay?.hide()
  }

  fun sendText(t: String) { startSession(); processUtterance(t) }

  fun runScheduled(text: String): Boolean {
    Bus.add("system", "Scheduled task fired at ${SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())}: $text", true)
    return false
  }

  fun onNotification(key: String, pkg: String, title: String, text: String) {
    Bus.add("system", "A notification arrived from $pkg. Its contents were kept on this device.", true)
  }

  // ---------- Wake/idle + continuous conversation (software gating) ----------
  private fun handleHeardText(raw: String) {
    val heard = raw.trim()
    if (heard.isBlank()) return
    val now = System.currentTimeMillis()
    val norm = normForCompare(heard)
    // Echo/self-voice guards. Saara can hear her own TTS via mic; ignore likely echoes of the last reply.
    val speakingNow = now < speakingUntil + ECHO_GUARD_MS
    if (norm.isNotEmpty()) {
      if (norm == lastHeardNorm && (now - lastHeardAt) < 2500L) return
      if (lastReplyNorm.isNotEmpty() && norm == lastReplyNorm && (now - lastReplyAt) < 12000L) return
      if (lastReplyNorm.isNotEmpty() && (now - lastReplyAt) < 8000L && (lastReplyNorm.contains(norm) || norm.contains(lastReplyNorm)) && norm.length > 2) return
      if (speakingNow && isSaraActive) return // prevent Saara hearing her own voice while speaking; resume after playback
      if (speakingNow && !isSaraActive) return
      lastHeardNorm = norm; lastHeardAt = now
    } else if (speakingNow) return

    if (!isSaraActive) {
      val afterWake = extractWakeCommand(heard) ?: return // software: ignore all non-wake/background
      isSaraActive = true
      resetSessionTimer()
      Bus.add("system", "Wake word heard.", true)
      if (afterWake.isNotBlank()) processUtterance(afterWake)
      return
    }

    resetSessionTimer()
    processUtterance(heard)
  }

  private fun processUtterance(text: String) {
    if (text.isBlank()) return
    val cleaned = extractWakeCommand(text)?.takeIf { it.isNotBlank() } ?: text.trim()
    if (cleaned.isBlank()) { resetSessionTimer(); return }
    armScreenAuto(300_000L)
    Bus.add("you", cleaned, true)
    if (TaskControl.isStop(cleaned)) {
      TaskControl.clear(); stopSpeaking()
      Bus.add("system", "Stopped the current task.", true)
      resetSessionTimer(); return
    }
    try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
    if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", cleaned)).toString())
    else Bus.add("saara", "Gemini is not connected yet. Check the API key and connection in Settings.", true)
  }

  private fun extractWakeCommand(raw: String): String? {
    var patterns = wakePatterns
    if (patterns.isEmpty()) {
      patterns = buildWakePatterns(loadWakePhrases(Prefs.plain(this)))
      wakePatterns = patterns
    }
    val text = raw.trim()
    val norm = normalizePhrase(text)
    for (p in patterns) {
      val m = p.find(text) ?: p.find(norm) ?: continue
      return m.groupValues.getOrNull(1)?.trim().orEmpty()
    }
    // Fallback tolerant matching for transcription variants (sara/saara/sarah, hey/hi/ok/okay/oye)
    if (Regex("\\b(hey|hi|hello|ok|okay|oye|are|arre|o)\\s+sa+ra+h?\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
      return Regex("(?i)\\b(?:hey|hi|hello|ok|okay|oye|are|arre|o)\\s+sa+ra+h?\\b[,!?. ]*(.*)$").find(norm)?.groupValues?.getOrNull(1)?.trim().orEmpty() ?: ""
    }
    if (Regex("\\bsa+ra+h?\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm) && norm.split(" ").size <= 4) {
      return Regex("(?i)\\bsa+ra+h?\\b[,!?. ]*(.*)$").find(norm)?.groupValues?.getOrNull(1)?.trim().orEmpty() ?: ""
    }
    return null
  }

  // ---------- On-device OpenWakeWord/TFLite (idle: local wake only, no Gemini stream) ----------
  private fun maybeStartIdleWakeEngine() {
    if (localWakeUnavailable) return
    if (!running || !ready || !micEnabled || callActive || enrolling || isSaraActive || !captureRunning) return
    synchronized(wakeLock) {
      val cur = wakeEngine
      if (cur != null && cur.running) return
      cur?.stop()
      val eng = WakeEngine(applicationContext,
        onWake = { main.post { onLocalWakeDetected() } },
        onUnavailable = { msg -> main.post {
          localWakeUnavailable = true
          stopIdleWakeEngine()
          if (!localWakeWarned) {
            localWakeWarned = true
            if (running) Bus.add("system", "Wake engine failed: $msg Using cloud wake fallback in idle (wake-phrase gated; Gemini responses suppressed in idle).", true)
          }
          if (running && !isSaraActive && captureRunning) Bus.setStatus("Connected · listening for wake phrase")
        } },
        onNotice = { msg -> main.post { if (running) Bus.add("system", msg, true) } }
      )
      if (eng.start()) {
        localWakeUnavailable = false
        localWakeWarned = false
        wakeEngine = eng
        Bus.setStatus("Connected · say a wake phrase (on-device)")
      } else {
        localWakeUnavailable = true
        wakeEngine = null
        if (!localWakeWarned) { localWakeWarned = true; Bus.add("system", "On-device wake unavailable on this device. Using cloud wake fallback in idle (wake-phrase gated; Gemini responses suppressed in idle to reduce noise actions).", true) }
        Bus.setStatus("Connected · listening for wake phrase")
      }
    }
  }

  private fun stopIdleWakeEngine() {
    synchronized(wakeLock) {
      try { wakeEngine?.stop() } catch (_: Exception) {}
      wakeEngine = null
    }
  }

  private fun onLocalWakeDetected() {
    if (!running || !micEnabled || callActive || enrolling) return
    if (!isSaraActive) {
      isSaraActive = true
      resetSessionTimer()
      stopIdleWakeEngine()
      Bus.add("system", "Wake word heard.", true)
      Bus.setStatus("Connected · listening")
    }
  }

  // ---------- Continuous AudioRecord capture (hardware on, software gated) ----------
  private fun ensureCaptureStarted() {
    if (!running || !ready || !micEnabled || callActive || enrolling || captureRunning) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Bus.add("system", "Microphone permission is missing. Allow Microphone in Android app permissions.", true)
      return
    }
    if (!captureStarting.compareAndSet(false, true)) return
    try {
      val rate = 16000
      val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val rec = try {
        AudioRecord.Builder()
          .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
          .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
          .setBufferSizeInBytes(maxOf(min, 6400)).build()
      } catch (_: Exception) {
        AudioRecord.Builder()
          .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
          .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
          .setBufferSizeInBytes(maxOf(min, 6400)).build()
      }
      if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        Bus.add("system", "Microphone could not start.", true)
        return
      }
      audioRecord = rec
      rec.startRecording()
      captureRunning = true
      refreshNotif()
      Bus.setStatus(if (isSaraActive) "Connected · listening" else "Connected · say a wake phrase")
      if (!isSaraActive) main.post { maybeStartIdleWakeEngine() }
      captureThread = Thread {
        val buf = ByteArray(3200)
        val shorts = ShortArray(buf.size / 2)
        while (running && captureRunning) {
          val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
          if (n > 0) {
            if (!micEnabled || callActive || enrolling || !ready) continue
            val speaking = System.currentTimeMillis() < speakingUntil + 350L
            if (!isSaraActive) {
              val eng = wakeEngine
              if (eng != null && eng.running && !localWakeUnavailable) {
                // IDLE: on-device wake only. No Gemini stream.
                if (!speaking) {
                  var si = 0; var bi = 0
                  val scount = n / 2
                  while (bi + 1 < n && si < scount) {
                    val lo = buf[bi].toInt() and 0xff
                    val hi = buf[bi + 1].toInt()
                    shorts[si++] = ((hi shl 8) or lo).toShort()
                    bi += 2
                  }
                  try { eng.feed(shorts, si) } catch (_: Exception) {}
                }
                continue
              }
              // Fallback: stream for inputTranscription wake detection only; handle() suppresses Gemini responses/actions in idle.
              if (speaking) continue
              val socket = ws ?: continue
              val chunk = ByteArray(n)
              System.arraycopy(buf, 0, chunk, 0, n)
              try {
                val data = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val payload = JSONObject().put("realtimeInput", JSONObject().put("audio",
                  JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000")))
                socket.send(payload.toString())
              } catch (_: Exception) {}
              continue
            }
            // ACTIVE: do NOT stream mic to Gemini while Saara is speaking — prevents self-voice feedback loop (mic hears TTS).
            // Barge-in via voice is disabled during playback; listening resumes after speaking guard.
            if (speaking) continue
            val socket = ws ?: continue
            val chunk = ByteArray(n)
            System.arraycopy(buf, 0, chunk, 0, n)
            try {
              val data = Base64.encodeToString(chunk, Base64.NO_WRAP)
              val payload = JSONObject().put("realtimeInput", JSONObject().put("audio",
                JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=16000")))
              socket.send(payload.toString())
            } catch (_: Exception) {}
          } else if (n < 0) {
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
          }
        }
      }.also { it.name = "saara-capture"; it.isDaemon = true; it.start() }
    } catch (e: Exception) {
      Log.w(TAG, "capture start failed", e)
      captureRunning = false
      Bus.add("system", "Microphone error: ${e.message}", true)
    } finally {
      captureStarting.set(false)
    }
  }

  private fun stopCapture() {
    captureRunning = false
    stopIdleWakeEngine()
    try { audioRecord?.stop() } catch (_: Exception) {}
    try { audioRecord?.release() } catch (_: Exception) {}
    audioRecord = null
    captureThread = null
    refreshNotif()
  }

  // ---------- Foreground service / UI ----------
  override fun onBind(i: Intent?): IBinder? = null

  private fun notif(): Notification {
    val stop = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val sharing = listOfNotNull(if (Bus.camOn) "camera" else null, if (Bus.screenOn) "screen" else null)
    val state = when {
      !micEnabled -> "Saara · microphone muted"
      captureRunning && isSaraActive -> "Saara · listening (active)"
      captureRunning -> "Saara · say a wake phrase"
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
        if (t == 0) { Bus.add("system", "Microphone permission is missing, so Saara cannot listen in the background.", true); stopSelf(); return }
        startForeground(1, notif(), t)
      } else startForeground(1, notif())
    } catch (e: Exception) { Bus.add("system", "Foreground service: ${e.message}", true) }
  }

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    Bus.init(this)
    when (i?.action) {
      "STOP" -> { stopSelf(); return START_NOT_STICKY }
      "WAKE" -> { if (running && ready && micEnabled && !callActive) ensureCaptureStarted(); return START_STICKY }
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
    localWakeUnavailable = false; localWakeWarned = false
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
        val b: ByteArray = (try { audioQ.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }) ?: continue
        try { track?.write(b, 0, b.size) } catch (_: Exception) {}
      }
    }.apply { name = "saara-playback"; isDaemon = true; start() }
    refreshBubble(); Bus.listen(busListener); Bus.add("system", "Connecting…", true); refreshCalls(); connect()
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
    val was = Bus.camOn; Bus.camOn = true; fg()
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
    main.removeCallbacks(screenAutoStop); screenAuto = auto; Bus.screenAuto = auto; Bus.screenOn = true; fg()
    val s = screen ?: ScreenShare(this, { sendFrame(it) }, { main.post { stopScreen(true) } }).also { screen = it }
    val err = s.start(code, data)
    if (err != null) { Bus.add("system", err, true); stopScreen(false); return }
    Bus.add("system", if (auto) "Screen sharing is ON for this task. It will stop automatically when the task ends." else "Screen sharing is ON. Tap the Screen chip to stop it.", true)
    Bus.add("system", "Live screen frames are being sent to Gemini.", true)
    if (auto) armScreenAuto(300_000L)
    tellModel("[System] Screen sharing is now ON. Live screen frames are being sent to you. Use them to complete the user's mobile task." + if (auto) " This sharing was requested for the current task; it will be turned off automatically when the task is done." else "")
  }
  private fun stopScreen(say: Boolean) {
    main.removeCallbacks(screenAutoStop); screenAuto = false; Bus.screenAuto = false; screen?.stop(); screen = null
    if (Bus.screenOn) { Bus.screenOn = false; fg(); if (say) { Bus.add("system", "Screen sharing stopped.", true); tellModel("[System] Screen sharing stopped. Do not reply.") } }
    Bus.ping()
  }
  private fun armScreenAuto(delayMs: Long) {
    if (!screenAuto || !Bus.screenOn) return
    main.removeCallbacks(screenAutoStop); main.postDelayed(screenAutoStop, delayMs)
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

  private fun connect() {
    if (!running) return
    ready = false; Bus.setStatus("Connecting…")
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
    Bus.setStatus("Reconnecting…"); main.postDelayed({ connect() }, 3000)
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
    val rules = try { Db.get(this).rulesPrompt() } catch (_: Exception) { "" }
    val s = JSONObject()
      .put("model", "models/$model")
      .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ins + rules))))
      .put("tools", Tools.declarations())
      .put("inputAudioTranscription", JSONObject())
      .put("outputAudioTranscription", JSONObject())
    return JSONObject().put("setup", s).toString()
  }

  private fun handle(s: String) {
    val m = try { JSONObject(s) } catch (_: Exception) { return }
    if (m.has("setupComplete")) {
      ready = true; retries = 0; Bus.setStatus("Connected · say a wake phrase")
      val cmd = pendingCommand; pendingCommand = null
      if (cmd != null) processUtterance(cmd)
      if (micEnabled) { ensureCaptureStarted(); if (!isSaraActive) maybeStartIdleWakeEngine() } else Bus.add("system", "Gemini connected. Microphone is muted.", true)
    }
    if (m.has("error")) Bus.add("system", "Gemini error: " + m.optString("error"), true)
    val sc = m.optJSONObject("serverContent")
    if (sc != null) {
      // Always accept input transcriptions (gated in handleHeardText) so idle cloud-wake fallback can detect wake phrases.
      sc.optJSONObject("inputTranscription")?.let {
        val heard = it.optString("text")
        if (heard.isNotBlank()) {
          armScreenAuto(300_000L)
          handleHeardText(heard)
        }
      }
      // In IDLE, suppress Gemini audio/output/tools to prevent background noise from causing speech/actions.
      if (isSaraActive) {
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
          val inl = parts.getJSONObject(i).optJSONObject("inlineData")
          if (inl != null) { speakingUntil = maxOf(speakingUntil, System.currentTimeMillis() + 400L); play(Base64.decode(inl.getString("data"), Base64.DEFAULT)) }
        }
        sc.optJSONObject("outputTranscription")?.let {
          val txt = it.optString("text")
          Bus.add("saara", txt)
          val rn = normForCompare(txt)
          if (rn.isNotEmpty()) { lastReplyNorm = rn; lastReplyAt = System.currentTimeMillis() }
        }
        if (sc.optBoolean("interrupted")) { stopSpeaking() }
        if (sc.optBoolean("turnComplete")) {
          Bus.endTurn(); armScreenAuto(6000L)
          try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
        }
      } else {
        // If model started responding in idle fallback, ignore/interrupt.
        if (sc.optBoolean("interrupted") || sc.has("modelTurn") || sc.optBoolean("turnComplete")) stopSpeaking()
      }
    }
    val calls = m.optJSONObject("toolCall")?.optJSONArray("functionCalls")
    if (calls != null) {
      if (isSaraActive) {
        Thread {
          armScreenAuto(300_000L); resetSessionTimerIfActive()
          val out = JSONArray()
          for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val name = c.getString("name"); val args = c.optJSONObject("args") ?: JSONObject()
            Bus.add("tool", "$name $args", true)
            val r = Tools.run(this, name, args)
            Bus.add("tool", "→ $r", true)
            out.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", JSONObject().put("result", Tools.modelResult(name, r))))
          }
          ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
        }.start()
      }
      // idle: do not execute tools
    }
  }

  private fun play(pcm: ByteArray) {
    if (track == null) return
    // pcm 16-bit mono @ 24kHz => 2 bytes/sample, 48 bytes/ms
    val dur = pcm.size / 48
    speakingUntil = maxOf(speakingUntil, System.currentTimeMillis()) + dur + 250L
    audioQ.offer(pcm)
  }

  private fun stopSpeaking() {
    audioQ.clear()
    try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    speakingUntil = System.currentTimeMillis() + 500L
  }

  override fun onDestroy() {
    running = false; ready = false; isSaraActive = false
    main.removeCallbacks(sessionTimer); main.removeCallbacks(screenAutoStop)
    screenAuto = false; Bus.screenAuto = false
    stopIdleWakeEngine()
    stopCapture()
    Bus.unlisten(busListener)
    calls?.stop(); calls = null; callActive = false
    TaskControl.clear(); audioQ.clear()
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
