package com.saara.assistant

/** Common contract for the wake-word listener used by the assistant. */
interface HotwordDetector {
  fun start()
  fun stop()
  fun isRunning(): Boolean
}
