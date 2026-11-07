package com.saara.assistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Offline "Hey Saara" detector powered by Picovoice Porcupine.
 *
 * Put the custom keyword file at assets/keywords/hey_saara.ppn (train and download it
 * from https://console.picovoice.ai). If the file is missing this falls back to the
 * built-in "Jarvis" keyword so the app still works out of the box.
 */
class PorcupineWakeWord(
  private val c: Context,
  private val accessKey: String,
  private val onWake: () -> Unit,
  private val onUnavailable: (String) -> Unit,
  private val onNotice: (String) -> Unit = {},
) : HotwordDetector {

  companion object {
    private const val CUSTOM_FILE = "hey_saara.ppn"
    private const val CUSTOM_PATH = "keywords/$CUSTOM_FILE"
    @Volatile private var warnedMissingCustom = false
  }

  private val main = Handler(Looper.getMainLooper())

  @Volatile private var manager: PorcupineManager? = null
  @Volatile private var running = false

  override fun isRunning() = running

  override fun start() {
    main.post {
      if (running) return@post
      if (accessKey.isBlank()) {
        onUnavailable("Picovoice AccessKey is missing. Add it in Settings · Offline wake word.")
        return@post
      }
      val hasCustom = runCatching {
        c.assets.list("keywords")?.contains(CUSTOM_FILE) == true
      }.getOrDefault(false)
      if (!hasCustom && !warnedMissingCustom) {
        warnedMissingCustom = true
        onNotice("Custom \"Hey Saara\" model not found, so the built-in \"Jarvis\" keyword is active. Add assets/keywords/$CUSTOM_FILE to enable \"Hey Saara\".")
      }
      try {
        val m = try {
          buildManager(hasCustom)
        } catch (e: PorcupineException) {
          // A missing/corrupt .ppn must not leave the user with a silent assistant.
          if (!hasCustom) throw e
          onNotice("The \"Hey Saara\" model could not load, so the built-in \"Jarvis\" keyword is active instead.")
          buildManager(false)
        }
        manager = m
        running = true
        m.start()
      } catch (e: PorcupineException) {
        running = false
        manager = null
        onUnavailable(e.message ?: "Wake word engine could not start.")
      } catch (e: Exception) {
        running = false
        manager = null
        onUnavailable("Wake word engine could not start: ${e.message}")
      }
    }
  }

  private fun buildManager(custom: Boolean): PorcupineManager {
    val builder = PorcupineManager.Builder().setAccessKey(accessKey).setSensitivity(0.6f)
    if (custom) builder.setKeywordPath(CUSTOM_PATH) else builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
    return builder.build(c) { keywordIndex -> if (keywordIndex == 0) wake() }
  }

  private fun wake() {
    // Porcupine invokes the callback on its own audio thread. Release the engine
    // before handing the microphone to the Gemini Live session.
    main.post {
      if (!running) return@post
      release()
      onWake()
    }
  }

  override fun stop() {
    main.post { release() }
  }

  private fun release() {
    running = false
    val m = manager ?: return
    manager = null
    try { m.stop() } catch (_: Exception) {}
    try { m.delete() } catch (_: Exception) {}
  }
}
