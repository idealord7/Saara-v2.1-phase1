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
import kotlin.math.max
import kotlin.math.min

/** Compact click-through status/caption pill. Anchors beside floating bubble when available. */
class Overlay(private val c: Context) {
  data class Anchor(val x: Int, val y: Int, val size: Int, val onRight: Boolean)
  private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())
  private var v: TextView? = null
  private val hideRun = Runnable { hide() }

  fun show(text: String, persistent: Boolean = false, anchor: Anchor? = null) {
    if (!Settings.canDrawOverlays(c)) return
    val d = c.resources.displayMetrics.density
    val sw = c.resources.displayMetrics.widthPixels
    val sh = c.resources.displayMetrics.heightPixels
    var t = v
    if (t == null) {
      t = TextView(c)
      t.setTextColor(Color.WHITE); t.textSize = 12f; t.maxLines = 3; t.gravity = Gravity.START
      t.setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
      t.setShadowLayer(3f, 0f, 1f, Color.BLACK)
      val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT)
      lp.gravity = Gravity.TOP or Gravity.START
      try { wm.addView(t, lp) } catch (e: Exception) { return }
      v = t
    }
    val status = Bus.status.lowercase()
    val color = when {
      status.contains("retry") || status.contains("503") || status.contains("reconnect") -> 0xB3B26A00.toInt()
      status.contains("execut") || status.contains("working") -> 0xB31A73E8.toInt()
      status.contains("thinking") || status.contains("connect") -> 0xB36A1B9A.toInt()
      status.contains("listen") || status.contains("wake") -> 0xB31B5E20.toInt()
      else -> 0x99000000.toInt()
    }
    t.background = GradientDrawable().apply { setColor(color); cornerRadius = 16 * d; setStroke((1*d).toInt(), 0x55FFFFFF) }
    t.text = "Saara: ${Bus.status}\n${text.take(120)}"
    val lp = t.layoutParams as WindowManager.LayoutParams
    val margin = (8 * d).toInt()
    val maxW = if (anchor != null) {
      val sideSpace = if (anchor.onRight) anchor.x - margin * 2 else sw - (anchor.x + anchor.size) - margin * 2
      max(margin * 8, min((sw * 0.62f).toInt(), sideSpace))
    } else (sw * 0.72f).toInt()
    t.maxWidth = maxW
    t.measure(
      android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST),
      android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
    )
    val w = max(t.measuredWidth, margin * 6)
    val h = max(t.measuredHeight, (28 * d).toInt())
    if (anchor != null) {
      lp.x = if (anchor.onRight) max(margin, anchor.x - w - margin) else min(sw - w - margin, anchor.x + anchor.size + margin)
      val centerY = anchor.y + anchor.size / 2 - h / 2
      lp.y = max((24 * d).toInt(), min(sh - h - margin, centerY))
    } else {
      lp.x = sw - w - margin
      lp.y = (36 * d).toInt()
    }
    try { wm.updateViewLayout(t, lp) } catch (_: Exception) {}
    main.removeCallbacks(hideRun)
    if (!persistent) main.postDelayed(hideRun, 4500)
  }

  fun hide() {
    main.removeCallbacks(hideRun)
    v?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    v = null
  }
}
