package com.saara.assistant

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast, rule-compliant background auto-replies for notification direct-reply (WhatsApp, WA Business, Telegram, etc.).
 * - Runs off the listener thread with parallel workers for low latency.
 * - Applies saved user rules and custom AI instructions on every reply.
 * - Sends via Notification RemoteInput (no app open).
 */
object AiAutoReply {
  private val exec = Executors.newCachedThreadPool()
  private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(22, TimeUnit.SECONDS)
    .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
    .build()
  private val recent = LinkedHashMap<String, ArrayDeque<String>>()
  private val log = ArrayDeque<String>()
  private val inflight = ConcurrentHashMap<String, Long>()
  private val lastHash = ConcurrentHashMap<String, String>()
  private val seq = AtomicLong(0)

  private fun pref(c: Context) = Prefs.plain(c)
  fun enabled(c: Context) = Prefs.on(c, "f_notif", false) && Prefs.on(c, "f_ai_reply", false)

  fun defaultPrompt() =
    "You are the user's smart personal messaging assistant. Write a natural, human-like, context-aware reply on the user's behalf. " +
      "Be concise, polite, and helpful. Match the sender's language and tone when appropriate. " +
      "Reply with ONLY the final message text to send (no quotes, no explanations, no labels)."

  fun systemPrompt(c: Context): String =
    pref(c).getString("ai_reply_prompt", null)?.takeIf { it.isNotBlank() } ?: defaultPrompt()

  private fun pushContext(sender: String, line: String) {
    synchronized(recent) {
      val q = recent.getOrPut(sender) { ArrayDeque() }
      q.addLast(line)
      while (q.size > 8) q.removeFirst()
      if (recent.size > 60) recent.remove(recent.keys.first())
    }
  }
  private fun contextFor(sender: String): String = synchronized(recent) { recent[sender]?.joinToString("\n") ?: "" }

  fun logs(): List<String> = synchronized(log) { log.toList().reversed() }
  fun clearLogs() {
    synchronized(log) { log.clear() }
    prefRunClear()
    Bus.ping()
  }
  private fun prefRunClear() { /* persisted logs trimmed on next write */ }
  private fun stamp() = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
  private fun addLog(c: Context, s: String) {
    val line = "[${stamp()}] $s"
    synchronized(log) { log.addLast(line); while (log.size > 120) log.removeFirst() }
    try {
      val p = Prefs.plain(c)
      val arr = JSONArray(p.getString("ai_reply_log", "[]") ?: "[]")
      val out = JSONArray()
      val start = (arr.length() - 59).coerceAtLeast(0)
      for (i in start until arr.length()) out.put(arr.optString(i))
      out.put(line)
      p.edit().putString("ai_reply_log", out.toString()).apply()
    } catch (_: Exception) {}
    Bus.ping()
  }
  fun loadPersisted(c: Context) {
    synchronized(log) {
      if (log.isNotEmpty()) return
      try {
        val arr = JSONArray(Prefs.plain(c).getString("ai_reply_log", "[]") ?: "[]")
        for (i in 0 until arr.length()) log.addLast(arr.optString(i))
      } catch (_: Exception) {}
    }
  }

