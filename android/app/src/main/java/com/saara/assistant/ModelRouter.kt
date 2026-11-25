package com.saara.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ModelTask { AUTO_REPLY, LIVE_VOICE, VISION, COMPLEX, DEFAULT }

data class ModelAttempt(val model: String, val task: ModelTask, val ok: Boolean, val error: String = "", val ms: Long = 0L)
data class ModelResult(val text: String, val model: String, val attempts: List<ModelAttempt>)

object ModelRouter {
  private val jsonType = "application/json; charset=utf-8".toMediaType()
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

  val DEFAULT_LIVE = listOf(
    "gemini-3.1-flash-live-preview",
    "gemini-2.5-flash-native-audio-preview-12-2025"
  )
  val DEFAULT_TEXT_FAST = listOf(
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-3-flash-preview",
    "gemini-2.5-flash-lite"
  )
  val DEFAULT_TEXT_CAPABLE = listOf(
    "gemini-3.8-flash",
    "gemini-3-flash-preview",
    "gemini-2.5-pro",
    "gemini-3.5-flash-lite"
  )
  val DEFAULT_VISION = listOf(
    "gemini-3.5-flash-lite",
    "gemini-3.8-flash",
    "gemini-3-flash-preview",
    "gemini-2.5-flash-lite"
  )

