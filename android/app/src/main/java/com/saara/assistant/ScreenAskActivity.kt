package com.saara.assistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Transparent helper that asks Android for screen-capture permission when a task needs it. */
class ScreenAskActivity : Activity() {
  @Suppress("DEPRECATION")
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    Bus.init(this)
    try {
      val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      startActivityForResult(mpm.createScreenCaptureIntent(), 10)
    } catch (t: Throwable) {
      Crash.save(this, t, "ScreenAskActivity.request")
      Bus.add("system", "Screen sharing could not start: ${t.message}", true)
      finish()
    }
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 10) {
      if (resultCode == RESULT_OK && data != null) {
        Bus.add("system", "Screen sharing allowed. Starting live frames…", true)
        startService(Intent(this, VoiceService::class.java).setAction("SCREEN_START").putExtra("code", resultCode).putExtra("data", data))
      } else {
        Bus.add("system", "Screen sharing was not allowed. Tap the Screen chip when you are ready.", true)
      }
    }
    finish()
  }
}
