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
      try {
        val preferred = Prefs.plain(c).getString("model_vision", "")?.trim()?.takeIf { it.isNotBlank() }
        val mr = ModelRouter.generateContentWithFallback(
          c, ModelTask.VISION, prompt, imageB64 = imageB64, preferredModel = preferred, temperature = 0.2, maxOutputTokens = 900
        )
        cb(mr.text.ifBlank { "No screen analysis returned." } + "\n\n[model: ${mr.model}]")
      } catch (e: Exception) {
        cb("Screen analysis error: ${e.message}")
      }
    }.start()
  }
}
