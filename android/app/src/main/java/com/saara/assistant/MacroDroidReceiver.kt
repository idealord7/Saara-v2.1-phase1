package com.saara.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Intent-based execution bridge for MacroDroid / third-party automations.
 *
 * MacroDroid "Send Intent" template:
 *   Action: com.saara.ACTION_EXECUTE
 *   Package: com.saara.assistant (optional but recommended)
 *   Target: Broadcast Receiver
 *   Extra: action_text:<command> (String)
 */
class MacroDroidReceiver : BroadcastReceiver() {
  companion object {
    const val TAG = "SaaraIntentReceiver"
    const val ACTION_EXECUTE = "com.saara.ACTION_EXECUTE"
    const val EXTRA_ACTION_TEXT = "action_text"
    private const val ASSISTANT_COMMAND_EXTRA = "saara_command"

    fun dispatch(context: Context, actionText: String): Boolean {
      val text = actionText.trim()
      if (text.isEmpty()) {
        Log.w(TAG, "Ignored dispatch: action_text is empty")
        return false
      }
      Log.d(TAG, "Dispatching action_text (len=${text.length}): ${text.take(120)}")
      val i = Intent(context, VoiceService::class.java)
        .putExtra(ASSISTANT_COMMAND_EXTRA, text)
        .putExtra(EXTRA_ACTION_TEXT, text)
      try {
        ContextCompat.startForegroundService(context, i)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start VoiceService for intent execution", e)
        return false
      }
      return true
    }

    /** Internal/test helper: send the same broadcast Saara receives from MacroDroid. */
    fun sendTestBroadcast(context: Context, actionText: String) {
      val i = Intent(ACTION_EXECUTE).setPackage(context.packageName)
        .putExtra(EXTRA_ACTION_TEXT, actionText)
      Log.d(TAG, "Sending internal test broadcast")
      context.sendBroadcast(i)
    }
  }

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ACTION_EXECUTE) {
      Log.d(TAG, "Ignored intent action=${intent?.action}")
      return
    }
    val actionText = intent.getStringExtra(EXTRA_ACTION_TEXT)?.trim().orEmpty()
    Log.d(TAG, "Received ACTION_EXECUTE from=${intent.`package` ?: "unknown"} extras=${intent.extras?.keySet()?.joinToString()}")
    if (actionText.isEmpty()) {
      Log.w(TAG, "Rejected execution: missing/empty extra '$EXTRA_ACTION_TEXT'")
      return
    }
    dispatch(context, actionText)
  }
}
