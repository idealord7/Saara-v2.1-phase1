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
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
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
      "Be concise, polite, and helpful. Match sender language/tone. Reply with ONLY final message text."
  fun systemPrompt(c: Context): String = pref(c).getString("ai_reply_prompt", null)?.takeIf { it.isNotBlank() } ?: defaultPrompt()

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
  fun addLog(c: Context, s: String) {
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

  data class GuardDecision(val allow: Boolean, val reason: String, val instruction: String = "", val confidenceThreshold: Int = 75, val draftOnly: Boolean = false)

  private fun normSender(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")
  private fun recentReplyCount(ctx: Context, sender: String, windowMin: Int): Int {
    val cutoff = System.currentTimeMillis() - windowMin.coerceIn(1, 120) * 60_000L
    return synchronized(log) { log.count { it.contains("Replied", true) && it.contains("· $sender ·", true) && tsFromLog(it) >= cutoff } }
  }
  private fun tsFromLog(line: String): Long {
    return try {
      val m = Regex("^\\[(.*?)\\]").find(line)?.groupValues?.getOrNull(1) ?: return 0L
      SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).parse(m)?.time ?: 0L
    } catch (_: Exception) { 0L }
  }
  private fun looksLikeLoop(sender: String, incoming: String): Boolean {
    val n = normForCompare(incoming)
    if (n.isBlank()) return true
    val ctxLines = synchronized(recent) { recent[sender]?.toList() ?: emptyList() }
    val recentFromSender = ctxLines.takeLast(4).count { it.startsWith("$sender:") }
    val repeated = ctxLines.takeLast(6).any { normForCompare(it.removePrefix("$sender:").trim()) == n }
    return repeated || recentFromSender >= 3
  }
  private fun normForCompare(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ").replace(Regex("\\s+"), " ").trim()

  fun handle(c: Context, key: String, pkg: String, sender: String, text: String, isGroup: Boolean, ruleInstruction: String = "") {
    val ctx = c.applicationContext
    val who = sender.ifBlank { "Unknown" }.trim()
    val msg = text.trim()
    if (msg.isBlank()) { addLog(ctx, "Skipped blank message · $who · ${appLabel(pkg)}"); return }
    val decision = evaluate(ctx, pkg, who, msg, isGroup, screenOff(ctx), ruleInstruction)
    if (!decision.allow) { addLog(ctx, "Skipped (${decision.reason}) · $who · ${appLabel(pkg)}"); return }
    if (!Prefs.on(ctx, "f_ai_reply", false)) { addLog(ctx, "Skipped (AI smart auto-reply off) · $who · ${appLabel(pkg)}"); return }

    val dedupe = "$pkg|$who|${msg.hashCode()}"
    val now = System.currentTimeMillis()
    val prev = lastHash[who]
    if (prev == dedupe && (now - (inflight["$who:last"] ?: 0L)) < 12000) {
      addLog(ctx, "Skipped duplicate · $who · ${appLabel(pkg)}"); return
    }
    lastHash[who] = dedupe; inflight["$who:last"] = now

    val apiKey = Prefs.secure(ctx).getString("apiKey", "") ?: ""
    if (apiKey.isBlank()) { addLog(ctx, "Skipped ($who): Gemini API key missing"); Bus.add("system", "AI auto-reply skipped ($who): set Gemini API key.", true); return }

    pushContext(who, "$who: $msg")
    val mySeq = seq.incrementAndGet()
    addLog(ctx, "Queued #$mySeq · $who · ${appLabel(pkg)}\n← ${msg.take(500)}")
    exec.execute {
      try {
        val rules = try { Db.get(ctx).rulesPrompt(40) } catch (_: Exception) { "" }
        val facts = try { Db.get(ctx).factsPrompt(msg, 10) } catch (_: Exception) { "" }
        val reply = generateWithRetry(apiKey, systemPrompt(ctx), rules, facts, decision.instruction, who, msg, contextFor(who))
        val clean = sanitize(reply)
        if (clean.isBlank()) { addLog(ctx, "Empty reply after sanitizing · $who · ${appLabel(pkg)}"); return@execute }
        val confidence = estimateConfidence(msg, clean, decision)
        if (decision.draftOnly || confidence < decision.confidenceThreshold) {
          try { Db.get(ctx).replyDraftAdd(key, who, pkg, msg, clean, "confidence=$confidence<threshold=${decision.confidenceThreshold}; ${decision.reason}") } catch (_: Exception) {}
          addLog(ctx, "Draft held #$mySeq · $who · ${appLabel(pkg)} · confidence=$confidence\n← ${msg.take(400)}\n✎ ${clean.take(600)}")
          Bus.add("system", "Auto-reply draft held for $who (${appLabel(pkg)}, confidence $confidence%). Review in Auto-reply logs/drafts.", true)
          return@execute
        }
        pushContext(who, "You: $clean")
        val result = SaaraNotifications.reply(key, who, clean)
        val ok = result == "replied"
        addLog(ctx, (if (ok) "Replied #$mySeq" else "Failed ($result)") + " · $who · ${appLabel(pkg)} · confidence=$confidence\n← ${msg.take(500)}\n→ ${clean.take(700)}")
        if (ok) Bus.add("system", "Auto-replied to $who (${appLabel(pkg)}): ${clean.take(250)}", true) else Bus.add("system", "Auto-reply failed for $who: $result", true)
      } catch (e: Exception) {
        val detail = e.message?.take(300) ?: e.javaClass.simpleName
        addLog(ctx, "API/error · $who · ${appLabel(pkg)}: $detail")
        Bus.add("system", "AI auto-reply error for $who: $detail", true)
      }
    }
  }

  private fun estimateConfidence(incoming: String, reply: String, d: GuardDecision): Int {
    var score = 72
    val inLen = incoming.trim().length
    val outLen = reply.trim().length
    if (inLen in 4..240) score += 8
    if (outLen in 2..280) score += 8
    if (Regex("\\b(otp|password|pin|cvv|bank|upi|pay|payment|address|card|login)\\b", RegexOption.IGNORE_CASE).containsMatchIn(incoming)) score -= 25
    if (Regex("\?").containsMatchIn(incoming) && outLen < 2) score -= 20
    if (d.instruction.contains("vip", true)) score += 5
    if (looksLikeLoop("sender", incoming)) score -= 15
    return score.coerceIn(0, 100)
  }

  private fun screenOff(c: Context): Boolean = try {
    val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    pm?.isInteractive == false
  } catch (_: Exception) { false }

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

  private fun appKey(pkg: String) = when (pkg) {
    "com.whatsapp" -> "whatsapp"
    "com.whatsapp.w4b" -> "whatsapp_business"
    "org.telegram.messenger", "org.telegram.messenger.web" -> "telegram"
    "com.instagram.android" -> "instagram"
    "com.facebook.orca" -> "messenger"
    else -> pkg
  }

  data class ReplyDecision(val allow: Boolean, val reason: String, val instruction: String = "")

  private fun csvList(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
  private fun matchesAny(hay: String, needles: List<String>) = needles.any { n -> hay.contains(n) }

  private fun inQuietHours(nowMin: Int, start: String, end: String): Boolean {
    fun parse(hm: String): Int? { val p = hm.split(":"); if (p.size != 2) return null; val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null; if (h !in 0..23 || m !in 0..59) return null; return h * 60 + m }
    val s = parse(start) ?: return false; val e = parse(end) ?: return false
    return if (s <= e) nowMin in s..e else nowMin >= s || nowMin <= e
  }

  fun evaluate(c: Context, pkg: String, sender: String, text: String, isGroup: Boolean, screenOff: Boolean, extraInstruction: String = ""): GuardDecision {
    if (!Prefs.on(c, "f_notif", false)) return GuardDecision(false, "Notification auto-reply off")
    val rules = try { Db.get(c).replyRules().filter { it.enabled } } catch (_: Exception) { emptyList() }
    val senderNorm = sender.lowercase().trim()
    val textNorm = text.lowercase()
    val app = appKey(pkg)
    val cal = java.util.Calendar.getInstance()
    val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

    fun appMatch(r: ReplyRule): Boolean {
      val apps = csvList(r.apps)
      return apps.isEmpty() || apps.any { it == app || it == pkg.lowercase() || appLabel(pkg).lowercase().contains(it) }
    }
    fun senderMatch(r: ReplyRule): Boolean {
      val allowSenders = csvList(r.senders)
      val senderOk = allowSenders.isEmpty() || matchesAny(senderNorm, allowSenders)
      val blocked = csvList(r.blockedSenders)
      val blockedOk = blocked.isEmpty() || !matchesAny(senderNorm, blocked)
      return senderOk && blockedOk
    }
    val matched = if (rules.isNotEmpty()) rules.filter { r ->
      val chatOk = when (r.chatType.lowercase()) { "group" -> isGroup; "direct" -> !isGroup; else -> true }
      val kws = csvList(r.keywords)
      val kwOk = kws.isEmpty() || matchesAny(textNorm, kws)
      appMatch(r) && senderMatch(r) && chatOk && kwOk
    } else emptyList()

    if (rules.isNotEmpty() && matched.isEmpty()) return GuardDecision(false, "no matching Replying Rule")
    val applicable = if (matched.isNotEmpty()) matched else rules.filter { appMatch(it) && senderMatch(it) }.ifEmpty { emptyList() }

    val mute = applicable.firstOrNull { csvList(it.muteSenders).any { senderNorm.contains(it) } || csvList(it.blockedSenders).any { senderNorm.contains(it) } }
    if (mute != null) return GuardDecision(false, "muted/blocked sender by rule '${mute.name.ifBlank { "rule" }}'")
    val block = applicable.firstOrNull { it.mode.equals("block", true) || it.mode.equals("never", true) }
    if (block != null) return GuardDecision(false, "blocked by rule '${block.name.ifBlank { "rule" }}'")
    val quiet = applicable.firstOrNull { inQuietHours(nowMin, it.quietStart, it.quietEnd) }
    if (quiet != null) return GuardDecision(false, "quiet hours rule '${quiet.name.ifBlank { "rule" }}'")
    val screenRule = applicable.firstOrNull { it.screenOffOnly }
    if (screenRule != null && !screenOff) return GuardDecision(false, "screen-off-only rule '${screenRule.name.ifBlank { "rule" }}'")

    val vip = applicable.any { csvList(it.vipSenders).any { senderNorm.contains(it) } }
    val threshold = applicable.minOfOrNull { it.confidenceThreshold.coerceIn(0,100) } ?: 75
    val rateLimit = applicable.minOfOrNull { it.rateLimitPerHour.coerceIn(1,60) } ?: 6
    val loopWindow = applicable.maxOfOrNull { it.loopWindowMin.coerceIn(1,120) } ?: 10
    if (recentReplyCount(c, sender, 60) >= rateLimit) return GuardDecision(false, "rate limit reached (${rateLimit}/h)")
    if (looksLikeLoop(sender, text)) return GuardDecision(false, "loop protection: repeated/self-loop conversation detected")

    val instr = buildString {
      val ruleInstr = applicable.mapNotNull { it.instruction.takeIf { s -> s.isNotBlank() } }
      if (ruleInstr.isNotEmpty()) append(ruleInstr.joinToString("\n- ", prefix = "- "))
      if (vip) append("\n- VIP sender: be extra careful, polite, and accurate.")
      if (extraInstruction.isNotBlank()) { if (isNotEmpty()) append('\n'); append(extraInstruction.trim()) }
    }
    val draftOnly = !vip && Regex("\\b(otp|password|pin|cvv|bank|upi|pay|payment|login|address|card)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val reason = when {
      applicable.isNotEmpty() -> "matched ${applicable.size} rule(s)"
      rules.isEmpty() -> "default filter"
      else -> "app/sender rule"
    }
    if (rules.isEmpty()) {
      val names = (Prefs.plain(c).getString("reply_names", "") ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
      val wanted = if (names.isEmpty()) pkg in SaaraNotifications.supportedMsgApps() else names.any { senderNorm.contains(it) || it.contains(pkg.lowercase()) }
      if (!wanted) return GuardDecision(false, "name/app filter")
      if (isGroup && !Prefs.on(c, "reply_groups", false)) return GuardDecision(false, "group replies off")
    }
    return GuardDecision(true, reason, instr, threshold, draftOnly)
  }

  // Backward-compatible helper for older callers
  fun shouldReply(c: Context, pkg: String, sender: String, text: String, isGroup: Boolean, screenOff: Boolean): Triple<Boolean, String, String> {
    val d = evaluate(c, pkg, sender, text, isGroup, screenOff)
    return Triple(d.allow, d.reason, d.instruction)
  }

  private fun generateWithRetry(apiKey: String, system: String, rules: String, facts: String, ruleInstr: String, sender: String, message: String, context: String): String {
    var last: Exception? = null
    for (attempt in 1..3) {
      try { return generate(apiKey, system, rules, facts, ruleInstr, sender, message, context) }
      catch (e: Exception) {
        last = e
        val msg = e.message.orEmpty()
        val retryable = msg.contains("HTTP 503") || msg.contains("UNAVAILABLE") || msg.contains("high demand", true) || msg.contains("temporar", true) || msg.contains("timeout", true)
        if (!retryable || attempt == 3) throw e
        try { Thread.sleep((attempt * 1200L).coerceAtMost(3000L)) } catch (_: Exception) {}
      }
    }
    throw last ?: RuntimeException("Unknown auto-reply error")
  }

  private fun generate(apiKey: String, system: String, rules: String, facts: String, ruleInstr: String, sender: String, message: String, context: String): String {
    val model = "gemini-3.8-flash"
    val instruction = buildString {
      append(system.trim())
      append("\n\nSTRICT COMPLIANCE:\n- Output only reply text to send. No preamble/quotes/markdown.\n- Follow all user rules and replying rules.\n")
      if (rules.isNotBlank()) append(rules.trim()).append('\n')
      if (facts.isNotBlank()) append(facts.trim()).append('\n')
      if (ruleInstr.isNotBlank()) append("REPLYING RULES FOR THIS CHAT:\n").append(ruleInstr.trim()).append('\n')
    }
    val user = buildString {
      append("App chat auto-reply request.\nSender: ").append(sender.ifBlank { "Unknown" }).append('\n')
      if (context.isNotBlank()) append("Recent conversation:\n").append(context.take(2500)).append('\n')
      append("Incoming message: ").append(message.take(1800)).append("\nWrite the best reply now.")
    }
    val body = JSONObject()
      .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
      .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
      .put("generationConfig", JSONObject().put("temperature", 0.4).put("topP", 0.95).put("maxOutputTokens", 220))
    val req = Request.Builder()
      .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
      .header("Content-Type", "application/json")
      .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()
    http.newCall(req).execute().use { resp ->
      val s = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        val detail = try { JSONObject(s).optJSONObject("error")?.optString("message")?.take(220) ?: s.take(220) } catch (_: Exception) { s.take(220) }
        throw RuntimeException("HTTP ${resp.code}: $detail")
      }
      val j = JSONObject(s)
      val blocked = j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }
      if (blocked != null) throw RuntimeException("Blocked by Gemini: $blocked")
      val cand = j.optJSONArray("candidates")?.optJSONObject(0)
      val finish = cand?.optString("finishReason").orEmpty()
      val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
      val sb = StringBuilder()
      if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
      val out = sb.toString().trim()
      if (out.isBlank()) throw RuntimeException("Empty model output" + if (finish.isNotBlank()) " (finishReason=$finish)" else "")
      return out
    }
  }
}
