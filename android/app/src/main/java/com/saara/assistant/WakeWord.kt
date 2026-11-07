package com.saara.assistant

import android.content.Context
import android.content.Intent
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
) {
  companion object {
    private fun normalize(text: String) = text.lowercase(Locale.ROOT)
      .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    private val PHRASES = listOf(
      "hey saara", "hi saara", "ok saara", "okay saara", "hello saara", "hey sara", "hi sara",
      "saara", "sara", "हे सारा", "सारा", "হে সারা", "সারা"
    ).map { normalize(it) }
  }

  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  @Volatile private var running = false
  @Volatile private var generation = 0
  private val restart = Runnable { begin() }

  fun isRunning() = running

  fun start() {
    main.post {
      if (running) return@post
      if (!SpeechRecognizer.isRecognitionAvailable(c)) {
        onUnavailable("Wake phrase is unavailable because this phone has no speech recognition service.")
        return@post
      }
      running = true
      generation++
      begin()
    }
  }

  fun stop() {
    main.post {
      running = false
      generation++
      main.removeCallbacksAndMessages(null)
      destroy()
    }
  }

  private fun destroy() {
    val r = recognizer
    recognizer = null
    try { r?.stopListening() } catch (_: Exception) {}
    try { r?.cancel() } catch (_: Exception) {}
    try { r?.destroy() } catch (_: Exception) {}
  }

  private fun schedule(delay: Long) {
    if (!running) return
    main.removeCallbacks(restart)
    main.postDelayed(restart, delay)
  }

  private fun begin() {
    if (!running) return
    destroy()
    val lang = when (Prefs.plain(c).getString("lang", "auto")) {
      "bn" -> "bn-IN"
      "hi" -> "hi-IN"
      else -> Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
    val r = try { SpeechRecognizer.createSpeechRecognizer(c) } catch (_: Exception) { null }
    if (r == null) { running = false; onUnavailable("Wake phrase could not start on this phone."); return }
    recognizer = r
    r.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
      override fun onError(error: Int) {
        schedule(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 450L else 1200L)
      }
      override fun onResults(results: Bundle?) { inspect(results, false) }
      override fun onPartialResults(partialResults: Bundle?) { inspect(partialResults, true) }
    })
    try { r.startListening(intent) } catch (_: Exception) { schedule(1500L) }
  }

  private fun inspect(bundle: Bundle?, partial: Boolean) {
    if (!running) return
    val heard = normalize(bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ").orEmpty())
    if (heard.isNotEmpty() && PHRASES.any { heard.contains(it) }) {
      running = false
      val token = generation
      main.removeCallbacks(restart)
      destroy()
      main.postDelayed({ if (!isRunning() && token == generation) onWake() }, 400L)
    } else if (!partial) schedule(450L)
  }
}
