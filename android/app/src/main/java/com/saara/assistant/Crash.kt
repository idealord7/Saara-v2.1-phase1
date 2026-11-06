package com.saara.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes uncaught-exception reports to Downloads so they can be read without ADB. */
object Crash {
  @Synchronized
  fun save(c: Context, t: Throwable, tag: String) {
    val name = "SaaraCrash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
    val body = buildString {
      appendLine(tag)
      appendLine("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date()))
      appendLine("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
      appendLine("package: " + c.packageName)
      appendLine()
      val sw = StringWriter()
      t.printStackTrace(PrintWriter(sw))
      append(sw.toString())
    }
    val bytes = body.toByteArray(Charsets.UTF_8)
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        val values = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, name)
          put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val cr = c.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
          cr.openOutputStream(uri)?.use { it.write(bytes) }
          return
        }
      } catch (_: Throwable) {}
    }
    try {
      val dir = c.getExternalFilesDir(null) ?: c.filesDir
      File(dir, name).writeBytes(bytes)
    } catch (_: Throwable) {}
  }
}
