package com.saara.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Listens for "Hey Saara" with the phone's speech recognizer, then hands the mic to Gemini. */
class WakeWord(
  private val c: Context,
  private val onWake: () -> Unit,
  private val onUnavailable: (String) -> Unit
) : HotwordDetector {
  companion object {
    internal fun normalize(text: String) = text.lowercase(Locale.ROOT)
      .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    private val PHRASES = listOf(
      "hey saara", "hi saara", "ok saara", "okay saara", "hello saara", "arre saara", "are saara", "oye saara", "o saara",
      "hey sara", "hi sara", "ok sara", "okay sara", "hello sara", "arre sara", "are sara", "oye sara", "o sara",
      "हे सारा", "अरे सारा", "हाय सारा", "হে সারা", "আরে সারা"
    ).map { normalize(it) }
    private val BARE = setOf("saara", "sara", "सारा", "সারা").map { normalize(it) }.toSet()
    internal fun isWake(text: String): Boolean =
      text in BARE || PHRASES.any { text.contains(it) }
  }

  private val main = Handler(Looper.getMainLooper())
  private val am = c.getSystemService(AudioManager::class.java)
  private var recognizer: SpeechRecognizer? = null
  @Volatile private var running = false
  @Volatile private var generation = 0
  private var consecutiveErrors = 0
  private var quietGeneration = 0
  private var quietSystem = -1
  private var quietMusic = -1
  private val restart = Runnable { listen() }
  private val restoreQuiet = Runnable { restoreSystemSound() }
  private val watchdog = Runnable { if (running) { destroy(); schedule(600L) } }

  override fun isRunning() = running

  override fun start() {
    main.post {
      if (running) return@post
      if (!SpeechRecognizer.isRecognitionAvailable(c)) {
        onUnavailable("Wake phrase is unavailable because this phone has no speech recognition service.")
        return@post
      }
      running = true
      generation++
      consecutiveErrors = 0
      listen()
    }
  }

  override fun stop() {
    main.post {
      running = false
      generation++
      main.removeCallbacks(restart)
      main.removeCallbacks(watchdog)
      main.removeCallbacks(restoreQuiet)
      restoreSystemSound()
      destroy()
    }
  }

  private fun destroy() {
    val r = recognizer
    recognizer = null
    try { r?.cancel() } catch (_: Exception) {}
    try { r?.destroy() } catch (_: Exception) {}
  }

  private fun schedule(delay: Long) {
    if (!running) return
    main.removeCallbacks(restart)
    main.postDelayed(restart, delay)
  }

  private fun listen() {
    if (!running) return
    val r = recognizer ?: createRecognizer() ?: return
    quietStart()
    main.removeCallbacks(watchdog)
    main.postDelayed(watchdog, 15000L)
    try {
      r.startListening(intent())
    } catch (_: Exception) {
      destroy()
      schedule(1400L)
    }
  }

  private fun createRecognizer(): SpeechRecognizer? {
    destroy()
    val r = try { SpeechRecognizer.createSpeechRecognizer(c) } catch (_: Exception) { null }
    if (r == null) { fail("Wake phrase could not start on this phone."); return null }
    recognizer = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) { main.removeCallbacks(watchdog) }
      override fun onBeginningOfSpeech() { main.removeCallbacks(watchdog) }
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
      override fun onError(error: Int) {
        main.removeCallbacks(watchdog)
        if (!running) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
          fail("Microphone permission is missing, so the wake phrase cannot listen.")
          return
        }
        consecutiveErrors++
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) destroy()
        val delay = when (error) {
          SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1200L
          else -> (1300L + consecutiveErrors * 250L).coerceAtMost(4000L)
        }
        schedule(delay)
      }
      override fun onResults(results: Bundle?) { inspect(results, true) }
      override fun onPartialResults(partialResults: Bundle?) { inspect(partialResults, false) }
    })
    return r
  }

  private fun intent(): Intent {
    val lang = when (Prefs.plain(c).getString("lang", "auto")) {
      "bn" -> "bn-IN"
      "hi" -> "hi-IN"
      else -> Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
    }
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
    }
  }

  private fun inspect(bundle: Bundle?, finished: Boolean) {
    main.removeCallbacks(watchdog)
    if (!running) return
    val heard = normalize(bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ").orEmpty())
    if (heard.isNotEmpty() && isWake(heard)) {
      running = false
      val token = generation
      generation++
      main.removeCallbacks(restart)
      main.removeCallbacks(restoreQuiet)
      restoreSystemSound()
      destroy()
      main.postDelayed({ if (!isRunning() && token == generation - 1) onWake() }, 250L)
    } else if (finished) schedule(700L)
  }

  private fun quietStart() {
    if (!Prefs.on(c, "quiet_wake", true)) return
    try {
      if (quietSystem < 0) {
        quietSystem = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
        if (quietSystem > 0) am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
      }
      if (quietMusic < 0 && !am.isMusicActive) {
        quietMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (quietMusic > 0) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
      }
      quietGeneration++
      val token = quietGeneration
      main.removeCallbacks(restoreQuiet)
      main.postDelayed({ if (token == quietGeneration) restoreSystemSound() }, 650L)
    } catch (_: Exception) {}
  }

  private fun restoreSystemSound() {
    if (quietSystem >= 0) {
      val volume = quietSystem
      quietSystem = -1
      try { am.setStreamVolume(AudioManager.STREAM_SYSTEM, volume, 0) } catch (_: Exception) {}
    }
    if (quietMusic >= 0) {
      val volume = quietMusic
      quietMusic = -1
      try { am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) } catch (_: Exception) {}
    }
  }

  private fun fail(message: String) {
    running = false
    generation++
    main.removeCallbacks(restart)
    main.removeCallbacks(watchdog)
    main.removeCallbacks(restoreQuiet)
    restoreSystemSound()
    destroy()
    main.post { onUnavailable(message) }
  }
}
