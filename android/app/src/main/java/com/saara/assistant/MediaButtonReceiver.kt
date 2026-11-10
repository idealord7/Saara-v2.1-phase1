package com.saara.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Wakes Saara when a Bluetooth headset/media button is pressed.
 * Triggered via ACTION_MEDIA_BUTTON (headset hook / play-pause etc).
 */
class MediaButtonReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
    @Suppress("DEPRECATION")
    val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
    if (event.action != KeyEvent.ACTION_DOWN) return
    when (event.keyCode) {
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK,
      KeyEvent.KEYCODE_MEDIA_NEXT,
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> wake(context)
    }
  }

  private fun wake(context: Context) {
    try {
      val i = Intent(context, VoiceService::class.java)
        .setAction(VoiceService.ACTION_WAKE)
        .putExtra(VoiceService.ASSISTANT_WAKE, true)
      ContextCompat.startForegroundService(context, i)
    } catch (_: Exception) {}
  }
}
