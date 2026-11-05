package com.saara.assistant
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Talks to Gemini Live directly (no Termux). Mic -> Gemini -> speaker, runs tools natively, can stream camera / screen frames. */
class VoiceService : Service() {
  companion object { var instance: VoiceService? = null }
  private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
  private var ws: WebSocket? = null
  @Volatile private var running = false
  @Volatile private var ready = false
  @Volatile private var speakingUntil = 0L
  @Volatile var muted = false
  private var micStarted = false
  private var retries = 0
  private var track: AudioTrack? = null
  private val audioQ = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var bubble: Bubble? = null
  private var overlay: Overlay? = null
  private var cam: CamShare? = null
  private var screen: ScreenShare? = null
  private var gotSpeech = false
  private val main = Handler(Looper.getMainLooper())
  private val busListener: () -> Unit = { updateOverlay() }

  override fun onBind(i: Intent?): IBinder? = null

  private fun notif(): Notification {
    val stop = PendingIntent.getService(this, 0, Intent(this, VoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val sharing = listOfNotNull(if (Bus.camOn) "camera" else null, if (Bus.screenOn) "screen" else null)
    val title = if (sharing.isEmpty()) "Saara is listening" else "Saara is listening • sharing ${sharing.joinToString(" & ")}"
    return Notification.Builder(this, "saara").setContentTitle(title).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(open).addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build()).build()
  }

  /** (Re)declares the foreground service types that are currently needed. */
  private fun fg() {
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Bus.camOn) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (Bus.screenOn) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(1, notif(), t)
      } else startForeground(1, notif())
    } catch (e: Exception) { Bus.add("system", "Foreground service: ${e.message}", true) }
  }

  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    Bus.init(this)
    when (i?.action) {
      "STOP" -> { stopSelf(); return START_NOT_STICKY }
      "CAM_START", "CAM_STOP", "SCREEN_START", "SCREEN_STOP" -> { if (!running) { stopSelf(); return START_NOT_STICKY }; share(i!!); return START_STICKY }
    }
    if (running) return START_STICKY
    running = true; instance = this; Bus.running = true
    migrateFacts(this)
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("saara", "Saara", NotificationManager.IMPORTANCE_LOW))
    fg()
    // USAGE_MEDIA => loudspeaker (USAGE_VOICE_COMMUNICATION is routed to the earpiece). Also makes the volume tools (STREAM_MUSIC) control Saara's voice.
    val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    track = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(maxOf(48000, minBuf)).build().also { it.play() }
    // Separate playback thread so a full audio buffer never blocks the websocket (tool calls / interruptions stay responsive).
    Thread {
      while (running) {
        val b: ByteArray = (try { audioQ.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }) ?: continue
        try { track?.write(b, 0, b.size) } catch (e: Exception) {}
      }
    }.start()
    refreshBubble()
    Bus.listen(busListener)
    Bus.add("system", "Connecting…", true)
    connect()
    return START_STICKY
  }

  // ---------- floating bubble + caption overlay ----------
  fun refreshBubble() {
    if (Prefs.on(this, "f_bubble", false)) {
      if (bubble == null) bubble = Bubble(this) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      bubble?.show()
    } else bubble?.hide()
    if (!Prefs.on(this, "f_overlay", true)) overlay?.hide()
  }

  private fun updateOverlay() {
    if (!running) return
    if (!Prefs.on(this, "f_overlay", true) || MainActivity.visible || !Settings.canDrawOverlays(this)) { overlay?.hide(); return }
    val m = synchronized(Bus) { Bus.msgs.lastOrNull() } ?: return
    val icon = when (m.role) { "you" -> "🧑 "; "saara" -> "✨ "; "tool" -> "⚙️ "; else -> "ℹ️ " }
    if (overlay == null) overlay = Overlay(this)
    overlay?.show(icon + m.text.takeLast(220))
  }

  // ---------- camera / screen sharing ----------
  private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun share(i: Intent) {
    when (i.action) {
      "CAM_START" -> startCam(i.getBooleanExtra("front", false))
      "CAM_STOP" -> stopCam(true)
      "SCREEN_START" -> startScreen(i.getIntExtra("code", 0), projData(i))
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
    if (!was) { Bus.add("system", "Camera sharing is ON (${if (front) "front" else "back"}).", true); tellModel("[System] The user started sharing their live camera. You now receive video frames about once per second. Use them whenever the user asks what they see. Say only one very short sentence to confirm.") }
  }
  private fun stopCam(say: Boolean) {
    cam?.stop(); cam = null
    if (Bus.camOn) { Bus.camOn = false; fg(); if (say) { Bus.add("system", "Camera sharing stopped.", true); tellModel("[System] Camera sharing stopped. Do not reply.") } }
    Bus.ping()
  }
  private fun startScreen(code: Int, data: Intent?) {
    if (data == null || code == 0) { Bus.add("system", "Screen sharing was not allowed.", true); return }
    Bus.screenOn = true; fg()
    val s = screen ?: ScreenShare(this, { sendFrame(it) }, { main.post { stopScreen(true) } }).also { screen = it }
    val err = s.start(code, data)
    if (err != null) { Bus.add("system", err, true); stopScreen(false); return }
    Bus.add("system", "Screen sharing is ON.", true)
    tellModel("[System] The user started sharing their live screen. You now receive screenshots about once per second. Read them when the user asks about the screen, and combine them with ui_action to act. Say only one very short sentence to confirm.")
  }
  private fun stopScreen(say: Boolean) {
    screen?.stop(); screen = null
    if (Bus.screenOn) { Bus.screenOn = false; fg(); if (say) { Bus.add("system", "Screen sharing stopped.", true); tellModel("[System] Screen sharing stopped. Do not reply.") } }
    Bus.ping()
  }

  fun sendFrame(jpeg: ByteArray) {
    if (!ready) return
    val v = JSONObject().put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg")
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("video", v)).toString())
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

  private fun factsText(): String {
    val fs = try { Db.get(this).facts() } catch (e: Exception) { emptyList() }
    val sb = StringBuilder()
    for (f in fs) { val line = "- [${f.cat}] ${f.text}\n"; if (sb.length + line.length > 3500) break; sb.append(line) }
    return sb.toString().ifBlank { "(nothing yet)" }
  }

  private fun setup(): String {
    val p = Prefs.plain(this)
    val model = p.getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
    val voice = p.getString("voice", "Aoede") ?: "Aoede"
    val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(Date())
    val ins = "You are Saara, an elegant, proactive, warm FEMALE voice assistant (use feminine forms when speaking Hindi, e.g. 'main kar rahi hoon'). Be concise. " + langRule(this) + " " +
      "Current local time: $now. " +
      "You CAN control this phone with your tools: device_control, ui_action, remember, recall, reply_notification, shop_record, shop_report, schedule_task, list_tasks, cancel_task. When the user asks for an action, call the matching tool yourself. " +
      "If a tool result says a feature is OFF or an Android permission is missing, tell the user in one short sentence exactly what to switch on or grant in the Saara app; do not just say you cannot. " +
      // --- autonomous agent behaviour ---
      "AUTONOMY (very important): for any task with several steps (open an app, search, type, send, post, take a photo, answer a call...), complete the WHOLE task yourself by chaining tool calls one after another. " +
      "Do NOT ask the user anything between steps and never ask them where a button, field or app is. Every ui_action and open_app result already contains the new screen (t=text, id=view id, c=clickable, e=editable, b=bounds): read it, decide the next step, act. " +
      "If an element is not visible, scroll_down / scroll_up / swipe, try another label or the view id, or tap_xy using the bounds, and retry at least 3 different ways before reporting a problem. " +
      "To type, tap the field first if needed, then call type. type ADDS to existing text; use replace=true only when asked to overwrite or clear. " +
      "Only ask the user when information is truly missing (which of two contacts, what text to write) or before irreversible/sensitive actions like payments or deleting data. " +
      "Do not narrate each step; when everything is done, say one short sentence with the result. " +
      "Follow the user's command in the simplest way: never make it complicated, never lecture, find a way to get it done. " +
      "Photos: call device_control take_photo (it presses the shutter by itself; text=front for selfie). Calls: answer_call / end_call. " +
      "Incoming messages: when you get a [Notification ...] message, immediately call reply_notification with the key and a short natural reply in the sender's language; do not speak it aloud and do not ask permission. " +
      "Scheduled tasks: when you get a [Scheduled task] message, do it now and say one short sentence. " +
      // --- learning ---
      "SELF-IMPROVEMENT: keep learning the user. Whenever they state a preference, habit, routine, a person's name/relationship, correct you, or show HOW they like a task done, quietly call remember(fact, category) in the same turn without announcing it. " +
      "Before doing a repeated task, apply what you know; call recall(query) if you need older memory. Never repeat a mistake the user corrected. " +
      // --- shop ---
      "SHOP LEDGER: the user is a shop owner. When they mention buying stock (purchase price), selling (sale price), a cancelled or returned order, or a product price, call shop_record immediately. For questions about prices, profit, stock bought/sold or cancellations call shop_report. " +
      // --- vision ---
      "VISION: when the user shares the camera or screen you receive live frames about once per second; describe or use them whenever asked. " +
      "What you have learned about the user so far:\n" + factsText()
    val s = JSONObject()
      .put("model", "models/$model")
      .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", ins))))
      .put("tools", Tools.declarations())
      .put("inputAudioTranscription", JSONObject())
      .put("outputAudioTranscription", JSONObject())
    return JSONObject().put("setup", s).toString()
  }

  private fun handle(s: String) {
    val m = try { JSONObject(s) } catch (e: Exception) { return }
    if (m.has("setupComplete")) { ready = true; retries = 0; Bus.setStatus("Listening"); Bus.add("system", "Connected. Speak or type.", true); startMic() }
    if (m.has("error")) Bus.add("system", "Gemini error: " + m.optString("error"), true)
    val sc = m.optJSONObject("serverContent")
    if (sc != null) {
      val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
      if (parts != null) for (i in 0 until parts.length()) {
        val inl = parts.getJSONObject(i).optJSONObject("inlineData")
        if (inl != null) play(Base64.decode(inl.getString("data"), Base64.DEFAULT))
      }
      sc.optJSONObject("inputTranscription")?.let { gotSpeech = true; Bus.add("you", it.optString("text")) }
      sc.optJSONObject("outputTranscription")?.let { Bus.add("saara", it.optString("text")) }
      if (sc.optBoolean("interrupted")) { audioQ.clear(); track?.pause(); track?.flush(); track?.play(); speakingUntil = 0 }
      if (sc.optBoolean("turnComplete")) {
        Bus.endTurn()
        if (gotSpeech) { gotSpeech = false; try { Db.get(this).usage("command", "turn") } catch (_: Exception) {} }
      }
    }
    val calls = m.optJSONObject("toolCall")?.optJSONArray("functionCalls")
    if (calls != null) Thread {
      val out = JSONArray()
      for (i in 0 until calls.length()) {
        val c = calls.getJSONObject(i)
        val name = c.getString("name")
        val args = c.optJSONObject("args") ?: JSONObject()
        Bus.add("tool", "$name $args", true)
        val r = Tools.run(this, name, args)
        Bus.add("tool", "→ $r", true)
        out.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", JSONObject().put("result", r)))
      }
      ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", out)).toString())
    }.start()
  }

  private fun play(pcm: ByteArray) {
    if (track == null) return
    speakingUntil = maxOf(speakingUntil, System.currentTimeMillis()) + pcm.size / 48
    audioQ.offer(pcm)
  }

  fun sendText(t: String) {
    Bus.add("you", t, true)
    try { Db.get(this).usage("command", "turn") } catch (_: Exception) {}
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
  }

  /** Fired by the scheduler. Returns false when Saara is not connected (the caller then shows a notification). */
  fun runScheduled(text: String): Boolean {
    if (!ready) return false
    Bus.add("system", "Scheduled task: $text", true)
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", "[Scheduled task] $text . Do it now.")).toString())
    return true
  }

  fun onNotification(key: String, pkg: String, title: String, text: String) {
    if (!ready) { Bus.add("system", "Message from $title ignored: Saara is not connected yet.", true); return }
    Bus.add("system", "Notification from $title: $text", true)
    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text",
      "[Notification key=$key app=$pkg from=$title] $text . Now call the reply_notification tool with key=$key and a short, polite, context-aware reply in the sender's language. Do not ask the user and do not say the reply aloud.")).toString())
  }

  fun onIncomingCall() {
    if (!ready) {
      Bus.add("system", "Incoming call detected, but Saara is not connected.", true)
      return
    }
    Bus.add("system", "Incoming call detected. Asking whether to answer.", true)
    tellModel("[System] An incoming phone call has arrived. Ask the user by voice whether they want to answer. Wait for an explicit spoken yes or no. Never answer automatically.")
  }

  private fun startMic() {
    if (micStarted) return
    micStarted = true
    Thread {
      var rec: AudioRecord? = null
      try {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("microphone buffer unavailable")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        rec = r
        if (r.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microphone could not be opened")
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.enabled = true
        val buf = ByteArray(3200)
        r.startRecording()
        while (running) {
          val n = r.read(buf, 0, buf.size)
          if (n < 0) break
          if (n > 0 && ready && !muted && System.currentTimeMillis() > speakingUntil + 600) {
            val a = JSONObject().put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).put("mimeType", "audio/pcm;rate=16000")
            ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", a)).toString())
          }
        }
      } catch (e: Exception) {
        Bus.add("system", "Microphone error: ${e.message}", true)
      } finally {
        micStarted = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
      }
    }.start()
  }

  override fun onDestroy() {
    running = false; ready = false
    Bus.unlisten(busListener)
    audioQ.clear()
    cam?.stop(); cam = null; Bus.camOn = false
    screen?.stop(); screen = null; Bus.screenOn = false
    ws?.close(1000, null)
    track?.release()
    bubble?.hide(); overlay?.hide()
    instance = null; Bus.running = false
    Bus.add("system", "Stopped.", true); Bus.setStatus("Stopped")
    super.onDestroy()
  }
}
