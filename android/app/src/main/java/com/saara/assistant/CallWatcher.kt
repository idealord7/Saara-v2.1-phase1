package com.saara.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Announces incoming calls locally, listens for "answer" / "reject", then answers via Telecom
 * and turns on the loudspeaker when no Bluetooth output is connected. Caller details never go to Gemini.
 */
class CallWatcher(
  private val ctx: Context,
  private val onCallStarted: (String) -> Unit,
  private val onCallAnswered: () -> Unit,
  private val onCallEnded: () -> Unit
) {
  private val main = Handler(Looper.getMainLooper())
  private val executor = Executor { r -> main.post(r) }
  @Volatile private var running = false
  @Volatile private var ringing = false
  @Volatile private var answered = false
  @Volatile private var number = ""
  private var lastState = TelephonyManager.CALL_STATE_IDLE
  private var telephonyCallback: Any? = null
  private var legacyListener: PhoneStateListener? = null
  private var tts: TextToSpeech? = null
  @Volatile private var ttsReady = false
  private var recognizer: SpeechRecognizer? = null
  private var timeout: Runnable? = null
  private var speakerForced = false

  private val phoneReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context?, i: Intent?) {
      val state = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
      i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }?.let { number = it }
      when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> handleState(TelephonyManager.CALL_STATE_RINGING)
        TelephonyManager.EXTRA_STATE_OFFHOOK -> handleState(TelephonyManager.CALL_STATE_OFFHOOK)
        TelephonyManager.EXTRA_STATE_IDLE -> handleState(TelephonyManager.CALL_STATE_IDLE)
      }
    }
  }

  fun start() {
    if (running) return
    running = true
    initTts()
    val tm = ctx.getSystemService(TelephonyManager::class.java)
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        telephonyCallback = registerPhoneCallback(tm)
      } else {
        @Suppress("DEPRECATION")
        val l = object : PhoneStateListener() {
          @Deprecated("Deprecated in Java")
          override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (!phoneNumber.isNullOrBlank()) number = phoneNumber
            handleState(state)
          }
        }
        @Suppress("DEPRECATION")
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = l
      }
    } catch (_: Exception) {}
    try {
      val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
      ContextCompat.registerReceiver(ctx, phoneReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    } catch (_: Exception) {
      try { ctx.registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) } catch (_: Exception) {}
    }
  }

  fun stop() {
    running = false
    ringing = false
    answered = false
    number = ""
    main.removeCallbacksAndMessages(null)
    stopListening()
    stopTts()
    if (speakerForced) {
      try { (ctx.getSystemService(AudioManager::class.java)).isSpeakerphoneOn = false } catch (_: Exception) {}
      speakerForced = false
    }
    try { telephonyCallback?.let { unregisterPhoneCallback(it) } } catch (_: Exception) {}
    telephonyCallback = null
    try {
      @Suppress("DEPRECATION")
      legacyListener?.let { ctx.getSystemService(TelephonyManager::class.java).listen(it, PhoneStateListener.LISTEN_NONE) }
    } catch (_: Exception) {}
    legacyListener = null
    try { ctx.unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
    tts?.shutdown(); tts = null; ttsReady = false
  }

  @androidx.annotation.RequiresApi(31)
  private fun registerPhoneCallback(tm: TelephonyManager): Any {
    val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
      override fun onCallStateChanged(state: Int) { handleState(state) }
    }
    tm.registerTelephonyCallback(executor, cb)
    return cb
  }

  @androidx.annotation.RequiresApi(31)
  private fun unregisterPhoneCallback(cb: Any) {
    ctx.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb as TelephonyCallback)
  }

  private fun handleState(state: Int) {
    if (!running) return
    if (state == lastState) return
    lastState = state
    when (state) {
      TelephonyManager.CALL_STATE_RINGING -> if (!ringing) {
        ringing = true
        answered = false
        main.postDelayed({ announce() }, 650L)
      }
      TelephonyManager.CALL_STATE_OFFHOOK -> if (ringing && !answered) {
        answered = true
        stopListening()
        stopTts()
        onCallAnswered()
        main.postDelayed({ speakerIfNoBluetooth() }, 1200L)
      }
      TelephonyManager.CALL_STATE_IDLE -> if (ringing || answered) {
        ringing = false
        answered = false
        number = ""
        stopListening()
        stopTts()
        restoreSpeaker()
        onCallEnded()
      }
    }
  }

  private fun announce() {
    if (!running || !ringing || answered) return
    val caller = callerLabel()
    onCallStarted(caller)
    val who = if (caller.isBlank()) "an unknown number" else caller
    speak("Incoming call from $who. Say answer or reject.", thenListen = true)
    Bus.ping()
  }

  private fun callerLabel(): String {
    val info = SaaraNotifications.callInfo()
    val pieces = listOfNotNull(info?.first, info?.second).map { it.trim() }.filter { it.isNotBlank() }
    val infoNumber = pieces.firstOrNull { looksLikeNumber(it) }
    val n = number.ifBlank { infoNumber.orEmpty() }
    if (n.isNotBlank()) contactName(n)?.let { return it }
    val name = pieces.firstOrNull { !looksLikeNumber(it) && !genericCallText(it) }
    if (name != null) return name
    if (n.isNotBlank()) return n
    return ""
  }

  private fun looksLikeNumber(s: String) = Regex("^[+]?[0-9][0-9 ()-]{4,}$").matches(s.trim())
  private fun genericCallText(s: String) = s.lowercase(Locale.ROOT).let {
    it == "incoming call" || it == "call" || it.contains("incoming") && !it.any { ch -> ch.isDigit() }
  }

  private fun contactName(n: String): String? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
      ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.takeIf { name -> name.isNotBlank() } else null
      }
    } catch (_: Exception) { null }
  }

  private fun initTts() {
    if (tts != null) return
    tts = TextToSpeech(ctx.applicationContext) { status ->
      main.post {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) try { tts?.language = appLocale() } catch (_: Exception) {}
      }
    }
  }

  private fun speak(text: String, thenListen: Boolean) {
    val t = tts
    if (t == null || !ttsReady) {
      if (thenListen) main.postDelayed({ if (ringing && !answered) startListening() }, 900L)
      return
    }
    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {}
      override fun onDone(utteranceId: String?) {
        main.post { if (thenListen && ringing && !answered) startListening() }
      }
      override fun onError(utteranceId: String?) {
        main.post { if (thenListen && ringing && !answered) startListening() }
      }
    })
    try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saara_call") } catch (_: Exception) {
      if (thenListen) main.postDelayed({ if (ringing && !answered) startListening() }, 700L)
    }
  }

  private fun stopTts() {
    try { tts?.stop() } catch (_: Exception) {}
  }

  private fun startListening() {
    if (!running || !ringing || answered || recognizer != null) return
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
    val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { return }
    recognizer = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
      override fun onError(error: Int) { finishListening() }
      override fun onResults(results: Bundle?) { inspect(results) }
      override fun onPartialResults(partialResults: Bundle?) { inspect(partialResults) }
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    try { r.startListening(intent) } catch (_: Exception) { finishListening(); return }
    timeout?.let { main.removeCallbacks(it) }
    timeout = Runnable {
      finishListening()
      if (ringing && !answered) Bus.add("system", "No answer command heard. The call is still ringing.", true)
    }.also { main.postDelayed(it, 9000L) }
  }

  private fun inspect(bundle: Bundle?) {
    if (!ringing || answered) return
    val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ").orEmpty().lowercase(Locale.ROOT)
    if (heard.isBlank()) return
    when {
      rejects(heard) -> { stopListening(); rejectCall() }
      answers(heard) -> { stopListening(); answerCall() }
    }
  }

  private fun answers(s: String) = Regex("\\b(answer|accept|pick up|receive|yes|yeah|okay|ok|haan|han)\\b").containsMatchIn(s) ||
    listOf("जवाब", "उत्तर", "स्वीकार", "उठाओ", "উত্তর", "রিসিভ", "ধরো", "হ্যাঁ").any { s.contains(it) }

  private fun rejects(s: String) = Regex("\\b(reject|decline|end call|hang up|cut|no|nope|cancel)\\b").containsMatchIn(s) ||
    listOf("काट", "अस्वीकार", "नहीं", "কাট", "রিজেক্ট", "না").any { s.contains(it) }

  private fun finishListening() {
    timeout?.let { main.removeCallbacks(it) }
    timeout = null
    val r = recognizer
    recognizer = null
    try { r?.stopListening() } catch (_: Exception) {}
    try { r?.cancel() } catch (_: Exception) {}
    try { r?.destroy() } catch (_: Exception) {}
  }

  private fun stopListening() = finishListening()

  @Suppress("MissingPermission")
  private fun answerCall() {
    stopTts()
    val ok = try {
      if (Build.VERSION.SDK_INT >= 26 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
        ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
        true
      } else false
    } catch (_: Exception) { false }
    val answeredNow = ok || SaaraNotifications.callAction(listOf("answer", "accept", "pick up", "receive", "जवाब", "उत्तर", "उठाएं", "स्वीकार", "रिसीव"))
    if (answeredNow) {
      Bus.add("system", "Answering the call.", true)
      if (!answered) {
        answered = true
        onCallAnswered()
        main.postDelayed({ speakerIfNoBluetooth() }, 1400L)
      }
    } else Bus.add("system", "Could not answer automatically. Answer it on the phone.", true)
  }

  @Suppress("MissingPermission")
  private fun rejectCall() {
    stopTts()
    val ok = try {
      if (Build.VERSION.SDK_INT >= 28 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
        ctx.getSystemService(TelecomManager::class.java).endCall()
      } else false
    } catch (_: Exception) { false }
    val rejected = ok || SaaraNotifications.callAction(listOf("decline", "reject", "hang up", "hangup", "end call", "disconnect", "काट", "अस्वीकार", "रिजेक्ट"))
    Bus.add("system", if (rejected) "Rejecting the call." else "Could not reject automatically. Reject it on the phone.", true)
  }

  private fun speakerIfNoBluetooth() {
    if (!running || !answered) return
    try {
      val am = ctx.getSystemService(AudioManager::class.java)
      val bt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
          it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
          it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
      }
      if (bt) {
        Bus.add("system", "Bluetooth audio is connected, so the call stays on Bluetooth.", true)
        return
      }
      if (!am.isSpeakerphoneOn) {
        am.isSpeakerphoneOn = true
        speakerForced = true
        Bus.add("system", "Loudspeaker is ON for the call.", true)
      }
    } catch (_: Exception) {}
  }

  private fun restoreSpeaker() {
    if (!speakerForced) return
    try { (ctx.getSystemService(AudioManager::class.java)).isSpeakerphoneOn = false } catch (_: Exception) {}
    speakerForced = false
  }

  private fun languageTag(): String = when (Prefs.plain(ctx).getString("lang", "auto")) {
    "bn" -> "bn-IN"
    "hi" -> "hi-IN"
    else -> Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
  }

  private fun appLocale(): Locale = when (Prefs.plain(ctx).getString("lang", "auto")) {
    "bn" -> Locale("bn", "IN")
    "hi" -> Locale("hi", "IN")
    else -> Locale.getDefault()
  }
}
