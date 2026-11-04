package com.saara.assistant
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/** Streams the phone camera to Gemini as ~1 JPEG frame per second (Camera2, no preview needed). */
class CamShare(private val c: Context, private val onFrame: (ByteArray) -> Unit, private val fail: (String) -> Unit) {
  private var dev: CameraDevice? = null
  private var sess: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var thread: HandlerThread? = null
  private var lastSent = 0L
  private var rotation = 0
  private var mirror = false

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  fun start(front: Boolean): String? {
    stop()
    return try {
      val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
      val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
        ?: return "no ${if (front) "front" else "back"} camera on this phone"
      val ch = cm.getCameraCharacteristics(id)
      rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
      mirror = front
      val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val size = map?.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width <= 800 && it.height <= 800 }?.maxByOrNull { it.width * it.height }
      val w = size?.width ?: 640; val h = size?.height ?: 480
      val t = HandlerThread("saara-cam").also { it.start() }; thread = t
      val handler = Handler(t.looper)
      val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2); reader = r
      r.setOnImageAvailableListener({ rd ->
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
          try {
            val now = System.currentTimeMillis()
            if (now - lastSent >= 1000) { lastSent = now; onFrame(jpeg(img)) }
          } catch (e: Exception) {} finally { img.close() }
        }
      }, handler)
      cm.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
          dev = d
          try {
            d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(s: CameraCaptureSession) {
                sess = s
                try {
                  val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                  rb.addTarget(r.surface)
                  s.setRepeatingRequest(rb.build(), null, handler)
                } catch (e: Exception) { fail("camera: ${e.message}") }
              }
              override fun onConfigureFailed(s: CameraCaptureSession) { fail("camera could not start") }
            }, handler)
          } catch (e: Exception) { fail("camera: ${e.message}") }
        }
        override fun onDisconnected(d: CameraDevice) { d.close(); dev = null; fail("camera disconnected (another app may be using it)") }
        override fun onError(d: CameraDevice, error: Int) { d.close(); dev = null; fail("camera error $error") }
      }, handler)
      null
    } catch (e: Exception) { "camera: ${e.message}" }
  }

  fun stop() {
    try { sess?.close() } catch (_: Exception) {}
    try { dev?.close() } catch (_: Exception) {}
    try { reader?.close() } catch (_: Exception) {}
    try { thread?.quitSafely() } catch (_: Exception) {}
    sess = null; dev = null; reader = null; thread = null
  }

  private fun nv21(img: Image): ByteArray {
    val w = img.width; val h = img.height
    val out = ByteArray(w * h * 3 / 2)
    val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
    var pos = 0
    for (row in 0 until h) { val off = row * yp.rowStride; for (col in 0 until w) out[pos++] = yp.buffer.get(off + col * yp.pixelStride) }
    for (row in 0 until h / 2) for (col in 0 until w / 2) {
      out[pos++] = vp.buffer.get(row * vp.rowStride + col * vp.pixelStride)
      out[pos++] = up.buffer.get(row * up.rowStride + col * up.pixelStride)
    }
    return out
  }

  private fun jpeg(img: Image): ByteArray {
    val w = img.width; val h = img.height
    val bos = ByteArrayOutputStream()
    YuvImage(nv21(img), ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 65, bos)
    var bytes = bos.toByteArray()
    if (rotation != 0 || mirror) {
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      val m = Matrix(); m.postRotate(rotation.toFloat()); if (mirror) m.postScale(-1f, 1f)
      val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
      val o = ByteArrayOutputStream(); rb.compress(Bitmap.CompressFormat.JPEG, 65, o); bytes = o.toByteArray()
      bmp.recycle(); rb.recycle()
    }
    return bytes
  }
}
