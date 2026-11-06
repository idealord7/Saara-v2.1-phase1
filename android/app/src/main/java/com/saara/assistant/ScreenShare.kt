package com.saara.assistant
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Live screen sharing through MediaProjection: ~1 JPEG frame per second sent to Gemini. */
class ScreenShare(private val c: Context, private val onFrame: (ByteArray) -> Unit, private val onStopped: () -> Unit) {
  private var mp: MediaProjection? = null
  private var vd: VirtualDisplay? = null
  private var reader: ImageReader? = null
  private var thread: HandlerThread? = null
  private var lastSent = 0L

  fun start(code: Int, data: Intent): String? {
    stop()
    return try {
      val mgr = c.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      val p = mgr.getMediaProjection(code, data) ?: return "screen capture was not allowed"
      mp = p
      val t = HandlerThread("saara-screen").also { it.start() }; thread = t
      val handler = Handler(t.looper)
      p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { release(); onStopped() } }, handler)
      val dm = c.resources.displayMetrics
      val sc = 720f / max(dm.widthPixels, dm.heightPixels)
      val w = (dm.widthPixels * sc).toInt() and 1.inv(); val h = (dm.heightPixels * sc).toInt() and 1.inv()
      val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = r
      r.setOnImageAvailableListener({ rd ->
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
          try {
            val now = System.currentTimeMillis()
            if (now - lastSent >= 1000) {
              lastSent = now
              val pl = img.planes[0]
              val rowPx = pl.rowStride / pl.pixelStride
              val bmp = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
              bmp.copyPixelsFromBuffer(pl.buffer)
              val crop = if (rowPx != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
              val o = ByteArrayOutputStream(); crop.compress(Bitmap.CompressFormat.JPEG, 60, o)
              if (crop !== bmp) crop.recycle(); bmp.recycle()
              onFrame(o.toByteArray())
            }
          } catch (e: Exception) {} finally { img.close() }
        }
      }, handler)
      vd = p.createVirtualDisplay("saara-screen", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
      null
    } catch (e: Exception) { "screen share: ${e.message}" }
  }

  private fun release() {
    try { vd?.release() } catch (_: Exception) {}
    try { reader?.close() } catch (_: Exception) {}
    try { thread?.quitSafely() } catch (_: Exception) {}
    vd = null; reader = null; thread = null
  }

  fun stop() {
    val p = mp; mp = null
    release()
    try { p?.stop() } catch (_: Exception) {}
  }
}