  fun handle(c: Context, key: String, pkg: String, sender: String, text: String, isGroup: Boolean) {
    val ctx = c.applicationContext
    if (!enabled(ctx)) return
    val who = sender.ifBlank { "Unknown" }.trim()
    val msg = text.trim()
    if (msg.isBlank()) return
    if (isGroup && !Prefs.on(ctx, "reply_groups", false)) { addLog(ctx, "Skipped group · $who · ${appLabel(pkg)}"); return }
    val dedupe = "$pkg|$who|${msg.hashCode()}"
    val now = System.currentTimeMillis()
    val prev = lastHash[who]
    if (prev == dedupe && (now - (inflight["$who:last"] ?: 0L)) < 12000) return
    lastHash[who] = dedupe
    inflight["$who:last"] = now
    val appKey = Prefs.secure(ctx).getString("apiKey", "") ?: ""
    if (appKey.isBlank()) {
      addLog(ctx, "Skipped ($who): Gemini API key missing")
      Bus.add("system", "AI auto-reply skipped ($who): set Gemini API key in Settings.", true)
      return
    }
    pushContext(who, "$who: $msg")
    val mySeq = seq.incrementAndGet()
    exec.execute {
      try {
        val rules = try { Db.get(ctx).rulesPrompt(40) } catch (_: Exception) { "" }
        val ctxText = contextFor(who)
        val reply = generate(appKey, systemPrompt(ctx), rules, who, msg, ctxText)
        if (reply.isNullOrBlank()) { addLog(ctx, "No reply generated · $who"); return@execute }
        val clean = sanitize(reply)
        if (clean.isBlank()) { addLog(ctx, "Empty reply after sanitizing · $who"); return@execute }
        pushContext(who, "You: $clean")
        val result = SaaraNotifications.reply(key, clean)
        val ok = result == "replied"
        addLog(ctx, (if (ok) "Replied #$mySeq" else "Failed ($result)") + " · $who · ${appLabel(pkg)}\n← ${msg.take(600)}\n→ ${clean.take(800)}")
        if (ok) Bus.add("system", "Auto-replied to $who (${appLabel(pkg)}): ${clean.take(300)}", true)
        else Bus.add("system", "Auto-reply failed for $who: $result", true)
      } catch (e: Exception) {
        addLog(ctx, "Error · $who: ${e.message}")
      }
    }
  }

  private fun sanitize(s: String): String {
    var t = s.trim()
    if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) t = t.drop(1).dropLast(1).trim()
    // strip common assistant prefixes
    t = t.replace(Regex("^(reply|response|message)\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
    return t.take(1500)
  }

  private fun appLabel(pkg: String) = when (pkg) {
    "com.whatsapp" -> "WhatsApp"
    "com.whatsapp.w4b" -> "WhatsApp Business"
    "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
    "com.facebook.orca" -> "Messenger"
    "com.instagram.android" -> "Instagram"
    "com.google.android.apps.messaging" -> "Messages"
    else -> pkg.substringAfterLast('.')
  }

  private fun generate(apiKey: String, system: String, rules: String, sender: String, message: String, context: String): String? {
    val model = "gemini-2.0-flash"
    val instruction = buildString {
      append(system.trim())
      append("\n\nSTRICT COMPLIANCE (must follow):\n")
      append("- Output only the reply text to send. No preamble, no quotes, no markdown fences, no explanations.\n")
      append("- Adhere to every user-defined rule below at all times. Never violate them.\n")
      append("- If a rule requires confirmation or forbids sending, respond with a brief, appropriate text-only reply that follows the rule (do not claim actions you did not take).\n")
      if (rules.isNotBlank()) append(rules.trim()).append('\n')
    }
    val user = buildString {
      append("App chat auto-reply request.\nSender: ").append(sender.ifBlank { "Unknown" }).append('\n')
      if (context.isNotBlank()) append("Recent conversation:\n").append(context.take(3000)).append('\n')
      append("Incoming message: ").append(message.take(2000)).append("\nWrite the best reply now.")
    }
    val body = JSONObject()
      .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
      .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
      .put("generationConfig", JSONObject()
        .put("temperature", 0.4)
        .put("topP", 0.95)
        .put("maxOutputTokens", 220))
    val req = Request.Builder()
      .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
      .header("Content-Type", "application/json")
      .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()
    http.newCall(req).execute().use { resp ->
      val s = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${s.take(220)}")
      val j = JSONObject(s)
      val cand = j.optJSONArray("candidates")?.optJSONObject(0)
      val parts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: return null
      val sb = StringBuilder()
      for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
      return sb.toString().trim().ifBlank { null }
    }
  }
}
