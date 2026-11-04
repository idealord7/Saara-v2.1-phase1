package com.saara.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Detects ringing calls only when the user has explicitly enabled the feature. */
class PhoneStateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
    if (!Prefs.on(context, "wake_on_call", false)) return
    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
    if (state == TelephonyManager.EXTRA_STATE_RINGING) {
      // Android restricts starting a microphone foreground service from a background
      // broadcast. If Saara is already active, it can safely ask the user by voice.
      VoiceService.instance?.onIncomingCall()
    }
  }
}
