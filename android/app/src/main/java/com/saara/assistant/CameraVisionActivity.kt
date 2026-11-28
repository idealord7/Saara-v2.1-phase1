package com.saara.assistant

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class CameraVisionActivity : AppCompatActivity() {
  private val main = Handler(Looper.getMainLooper())
  private val exec = Executors.newSingleThreadExecutor()
  private lateinit var preview: TextureView
  private lateinit var statusTv: TextView
  private lateinit var resultTv: TextView
  private lateinit var modeSpinner: Spinner
  private lateinit var cadenceSpinner: Spinner
  private lateinit var analyzeBtn: Button
  private lateinit var liveBtn: Button
  private var cam: CameraDevice? = null
  private var sess: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var camThread: android.os.HandlerThread? = null
  private var camHandler: Handler? = null
  private var front = false
  private var live = false
  private var analyzing = false
  private var lastFrameB64: String? = null
  private var lastFrameAt = 0L
  private var lastLiveSentAt = 0L
  private val REQ_CAM = 71
  private val REQ_PICK = 72
  private val REQ_CAPTURE = 73
  private var captureUri: android.net.Uri? = null

  private val modes = listOf(
    "General / Describe", "Object / Item / Product", "Face Recognition", "OCR / Text", "Handwriting / Math / Notes",
    "Translate", "Overlay / Screen Text Translation", "Invoice / Bill to Vyapar", "Day Book to AppSheet"
  )
  private val cadences = listOf("Manual", "1s", "2s", "5s")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = ScrollView(this).apply { setBackgroundColor(0xFF111214.toInt()) }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(20)) }
    root.addView(col)

    col.addView(TextView(this).apply { text = "Saara Camera Vision"; textSize = 22f; setTextColor(0xFFE3E3E3.toInt()) })
    statusTv = TextView(this).apply { text = "Live camera preview. Choose a mode and tap Describe or start Live."; textSize = 12f; setTextColor(0xFF9AA0A6.toInt()) }
    col.addView(statusTv)

    preview = TextureView(this)
    val pvWrap = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
    pvWrap.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.CENTER))
    col.addView(pvWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val flip = mkBtn("Flip") { front = !front; restartCamera() }
    val pick = mkBtn("Pick Image") { pickImage() }
    val capture = mkBtn("Capture") { capturePhoto() }
    row1.addView(flip, lpW(0, 1f)); row1.addView(space(8), lpW(dp(8), 0f)); row1.addView(pick, lpW(0, 1f)); row1.addView(space(8), lpW(dp(8), 0f)); row1.addView(capture, lpW(0, 1f))
    col.addView(row1, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })

    modeSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@CameraVisionActivity, android.R.layout.simple_spinner_dropdown_item, modes) }
    cadenceSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@CameraVisionActivity, android.R.layout.simple_spinner_dropdown_item, cadences); setSelection(1) }
    col.addView(label("Mode")); col.addView(modeSpinner)
    col.addView(label("Live cadence")); col.addView(cadenceSpinner)

    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    analyzeBtn = mkBtn("Describe / What do you see?") { analyzeCurrent("Describe what you see clearly and concisely.") }
    liveBtn = mkBtn("Start Live") { toggleLive() }
    row2.addView(analyzeBtn, lpW(0, 1f)); row2.addView(space(8), lpW(dp(8), 0f)); row2.addView(liveBtn, lpW(dp(120), 0f))
    col.addView(row2, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10) })

    val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row3.addView(mkBtn("Remember Face") { promptRememberFace() }, lpW(0, 1f))
    row3.addView(space(8), lpW(dp(8), 0f))
    row3.addView(mkBtn("Send to Chat") { sendLastToChat() }, lpW(0, 1f))
    col.addView(row3, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })

    resultTv = TextView(this).apply {
      text = "What Saara sees will appear here."
      textSize = 14f; setTextColor(0xFFE3E3E3.toInt()); setBackgroundColor(0xFF1E1F20.toInt())
      setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    col.addView(label("What Saara sees"))
    col.addView(resultTv, LinearLayout.LayoutParams(-1,-2))

    setContentView(root)
    preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { ensureCamera() }
      override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }
  }

  override fun onResume() { super.onResume(); if (preview.isAvailable) ensureCamera() }
  override fun onPause() { live = false; updateLiveBtn(); super.onPause() }
  override fun onDestroy() { stopCamera(); exec.shutdownNow(); super.onDestroy() }

  private fun label(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setPadding(0, dp(10), 0, dp(4)) }
  private fun mkBtn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2A2C2E.toInt()); setOnClickListener { f() } }
  private fun space(w: Int) = View(this)
  private fun lpW(w: Int, weight: Float) = LinearLayout.LayoutParams(if (w == 0) 0 else w, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun ensureCamera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM); return
    }
    startCamera()
  }

  private fun restartCamera() { stopCamera(); startCamera() }

  private fun startCamera() {
    if (!preview.isAvailable) return
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
    try {
      val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
      val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } ?: cm.cameraIdList.firstOrNull() ?: return
      val ch = cm.getCameraCharacteristics(id)
      val map = ch.get( CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val size = map?.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width <= 1280 && it.height <= 1280 }?.maxByOrNull { it.width * it.height }
      val w = size?.width ?: 1280; val h = size?.height ?: 720
      if (camThread == null) { camThread = android.os.HandlerThread("saara-vision-cam").also { it.start() }; camHandler = Handler(camThread!!.looper) }
      val handler = camHandler ?: return
      reader?.close(); reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2).apply {
        setOnImageAvailableListener({ r ->
          val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
          try {
            val now = System.currentTimeMillis()
            val b64 = jpegBase64(img, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, front)
            lastFrameB64 = b64; lastFrameAt = now
            maybeLiveAnalyze(now, b64)
          } catch (_: Exception) {} finally { try { img.close() } catch (_: Exception) {} }
        }, handler)
      }
      val st = preview.surfaceTexture ?: return
      st.setDefaultBufferSize(w, h)
      val surface = Surface(st)
      cm.openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
          cam = camera
          try {
            val targets = listOf(surface, reader!!.surface)
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(session: CameraCaptureSession) {
                sess = session
                try {
                  val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                  req.addTarget(surface); req.addTarget(reader!!.surface)
                  req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                  session.setRepeatingRequest(req.build(), null, handler)
                  main.post { statusTv.text = "Camera ready · ${if (front) "front" else "back"} · mode=${modes[modeSpinner.selectedItemPosition]}" }
                } catch (e: Exception) { main.post { statusTv.text = "Camera start failed: ${e.message}" } }
              }
              override fun onConfigureFailed(session: CameraCaptureSession) { main.post { statusTv.text = "Camera configure failed" } }
            }, handler)
          } catch (e: Exception) { main.post { statusTv.text = "Camera error: ${e.message}" } }
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cam = null }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); cam = null; main.post { statusTv.text = "Camera error $error" } }
      }, handler)
    } catch (e: Exception) { statusTv.text = "Camera error: ${e.message}" }
  }

  private fun stopCamera() {
    try { sess?.close() } catch (_: Exception) {}
    try { cam?.close() } catch (_: Exception) {}
    try { reader?.close() } catch (_: Exception) {}
    sess = null; cam = null; reader = null
    try { camThread?.quitSafely() } catch (_: Exception) {}
    camThread = null; camHandler = null
  }

  private fun cadenceMs(): Long = when (cadences.getOrNull(cadenceSpinner.selectedItemPosition) ?: "Manual") {
    "1s" -> 1000L; "2s" -> 2000L; "5s" -> 5000L; else -> Long.MAX_VALUE
  }

  private fun toggleLive() {
    live = !live
    if (live && cadenceMs() == Long.MAX_VALUE) cadenceSpinner.setSelection(1)
    updateLiveBtn()
    statusTv.text = if (live) "Live vision ON · cadence ${cadences[cadenceSpinner.selectedItemPosition]}" else "Live vision OFF"
  }
  private fun updateLiveBtn() { liveBtn.text = if (live) "Stop Live" else "Start Live" }

  private fun maybeLiveAnalyze(now: Long, b64: String) {
    if (!live || analyzing) return
    val every = cadenceMs(); if (every == Long.MAX_VALUE) return
    if (now - lastLiveSentAt < every) return
    lastLiveSentAt = now
    analyzeB64(b64, promptForMode(), liveCaption = true)
  }

  private fun promptForMode(extra: String = ""): String {
    val mode = modes.getOrNull(modeSpinner.selectedItemPosition) ?: modes.first()
    val base = when (mode) {
      "Object / Item / Product" -> "Identify visible objects, tools, spare parts, or products. Describe what each item is, likely use, and any visible brand/model/condition clues. Be clear if uncertain."
      "Face Recognition" -> "Describe the visible person(s) without guessing private identity. If a user-provided enrolled face label context is given, compare carefully and say if it likely matches or not. Never claim certainty from a weak match."
      "OCR / Text" -> "Extract all visible printed text accurately. Preserve layout where useful. Mask sensitive numbers partially."
      "Handwriting / Math / Notes" -> "Extract handwritten text accurately. If math expressions are present, solve them step by step briefly and give final answer. If notes, format clearly."
      "Translate" -> "Extract visible text and provide the original text and an English translation. If already English, translate to the user's likely local language only if obvious; otherwise explain briefly."
      "Overlay / Screen Text Translation" -> "Extract on-screen/overlay text and translate it clearly. Keep original and translated text separated."
      "Invoice / Bill to Vyapar" -> "Extract invoice/bill details as JSON in text with keys: type, vendor, date, items[{name,qty,rate,tax,amount}], subtotal,tax,total,currency,notes. Also give a short reconciliation summary. Do not make up missing values."
      "Day Book to AppSheet" -> "Extract day-book/staff sales entries as JSON in text with keys: date, entries[{staff,items[{name,qty,amount}],total}], grand_total,currency,notes. Compute per-staff totals and grand total if visible. Do not invent missing values."
      else -> "Describe what you see clearly and concisely. Mention important objects, text, people count (no identity guessing), and actionable observations."
    }
    return if (extra.isBlank()) base else "$base\n\nUser request: $extra"
  }

  private fun analyzeCurrent(extra: String) {
    val b64 = lastFrameB64
    if (b64 == null) { Toast.makeText(this, "No frame yet. Wait for camera or pick an image.", Toast.LENGTH_SHORT).show(); return }
    analyzeB64(b64, promptForMode(extra), liveCaption = false)
  }

  private fun analyzeB64(b64: String, prompt: String, liveCaption: Boolean) {
    if (analyzing) return
    analyzing = true
    main.post { if (!liveCaption) statusTv.text = "Analyzing..." }
    val mode = modes.getOrNull(modeSpinner.selectedItemPosition) ?: modes.first()
    val finalPrompt = if (mode == "Face Recognition") enrichFacePrompt(prompt) else prompt
    ScreenAnalyzer.geminiVision(this, finalPrompt, b64) { raw ->
      val clean = raw.trim()
      Db.get(this).visionEventAdd(mode, clean.take(300), clean)
      main.post {
        analyzing = false
        resultTv.text = clean.ifBlank { "No analysis returned." }
        if (!liveCaption) statusTv.text = "Analysis complete · $mode"
        if (mode == "Invoice / Bill to Vyapar") maybeOfferVyapar(clean)
        if (mode == "Day Book to AppSheet") maybeOfferAppSheet(clean)
      }
    }
  }

  private fun enrichFacePrompt(base: String): String {
    val faces = try { Db.get(this).faces().take(8) } catch (_: Exception) { emptyList() }
    if (faces.isEmpty()) return "$base\n\nNo enrolled faces are stored locally. If asked to remember, user must enroll explicitly."
    val labels = faces.joinToString(", ") { it.label }
    return "$base\n\nLocally enrolled face labels (user-enrolled): $labels. Compare only against these labels if the user asked who this is. If uncertain, say uncertain."
  }

  private fun pickImage() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    startActivityForResult(i, REQ_PICK)
  }

  private fun capturePhoto() {
    val cv = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "saara_vision_${System.currentTimeMillis()}.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    captureUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
    val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
    try { startActivityForResult(i, REQ_CAPTURE) } catch (_: Exception) { Toast.makeText(this, "Camera capture unavailable", Toast.LENGTH_SHORT).show() }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ_PICK && resultCode == Activity.RESULT_OK) {
      val uri = data?.data ?: return
      try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
      loadUri(uri)
    }
    if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK) {
      captureUri?.let { loadUri(it) }
    }
  }

  private fun loadUri(uri: android.net.Uri) {
    exec.execute {
      try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@execute
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
        val scaled = scaleBmp(bmp, 1280)
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        lastFrameB64 = b64; lastFrameAt = System.currentTimeMillis()
        main.post { statusTv.text = "Image loaded. Tap Describe or choose a mode."; resultTv.text = "Image ready for analysis." }
      } catch (e: Exception) { main.post { statusTv.text = "Image load failed: ${e.message}" } }
    }
  }

  private fun promptRememberFace() {
    val b64 = lastFrameB64 ?: run { Toast.makeText(this, "No image/frame available", Toast.LENGTH_SHORT).show(); return }
    val input = EditText(this).apply { hint = "Person name / label" }
    android.app.AlertDialog.Builder(this).setTitle("Remember this face").setMessage("This saves a local face label and image on this device for later recognition help. Only enroll with consent.")
      .setView(input)
      .setPositiveButton("Save") { _, _ ->
        val label = input.text.toString().trim()
        if (label.isBlank()) { Toast.makeText(this, "Enter a label", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
        saveFace(label, b64)
      }.setNegativeButton("Cancel", null).show()
  }

  private fun saveFace(label: String, b64: String) {
    exec.execute {
      try {
        val dir = File(filesDir, "faces").apply { mkdirs() }
        val f = File(dir, "face_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
        Db.get(this).faceAdd(label, f.absolutePath)
        Db.get(this).factAdd("people_faces", "Face enrolled locally: $label")
        main.post { Toast.makeText(this, "Face saved locally as $label", Toast.LENGTH_SHORT).show(); Bus.add("system", "Done: Remembered face as ${label.take(60)}", true) }
      } catch (e: Exception) { main.post { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() } }
    }
  }

  private fun sendLastToChat() {
    val b64 = lastFrameB64 ?: run { Toast.makeText(this, "No image/frame available", Toast.LENGTH_SHORT).show(); return }
    val summary = resultTv.text?.toString()?.trim().orEmpty().take(500)
    val msg = buildString {
      append("[Photo sent from Camera Vision]")
      if (summary.isNotBlank() && summary != "What Saara sees will appear here." && summary != "Image ready for analysis.") append("\n").append(summary.take(400))
    }
    Bus.add("you", "[Photo] Camera Vision image", true)
    Bus.add("saara", if (summary.isNotBlank()) summary.take(800) else "Received image. Ask me what you want me to analyze.", true)
    Db.get(this).visionEventAdd("shared_to_chat", msg, b64.take(2000))
    Toast.makeText(this, "Sent to chat", Toast.LENGTH_SHORT).show()
  }

  private fun maybeOfferVyapar(analysis: String) {
    val pkg = "com.vyapar.app"
    android.app.AlertDialog.Builder(this).setTitle("Vyapar entry").setMessage("I extracted bill/invoice details. Open Vyapar and help enter it? Financial writes should be reviewed before submitting.")
      .setPositiveButton("Open Vyapar") { _, _ ->
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_VIEW)
        try { startActivity(i) } catch (_: Exception) { Toast.makeText(this, "Vyapar not installed", Toast.LENGTH_SHORT).show() }
        Bus.add("system", "Done: Prepared invoice summary for Vyapar review", true)
        Db.get(this).factAdd("billing", "Invoice analysis: ${analysis.take(1200)}")
      }
      .setNegativeButton("Not now", null).show()
  }

  private fun maybeOfferAppSheet(analysis: String) {
    val webhook = Prefs.plain(this).getString("appsheet_webhook_url", "").orEmpty().trim()
    val msg = if (webhook.isBlank()) "I extracted day-book/staff entries. Configure an AppSheet webhook URL in Settings to submit automatically, or copy the summary for manual entry." else "I extracted day-book/staff entries. Submit to configured AppSheet webhook? Review before external write."
    android.app.AlertDialog.Builder(this).setTitle("AppSheet day-book").setMessage(msg)
      .setPositiveButton(if (webhook.isBlank()) "OK" else "Submit") { _, _ ->
        if (webhook.isBlank()) {
          Bus.add("system", "Done: Day-book extracted. Configure AppSheet webhook to submit.", true)
        } else {
          submitAppSheet(webhook, analysis)
        }
        Db.get(this).factAdd("daybook", "Day-book analysis: ${analysis.take(1200)}")
      }
      .setNegativeButton("Cancel", null).show()
  }

  private fun submitAppSheet(webhook: String, analysis: String) {
    exec.execute {
      try {
        val client = okhttp3.OkHttpClient()
        val body = JSONObject().put("type", "daybook").put("analysis", analysis.take(8000)).put("ts", System.currentTimeMillis()).toString().toRequestBody("application/json".toMediaType())
        val req = okhttp3.Request.Builder().url(webhook).post(body).build()
        client.newCall(req).execute().use { resp ->
          main.post { Bus.add("system", if (resp.isSuccessful) "Done: Submitted day-book to AppSheet webhook" else "Couldn't complete AppSheet submit: HTTP ${resp.code}", true) }
        }
      } catch (e: Exception) { main.post { Bus.add("system", "Couldn't complete AppSheet submit: ${e.message?.take(120)}", true) } }
    }
  }

  private fun scaleBmp(src: Bitmap, maxDim: Int): Bitmap {
    val w = src.width; val h = src.height; val m = maxOf(w, h)
    if (m <= maxDim) return src
    val scale = maxDim.toFloat() / m.toFloat()
    return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
  }

  private fun jpegBase64(img: android.media.Image, sensorOrientation: Int, mirror: Boolean): String {
    val w = img.width; val h = img.height
    val yuv = YuvImage(nv21(img), ImageFormat.NV21, w, h, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, w, h), 75, out)
    var bytes = out.toByteArray()
    if (sensorOrientation != 0 || mirror) {
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      val m = Matrix(); m.postRotate(sensorOrientation.toFloat()); if (mirror) m.postScale(-1f, 1f)
      val rb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
      val out2 = ByteArrayOutputStream(); rb.compress(Bitmap.CompressFormat.JPEG, 75, out2)
      bytes = out2.toByteArray(); bmp.recycle(); rb.recycle()
    }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  private fun nv21(img: android.media.Image): ByteArray {
    val w = img.width; val h = img.height
    val out = ByteArray(w * h * 3 / 2)
    val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
    var pos = 0
    for (row in 0 until h) {
      val off = row * yp.rowStride
      for (col in 0 until w) out[pos++] = yp.buffer.get(off + col * yp.pixelStride)
    }
    for (row in 0 until h / 2) {
      for (col in 0 until w / 2) {
        out[pos++] = vp.buffer.get(row * vp.rowStride + col * vp.pixelStride)
        out[pos++] = up.buffer.get(row * up.rowStride + col * up.pixelStride)
      }
    }
    return out
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQ_CAM && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
  }
}