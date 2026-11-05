package com.saara.assistant
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/** Draggable floating bubble; tap opens the app. */
class Bubble(private val c: Context, private val onTap: () -> Unit) {
  private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var v: ImageView? = null
  fun show() {
    if (v != null || !Settings.canDrawOverlays(c)) return
    val d = c.resources.displayMetrics.density
    val size = (56 * d).toInt()
    val t = ImageView(c)
    t.setImageResource(R.drawable.logo_symbol); t.scaleType = ImageView.ScaleType.CENTER_CROP
    t.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFF8FAF9.toInt()) }
    t.clipToOutline = true
    val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    lp.gravity = Gravity.TOP or Gravity.START; lp.x = (16 * d).toInt(); lp.y = (120 * d).toInt()
    var ix = 0; var iy = 0; var sx = 0f; var sy = 0f; var moved = false
    t.setOnTouchListener { _, e ->
      when (e.action) {
        MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; sx = e.rawX; sy = e.rawY; moved = false }
        MotionEvent.ACTION_MOVE -> {
          val dx = e.rawX - sx; val dy = e.rawY - sy
          if (abs(dx) > 10 || abs(dy) > 10) moved = true
          lp.x = ix + dx.toInt(); lp.y = iy + dy.toInt(); wm.updateViewLayout(t, lp)
        }
        MotionEvent.ACTION_UP -> if (!moved) onTap()
      }
      true
    }
    try { wm.addView(t, lp); v = t } catch (_: Exception) { v = null }
  }
  fun hide() { v?.let { try { wm.removeView(it) } catch (_: Exception) {} }; v = null }
}
