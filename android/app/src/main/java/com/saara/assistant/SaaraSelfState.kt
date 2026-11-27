package com.saara.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured self-awareness snapshot for Saara. Read-only by design for prompts;
 * mutation helpers return concise confirmations and keep UI/Prefs/Db in sync via callers.
 */
object SaaraSelfState {
  private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
  private fun fmt(ms: Long) = if (ms <= 0L) "never" else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

  fun snapshot(c: Context, section: String = "all"): String {
    val sec = section.trim().lowercase(Locale.ROOT).ifBlank { "all" }
    val out = JSONObject()
    if (sec in setOf("all", "status", "services", "service")) out.put("status", status(c))
    if (sec in setOf("all", "settings", "voice", "wake", "speaker", "session", "language")) out.put("settings", settings(c))
    if (sec in setOf("all", "permissions", "permission")) out.put("permissions", permissions(c))
    if (sec in setOf("all", "rules", "reply_rules", "replying", "automation", "pre_action", "preaction")) out.put("rules", rules(c))
    if (sec in setOf("all", "history", "chat", "session", "continuity")) out.put("history", history(c))
    if (sec in setOf("all", "model", "models", "fallback")) out.put("models", models(c))
    if (sec in setOf("all", "capabilities", "capability")) out.put("capabilities", capabilities(c))
    return out.toString()
  }

  fun concise(c: Context): String {
    val st = status(c)
    val se = settings(c)
    val ru = rules(c)
    val mo = models(c)
    val hi = history(c)
    return buildString {
      append("SAARA SELF-STATE (real app state; private/local):\n")
      append("- Running: ${st.optBoolean("running")} · ready=${st.optBoolean("geminiReady")} · mic=${st.optString("mic")} · activeSession=${st.optBoolean("activeSession")}\n")
      append("- Services: notifListener=${st.optBoolean("notificationListenerConnected")} notifAccess=${st.optBoolean("notificationAccessEnabled")} a11y=${st.optBoolean("accessibilityEnabled")} overlay=${st.optBoolean("overlayPermission")} batteryOptIgnored=${st.optBoolean("batteryOptimizationIgnored")}\n")
      append("- Sharing: cam=${st.optBoolean("cameraSharing")} screen=${st.optBoolean("screenSharing")} autoScreenStop=${st.optBoolean("screenAutoStop")}\n")
      append("- Session timeout: ${if (se.optBoolean("sessionTimeoutEnabled")) se.optInt("sessionTimeoutSec").toString() + "s" else "OFF"} · language=${se.optString("language")}\n")
      append("- Wake phrases: ${se.optJSONArray("wakePhrases")?.length() ?: 0}\n")
      append("- Speaker focus: onlyMyVoice=${se.optBoolean("onlyMyVoice")} verifyAll=${se.optBoolean("verifyEveryInput")} bypassManual=${se.optBoolean("bypassOnManualTap")} ignoreMedia=${se.optBoolean("ignoreMediaNoise")} strictness=${se.optString("voiceStrictness")} minMatch=${se.optInt("voiceMatchThreshold")}% enrolled=${se.optBoolean("voiceEnrolled")}\n")
      append("- Auto-reply master=${se.optBoolean("notificationAutoReplyMaster")} AI smart reply=${se.optBoolean("aiSmartAutoReply")}\n")
      append("- Rules: reply=${ru.optInt("replyRuleCount")} enabled=${ru.optInt("replyRuleEnabled")} automation=${ru.optInt("automationRuleCount")} enabled=${ru.optInt("automationRuleEnabled")} preAction=${ru.optInt("preActionRuleCount")} enabled=${ru.optInt("preActionRuleEnabled")}\n")
      append("- History: chatMsgs=${hi.optInt("chatMessages")} recentSession=${hi.optString("recentSessionStatus")} goal=${hi.optString("activeGoal").take(60)}\n")
      append("- Models: primary=${mo.optString("primary")} live=${mo.optString("liveLastUsed")} autoFallback=${mo.optBoolean("autoFallback")} smart=${mo.optBoolean("smartSelection")}\n")
      append("Use saara_app_control for read/modify/create/delete/trigger actions; answer settings questions from this real state.")
    }.take(3500)
  }