  fun autoFallbackEnabled(c: Context) = Prefs.on(c, "f_auto_model_fallback", true)
  fun smartSelectionEnabled(c: Context) = Prefs.on(c, "f_smart_model", true)
  fun retryCount(c: Context) = Prefs.plain(c).getInt("model_retry_count", 2).coerceIn(0, 5)
  fun retryDelayMs(c: Context) = Prefs.plain(c).getInt("model_retry_delay_ms", 1200).coerceIn(250, 10000)
  fun primaryModel(c: Context) = Prefs.plain(c).getString("model_primary", "")?.trim().orEmpty()
    .ifBlank { Prefs.plain(c).getString("model", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview" }

  fun fallbackModels(c: Context): MutableList<String> {
    val raw = Prefs.plain(c).getString("model_fallbacks", "") ?: ""
    val list = raw.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    if (list.isEmpty()) list.addAll(DEFAULT_TEXT_FAST)
    return list
  }

  fun saveFallbackModels(c: Context, models: List<String>) {
    val clean = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    Prefs.plain(c).edit().putString("model_fallbacks", clean.joinToString("\n")).apply()
  }

  fun supportedCache(c: Context): List<String> {
    val raw = Prefs.plain(c).getString("model_supported_cache", "") ?: ""
    return raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
  }

  fun saveSupportedCache(c: Context, models: List<String>) {
    Prefs.plain(c).edit().putString("model_supported_cache", models.distinct().joinToString("\n")).apply()
  }

  fun lastStatus(c: Context): String = Prefs.plain(c).getString("model_last_status", "") ?: ""
  fun recordStatus(c: Context, status: String) {
    Prefs.plain(c).edit().putString("model_last_status", status.take(500)).putLong("model_last_status_ts", System.currentTimeMillis()).apply()
    Bus.add("system", status, true)
  }

  fun candidatesFor(c: Context, task: ModelTask, preferred: String? = null): List<String> {
    val p = Prefs.plain(c)
    val configuredPrimary = primaryModel(c)
    val userFallbacks = fallbackModels(c)
    val supported = supportedCache(c)
    val smart = smartSelectionEnabled(c)
    val base = LinkedHashSet<String>()
    val pref = preferred?.trim()?.takeIf { it.isNotBlank() }
    if (pref != null) base.add(pref)

    if (smart) {
      when (task) {
        ModelTask.AUTO_REPLY -> {
          base.add(p.getString("model_auto_reply", "")?.trim().orEmpty().ifBlank { DEFAULT_TEXT_FAST.first() })
          base.addAll(DEFAULT_TEXT_FAST)
        }
        ModelTask.LIVE_VOICE -> {
          base.add(configuredPrimary)
          base.addAll(DEFAULT_LIVE)
        }
        ModelTask.VISION -> {
          base.add(p.getString("model_vision", "")?.trim().orEmpty().ifBlank { DEFAULT_VISION.first() })
          base.addAll(DEFAULT_VISION)
        }
        ModelTask.COMPLEX -> {
          base.add(p.getString("model_complex", "")?.trim().orEmpty().ifBlank { DEFAULT_TEXT_CAPABLE.first() })
          base.addAll(DEFAULT_TEXT_CAPABLE)
        }
        ModelTask.DEFAULT -> {
          base.add(configuredPrimary); base.addAll(userFallbacks); base.addAll(DEFAULT_TEXT_FAST)
        }
      }
    } else {
      base.add(configuredPrimary); base.addAll(userFallbacks)
    }

    // If we have supported cache, keep known-supported first but don't drop unknown user choices.
    if (supported.isNotEmpty()) {
      val supportedSet = supported.toSet()
      val ordered = LinkedHashSet<String>()
      base.forEach { if (supportedSet.contains(it)) ordered.add(it) }
      base.forEach { if (!supportedSet.contains(it)) ordered.add(it) }
      return ordered.filter { it.isNotBlank() }.distinct().take(8)
    }
    return base.filter { it.isNotBlank() }.distinct().take(8)
  }

  fun isTransientError(msg: String?, code: Int = 0): Boolean {
    val m = msg.orEmpty()
    return code == 429 || code == 503 || code == 500 || code == 502 || code == 504 ||
      m.contains("503", true) || m.contains("429", true) || m.contains("UNAVAILABLE", true) ||
      m.contains("high demand", true) || m.contains("overload", true) || m.contains("RESOURCE_EXHAUSTED", true) ||
      m.contains("rate limit", true) || m.contains("quota", true) || m.contains("temporar", true) ||
      m.contains("timeout", true) || m.contains("deadline", true)
  }

  private fun isModelUnavailable(msg: String?, code: Int = 0): Boolean {
    val m = msg.orEmpty()
    return code == 404 || m.contains("not found", true) || m.contains("no longer available", true) ||
      m.contains("not supported for generateContent", true) || m.contains("is not found for API version", true)
  }

  fun generateContentWithFallback(
    c: Context,
    task: ModelTask,
    prompt: String,
    system: String? = null,
    imageB64: String? = null,
    preferredModel: String? = null,
    temperature: Double = 0.4,
    maxOutputTokens: Int = 512
  ): ModelResult {
    val apiKey = Prefs.secure(c).getString("apiKey", "") ?: ""
    if (apiKey.isBlank()) throw RuntimeException("Gemini API key missing")
    val auto = autoFallbackEnabled(c)
    var candidates = if (auto) candidatesFor(c, task, preferredModel) else listOf(preferredModel ?: candidatesFor(c, task).firstOrNull() ?: DEFAULT_TEXT_FAST.first())
    val retries = retryCount(c)
    val delay = retryDelayMs(c)
    val attempts = ArrayList<ModelAttempt>()
    var lastErr: Exception? = null
    var refreshed = false
    var idx = 0
    while (idx < candidates.size) {
      val model = candidates[idx]
      val maxTry = if (auto) retries + 1 else 1
      for (attempt in 1..maxTry) {
        val t0 = System.currentTimeMillis()
        try {
          val text = generateOnce(apiKey, model, prompt, system, imageB64, temperature, maxOutputTokens)
          val ms = System.currentTimeMillis() - t0
          attempts.add(ModelAttempt(model, task, true, ms = ms))
          val status = if (idx > 0 || attempt > 1) "Model fallback used: $model for ${task.name.lowercase()} after ${attempts.size - 1} failed attempt(s)." else "Model used: $model (${task.name.lowercase()})."
          Prefs.plain(c).edit().putString("model_last_used", model).putString("model_last_status", status).putLong("model_last_status_ts", System.currentTimeMillis()).apply()
          if (idx > 0 || attempt > 1) Bus.add("system", status, true)
          return ModelResult(text, model, attempts)
        } catch (e: Exception) {
          val ms = System.currentTimeMillis() - t0
          val msg = e.message ?: e.javaClass.simpleName
          attempts.add(ModelAttempt(model, task, false, msg, ms))
          lastErr = e
          val transient = isTransientError(msg)
          val unavailable = isModelUnavailable(msg)
          Prefs.plain(c).edit().putString("model_last_status", "Model $model failed (${task.name.lowercase()}): ${msg.take(180)}").putLong("model_last_status_ts", System.currentTimeMillis()).apply()
          if (!auto) throw e
          if (attempt < maxTry && transient && !unavailable) {
            Bus.add("system", "Model $model busy/error, retrying ($attempt/$maxTry)...", true)
            try { Thread.sleep((delay.toLong() * attempt).coerceAtMost(5000L)) } catch (_: Exception) {}
            continue
          }
          if ((transient || unavailable) && idx < candidates.lastIndex) {
            Bus.add("system", "Model $model unavailable (${msg.take(120)}). Switching to ${candidates[idx + 1]}...", true)
            break
          }
          if (unavailable && !refreshed && auto) {
            refreshed = true
            val fresh = fetchSupportedModels(c).filter { it.isNotBlank() }
            if (fresh.isNotEmpty()) {
              val merged = LinkedHashSet<String>()
              candidates.forEach { merged.add(it) }
              fresh.filter { !it.contains("live", true) && !it.contains("tts", true) && !it.contains("image", true) }.forEach { merged.add(it) }
              candidates = merged.toList()
              Bus.add("system", "Detected ${fresh.size} supported models; retrying with available models...", true)
              break
            }
          }
          if (!transient && !unavailable && idx < candidates.lastIndex) {
            Bus.add("system", "Model $model failed (${msg.take(120)}). Trying ${candidates[idx + 1]}...", true)
            break
          }
          throw e
        }
      }
      idx++
    }
    throw lastErr ?: RuntimeException("No model available")
  }

  private fun generateOnce(apiKey: String, model: String, prompt: String, system: String?, imageB64: String?, temperature: Double, maxOutputTokens: Int): String {
    val parts = JSONArray().put(JSONObject().put("text", prompt))
    if (!imageB64.isNullOrBlank()) {
      parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)))
    }
    val body = JSONObject()
      .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
      .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxOutputTokens))
    if (!system.isNullOrBlank()) {
      body.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
    }
    val req = Request.Builder()
      .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
      .header("Content-Type", "application/json")
      .post(body.toString().toRequestBody(jsonType))
      .build()
    http.newCall(req).execute().use { resp ->
      val s = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        val detail = try { JSONObject(s).optJSONObject("error")?.optString("message")?.take(300) ?: s.take(300) } catch (_: Exception) { s.take(300) }
        throw RuntimeException("HTTP ${resp.code}: $detail")
      }
      val j = JSONObject(s)
      val blocked = j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
      if (blocked != null) throw RuntimeException("Blocked by Gemini: $blocked")
      val cand = j.optJSONArray("candidates")?.optJSONObject(0)
      val finish = cand?.optString("finishReason").orEmpty()
      val pArr = cand?.optJSONObject("content")?.optJSONArray("parts")
      val sb = StringBuilder()
      if (pArr != null) for (i in 0 until pArr.length()) sb.append(pArr.optJSONObject(i)?.optString("text").orEmpty())
      val out = sb.toString().trim()
      if (out.isBlank()) throw RuntimeException("Empty model output" + if (finish.isNotBlank()) " (finishReason=$finish)" else "")
      return out
    }
  }

  fun fetchSupportedModels(c: Context): List<String> {
    val key = Prefs.secure(c).getString("apiKey", "") ?: ""
    if (key.isBlank()) return emptyList()
    val out = LinkedHashSet<String>()
    try {
      val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$key").build()
      http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return emptyList()
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        for (i in 0 until arr.length()) {
          val m = arr.optJSONObject(i) ?: continue
          val name = m.optString("name").removePrefix("models/").trim()
          if (name.isBlank()) continue
          val methods = m.optJSONArray("supportedGenerationMethods")
          var gen = false; var live = false
          if (methods != null) for (k in 0 until methods.length()) {
            val me = methods.optString(k)
            if (me == "generateContent") gen = true
            if (me == "bidiGenerateContent") live = true
          }
          if (gen || live) out.add(name)
        }
      }
      if (out.isNotEmpty()) saveSupportedCache(c, out.toList())
    } catch (_: Exception) {}
    return out.toList()
  }
}
