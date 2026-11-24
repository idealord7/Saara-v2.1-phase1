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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

object ScreenCapture {
  fun captureOnce(ctx: Context, code: Int, data: Intent, timeoutMs: Long = 4500L): ByteArray? {
    val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    var mp: MediaProjection? = null
    var vd: VirtualDisplay? = null
    var reader: ImageReader? = null
    var thread: HandlerThread? = null
    val latch = CountDownLatch(1)
    var out: ByteArray? = null
    try {
      val p = mgr.getMediaProjection(code, data) ?: return null
      mp = p
      val t = HandlerThread("saara-screen-capture").also { it.start() }; thread = t
      val handler = Handler(t.looper)
      p.registerCallback(object : MediaProjection.Callback() { override fun onStop() {} }, handler)
      val dm = ctx.resources.displayMetrics
      val sc = 1080f / max(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
      val w = ((dm.widthPixels * sc).toInt().coerceAtLeast(320)) and 1.inv()
      val h = ((dm.heightPixels * sc).toInt().coerceAtLeast(320)) and 1.inv()
      val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2); reader = r
      r.setOnImageAvailableListener({ rd ->
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
        try {
          if (out != null) return@setOnImageAvailableListener
          val pl = img.planes[0]
          val rowPx = pl.rowStride / pl.pixelStride
          val bmp = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888)
          bmp.copyPixelsFromBuffer(pl.buffer)
          val crop = if (rowPx != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
          val bos = ByteArrayOutputStream()
          crop.compress(Bitmap.CompressFormat.JPEG, 80, bos)
          if (crop !== bmp) crop.recycle(); bmp.recycle()
          out = bos.toByteArray()
          latch.countDown()
        } catch (_: Exception) {} finally { try { img.close() } catch (_: Exception) {} }
      }, handler)
      vd = p.createVirtualDisplay("saara-capture", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
      latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
    } finally {
      try { vd?.release() } catch (_: Exception) {}
      try { reader?.close() } catch (_: Exception) {}
      try { mp?.stop() } catch (_: Exception) {}
      try { thread?.quitSafely() } catch (_: Exception) {}
    }
    return out
  }
}
