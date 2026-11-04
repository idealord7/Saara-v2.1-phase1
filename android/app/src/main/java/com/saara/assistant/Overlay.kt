package com.saara.assistant
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Click-through, semi-transparent caption shown over every app: what Saara hears, says and does. */
class Overlay(private val c: Context) {
  private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())
  private var v: TextView? = null
  private val hideRun = Runnable { hide() }

  fun show(text: String) {
    if (!Settings.canDrawOverlays(c)) return
    var t = v
    if (t == null) {
      t = TextView(c)
      t.setTextColor(Color.WHITE); t.textSize = 15f; t.maxLines = 6; t.gravity = Gravity.START
      val d = c.resources.displayMetrics.density
      t.setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
      t.background = GradientDrawable().apply { setColor(0x8C000000.toInt()); cornerRadius = 18 * d }
      t.setShadowLayer(4f, 0f, 1f, Color.BLACK)
      val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT)
      lp.gravity = Gravity.BOTTOM; lp.y = (96 * d).toInt(); lp.horizontalMargin = 0.04f
      try { wm.addView(t, lp) } catch (e: Exception) { return }
      v = t
    }
    t.text = text
    main.removeCallbacks(hideRun); main.postDelayed(hideRun, 7000)
  }

  fun hide() {
    main.removeCallbacks(hideRun)
    v?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    v = null
  }
}
