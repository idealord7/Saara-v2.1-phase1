package com.saara.assistant
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SaaraNotifications : NotificationListenerService() {
  companion object {
    private val cache = LinkedHashMap<String, Notification.Action>()
    private val lastSeen = HashMap<String, Pair<String, Long>>()
    private val sentTexts = ArrayList<String>()
    @Volatile private var lastKey: String? = null
    @Volatile private var svc: SaaraNotifications? = null

    /** Apps that are auto-replied when the "names" list is left empty. */
    private val MSG_APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
      "com.facebook.orca", "com.facebook.mlite", "com.instagram.android", "org.thoughtcrime.securesms",
      "com.google.android.apps.messaging", "com.samsung.android.messaging")

    /** Finds the inline-reply button: normal actions first, then the Wear extender (WhatsApp / WhatsApp Business also put it there). */
    fun replyAction(n: Notification): Notification.Action? {
      fun ok(a: Notification.Action) = a.remoteInputs?.isNotEmpty() == true
      n.actions?.firstOrNull { ok(it) }?.let { return it }
      try { Notification.WearableExtender(n).actions.firstOrNull { ok(it) }?.let { return it } } catch (_: Exception) {}
      return null
    }

    private fun scan(key: String): Notification.Action? = try {
      svc?.activeNotifications
        ?.asSequence()
        ?.filter { it.key == key || it.packageName in MSG_APPS }
        ?.sortedByDescending { it.postTime }
        ?.firstNotNullOfOrNull { replyAction(it.notification) }
    } catch (_: Exception) { null }

    fun reply(key: String, text: String): String {
      val a = synchronized(cache) { cache[key] ?: lastKey?.let { cache[it] } } ?: scan(key)
        ?: return "no reply button found for that notification (it may have been dismissed). Do not retry."
      val ri = a.remoteInputs?.firstOrNull() ?: return "no remote input"
      val ctx = svc ?: return "Notification access is not active. Ask the user to enable Notification access in the Saara app."
      val i = Intent(); val b = Bundle().apply { putCharSequence(ri.resultKey, text) }
      RemoteInput.addResultsToIntent(a.remoteInputs, i, b)
      if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(i, RemoteInput.SOURCE_FREE_FORM_INPUT)
      return try {
        a.actionIntent.send(ctx, 0, i)
        synchronized(sentTexts) { sentTexts.add(text); if (sentTexts.size > 12) sentTexts.removeAt(0) }
        "replied"
      } catch (e: Exception) { "failed: $e" }
    }

    /** Caller name/number shown on the active incoming-call notification, if notification access is enabled. */
    fun callInfo(): Pair<String, String>? {
      val s = svc ?: return null
      val list = try { s.activeNotifications ?: return null } catch (_: Exception) { return null }
      val call = list.asSequence()
        .filter { it.notification.category == Notification.CATEGORY_CALL }
        .maxByOrNull { it.postTime } ?: return null
      val title = call.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
      val text = call.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
      if (title.isBlank() && text.isBlank()) return null
      return title to text
    }

    /** Clicks a button (Answer / Decline ...) on the ongoing call notification. */
    fun callAction(words: List<String>): Boolean {
      val s = svc ?: return false
      val list: Array<StatusBarNotification> = try { s.activeNotifications ?: return false } catch (_: Exception) { return false }
      for (sbn in list) {
        val n = sbn.notification
        val acts = n.actions ?: continue
        if (n.category != Notification.CATEGORY_CALL && acts.none { a -> words.any { w -> a.title?.toString()?.contains(w, true) == true } }) continue
        for (a in acts) {
          val t = a.title?.toString() ?: continue
          if (words.any { t.contains(it, true) }) {
            try { a.actionIntent.send(); return true } catch (_: Exception) {}
          }
        }
      }
      return false
    }
  }

  override fun onListenerConnected() { svc = this }
  override fun onListenerDisconnected() { if (svc === this) svc = null }

  private fun isGroupConversation(n: Notification, sender: String): Boolean {
    if (n.extras.getBoolean("android.isGroupConversation", false)) return true
    val conv = n.extras.getCharSequence("android.conversationTitle")?.toString()?.trim().orEmpty()
    if (conv.isNotEmpty() && !sender.equals(conv, true)) return true
    if (conv.contains("•")) return true
    val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
    if (lines != null && lines.size > 1 && conv.isNotEmpty()) return true
    return false
  }

  private fun extractText(n: Notification): String {
    val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    if (big.isNotBlank()) return big
    val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
    if (lines != null && lines.isNotEmpty()) return lines.last().toString().trim()
    return n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val ctx = applicationContext
    if (!Prefs.on(ctx, "f_notif", false)) return
    val n = sbn.notification
    if (sbn.packageName == packageName || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || n.category == Notification.CATEGORY_CALL) return
    val act = replyAction(n) ?: return
    val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text = extractText(n)
    if (text.isBlank()) return
    val names = (Prefs.plain(ctx).getString("reply_names", "") ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    // Names list empty = reply to every chat of the known messaging apps (WhatsApp, WhatsApp Business, Telegram ...).
    val wanted = if (names.isEmpty()) sbn.packageName in MSG_APPS else names.any { title.lowercase().contains(it) || it.contains(sbn.packageName.lowercase()) }
    if (!wanted) return
    // Ignore our own just-sent reply echoing back as a new notification (prevents reply loops).
    if (synchronized(sentTexts) { sentTexts.any { it.isNotBlank() && (text == it || text.contains(it)) } }) return
    val now = System.currentTimeMillis()
    synchronized(lastSeen) {
      val prev = lastSeen[title]
      if (prev != null && prev.first == text && (now - prev.second) < 8000) return
      lastSeen[title] = text to now
      while (lastSeen.size > 80) lastSeen.remove(lastSeen.keys.first())
    }
    synchronized(cache) {
      cache[sbn.key] = act; lastKey = sbn.key
      while (cache.size > 60) cache.remove(cache.keys.first())
    }
    val isGroup = isGroupConversation(n, title)
    if (AiAutoReply.enabled(ctx)) {
      AiAutoReply.handle(ctx, sbn.key, sbn.packageName, title, text, isGroup)
    } else {
      val vs = VoiceService.instance
      if (vs == null) { Bus.add("system", "Message from $title received (notification reply is on). Start Saara for live handling, or enable AI smart auto-reply for background replies.", true); return }
      vs.onNotification(sbn.key, sbn.packageName, title, text)
    }
  }
}