  private fun status(c: Context): JSONObject = JSONObject().apply {
    val vs = VoiceService.instance
    val pm = c.getSystemService(PowerManager::class.java)
    put("running", Bus.running)
    put("geminiReady", vs != null && Bus.status.contains("Listening") || Bus.status.contains("Connected") || Bus.status.contains("Thinking") || Bus.status.contains("Executing"))
    put("statusText", Bus.status)
    put("mic", when { vs?.isMicOn() == true -> "on"; vs != null -> "muted_or_off"; else -> "service_stopped" })
    put("muted", vs?.muted ?: false)
    put("activeSession", vs?.isSaraActive ?: false)
    put("speakerStatus", vs?.speakerStatus() ?: "service not running")
    put("notificationListenerConnected", SaaraNotifications.isConnected())
    put("notificationAccessEnabled", NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName))
    put("accessibilityEnabled", try { val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""; enabled.contains(c.packageName) } catch (_: Exception) { false })
    put("overlayPermission", Settings.canDrawOverlays(c))
    put("batteryOptimizationIgnored", try { pm?.isIgnoringBatteryOptimizations(c.packageName) == true } catch (_: Exception) { false })
    put("cameraSharing", Bus.camOn)
    put("screenSharing", Bus.screenOn)
    put("screenAutoStop", Bus.screenAuto)
    put("callActive", try { vs?.javaClass?.getDeclaredField("callActive")?.apply { isAccessible = true }?.getBoolean(vs) ?: false } catch (_: Exception) { false })
  }

  private fun settings(c: Context): JSONObject = JSONObject().apply {
    val p = Prefs.plain(c)
    put("sessionTimeoutSec", p.getInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, VoiceService.DEFAULT_SESSION_TIMEOUT_SEC).coerceIn(0, 999))
    put("sessionTimeoutEnabled", p.getBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, true))
    put("language", p.getString("lang", "auto") ?: "auto")
    put("wakePhrases", JSONArray(VoiceService.loadWakePhrases(p)))
    put("onlyMyVoice", UserVoiceProfile.onlyUserVoiceEnabled(c))
    put("verifyEveryInput", UserVoiceProfile.verifyAllInputsEnabled(c))
    put("bypassOnManualTap", UserVoiceProfile.bypassOnManual(c))
    put("ignoreMediaNoise", UserVoiceProfile.rejectMediaNoise(c))
    put("voiceStrictness", UserVoiceProfile.strictnessPreset(c))
    put("voiceMatchThreshold", UserVoiceProfile.threshold(c))
    put("voiceEnrolled", UserVoiceProfile.exists(c))
    put("notificationAutoReplyMaster", Prefs.on(c, "f_notif", false))
    put("aiSmartAutoReply", Prefs.on(c, "f_ai_reply", false))
    put("replyGroupsFallback", Prefs.on(c, "reply_groups", false))
    put("confirmCalls", Prefs.on(c, "confirm_calls", true))
    put("confirmMessages", Prefs.on(c, "confirm_messages", true))
    put("sessionContinuity", Prefs.on(c, "f_session_continuity", true))
    put("bargeIn", Prefs.on(c, "f_barge_in", true))
    put("overlay", Prefs.on(c, "f_overlay", true))
    put("bubble", Prefs.on(c, "f_bubble", false))
    put("screenAutomation", Prefs.on(c, "f_ui", true))
    put("cameraScreenSharingFeature", Prefs.on(c, "f_share", true))
  }

  private fun permissions(c: Context): JSONObject = JSONObject().apply {
    val perms = listOf(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.CALL_PHONE,
      Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS,
      Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
      Manifest.permission.POST_NOTIFICATIONS
    )
    for (p in perms) put(p.substringAfterLast('.'), has(c, p))
    put("overlay", Settings.canDrawOverlays(c))
    put("notificationListener", NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName))
  }

  private fun rules(c: Context): JSONObject = JSONObject().apply {
    val db = Db.get(c)
    val reply = try { db.replyRules() } catch (_: Exception) { emptyList() }
    val auto = try { db.autoRules() } catch (_: Exception) { emptyList() }
    val pre = try { db.rules() } catch (_: Exception) { emptyList() }
    put("replyRuleCount", reply.size)
    put("replyRuleEnabled", reply.count { it.enabled })
    put("replyRules", JSONArray().apply {
      reply.take(30).forEach { r -> put(JSONObject().apply {
        put("id", r.id); put("name", r.name); put("enabled", r.enabled); put("apps", r.apps); put("senders", r.senders)
        put("blockedSenders", r.blockedSenders); put("chatType", r.chatType); put("keywords", r.keywords)
        put("quietStart", r.quietStart); put("quietEnd", r.quietEnd); put("screenOffOnly", r.screenOffOnly)
        put("mode", r.mode); put("instruction", r.instruction.take(300)); put("hasReplyData", r.replyData.isNotBlank())
        put("vipSenders", r.vipSenders); put("muteSenders", r.muteSenders)
      }) }
    })
    put("automationRuleCount", auto.size)
    put("automationRuleEnabled", auto.count { it.enabled })
    put("automationRules", JSONArray().apply {
      auto.take(30).forEach { r -> put(JSONObject().apply {
        put("id", r.id); put("name", r.name); put("enabled", r.enabled); put("trigger", r.trigger)
        put("condition", r.condition.take(200)); put("action", r.action.take(300)); put("cooldownMs", r.cooldownMs)
        put("lastFiredMs", r.lastFiredMs); put("lastFired", fmt(r.lastFiredMs))
      }) }
    })
    put("preActionRuleCount", pre.size)
    put("preActionRuleEnabled", pre.count { it.enabled })
    put("preActionRules", JSONArray().apply {
      pre.take(40).forEach { r -> put(JSONObject().apply { put("id", r.id); put("enabled", r.enabled); put("text", r.text.take(300)) }) }
    })
  }

  private fun history(c: Context): JSONObject = JSONObject().apply {
    val db = Db.get(c)
    val msgs = try { db.chatRecent(1); db.chatBefore(0, 5000).size } catch (_: Exception) { -1 }
    // chatBefore can be heavy; use lightweight count via direct query
    val count = try {
      db.readableDatabase.rawQuery("SELECT COUNT(*) FROM chat", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (_: Exception) { -1 }
    val ss = try { db.sessionState() } catch (_: Exception) { null }
    put("chatMessages", count)
    put("recentSessionStatus", ss?.status ?: "unknown")
    put("recentSessionId", ss?.sessionId ?: "")
    put("activeGoal", ss?.activeGoal ?: "")
    put("lastStep", ss?.lastStep ?: "")
    put("pending", ss?.pending ?: "")
    put("lastError", ss?.lastError ?: "")
    put("recentSessionUpdated", fmt(ss?.updatedTs ?: 0L))
  }

  private fun models(c: Context): JSONObject = JSONObject().apply {
    put("primary", ModelRouter.primaryModel(c))
    put("liveLastUsed", Prefs.plain(c).getString("model_last_used", "") ?: "")
    put("autoFallback", ModelRouter.autoFallbackEnabled(c))
    put("smartSelection", ModelRouter.smartSelectionEnabled(c))
    put("retryCount", ModelRouter.retryCount(c))
    put("retryDelayMs", ModelRouter.retryDelayMs(c))
    put("fallbacks", JSONArray(ModelRouter.fallbackModels(c)))
    put("supportedCache", JSONArray(ModelRouter.supportedCache(c).take(30)))
    put("taskAutoReply", Prefs.plain(c).getString("model_auto_reply", "") ?: "")
    put("taskVision", Prefs.plain(c).getString("model_vision", "") ?: "")
    put("taskComplex", Prefs.plain(c).getString("model_complex", "") ?: "")
    put("lastStatus", ModelRouter.lastStatus(c))
  }

  private fun capabilities(c: Context): JSONObject = JSONObject().apply {
    put("canReadClipboard", true)
    put("canControlSpeakerFocus", true)
    put("canManageWakePhrases", true)
    put("canManageRules", true)
    put("canManageModels", true)
    put("canClearHistoryWithConfirmation", true)
    put("notes", "Destructive actions require confirmation. Auto-reply isolation preserves no personal memory leakage.")
  }

  // ---------- Actions (used by Tools/Voice) ----------
  fun readClipboard(c: Context): String {
    return try {
      val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return "Clipboard unavailable."
      if (!cm.hasPrimaryClip()) return "Clipboard is empty."
      val clip = cm.primaryClip ?: return "Clipboard is empty."
      if (clip.itemCount <= 0) return "Clipboard is empty."
      val item = clip.getItemAt(0)
      val text = item.coerceToText(c)?.toString()?.trim().orEmpty()
      if (text.isBlank()) "Clipboard has no readable text." else "Clipboard: ${text.take(2000)}"
    } catch (e: SecurityException) {
      "Clipboard read blocked by Android privacy restrictions. Open Saara or grant focus, then try again."
    } catch (e: Exception) {
      "Clipboard read failed: ${e.message ?: "unknown error"}"
    }
  }

  fun saveModelSettings(c: Context, primary: String?, fallbacks: List<String>?, autoReply: String?, vision: String?, complex: String?, autoFallback: Boolean?, smart: Boolean?, retryCount: Int?, retryDelayMs: Int?): String {
    val p = Prefs.plain(c).edit()
    primary?.trim()?.takeIf { it.isNotBlank() }?.let { p.putString("model_primary", it).putString("model", it) }
    autoReply?.trim()?.let { p.putString("model_auto_reply", it) }
    vision?.trim()?.let { p.putString("model_vision", it) }
    complex?.trim()?.let { p.putString("model_complex", it) }
    autoFallback?.let { p.putBoolean("f_auto_model_fallback", it) }
    smart?.let { p.putBoolean("f_smart_model", it) }
    retryCount?.let { p.putInt("model_retry_count", it.coerceIn(0, 5)) }
    retryDelayMs?.let { p.putInt("model_retry_delay_ms", it.coerceIn(250, 10000)) }
    p.apply()
    if (fallbacks != null) ModelRouter.saveFallbackModels(c, fallbacks)
    VoiceService.instance?.refreshWakeConfig() // harmless; ensures service sees settings where relevant
    Bus.ping()
    return "Model settings saved. Restart Saara for live model changes to take effect."
  }

  fun detectSupportedModels(c: Context): String {
    val models = ModelRouter.fetchSupportedModels(c)
    if (models.isEmpty()) return "No supported models detected. Check API key and network."
    ModelRouter.saveSupportedCache(c, models)
    return "Detected ${models.size} supported models: ${models.take(12).joinToString(", ")}${if (models.size > 12) "…" else ""}"
  }
}
