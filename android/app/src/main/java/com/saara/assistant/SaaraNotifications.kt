package com.saara.assistant
import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class SaaraNotifications : NotificationListenerService() {
  companion object {
    private const val TAG = "SaaraNotif"
    private data class ReplyTarget(val action: Notification.Action, val sender: String, val pkg: String, val ts: Long)
    private val cache = LinkedHashMap<String, ReplyTarget>()
    private val lastSeen = HashMap<String, Pair<String, Long>>()
    private val sentTexts = ArrayList<String>()
    @Volatile private var lastKey: String? = null
    @Volatile private var svc: SaaraNotifications? = null

    /** Apps that are auto-replied when the "names" list is left empty. */
    private val MSG_APPS = setOf(
      "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
      "com.facebook.orca", "com.facebook.mlite", "com.instagram.android", "org.thoughtcrime.securesms",
      "com.google.android.apps.messaging", "com.samsung.android.messaging"
    )

    private fun log(ctx: android.content.Context?, msg: String) {
      Log.d(TAG, msg)
      if (ctx != null) try { AiAutoReply.addLog(ctx, msg) } catch (_: Exception) {}
    }

    private fun normSender(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Finds the inline-reply button: normal actions first, then the Wear extender (WhatsApp / WhatsApp Business also put it there). */
    fun replyAction(n: Notification): Notification.Action? {
      fun ok(a: Notification.Action) = a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null
      n.actions?.firstOrNull { ok(it) }?.let { return it }
      try { Notification.WearableExtender(n).actions.firstOrNull { ok(it) }?.let { return it } } catch (_: Exception) {}
      return null
    }

    private fun cachedTarget(key: String, sender: String): ReplyTarget? {
      val now = System.currentTimeMillis()
      synchronized(cache) {
        cache[key]?.let { if (now - it.ts <= 20 * 60_000L) return it else cache.remove(key) }
        val want = normSender(sender)
        if (want.isNotBlank()) {
          val bySender = cache.entries.asSequence()
            .map { it.value }
            .filter { now - it.ts <= 20 * 60_000L && normSender(it.sender) == want }
            .maxByOrNull { it.ts }
          if (bySender != null) return bySender
        }
        lastKey?.let { k -> cache[k]?.let { if (now - it.ts <= 20 * 60_000L) return it else cache.remove(k) } }
      }
      return null
    }

    private fun scan(key: String, sender: String): ReplyTarget? = try {
      val want = normSender(sender)
      svc?.activeNotifications
        ?.asSequence()
        ?.filter { it.key == key || it.packageName in MSG_APPS }
        ?.sortedByDescending { sbn ->
          var score = sbn.postTime
          if (sbn.key == key) score += 10_000_000L
          val t = normSender(sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
          if (want.isNotBlank() && t == want) score += 5_000_000L
          score
        }
        ?.firstNotNullOfOrNull { sbn ->
          replyAction(sbn.notification)?.let {
            ReplyTarget(it, sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), sbn.packageName, sbn.postTime)
          }
        }
    } catch (_: Exception) { null }

    fun reply(key: String, text: String): String = reply(key, "", text)

    fun requestRebindIfPossible(ctx: android.content.Context) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          val s = svc
          if (s != null) {
            s.requestRebind(ComponentName(ctx, SaaraNotifications::class.java))
            log(ctx, "Requested notification listener rebind")
          } else {
            log(ctx, "Listener not connected; rebind pending system notification access")
          }
        }
      } catch (e: Exception) {
        log(ctx, "requestRebind failed: ${e.message ?: e.javaClass.simpleName}")
      }
    }

    fun isConnected() = svc != null

    fun reply(key: String, sender: String, text: String): String {
      val ctx = svc
      val target = cachedTarget(key, sender) ?: scan(key, sender)
      if (target == null) {
        val msg = "no reply action cached/found for sender='${sender.ifBlank { "unknown" }}' key=$key (notification may be dismissed or expired). Do not retry."
        log(ctx, "Reply failed: $msg")
        return msg
      }
      val a = target.action
      val ri = a.remoteInputs?.firstOrNull()
      if (ri == null) {
        val msg = "no remote input on cached reply action for ${target.sender.ifBlank { sender }}"
        log(ctx, "Reply failed: $msg")
        return msg
      }
      if (ctx == null) {
        return "Notification access is not active. Ask the user to enable Notification access in the Saara app."
      }
      val i = Intent(); val b = Bundle().apply { putCharSequence(ri.resultKey, text) }
      RemoteInput.addResultsToIntent(a.remoteInputs, i, b)
      if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(i, RemoteInput.SOURCE_FREE_FORM_INPUT)
      return try {
        a.actionIntent.send(ctx, 0, i)
        synchronized(sentTexts) { sentTexts.add(text); if (sentTexts.size > 12) sentTexts.removeAt(0) }
        log(ctx, "Direct reply sent to ${target.sender.ifBlank { sender }} (${target.pkg})")
        "replied"
      } catch (e: Exception) {
        val msg = "failed to send direct reply: ${e.message ?: e.javaClass.simpleName}"
        log(ctx, "Reply failed: $msg")
        msg
      }
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

  override fun onListenerConnected() {
    svc = this
    log(applicationContext, "Notification listener connected")
    requestRebindIfNeeded()
  }

  override fun onListenerDisconnected() {
    if (svc === this) svc = null
    log(applicationContext, "Notification listener disconnected; requesting rebind")
    requestRebindIfNeeded()
  }

  private fun requestRebindIfNeeded() = requestRebindIfPossible(applicationContext)

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
    val ex = n.extras
    val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
    val lastLine = lines?.lastOrNull()?.toString()?.trim().orEmpty()
    val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
    val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    // MessagingStyle/summary notifications often have useful latest line in TEXT_LINES.
    val messagingStyle = Build.VERSION.SDK_INT >= 24 && try { n.isStyle(Notification.MessagingStyle::class.java) } catch (_: Exception) { false }
    return when {
      lastLine.isNotBlank() && (messagingStyle || lastLine.length >= text.length) -> lastLine
      big.isNotBlank() -> big
      text.isNotBlank() -> text
      else -> lastLine
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val ctx = applicationContext
    val n = sbn.notification
    val pkg = sbn.packageName
    val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text = extractText(n)
    val preview = "${title.ifBlank { "?" }}: ${text.take(80)}"

    try { AiAutoReply.loadPersisted(ctx) } catch (_: Exception) {}

    if (pkg == packageName) return
    if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) { log(ctx, "Filtered summary · $pkg · $preview"); return }
    if (n.category == Notification.CATEGORY_CALL) { log(ctx, "Filtered call notification · $pkg · $preview"); return }

    val isWhatsApp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
    val messagingStyle = Build.VERSION.SDK_INT >= 24 && try { n.isStyle(Notification.MessagingStyle::class.java) } catch (_: Exception) { false }
    val hasReply = replyAction(n) != null
    if (!isWhatsApp && !messagingStyle && !hasReply) return // avoid noisy non-message notifications

    if (!Prefs.on(ctx, "f_notif", false)) { log(ctx, "Filtered (Notification auto-reply off) · $pkg · $preview"); return }
    if (title.isBlank() && text.isBlank()) { log(ctx, "Filtered empty title/text · $pkg"); return }
    if (text.isBlank()) { log(ctx, "Filtered blank text · $pkg · sender=$title"); return }

    val names = (Prefs.plain(ctx).getString("reply_names", "") ?: "")
      .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val wanted = if (names.isEmpty()) pkg in MSG_APPS else names.any { title.lowercase().contains(it) || it.contains(pkg.lowercase()) }
    if (!wanted) { log(ctx, "Filtered by name/app rule · $pkg · $preview"); return }

    val act = replyAction(n)
    if (act != null) {
      synchronized(cache) {
        cache[sbn.key] = ReplyTarget(act, title, pkg, sbn.postTime)
        lastKey = sbn.key
        while (cache.size > 80) cache.remove(cache.keys.first())
      }
    } else {
      log(ctx, "No direct-reply action now · $pkg · $preview")
    }

    // Ignore our own just-sent reply echoing back as a new notification (prevents reply loops).
    if (synchronized(sentTexts) { sentTexts.any { it.isNotBlank() && (text == it || text.contains(it)) } }) {
      log(ctx, "Filtered self-echo · $pkg · $preview")
      return
    }

    val now = System.currentTimeMillis()
    synchronized(lastSeen) {
      val dedupeKey = "$pkg|${normSender(title)}|$text"
      val prev = lastSeen[dedupeKey]
      if (prev != null && prev.first == text && (now - prev.second) < 8000) {
        log(ctx, "Filtered duplicate · $pkg · $preview")
        return
      }
      lastSeen[dedupeKey] = text to now
      while (lastSeen.size > 120) lastSeen.remove(lastSeen.keys.first())
    }

    val isGroup = isGroupConversation(n, title)
    log(ctx, "Received notification · $pkg · sender=$title · group=$isGroup · replyAction=${act != null}\n← ${text.take(400)}")

    val aiOn = Prefs.on(ctx, "f_ai_reply", false)
    if (aiOn) {
      AiAutoReply.handle(ctx, sbn.key, pkg, title, text, isGroup)
    } else {
      val vs = VoiceService.instance
      if (vs == null) {
        log(ctx, "Not auto-replied (AI smart auto-reply off, Saara not running) · $pkg · $title")
        Bus.add("system", "Message from $title received (notification reply is on). Start Saara for live handling, or enable AI smart auto-reply for background replies.", true)
        return
      }
      log(ctx, "Routing to live VoiceService (AI smart auto-reply off) · $pkg · $title")
      vs.onNotification(sbn.key, pkg, title, text)
    }
  }
}
