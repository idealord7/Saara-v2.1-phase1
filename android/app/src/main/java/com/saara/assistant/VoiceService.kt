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
    const val ASSISTANT_WAKE = "assistant_wake"
    const val ACTION_WAKE = "WAKE"
    const val PREF_WAKE_PHRASES = "wake_phrases"
    const val PREF_SESSION_TIMEOUT_SEC = "session_timeout_sec"
    const val PREF_SESSION_TIMEOUT_ENABLED = "session_timeout_enabled"
    const val DEFAULT_SESSION_TIMEOUT_SEC = 9
    private const val ECHO_GUARD_MS = 350L
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
  @Volatile private var liveModelIndex = 0
  private var liveCandidates: List<String> = emptyList()
  private var liveModel: String = ""
  private var pendingCommand: String? = null
  private var track: AudioTrack? = null
  private val audioQ = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var chatHead: ChatHeadOverlay? = null
  private var overlay: Overlay? = null
  private var cam: CamShare? = null
  private var screen: ScreenShare? = null
  @Volatile private var screenAuto = false
  private val screenAutoStop = Runnable { if (Bus.screenOn && screenAuto) stopScreen(true) }
  private var automationReceiver: android.content.BroadcastReceiver? = null
  private var calls: CallWatcher? = null
  @Volatile private var callActive = false
  @Volatile private var enrolling = false
  @Volatile private var pendingWakeNoPhrase = false
  @Volatile private var bubbleDismissedByUser = false
  private val micLock = Any()
  private var lastMicToggle = 0L
  private val main = Handler(Looper.getMainLooper())
  private val busListener: () -> Unit = { updateOverlay(); chatHead?.onBusUpdate() }

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
  @Volatile private var sessionId: String = ""
  @Volatile private var lastTaskNote: String = ""

  // --- User-only voice / speaker focus ---
  private val speakerGuard = SpeakerGuard(this)
  @Volatile private var manualVoiceBypassUntil = 0L
  @Volatile private var lastVoiceRejectLogAt = 0L

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
      refreshSpeakerGuardConfig()
      if (isSaraActive) resetSessionTimer()
    }
  }

  fun refreshSpeakerGuardConfig() {
    val enabled = UserVoiceProfile.onlyUserVoiceEnabled(this)
    speakerGuard.enabled = enabled
    if (!enabled) {
      speakerGuard.reset()
      speakerGuard.lastDecision = "disabled"
    } else {
      speakerGuard.ensureStarted()
      speakerGuard.markStarted()
      if (!UserVoiceProfile.exists(this)) {
        Bus.add("system", "Only My Voice is ON, but no voice sample is enrolled. Open Settings > User-Only Voice and enroll your voice.", true)
      }
    }
  }

  fun isManualVoiceBypassActive(): Boolean =
    UserVoiceProfile.bypassOnManual(this) && System.currentTimeMillis() < manualVoiceBypassUntil

  fun markManualVoiceBypass(reason: String = "manual") {
    if (!UserVoiceProfile.bypassOnManual(this)) return
    manualVoiceBypassUntil = System.currentTimeMillis() + 12_000L
    speakerGuard.lastDecision = "bypass_$reason"
  }

  fun speakerStatus(): String {
    val enabled = UserVoiceProfile.onlyUserVoiceEnabled(this)
    if (!enabled) return "Only My Voice: OFF"
    val th = UserVoiceProfile.threshold(this)
    val pct = speakerGuard.lastMatchPercent
    val pctStr = if (pct >= 0f) "%.0f%%".format(pct) else "n/a"
    val enrolled = UserVoiceProfile.exists(this)
    val scope = if (UserVoiceProfile.verifyAllInputsEnabled(this)) "all-inputs" else "wake-only"
    return "Only My Voice: ON · $scope · enrolled=${if (enrolled) "yes" else "no"} · last=$pctStr · threshold=${th}% · ${speakerGuard.lastDecision}"
  }

  fun enrollUserVoice(samples: Int = 6, onProgress: (VoiceEnrollment.Progress) -> Unit = {}, onDone: (VoiceEnrollment.Result) -> Unit = {}) {
    Thread {
      val wasActive = isSaraActive
      try {
        val perm = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO)
        if (perm == PackageManager.PERMISSION_DENIED) {
          val res = VoiceEnrollment.Result(false, "Microphone permission denied. Allow Microphone permission and try again.")
          Bus.add("system", "Voice enrollment failed: ${res.message}", true)
          main.post { onDone(res) }
          return@Thread
        }

        val handoff = java.util.concurrent.CountDownLatch(1)
        main.post {
          try {
            suspendWakeForEnrollment()
            Bus.add("system", "Preparing microphone for voice enrollment…", false)
          } finally {
            handoff.countDown()
          }
        }
        try { handoff.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}

        // Wait for active capture/wake engine to fully release mic hardware.
        var waited = 0
        while (waited < 3500 && (captureRunning || audioRecord != null || captureStarting.get())) {
          try { Thread.sleep(80) } catch (_: Exception) {}
          waited += 80
        }
        try { Thread.sleep(350) } catch (_: Exception) {}

        if (captureRunning || audioRecord != null) {
          val res = VoiceEnrollment.Result(false, "Microphone is still in use by Saara capture. Stop Saara and try again.")
          Bus.add("system", "Voice enrollment failed: ${res.message}", true)
          main.post { onDone(res) }
          return@Thread
        }

        val res = VoiceEnrollment.enroll(applicationContext, samples, onProgress)
        if (res.ok) {
          val th = UserVoiceProfile.threshold(applicationContext)
          Bus.add("system", "Voice enrolled. Only My Voice matching active at ${th}% threshold.", true)
        } else {
          Bus.add("system", "Voice enrollment failed: ${res.message}", true)
        }
        main.post { onDone(res) }
      } catch (t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        Log.e("VoiceService", "Voice enrollment error", t)
        Bus.add("system", "Voice enrollment error: $msg", true)
        main.post { onDone(VoiceEnrollment.Result(false, msg)) }
      } finally {
        main.post {
          resumeWakeAfterEnrollment()
          if (wasActive && micEnabled && running) startSession()
        }
      }
    }.apply { name = "saara-voice-enroll"; isDaemon = true; start() }
  }

  fun clearUserVoiceEnrollment() {
    UserVoiceProfile.clear(this)
    speakerGuard.reset()
    speakerGuard.lastDecision = "cleared"
    Bus.add("system", "Enrolled voice sample cleared.", true)
  }

  private fun shouldRejectNonUserVoice(source: String, manualBypass: Boolean = false): Boolean {
    if (!UserVoiceProfile.onlyUserVoiceEnabled(this)) return false
    if (!UserVoiceProfile.exists(this)) return false // graceful fallback; warned in config
    if (manualBypass && UserVoiceProfile.bypassOnManual(this)) {
      speakerGuard.lastDecision = "bypass_manual"
      return false
    }
    if (UserVoiceProfile.rejectMediaNoise(this) && SpeakerGuard.isMediaPlaying(this)) {
      val now = System.currentTimeMillis()
      if (now - lastVoiceRejectLogAt > 3000L) {
        lastVoiceRejectLogAt = now
        val pct = speakerGuard.lastMatchPercent
        val th = UserVoiceProfile.threshold(this)
        val pctStr = if (pct >= 0f) "%.0f%%".format(pct) else "n/a"
        Bus.add("system", "Voice rejected: $pctStr < ${th}% threshold ($source, media_playback).", true)
      }
      speakerGuard.lastDecision = "media_playing"
      return true
    }
    val ok = speakerGuard.shouldAccept(manualBypass = false)
    if (ok) return false
    val now = System.currentTimeMillis()
    if (now - lastVoiceRejectLogAt > 2500L) {
      lastVoiceRejectLogAt = now
      val pct = speakerGuard.lastMatchPercent
      val th = UserVoiceProfile.threshold(this)
      val pctStr = if (pct >= 0f) "%.0f%%".format(pct) else "n/a"
      val reason = when (speakerGuard.lastDecision) {
        "no_recent_match" -> "no_voice_match"
        "media_playing" -> "media_playback"
        else -> "below_threshold"
      }
      Bus.add("system", "Voice rejected: $pctStr < ${th}% threshold ($source, $reason).", true)
    }
    return true
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
          out.add(Regex("""(?i)(?:^|\b)""" + body + """\b[,!?. ]*(.*)$"""))
        }
      } else {
        out.add(Regex(Regex.escape(t) + """[,!?.\s]*(.*)$"""))
      }
    }
    out.sortByDescending { it.pattern.length }
    return out
  }

  private fun normForCompare(s: String): String =
    s.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}\s]+"""), " ").replace(Regex("""\s+"""), " ").trim()

  private fun normalizePhrase(s: String): String =
    s.lowercase(Locale.ROOT).replace(Regex("[,!?।]+"), " ").replace(Regex("""\s+"""), " ").trim()

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
        bubbleDismissedByUser = false
        markManualVoiceBypass("mic_toggle")
        Bus.add("system", "Microphone ON. Listening now.", true)
        ensureCaptureStarted()
        if (ready && running && !callActive) {
          startSession()
          Bus.setStatus("Listening")
        } else {
          pendingWakeNoPhrase = true
        }
        refreshBubble()
      } else {
        // Mute/unmute must not close session; keep session context continuous.
        pauseSession(announce = false)
        stopCapture()
        Bus.add("system", "Microphone muted. Session context preserved.", true)
      }
      refreshNotif(); Bus.ping()
    }
  }

  fun startSession() {
    isSaraActive = true
    resetSessionTimer()
    stopIdleWakeEngine()
    Bus.setStatus("Listening")
    noteSession(status = "active")
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

  /** Deactivates active listening but keeps session context/memory continuous. */
  fun pauseSession(announce: Boolean = false) {
    if (!isSaraActive) return
    isSaraActive = false
    main.removeCallbacks(sessionTimer)
    noteSession(status = "paused", pending = lastTaskNote.ifBlank { "Resume previous conversation when active again." })
    if (announce) Bus.add("system", "Session paused. Say a wake phrase to continue.", true)
    maybeStartIdleWakeEngine()
  }

  /** Explicit session close/reset. Only call for explicit UI stop/off or explicit voice close command. */
  fun closeSession(reason: String = "explicit", announce: Boolean = true) {
    isSaraActive = false
    pendingWakeNoPhrase = false
    pendingCommand = null
    lastTaskNote = ""
    main.removeCallbacks(sessionTimer)
    stopSpeaking()
    try { Db.get(this).sessionStateClear(keepSessionId = true) } catch (_: Exception) {}
    noteSession(status = "closed", activeGoal = "", lastStep = "session closed ($reason)", pending = "", lastError = "")
    if (announce) Bus.add("system", "Session closed.", true)
    if (running && !callActive && micEnabled) maybeStartIdleWakeEngine()
  }

  private fun isExplicitCloseCommand(text: String): Boolean {
    val n = normForCompare(text)
    if (n.isBlank()) return false
    val patterns = listOf(
      Regex("""\b(close|end|stop|finish|terminate)\s+(this\s+)?(current\s+)?session\b"""),
      Regex("""\b(session)\s+(close|closed|end|ended|band|khatam)\b"""),
      Regex("""\b(সেশন)\s*(ক্লোজ|বন্ধ|শেষ)\s*(হও|করো|কর|করুন)?\b"""),
      Regex("""\b(सेशन|सत्र)\s*(क्लोज|बंद|समाप्त|खत्म)\s*(हो|करो|कर|कीजिए)?\b""")
    )
    return patterns.any { it.containsMatchIn(n) || it.containsMatchIn(text) }
  }

  fun endSession() { pauseSession(announce = false) }

  private fun onSessionTimeout() {
    if (!running || !isSaraActive) return
    // Do not close/wipe session on timeout; keep context continuous and only pause listening.
    isSaraActive = false
    main.removeCallbacks(sessionTimer)
    noteSession(status = "timeout_paused", pending = lastTaskNote.ifBlank { "Resume previous conversation on next wake." })
    Bus.add("system", "Listening paused after timeout. Say a wake phrase to continue the same session.", true)
    maybeStartIdleWakeEngine()
  }

  fun suspendWakeForEnrollment() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      enrolling = true
      stopIdleWakeEngine()
      stopCapture()
      if (running) Bus.add("system", "Voice capture paused for enrollment.", true)
    } else {
      main.post { suspendWakeForEnrollment() }
    }
  }

  fun resumeWakeAfterEnrollment() {
    val r = Runnable {
      enrolling = false
      if (running && ready && !callActive && micEnabled) {
        ensureCaptureStarted()
        if (!isSaraActive) maybeStartIdleWakeEngine() else Bus.setStatus("Listening")
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r)
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
    main.postDelayed({ if (running && ready && !callActive && micEnabled) { ensureCaptureStarted(); if (!isSaraActive) maybeStartIdleWakeEngine() else Bus.setStatus("Listening") } }, 900L)
    Bus.ping()
  }

  fun onExplicitTrigger() {
    bubbleDismissedByUser = false
    markManualVoiceBypass("bubble_tap")
    refreshBubble()
  }

  fun refreshBubble() {
    val want = Prefs.on(this, "f_bubble", false)
    if (want && !bubbleDismissedByUser && Settings.canDrawOverlays(this)) {
      if (chatHead == null) chatHead = ChatHeadOverlay(this, object : ChatHeadOverlay.Callbacks {
        override fun onOpenApp() { openMainApp() }
        override fun onSendText(text: String) { sendText(text) }
        override fun onMicToggle() { toggleMic() }
        override fun onScreenToggle() { toggleScreenShareFromOverlay() }
        override fun onCloseRequested() { hideChatHeadByUser() }
        override fun isMicOn(): Boolean = this@VoiceService.isMicOn()
        override fun isScreenOn(): Boolean = Bus.screenOn
        override fun isActive(): Boolean = isSaraActive
        override fun isSpeaking(): Boolean = System.currentTimeMillis() < speakingUntil
        override fun statusText(): String = Bus.status
        override fun recentMessages(limit: Int): List<Msg> = synchronized(Bus) { Bus.msgs.takeLast(limit).map { Msg(it.id, it.role, it.text, it.ts) } }
        override fun onAnchorChanged() { updateOverlay() }
      })
      chatHead?.show()
    } else {
      chatHead?.hide(); chatHead = null
    }
    updateOverlay()
  }

  fun overlayHide() { overlay?.hide() }

  private fun openMainApp() {
    try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) } catch (_: Exception) {}
  }

  private fun hideChatHeadByUser() {
    // User dismissed bubble via X target: hide runtime bubble only; keep f_bubble setting for next start/trigger.
    bubbleDismissedByUser = true
    chatHead?.hide(); chatHead = null
    Bus.add("system", "Floating bubble hidden for this session. It will return on next start if enabled in Settings.", true)
    updateOverlay()
    Bus.ping()
  }

  private fun sessionContinuityEnabled() = Prefs.on(this, "f_session_continuity", true)
  private fun ensureSessionId(): String {
    if (sessionId.isBlank()) sessionId = Prefs.plain(this).getString("session_id", "").orEmpty()
    if (sessionId.isBlank()) {
      sessionId = "sess_" + java.util.UUID.randomUUID().toString().take(12)
      Prefs.plain(this).edit().putString("session_id", sessionId).apply()
    }
    return sessionId
  }
  private fun noteSession(status: String? = null, activeGoal: String? = null, lastStep: String? = null, pending: String? = null, lastError: String? = null) {
    if (!sessionContinuityEnabled()) return
    try { Db.get(this).sessionStateSave(sessionId = ensureSessionId(), status = status, activeGoal = activeGoal, lastStep = lastStep, pending = pending, lastError = lastError) } catch (_: Exception) {}
  }
  private fun maybeResumeContext(reason: String) {
    if (!sessionContinuityEnabled()) return
    val st = try { Db.get(this).sessionState() } catch (_: Exception) { return }
    val recent = st.updatedTs > 0 && System.currentTimeMillis() - st.updatedTs < 6L * 60 * 60 * 1000
    if (!recent) return
    val goal = st.activeGoal.ifBlank { st.pending }
    if (goal.isBlank() && st.lastError.isBlank()) return
    val msg = "Resuming after $reason. Goal: ${goal.take(120).ifBlank { "(recent chat)" }}. Last: ${st.lastStep.take(100).ifBlank { "n/a" }}. Pending: ${st.pending.take(100).ifBlank { "continue naturally" }}." + if (st.lastError.isNotBlank()) " Last issue: ${st.lastError.take(100)}." else ""
    Bus.add("system", msg, true)
    if (ready) tellModel("[System continuity] $msg Continue the same task/topic seamlessly without asking the user to repeat unless critical info is missing.")
  }

  private fun toggleScreenShareFromOverlay() {
    if (!Prefs.on(this, "f_share", true)) { Bus.add("system", "Camera & screen sharing is switched off in Settings > App features.", true); return }
    if (!running) { Bus.add("system", "Start Saara first, then share screen.", true); return }
    if (Bus.screenOn) { stopScreen(true); return }
    try {
      startActivity(Intent(this, ScreenAskActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra("auto", false))
    } catch (_: Exception) { Bus.add("system", "Could not open screen sharing prompt.", true) }
  }

  fun sendText(t: String) { startSession(); processUtterance(t) }

  fun runScheduled(text: String): Boolean {
    Bus.add("system", "Scheduled task fired at ${SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date())}: $text", true)
    return false
  }

  fun onNotification(key: String, pkg: String, title: String, text: String) {
    val app = when (pkg) {
      "com.whatsapp" -> "WhatsApp"
      "com.whatsapp.w4b" -> "WhatsApp Business"
      "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
      "com.facebook.orca" -> "Messenger"
      "com.instagram.android" -> "Instagram"
      else -> pkg
    }
    val msg = "[Notification] app=$app sender=${title.ifBlank { "unknown" }} key=$key text=${text.take(800)}"
    Bus.add("system", msg, true)
    // If Saara is running live, let her handle/reply instantly (screen off supported by foreground service).
    if (running && ready) {
      startSession()
      tellModel("Incoming notification for you to handle if appropriate. $msg\nIf the user wants auto-replies or this needs a response, use reply_notification with key='$key' and sender='${title.replace("'","")}'. Keep reply natural and follow rules.")
    }
  }

  // ---------- Wake/idle + continuous conversation (software gating) ----------
  private fun handleHeardText(raw: String) {
    val heard = raw.trim()
    if (heard.isBlank()) return
    val now = System.currentTimeMillis()
    val norm = normForCompare(heard)
    // Echo/self-voice guards and barge-in.
    val speakingNow = now < speakingUntil
    if (norm.isNotEmpty()) {
      if (norm == lastHeardNorm && (now - lastHeardAt) < 2000L) return
      if (speakingNow) {
        val likelyEcho = (lastReplyNorm.isNotEmpty() && (norm == lastReplyNorm || ((now - lastReplyAt) < 7000L && (lastReplyNorm.contains(norm) || norm.contains(lastReplyNorm)) && norm.length > 2)))
        if (likelyEcho) return
        if (Prefs.on(this, "f_barge_in", true)) {
          // Barge-in: user intentionally speaking over Saara.
          stopSpeaking()
          Bus.add("system", "Barge-in detected; listening to new command.", false)
        } else {
          return
        }
      } else {
        if (lastReplyNorm.isNotEmpty() && norm == lastReplyNorm && (now - lastReplyAt) < 10000L) return
        if (lastReplyNorm.isNotEmpty() && (now - lastReplyAt) < 7000L && (lastReplyNorm.contains(norm) || norm.contains(lastReplyNorm)) && norm.length > 2) return
      }
      lastHeardNorm = norm; lastHeardAt = now
    } else if (speakingNow) return

    if (!isSaraActive) {
      val afterWake = extractWakeCommand(heard) ?: return // software: ignore all non-wake/background
      if (shouldRejectNonUserVoice("cloud-wake", manualBypass = isManualVoiceBypassActive())) return
      isSaraActive = true
      resetSessionTimer()
      val pct = speakerGuard.lastMatchPercent
      val th = UserVoiceProfile.threshold(this)
      if (UserVoiceProfile.onlyUserVoiceEnabled(this) && pct >= 0f) {
        Bus.add("system", "Wake word heard (voice match ${"%.0f".format(pct)}% >= ${th}%).", true)
      } else {
        Bus.add("system", "Wake word heard.", true)
      }
      if (afterWake.isNotBlank()) processUtterance(afterWake)
      return
    }

    if (UserVoiceProfile.verifyAllInputsEnabled(this) &&
        shouldRejectNonUserVoice("command", manualBypass = isManualVoiceBypassActive())) return
    resetSessionTimer()
    processUtterance(heard)
  }

  private fun processUtterance(text: String) {
    if (text.isBlank()) return
    val cleaned = extractWakeCommand(text)?.takeIf { it.isNotBlank() } ?: text.trim()
    if (cleaned.isBlank()) { resetSessionTimer(); return }
    armScreenAuto(300_000L)
    if (isExplicitCloseCommand(cleaned)) {
      Bus.add("you", cleaned, true)
      TaskControl.clear(); stopSpeaking()
      closeSession(reason = "voice_command", announce = true)
      return
    }
    Bus.add("you", cleaned, true)
    if (TaskControl.isStop(cleaned)) {
      TaskControl.clear(); stopSpeaking()
      noteSession(status = "stopped_task", lastStep = "user requested stop", pending = lastTaskNote.ifBlank { "" }, lastError = "")
      Bus.add("system", "Stopped the current task. Session is still active.", true)
      resetSessionTimer(); return
    }
    lastTaskNote = cleaned.take(220)
    noteSession(status = "active_goal", activeGoal = cleaned.take(300), lastStep = "received user request", pending = "understand and execute request", lastError = "")
    try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
    if (ready) ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", cleaned)).toString())
    else {
      noteSession(status = "disconnected", lastError = "Gemini not connected", pending = cleaned.take(220))
      Bus.add("saara", "Gemini is not connected yet. Check the API key and connection in Settings.", true)
    }
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
    if (Regex("""\b(hey|hi|hello|ok|okay|oye|are|arre|o)\s+sa+ra+h?\b""", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
      return Regex("""(?i)\b(?:hey|hi|hello|ok|okay|oye|are|arre|o)\s+sa+ra+h?\b[,!?. ]*(.*)$""").find(norm)?.groupValues?.getOrNull(1)?.trim().orEmpty() ?: ""
    }
    if (Regex("""\bsa+ra+h?\b""", RegexOption.IGNORE_CASE).containsMatchIn(norm) && norm.split(" ").size <= 4) {
      return Regex("""(?i)\bsa+ra+h?\b[,!?. ]*(.*)$""").find(norm)?.groupValues?.getOrNull(1)?.trim().orEmpty() ?: ""
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
        Bus.setStatus("Listening for wake phrase")
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
    if (shouldRejectNonUserVoice("wake-word", manualBypass = isManualVoiceBypassActive())) return
    if (!isSaraActive) {
      isSaraActive = true
      resetSessionTimer()
      stopIdleWakeEngine()
      val pct = speakerGuard.lastMatchPercent
      val th = UserVoiceProfile.threshold(this)
      if (UserVoiceProfile.onlyUserVoiceEnabled(this) && pct >= 0f) {
        Bus.add("system", "Wake word heard (voice match ${"%.0f".format(pct)}% >= ${th}%).", true)
      } else {
        Bus.add("system", "Wake word heard.", true)
      }
      Bus.setStatus("Listening")
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
      refreshSpeakerGuardConfig()
      if (UserVoiceProfile.onlyUserVoiceEnabled(this)) { speakerGuard.ensureStarted(); speakerGuard.markStarted(); speakerGuard.reset() }
      Bus.setStatus(if (isSaraActive) "Connected · listening" else "Connected · say a wake phrase")
      if (!isSaraActive) main.post { maybeStartIdleWakeEngine() }
      captureThread = Thread {
        val buf = ByteArray(3200)
        val shorts = ShortArray(buf.size / 2)
        while (running && captureRunning) {
          val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
          if (n > 0) {
            if (!micEnabled || callActive || enrolling || !ready) continue
            val speaking = System.currentTimeMillis() < speakingUntil + 120L
            var si = 0; var bi = 0
            val scount = n / 2
            while (bi + 1 < n && si < scount) {
              val lo = buf[bi].toInt() and 0xff
              val hi = buf[bi + 1].toInt()
              shorts[si++] = ((hi shl 8) or lo).toShort()
              bi += 2
            }
            val onlyUser = UserVoiceProfile.onlyUserVoiceEnabled(this@VoiceService)
            if (onlyUser && !speaking && si > 0) {
              try { speakerGuard.feed(shorts, si) } catch (_: Exception) {}
            }
            if (!isSaraActive) {
              val eng = wakeEngine
              if (eng != null && eng.running && !localWakeUnavailable) {
                // IDLE: on-device wake only. No Gemini stream.
                if (!speaking) {
                  try { eng.feed(shorts, si) } catch (_: Exception) {}
                }
                continue
              }
              // Fallback: stream for inputTranscription wake detection only; handle() suppresses Gemini responses/actions in idle.
              if (speaking) continue
              if (onlyUser && shouldRejectNonUserVoice("wake_audio", manualBypass = isManualVoiceBypassActive())) continue
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
            if (speaking) continue
            if (onlyUser && UserVoiceProfile.verifyAllInputsEnabled(this@VoiceService) &&
                shouldRejectNonUserVoice(if (isSaraActive) "live_audio" else "wake_audio", manualBypass = isManualVoiceBypassActive())) continue
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
    try { speakerGuard.stop() } catch (_: Exception) {}
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
    val wakeNoPhrase = i?.action == ACTION_WAKE || i?.getBooleanExtra(ASSISTANT_WAKE, false) == true
    val cmd = i?.getStringExtra(ASSISTANT_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }
      ?: i?.getStringExtra("action_text")?.trim()?.takeIf { it.isNotEmpty() }
    val explicitTrigger = wakeNoPhrase || cmd != null
    if (i?.action != "STOP") bubbleDismissedByUser = false
    when (i?.action) {
      "STOP" -> { closeSession(reason = "service_stop", announce = false); stopSelf(); return START_NOT_STICKY }
      ACTION_WAKE -> { if (running && ready && micEnabled && !callActive) { ensureCaptureStarted(); startSession(); Bus.add("system", "Woke up from device button.", true) }; if (!running) { /* fall through to start then wake */ } else { refreshBubble(); return START_STICKY } }
      "CAM_START", "CAM_STOP", "SCREEN_START", "SCREEN_CAPTURE", "SCREEN_STOP" -> { if (!running) { stopSelf(); return START_NOT_STICKY }; share(i!!); return START_STICKY }
    }
    if (cmd != null) {
      if (ready) {
        startSession(); processUtterance(cmd)
      } else pendingCommand = cmd
    }
    if (running) {
      if (wakeNoPhrase) {
        if (ready && micEnabled && !callActive) { ensureCaptureStarted(); startSession() }
        else pendingWakeNoPhrase = true
      }
      refreshBubble()
      return START_STICKY
    }
    if (!has(Manifest.permission.RECORD_AUDIO)) {
      Bus.add("system", "Microphone permission is missing. Allow Microphone in Android app permissions.", true)
      stopSelf(); return START_NOT_STICKY
    }
    running = true; instance = this; Bus.running = true
    micEnabled = Prefs.plain(this).getBoolean("mic_enabled", true)
    wakePatterns = buildWakePatterns(loadWakePhrases(Prefs.plain(this)))
    localWakeUnavailable = false; localWakeWarned = false
    refreshSpeakerGuardConfig()
    liveModelIndex = 0; liveCandidates = emptyList(); liveModel = ModelRouter.primaryModel(this)
    migrateFacts(this)
    ensureSessionId()
    noteSession(status = "connecting", lastStep = "service starting", lastError = "")
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
    refreshBubble(); Bus.listen(busListener); Bus.add("system", "Connecting…", true); refreshCalls(); registerAutomationEvents(); connect()
    if (wakeNoPhrase) pendingCommand = pendingCommand ?: "" // marker handled in setupComplete via flag
    if (wakeNoPhrase) pendingWakeNoPhrase = true
    return START_STICKY
  }

  private fun registerAutomationEvents() {
    if (automationReceiver != null) return
    val r = object : android.content.BroadcastReceiver() {
      override fun onReceive(c: android.content.Context, i: Intent?) {
        when (i?.action) {
          Intent.ACTION_SCREEN_ON -> Automation.onEvent(applicationContext, "screen_on")
          Intent.ACTION_SCREEN_OFF -> Automation.onEvent(applicationContext, "screen_off")
          Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_BATTERY_CHANGED -> Automation.onEvent(applicationContext, "battery")
        }
      }
    }
    val f = android.content.IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(Intent.ACTION_BATTERY_CHANGED)
    }
    try { registerReceiver(r, f); automationReceiver = r } catch (_: Exception) {}
  }

  fun bubbleAnchor(): Overlay.Anchor? {
    val ch = chatHead ?: return null
    return ch.anchorForOverlay()
  }

  private fun updateOverlay() {
    if (!running) return
    if (!Prefs.on(this, "f_overlay", true) || !Settings.canDrawOverlays(this)) { overlay?.hide(); return }
    if (chatHead?.isExpanded() == true) { overlay?.hide(); return }
    if (overlay == null) overlay = Overlay(this)
    val st = Bus.status
    when {
      st.contains("503", true) || st.contains("retry", true) || st.contains("reconnect", true) -> Bus.setStatusCompactIfNeeded("Retrying / 503 wait")
    }
    val anchor = bubbleAnchor()
    if (Bus.camOn) { overlay?.show("Camera live", true, anchor); return }
    if (MainActivity.visible) { overlay?.hide(); return }
    val m = synchronized(Bus) { Bus.msgs.lastOrNull() } ?: return
    val icon = when (m.role) { "you" -> "You"; "saara" -> "Saara"; "tool" -> "Tool"; else -> "Sys" }
    overlay?.show("$icon: ${m.text.takeLast(120)}", false, anchor)
  }

  private fun share(i: Intent) {
    when (i.action) {
      "CAM_START" -> startCam(i.getBooleanExtra("front", false))
      "CAM_STOP" -> stopCam(true)
      "SCREEN_START" -> startScreen(i.getIntExtra("code", 0), projData(i), i.getBooleanExtra("auto", false))
      "SCREEN_CAPTURE" -> captureScreenOnce(i.getIntExtra("code", 0), projData(i), i.getStringExtra("mode") ?: "capture")
      "SCREEN_STOP" -> stopScreen(true)
    }
  }

  private fun captureScreenOnce(code: Int, data: Intent?, mode: String) {
    if (data == null || code == 0) { Bus.add("system", "Screen capture was not allowed.", true); return }
    Bus.add("system", if (mode == "ocr") "Capturing screen for OCR/form extraction…" else "Capturing screen for context…", true)
    Thread {
      try {
        val cap = ScreenCapture.captureOnce(this, code, data)
        if (cap == null) { Bus.add("system", "Screen capture failed.", true); return@Thread }
        val b64 = android.util.Base64.encodeToString(cap, android.util.Base64.NO_WRAP)
        if (mode == "ocr") {
          ScreenAnalyzer.extractForm(this, b64) { result -> Bus.add("saara", result, true) }
        } else {
          ScreenAnalyzer.summarize(this, b64) { result -> Bus.add("saara", result, true) }
        }
      } catch (e: Exception) {
        Bus.add("system", "Screen capture error: ${e.message}", true)
      }
    }.start()
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
    ready = false
    val key = Prefs.secure(this).getString("apiKey", "") ?: ""
    if (key.isBlank()) { Bus.add("system", "API key missing. Add it in Settings.", true); stopSelf(); return }
    val primary = ModelRouter.primaryModel(this)
    liveCandidates = ModelRouter.candidatesFor(this, ModelTask.LIVE_VOICE, primary).ifEmpty { listOf(primary) }
    if (liveModelIndex >= liveCandidates.size) liveModelIndex = 0
    liveModel = liveCandidates.getOrElse(liveModelIndex) { primary }
    Bus.setStatus("Connecting / Thinking · $liveModel")
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
    val transient = ModelRouter.isTransientError(msg)
    val retryEnabled = Prefs.on(this, "f_offline_retry", true)
    val autoSwitch = ModelRouter.autoFallbackEnabled(this)
    // Keep session context continuous across disconnect/reconnect; do not speak stale content.
    noteSession(status = if (transient) "reconnecting" else "disconnected", lastError = msg.take(240), pending = lastTaskNote.ifBlank { "Resume recent conversation when reconnected." })
    Bus.add("system", if (transient && (retryEnabled || autoSwitch)) "$msg — retrying with model fallback." else msg, true)
    stopSpeaking(); audioQ.clear()
    if (transient && autoSwitch && liveCandidates.isNotEmpty() && liveModelIndex < liveCandidates.lastIndex) {
      val failed = liveModel
      liveModelIndex++
      val next = liveCandidates[liveModelIndex]
      retries = 0
      ModelRouter.recordStatus(this, "Live model fallback: $failed -> $next after error: ${msg.take(160)}")
      Bus.setStatus("Retrying / 503 wait · switching to $next")
      main.postDelayed({ if (running) connect() }, 900L)
      return
    }
    if (transient && retryEnabled) {
      Bus.setStatus("Retrying / 503 wait · $liveModel")
      val delay = (1500L * retries.coerceAtLeast(1)).coerceAtMost(12000L)
      retries++
      main.postDelayed({ if (running) connect() }, delay)
      return
    }
    if (++retries > 3) { Bus.add("system", "Stopped after repeated failures. Check API key / model availability in Settings.", true); main.post { stopSelf() }; return }
    if (retryEnabled) { Bus.setStatus("Retrying / 503 wait · $liveModel"); main.postDelayed({ connect() }, 3000) }
    else { Bus.setStatus("Disconnected"); Bus.add("system", "Auto-retry is off. Tap Start/reconnect manually.", true) }
  }

  private fun setup(): String {
    val p = Prefs.plain(this)
    val model = liveModel.ifBlank { ModelRouter.primaryModel(this) }
    val voice = p.getString("voice", "Aoede") ?: "Aoede"
    val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date())
    val taskMemory = if (Prefs.on(this, "learn_tasks", true)) try { TaskLearning.prompt(this) } catch (_: Exception) { "" } else ""
    val factMemory = try { Db.get(this).factsPrompt(Bus.lastUser, 18) } catch (_: Exception) { "" }
    val sessionMemory = try { Db.get(this).recentSessionPrompt(12) } catch (_: Exception) { "" }
    val ins = "You are Saara, an elegant, proactive, warm FEMALE voice assistant (use feminine forms when speaking Hindi, e.g. 'main kar rahi hoon'). Be concise. " + langRule(this) + " " +
      "Current local time: $now. " +
      "SESSION CONTINUITY: recent chat/session/task state may be provided from local memory. Use it to continue the same topic or pending task seamlessly after reconnect, network drop, session timeout, or restart. Do not ask the user to repeat what is already in recent context unless critical details are missing. If last step had an error (503/disconnect/timeout), briefly acknowledge and resume from the pending step. " +
      "PRE-TASK MEMORY CHECK: before executing tasks, check relevant stored memory/rules and current app/device context. Keep facts brief, categorized, and relevant to active context. For direct person actions (call, message, lookup), ALWAYS use device_control read_contacts/read_sms/read_call_log or direct device actions instead of relying only on cached memory. " +
      "You can use device_control and local task tools for permitted phone actions. Do not request or reveal contacts, SMS, call logs, notifications, raw local database dumps, chat history, or sheet contents to the user in chat; when helpful you may use recall/recall_task for user-taught preferences/rules context and summarize carefully without exposing private details. URL imports are handled locally by the app. Never ask for private data to be pasted into Gemini. " +
      "If a tool result says a feature is OFF or an Android permission is missing, tell the user in one short sentence exactly what to switch on or grant in the Saara app; do not just say you cannot. " +
      "AUTONOMY & PERSISTENCE (critical): for any task, keep trying until successful or truly blocked by missing permission/irreversible ambiguity. Do not give up after one failure. Chain tool calls, read returned screen after every action, and adapt. Provide short status updates during long tasks. Keep microphone and screen sharing ON until task is complete; if screen sharing is needed, request it and do not stop it until done. " +
      "When camera or screen sharing is ON, use live frames to ground exact UI/keyboard coordinates. For on-screen keyboard typing, prefer focused field type/paste; for individual key presses use ui_action tap_key or dump_ui then tap_xy on key bounds. " +
      "SPEED & SELF-HEALING: if an element/tap fails, do not repeat blindly: try tap_text/tap_id/tap_key, dump_ui bounds+tap_xy, scroll, wait_text, back, reopen, alternate route. Keep going until done; report brief blocker only when genuinely stuck. " +
      "MULTI-FIELD FORMS (STRICT, for accuracy): when the user gives multiple values/items to enter into multiple inputs/boxes, FIRST call ui_action op=list_fields to get structured fields (index,id,label,hint,desc,value,bounds,focused). Map each user-provided value to exactly one field. Fill fields step-by-step: for each value target the exact field with field_index/field_id/field_label, focus & verify the target, type only that single value (replace=true as appropriate), read the returned result/screen, then move to the next field. NEVER dump or paste all/combined data into one field unless the user explicitly asked for that exact bulk text in that specific field (allow_bulk=true). " +
      "AMBIGUITY: if you cannot confidently match a value to a specific field, or multiple fields could match, or a value is missing/extra, STOP filling the ambiguous item and ask the user to clarify which value goes to which field (show the available fields/indices). Do not guess or write to an arbitrary field. " +
      "REPORTING: keep execution fast and direct, but clearly report what was entered into each field as you complete them / in your final short result. " +
      "CONFIRMATION (be smart and low-friction): do NOT ask for confirmation for clear, short, reversible actions: open app, tap, scroll, type, search, copy/paste, take photo, volume, torch, calls or messages when the recipient and content are already clear. Only ask ONCE when the action is destructive or irreversible (delete, erase, remove, cancel, pay, order, book, reset, submit, logout, block, report) or when the target/content is genuinely ambiguous. If the user already told you clearly, just do it; never ask again just to repeat the same yes. After a required confirmation, finish the whole task without asking again. Read-only questions are answered immediately. " +
      "If the user says stop, wait, hold on, ruko, bas or changes their mind at any time, stop immediately, call no more action tools, and briefly acknowledge. If a tool really fails after alternatives, say briefly what failed and ask how to proceed; never pretend it worked. " +
      "CLIPBOARD: use ui_action copy_text to copy given text (or the selected text), select_all, and paste. Use copy/paste for fields that reject typing. " +
      "CALLS: incoming calls are announced locally by the app; caller details are never sent to Gemini. Answer or reject when the user's instruction is clear. " +
      "Ask only when information is truly missing (which of two contacts, what text to write). " +
      "Do not narrate each step; when everything is done, say one short sentence with the result. " +
      "Follow the user's command in the simplest way: never make it complicated, never lecture, find a way to get it done. " +
      "Photos: call device_control take_photo (it presses the shutter by itself; text=front for selfie). Calls: answer_call / end_call. " +
      "Notifications and scheduled-task text are private local data and are never forwarded to Gemini. " +
      "Do not store personal facts in Gemini. The app stores Learning/Rules locally on the phone. " +
      "SELF-LEARNING: before repeating a multi-step task, call recall_task to check which steps worked or failed. After solving a new task or discovering a better method, call remember_task with concise working steps. Never put contacts, messages, ledger data, URLs, or screen text into remember_task. " +
      "Do not ask for private shop ledger details. Local ledger data is not sent to Gemini. " +
      "Live camera and screen frames are sent to Gemini only while the user turns camera or screen sharing ON; the user can turn them off at any time. While Screen automation is ON, on-device tool results may include screen text so you can complete the task. Contacts, SMS, call log, and notification contents may be accessed only when the user asks or when handling an incoming notification/reply; use them only for the requested task and keep responses concise. Links and spreadsheet data are fetched and stored locally by the app, not by Gemini. " +
      if (taskMemory.isBlank()) "" else "The short task-memory hints below contain goal names, tool names and outcomes with numbers/emails redacted; they do not contain contacts, messages, ledger data, or screen text.\n$taskMemory"
    val rules = try { Db.get(this).rulesPrompt() } catch (_: Exception) { "" }
    val s = JSONObject()
      .put("model", "models/$model")
      .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ins + rules + factMemory + sessionMemory))))
      .put("tools", Tools.declarations())
      .put("inputAudioTranscription", JSONObject())
      .put("outputAudioTranscription", JSONObject())
    return JSONObject().put("setup", s).toString()
  }

  private fun handle(s: String) {
    val m = try { JSONObject(s) } catch (_: Exception) { return }
    if (m.has("setupComplete")) {
      ready = true; retries = 0
      stopSpeaking(); audioQ.clear() // never replay old audio on connect/reconnect
      Prefs.plain(this).edit().putString("model_last_used", liveModel).putString("model_last_status", "Live model connected: $liveModel").putLong("model_last_status_ts", System.currentTimeMillis()).apply()
      val resumed = try { Db.get(this).sessionState().updatedTs > 0 && System.currentTimeMillis() - Db.get(this).sessionState().updatedTs < 6L * 60 * 60 * 1000 } catch (_: Exception) { false }
      noteSession(status = if (isSaraActive) "active" else "ready", lastError = "")
      val wakeNow = pendingWakeNoPhrase; pendingWakeNoPhrase = false
      val cmd = pendingCommand?.takeIf { it.isNotBlank() }; pendingCommand = null
      if (cmd != null) {
        startSession()
        processUtterance(cmd)
      } else if (wakeNow && micEnabled) {
        startSession()
        Bus.add("system", "Woke up — listening (no wake phrase needed).", true)
      } else {
        // Silent continuity on connect/reconnect/mute-state transitions; no old-session speech replay.
        Bus.setStatus(if (isSaraActive) "Listening · $liveModel" else "Listening for wake phrase · $liveModel")
      }
      if (micEnabled) { ensureCaptureStarted(); if (!isSaraActive) maybeStartIdleWakeEngine() } else Bus.add("system", "Gemini connected ($liveModel). Microphone is muted.", true)
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
        if (sc.has("modelTurn")) Bus.setStatus("Thinking")
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
          val inl = parts.getJSONObject(i).optJSONObject("inlineData")
          if (inl != null) { speakingUntil = maxOf(speakingUntil, System.currentTimeMillis() + 120L); play(Base64.decode(inl.getString("data"), Base64.DEFAULT)) }
        }
        sc.optJSONObject("outputTranscription")?.let {
          val txt = it.optString("text")
          if (txt.isNotBlank()) {
            val last = synchronized(Bus) { val m = Bus.msgs.lastOrNull(); if (m != null && m.role == "saara") m.text else "" }
            val add = when {
              last.isEmpty() -> txt
              txt.startsWith(last) && txt.length > last.length -> txt.substring(last.length)
              last.endsWith(txt) || txt == last -> ""
              txt.length < last.length && last.startsWith(txt) -> ""
              else -> txt
            }
            if (add.isNotEmpty()) Bus.add("saara", add, last.isEmpty())
            val shown = synchronized(Bus) { val m = Bus.msgs.lastOrNull(); if (m != null && m.role == "saara") m.text else "" }
            val rn = normForCompare(if (shown.isNotBlank()) shown else txt)
            if (rn.isNotEmpty()) { lastReplyNorm = rn; lastReplyAt = System.currentTimeMillis() }
          }
        }
        if (sc.optBoolean("interrupted")) { stopSpeaking(); Bus.add("system", "Interrupted.", false); resetSessionTimerIfActive() }
        if (sc.optBoolean("turnComplete")) {
          speakingUntil = minOf(speakingUntil, System.currentTimeMillis() + 180L)
          Bus.endTurn(); armScreenAuto(6000L)
          resetSessionTimerIfActive()
          noteSession(status = if (isSaraActive) "active_idle" else "idle", lastStep = "assistant turn complete", pending = lastTaskNote.ifBlank { "await next user input" })
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
          Bus.setStatus("Executing")
          val out = JSONArray()
          for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val name = c.getString("name"); val args = c.optJSONObject("args") ?: JSONObject()
            noteSession(status = "executing", activeGoal = lastTaskNote, lastStep = "running $name", pending = "complete $name and continue task")
            Bus.add("tool", "$name $args", true)
            val r = Tools.run(this, name, args)
            Bus.add("tool", "→ $r", true)
            val failed = r.startsWith("error") || r.startsWith("timeout") || r.contains("permission missing", true)
            noteSession(status = if (failed) "task_error" else "executing", activeGoal = lastTaskNote, lastStep = "$name: ${r.take(120)}", pending = if (failed) "retry or adapt after $name failure" else "continue task after $name", lastError = if (failed) r.take(200) else "")
            out.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", JSONObject().put("result", Tools.modelResult(name, r))))
          }
          ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
          Bus.setStatus(if (isSaraActive) "Listening" else "Listening for wake phrase")
        }.start()
      }
      // idle: do not execute tools
    }
  }

  private fun play(pcm: ByteArray) {
    if (track == null) return
    // pcm 16-bit mono @ 24kHz => 2 bytes/sample, 48 bytes/ms
    val dur = pcm.size / 48
    speakingUntil = maxOf(speakingUntil, System.currentTimeMillis()) + dur + 120L
    audioQ.offer(pcm)
  }

  private fun stopSpeaking() {
    audioQ.clear()
    try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    speakingUntil = System.currentTimeMillis() + 300L
  }

  override fun onDestroy() {
    running = false; ready = false; isSaraActive = false
    main.removeCallbacks(sessionTimer); main.removeCallbacks(screenAutoStop)
    screenAuto = false; Bus.screenAuto = false
    stopIdleWakeEngine()
    stopCapture()
    Bus.unlisten(busListener)
    try { automationReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    automationReceiver = null
    calls?.stop(); calls = null; callActive = false
    TaskControl.clear(); audioQ.clear()
    stopSpeaking()
    cam?.stop(); cam = null; Bus.camOn = false
    screen?.stop(); screen = null; Bus.screenOn = false
    ws?.close(1000, null)
    try { track?.release() } catch (_: Exception) {}
    chatHead?.hide(); chatHead = null; overlay?.hide()
    try { Db.get(this).sessionStateClear(keepSessionId = true) } catch (_: Exception) {}
    lastTaskNote = ""; pendingCommand = null; pendingWakeNoPhrase = false
    instance = null; Bus.running = false
    Bus.add("system", "Stopped.", true); Bus.setStatus("Stopped")
    super.onDestroy()
  }
}
