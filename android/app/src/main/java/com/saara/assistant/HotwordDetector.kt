package com.saara.assistant

/** Common contract for wake-word engines so Porcupine and the SpeechRecognizer fallback are interchangeable. */
interface HotwordDetector {
  fun start()
  fun stop()
  fun isRunning(): Boolean
}
