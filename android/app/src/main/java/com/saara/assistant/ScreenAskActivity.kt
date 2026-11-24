package com.saara.assistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Transparent helper for screen-capture permission and on-demand screen context capture. */
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
      val auto = intent?.getBooleanExtra("auto", false) == true
      val mode = intent?.getStringExtra("mode") ?: "share" // share | capture | ocr
      if (resultCode == RESULT_OK && data != null) {
        when (mode) {
          "capture", "ocr" -> {
            Bus.add("system", if (mode == "ocr") "Capturing screen for OCR…" else "Capturing screen context…", true)
            startService(Intent(this, VoiceService::class.java).setAction("SCREEN_CAPTURE").putExtra("code", resultCode).putExtra("data", data).putExtra("mode", mode))
          }
          else -> {
            Bus.add("system", if (auto) "Screen sharing allowed for this task. Starting live frames…" else "Screen sharing allowed. Starting live frames…", true)
            startService(Intent(this, VoiceService::class.java).setAction("SCREEN_START").putExtra("code", resultCode).putExtra("data", data).putExtra("auto", auto))
          }
        }
      } else {
        Bus.add("system", "Screen sharing/capture was not allowed.", true)
      }
    }
    finish()
    overridePendingTransition(0, 0)
  }
}
