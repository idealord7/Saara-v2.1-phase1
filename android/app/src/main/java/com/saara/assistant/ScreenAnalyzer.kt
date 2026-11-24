package com.saara.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ScreenAnalyzer {
  private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

  fun summarize(c: Context, imageB64: String, cb: (String) -> Unit) {
    val prompt = "You are Saara Screen Intelligence. Analyze this mobile screenshot. Return concise plain text with: 1) What is on screen, 2) active app/context, 3) key fields/messages/errors, 4) suggested next 1-3 actions. If sensitive data appears, mask it partially."
    geminiVision(c, prompt, imageB64, cb)
  }

  fun extractForm(c: Context, imageB64: String, cb: (String) -> Unit) {
    val prompt = """Extract structured data from this screenshot. Return ONLY valid JSON with shape:
{"type":"invoice|bill|form|message|unknown","fields":[{"label":"...","value":"...","confidence":0-1}],"summary":"short summary","suggested_actions":["..."]}
Mask sensitive numbers partially. If no fields, return empty fields array."""
    geminiVision(c, prompt, imageB64) { raw ->
      val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
      try {
        val j = JSONObject(cleaned)
        val fields = j.optJSONArray("fields") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("Screen OCR/Form extraction: ").append(j.optString("type", "unknown")).append('\n')
        sb.append(j.optString("summary").take(300)).append('\n')
        for (i in 0 until fields.length().coerceAtMost(30)) {
          val f = fields.optJSONObject(i) ?: continue
          sb.append("- ").append(f.optString("label").ifBlank { "field" }).append(": ").append(f.optString("value")).append('\n')
        }
        val acts = j.optJSONArray("suggested_actions")
        if (acts != null && acts.length() > 0) {
          sb.append("Suggested: ")
          for (i in 0 until acts.length().coerceAtMost(5)) sb.append(acts.optString(i)).append(if (i < acts.length()-1) "; " else "")
        }
        cb(sb.toString().trim().ifBlank { raw })
      } catch (_: Exception) {
        cb(raw)
      }
    }
  }

  fun geminiVision(c: Context, prompt: String, imageB64: String, cb: (String) -> Unit) {
    Thread {
      val key = Prefs.secure(c).getString("apiKey", "") ?: ""
      if (key.isBlank()) { cb("Gemini API key missing. Set it in Settings."); return@Thread }
      val model = Prefs.plain(c).getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
      // Use text model endpoint for image understanding; fallback to configured model.
      val visionModel = if (model.contains("live", true)) "gemini-2.5-flash" else model
      val body = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
          .put(JSONObject().put("text", prompt))
          .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageB64))))))
        .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 900))
      val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$visionModel:generateContent?key=$key")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
      try {
        http.newCall(req).execute().use { resp ->
          val s = resp.body?.string().orEmpty()
          if (!resp.isSuccessful) {
            val msg = try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s } catch (_: Exception) { s }
            cb("Screen analysis failed: HTTP ${resp.code} ${msg.take(220)}")
            return@use
          }
          val j = JSONObject(s)
          val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
          val sb = StringBuilder()
          if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
          cb(sb.toString().trim().ifBlank { "No screen analysis returned." })
        }
      } catch (e: Exception) {
        cb("Screen analysis error: ${e.message}")
      }
    }.start()
  }
}
