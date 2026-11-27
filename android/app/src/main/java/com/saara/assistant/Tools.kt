package com.saara.assistant
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.text.Html
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import java.util.concurrent.TimeUnit as JTimeUnit
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Everything Saara can do on the phone, natively (no Termux needed). */
object Tools {
  fun declarations(): JSONArray = JSONArray("""[{"functionDeclarations":[
{"name":"device_control","description":"Phone controls. action is one of: torch_on, torch_off, volume_up, volume_down, volume_set (level 0-100), open_app (target = app name, e.g. WhatsApp, WhatsApp Business, Instagram, Camera), open_camera, take_photo (opens the camera AND presses the shutter by itself; text = front for a selfie), call (target = contact name or number), answer_call, end_call, reject_call, sms (target + text), read_sms (target optional contact/name/number, limit), read_call_log (target optional, limit), read_contacts (target query optional, limit), wifi_panel, bluetooth_panel. open_app returns the new screen contents. PRE-TASK: before acting, check relevant memory/rules and current device/app context. For direct person actions (call/message/contact lookup), ALWAYS use read_contacts/read_sms/read_call_log or direct device actions rather than relying only on cached memory. Ask once only for destructive/irreversible steps or genuinely unclear targets; clear calls, messages, reads, app opens, taps and typing can proceed.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"},"target":{"type":"STRING"},"text":{"type":"STRING"},"level":{"type":"NUMBER"},"limit":{"type":"NUMBER"},"confirmed":{"type":"BOOLEAN"}},"required":["action"]}},
{"name":"ui_action","description":"Control screen via accessibility. EVERY op returns new screen. op: dump_ui,dump_small,list_fields,tap_text,tap_id,tap_xy,tap_key (tap on-screen keyboard key by text/key),double_tap,long_press,type,clear_text,select_all,copy_text,paste,enter,swipe,scroll_down,scroll_up,wait,wait_text,back,home,recents,notifications,shutter,flip_camera. For keyboard typing, prefer type on focused field; for individual key taps use tap_key or dump_ui+tap_xy on key bounds. Keep trying alternate UI paths until success; report status after major steps. FORM RULES: for 2+ inputs call list_fields, fill one field at a time, verify target, no bulk dump into one field unless allow_bulk=true. If ambiguous, ask user to clarify.","parameters":{"type":"OBJECT","properties":{"op":{"type":"STRING"},"text":{"type":"STRING"},"id":{"type":"STRING"},"key":{"type":"STRING"},"x":{"type":"NUMBER"},"y":{"type":"NUMBER"},"x2":{"type":"NUMBER"},"y2":{"type":"NUMBER"},"direction":{"type":"STRING"},"ms":{"type":"NUMBER"},"timeout":{"type":"NUMBER"},"replace":{"type":"BOOLEAN"},"field_index":{"type":"NUMBER"},"field_id":{"type":"STRING"},"field_label":{"type":"STRING"},"allow_bulk":{"type":"BOOLEAN"},"confirmed":{"type":"BOOLEAN"}},"required":["op"]}},
{"name":"remember","description":"Save a concise user fact for improvement. Store brief, categorized, non-conversational facts; consolidate related details under clear headwords (personal/work/preferences/shop). Prioritize new over stale data and update/remove obsolete facts on correction. Call quietly; do not announce. category = personal | work | preferences | shop | general.","parameters":{"type":"OBJECT","properties":{"fact":{"type":"STRING"},"category":{"type":"STRING"}},"required":["fact"]}},
{"name":"recall","description":"Search everything you have learned about the user (older than the list in your instructions). query = keywords.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
{"name":"remember_task","description":"Save which tool steps worked for a repeated mobile task so future attempts improve. goal = the task, method = concise working steps. Never include contacts, messages, ledger data, URLs, or screen text.","parameters":{"type":"OBJECT","properties":{"goal":{"type":"STRING"},"method":{"type":"STRING"}},"required":["goal","method"]}},
{"name":"recall_task","description":"Recall which tool steps worked or failed for a similar mobile task. Stored locally on this phone.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
{"name":"request_screen_share","description":"Ask Android for screen-capture permission and start live screen sharing when a task needs to see the current screen. The user sees a permission prompt and must tap Allow. Sharing requested this way stops automatically when the task ends.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"stop_screen_share","description":"Stop live screen sharing when the task no longer needs to see the screen.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"capture_screen_context","description":"Capture current screen once (with user permission prompt if needed) and return/summarize what is on screen: active app, forms, chats, invoices, errors, and suggested next steps.","parameters":{"type":"OBJECT","properties":{"mode":{"type":"STRING","description":"context|ocr"}}}},
{"name":"screen_ocr_extract","description":"Capture screen and extract structured form/invoice/bill fields as JSON/text. Use before autofill or when user asks to read details from screen.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"shop_record","description":"Shop-owner ledger. kind = purchase (price the user BOUGHT stock at), sale (price SOLD at), cancellation (cancelled / returned order, price = refunded unit price), price (list or MRP price note). item, price per unit, qty, party = supplier or customer name, note.","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"},"kind":{"type":"STRING"},"price":{"type":"NUMBER"},"qty":{"type":"NUMBER"},"party":{"type":"STRING"},"note":{"type":"STRING"}},"required":["item","kind","price"]}},
{"name":"shop_report","description":"Read the shop ledger: purchase / sale prices, quantities, cancellations and margin per item. item optional (empty = all items).","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"}}}},
{"name":"schedule_task","description":"Schedule a task or reminder for later. text = what to do/say when it fires. Give in_minutes OR at (HH:mm 24h, or yyyy-MM-dd HH:mm). repeat = none | hourly | daily | weekly.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"},"in_minutes":{"type":"NUMBER"},"at":{"type":"STRING"},"repeat":{"type":"STRING"},"confirmed":{"type":"BOOLEAN"}},"required":["text"]}},
{"name":"list_tasks","description":"List scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"cancel_task","description":"Cancel a scheduled task by id. Cancellation needs confirmation.","parameters":{"type":"OBJECT","properties":{"id":{"type":"NUMBER"},"confirmed":{"type":"BOOLEAN"}},"required":["id"]}},
{"name":"reply_notification","description":"Reply to an incoming notification by key/sender (WhatsApp, WhatsApp Business, Telegram, SMS app notifications, etc.). Use when user asks to reply to a received message/notification. If key is unknown, provide sender/name; tool will target latest matching cached notification. Works via Notification access without opening app.","parameters":{"type":"OBJECT","properties":{"key":{"type":"STRING"},"sender":{"type":"STRING"},"text":{"type":"STRING"},"confirmed":{"type":"BOOLEAN"}},"required":["text"]}},
{"name":"send_direct_message","description":"Send a message directly without opening apps when possible. channel: sms | whatsapp | whatsapp_business | telegram. target=contact name or number. For sms sends via SMS manager. For WhatsApp/Telegram uses latest notification reply action for that sender/chat (no app open) and reports if no replyable notification exists.","parameters":{"type":"OBJECT","properties":{"channel":{"type":"STRING"},"target":{"type":"STRING"},"text":{"type":"STRING"},"confirmed":{"type":"BOOLEAN"}},"required":["channel","target","text"]}},
{"name":"fetch_url","description":"Fetch a public webpage or Google Sheet shared as 'Anyone with the link' and return its readable text.","parameters":{"type":"OBJECT","properties":{"url":{"type":"STRING"}},"required":["url"]}},
{"name":"read_saara_self_state","description":"Read Saara's real app settings/state/capabilities. Use for questions like current settings, status, wake phrases, speaker focus, rules, auto-reply toggles, models, chat/session counts. section optional: all|status|settings|permissions|rules|history|models|capabilities.","parameters":{"type":"OBJECT","properties":{"section":{"type":"STRING"}}}},
{"name":"saara_app_control","description":"Read or control Saara app settings/rules/models/history via voice. Actions: read_settings, read_clipboard, set_session_timeout, toggle_session_timeout, read_language, set_language, list_wake_phrases, add_wake_phrase, remove_wake_phrase, toggle_only_my_voice, toggle_verify_every_input, toggle_bypass_manual_tap, toggle_ignore_media_noise, set_voice_strictness, set_voice_match_threshold, enroll_voice, clear_voice_sample, test_voice_match, list_automation_rules, add_automation_rule, edit_automation_rule, delete_automation_rule, toggle_auto_reply_master, toggle_ai_auto_reply, list_reply_rules, add_reply_rule, edit_reply_rule, delete_reply_rule, list_pre_action_rules, add_pre_action_rule, edit_pre_action_rule, delete_pre_action_rule, clear_chat_history, clear_recent_session_state, detect_supported_models, set_model_settings, save_model_settings, clean_duplicates, list_behaviors, start_behavior_recording, stop_behavior_recording, replay_behavior, delete_behavior. Destructive actions require confirmed=true.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"},"section":{"type":"STRING"},"value":{"type":"STRING"},"enabled":{"type":"BOOLEAN"},"seconds":{"type":"NUMBER"},"percent":{"type":"NUMBER"},"id":{"type":"NUMBER"},"name":{"type":"STRING"},"text":{"type":"STRING"},"trigger":{"type":"STRING"},"condition":{"type":"STRING"},"ruleAction":{"type":"STRING"},"cooldownMs":{"type":"NUMBER"},"apps":{"type":"STRING"},"senders":{"type":"STRING"},"blockedSenders":{"type":"STRING"},"chatType":{"type":"STRING"},"keywords":{"type":"STRING"},"quietStart":{"type":"STRING"},"quietEnd":{"type":"STRING"},"screenOffOnly":{"type":"BOOLEAN"},"mode":{"type":"STRING"},"instruction":{"type":"STRING"},"replyData":{"type":"STRING"},"vipSenders":{"type":"STRING"},"muteSenders":{"type":"STRING"},"primaryModel":{"type":"STRING"},"fallbackModels":{"type":"STRING"},"autoReplyModel":{"type":"STRING"},"visionModel":{"type":"STRING"},"complexModel":{"type":"STRING"},"autoFallback":{"type":"BOOLEAN"},"smartSelection":{"type":"BOOLEAN"},"retryCount":{"type":"NUMBER"},"retryDelayMs":{"type":"NUMBER"},"confirmed":{"type":"BOOLEAN"}},"required":["action"]}}
]}]""")

  fun shopReport(c: Context, item: String): String {
    val (rows, summary) = Db.get(c).shopSummary(item, 10)
    val totalCount = Db.get(c).shopRows(item, 5000).size
    if (rows.isEmpty() && totalCount == 0) return "no ledger entries" + (if (item.isNotBlank()) " for $item" else "")
    val sb = StringBuilder()
    sb.append(summary).append("\n")
    val grouped = rows.groupBy { it.item.lowercase() }
    for ((name, list) in grouped) {
      val p = list.filter { it.kind == "purchase" }; val s = list.filter { it.kind == "sale" }; val x = list.filter { it.kind == "cancellation" }
      val pq = p.sumOf { it.qty }; val sq = s.sumOf { it.qty }
      val avgP = if (pq > 0) p.sumOf { it.price * it.qty } / pq else 0.0
      val avgS = if (sq > 0) s.sumOf { it.price * it.qty } / sq else 0.0
      sb.append("- ").append(name).append(": bought ").append(pq).append(" @avg ").append("%.2f".format(avgP))
        .append("; sold ").append(sq).append(" @avg ").append("%.2f".format(avgS))
        .append("; cancelled ").append(x.sumOf { it.qty })
      if (pq > 0 && sq > 0) sb.append("; margin/unit ").append("%.2f".format(avgS - avgP))
      val pr = list.firstOrNull { it.kind == "price" }; if (pr != null) sb.append("; listed ").append(pr.price)
      sb.append("\n")
    }
    if (totalCount > rows.size) sb.append("- older records: ").append(totalCount - rows.size).append(" summarized (not listed)\n")
    return sb.toString().take(3500)
  }


  /** Fetch public web pages and public Google Sheets without blocking the UI. Call from a worker thread. */
  fun fetchUrlText(rawUrl: String): String {
    val raw = rawUrl.trim()
    if (raw.isBlank()) return "error: URL is empty"
    val parsed = try { Uri.parse(raw) } catch (_: Exception) { return "error: invalid URL" }
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.lowercase()
    if (scheme !in listOf("http", "https") || host.isNullOrBlank()) return "error: only valid http/https links are supported"
    if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1" || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.") || host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\..*"))) {
      return "error: local/private network URLs are blocked"
    }
    var target = raw
    val sheet = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)").find(parsed.encodedPath ?: "")
    if (sheet != null && host.contains("google.com")) {
      val fragmentGid = Regex("(?:^|&)gid=([0-9]+)").find(parsed.fragment ?: "")?.groupValues?.get(1)
      val gid = parsed.getQueryParameter("gid") ?: fragmentGid ?: "0"
      target = "https://docs.google.com/spreadsheets/d/${sheet.groupValues[1]}/export?format=csv&gid=${Uri.encode(gid)}"
    }
    val client = OkHttpClient.Builder().followRedirects(true).callTimeout(25, JTimeUnit.SECONDS)
      .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
          val addresses: List<InetAddress> = InetAddress.getAllByName(hostname).toList()
          if (addresses.isEmpty() || addresses.any { address ->
              address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            }) {
            throw UnknownHostException("private or local network address blocked")
          }
          return addresses
        }
      }).build()
    return try {
      val request = Request.Builder().url(target).header("User-Agent", "SaaraAndroid/2.2.2").get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return "error: server returned HTTP ${response.code}. The page may require sign-in or sharing permission."
        val body = response.body?.string()?.take(2_000_000).orEmpty()
        if (body.isBlank()) return "error: the page returned no readable content"
        if (sheet != null && (body.contains("accounts.google.com", true) || body.contains("Sign in", true) && body.contains("<html", true))) {
          return "error: this Google Sheet is private. Change General access to 'Anyone with the link' or connect Google OAuth."
        }
        val type = response.header("Content-Type", "").orEmpty().lowercase()
        val text = if (type.contains("text/html") || body.contains("<html", true) || body.contains("<!doctype html", true)) {
          val clean = body.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
          Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\\n\\n").trim()
        } else body.trim()
        if (text.isBlank()) "error: no readable text was found" else "Fetched from $raw\\n\\n" + text.take(12000)
      }
    } catch (e: Exception) { "error: ${e.message ?: "could not fetch URL"}. Check internet access and whether the page is public." }
  }

  fun off(n: String) = "$n is switched OFF in the Saara app. Ask the user to turn it on in the Features section of the app."
  fun need(n: String) = "Android permission missing: $n. Ask the user to tap the Grant button for it in the Saara app."
  private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

  fun run(c: Context, name: String, a: JSONObject): String {
    val readOnly = setOf("remember", "recall", "remember_task", "recall_task", "shop_report", "list_tasks")
    if (TaskControl.isStop(Bus.lastUser) && name !in readOnly && name != "stop_screen_share") {
      TaskControl.clear()
      return "STOPPED: the user interrupted the task. Do not continue. Acknowledge briefly and wait for a new instruction."
    }
    val confirmation = TaskControl.confirmationFor(name, a)
    if (confirmation != null && !TaskControl.isApproved()) return TaskControl.request(confirmation)
    try { Db.get(c).recall(Bus.lastUser, 6) } catch (_: Exception) {} // pre-task memory touch/prune
    val started = System.currentTimeMillis()
    val result = try {
      when (name) {
        "device_control" -> { Db.get(c).usage("action", a.optString("action")); if (a.optString("action") == "open_app") Db.get(c).usage("app", a.optString("target")); device(c, a) }
        "ui_action" -> ui(c, a)
        "remember" -> { Db.get(c).factAdd(a.optString("category", "general"), a.optString("fact")); "saved" }
        "recall" -> Db.get(c).recall(a.optString("query")).joinToString("\n") { "- [${it.cat}] ${it.text}" }.ifBlank { "nothing found" }
        "remember_task" -> TaskLearning.remember(c, a.optString("goal"), a.optString("method"))
        "recall_task" -> TaskLearning.recall(c, a.optString("query"))
        "request_screen_share" -> when {
          !Prefs.on(c, "f_share", true) -> off("Camera & screen sharing")
          !Bus.running -> "Saara is not started. Ask the user to tap Start, then try again."
          Bus.screenOn -> "screen sharing is already on"
          !canLaunch(c) -> LAUNCH_HINT
          else -> {
            Bus.add("system", "Screen sharing is needed for this task. Opening the Android permission prompt…", true)
            launch(c, Intent(c, ScreenAskActivity::class.java).putExtra("auto", true))
            "screen sharing prompt opened; ask the user to tap Allow"
          }
        }
        "stop_screen_share" -> {
          if (Bus.screenOn) c.startService(Intent(c, VoiceService::class.java).setAction("SCREEN_STOP"))
          "screen sharing stopped"
        }
        "capture_screen_context" -> {
          if (!Prefs.on(c, "f_share", true)) off("Camera & screen sharing")
          else if (!Bus.running) "Saara is not started. Ask the user to tap Start, then try again."
          else if (!canLaunch(c)) LAUNCH_HINT
          else {
            val mode = a.optString("mode", "context").ifBlank { "context" }
            launch(c, Intent(c, ScreenAskActivity::class.java).putExtra("mode", if (mode == "ocr") "ocr" else "capture"))
            "screen capture prompt opened; after Allow Saara will analyze the screen"
          }
        }
        "screen_ocr_extract" -> {
          if (!Prefs.on(c, "f_share", true)) off("Camera & screen sharing")
          else if (!Bus.running) "Saara is not started. Ask the user to tap Start, then try again."
          else if (!canLaunch(c)) LAUNCH_HINT
          else { launch(c, Intent(c, ScreenAskActivity::class.java).putExtra("mode", "ocr")); "screen OCR prompt opened; after Allow Saara will extract structured fields" }
        }
        "shop_record" -> {
          val kind = a.optString("kind").lowercase().let { k -> when { k.startsWith("pur") || k.startsWith("buy") || k.startsWith("bought") -> "purchase"; k.startsWith("sal") || k.startsWith("sell") || k.startsWith("sold") -> "sale"; k.startsWith("can") || k.startsWith("ret") || k.startsWith("ref") -> "cancellation"; else -> "price" } }
          Db.get(c).shopAdd(a.optString("item"), kind, a.optDouble("price", 0.0), a.optDouble("qty", 1.0), a.optString("party"), a.optString("note"))
          Db.get(c).usage("action", "shop_$kind")
          "recorded $kind: ${a.optString("item")} @ ${a.optDouble("price", 0.0)} x ${a.optDouble("qty", 1.0)}"
        }
        "shop_report" -> shopReport(c, a.optString("item"))
        "schedule_task" -> Scheduler.add(c, a.optString("text"), a.optDouble("in_minutes", 0.0), a.optString("at"), a.optString("repeat"))
        "list_tasks" -> Scheduler.list(c)
        "cancel_task" -> { Scheduler.cancel(c, a.optLong("id")); "cancelled" }
        "reply_notification" -> if (!Prefs.on(c, "f_notif", false)) off("Notification auto-reply") else SaaraNotifications.reply(a.optString("key"), a.optString("sender"), a.optString("text").trim().ifBlank { return "error: reply text is empty" })
        "send_direct_message" -> sendDirect(c, a)
        "fetch_url" -> fetchUrlText(a.optString("url"))
        "read_saara_self_state" -> SaaraSelfState.snapshot(c, a.optString("section", "all"))
        "saara_app_control" -> saaraAppControl(c, a)
        else -> "unknown tool"
      }
    } catch (e: Exception) { "error: ${e.message}" }
    val ok = !result.startsWith("error") && !result.startsWith("unknown") && !result.startsWith("timeout") && !result.contains("permission missing", true)
    if (name !in readOnly && Prefs.on(c, "learn_tasks", true)) {
      try { TaskLearning.note(c, Bus.lastUser, name, a, result) } catch (_: Exception) {}
    }
    if (Prefs.on(c, "f_post_verify", true) && name in setOf("ui_action","device_control","send_direct_message","reply_notification","request_screen_share","stop_screen_share")) {
      try { PostTaskVerifier.verify(c, Bus.lastUser, name, a.toString(), result, ok) } catch (_: Exception) {}
    }
    return result
  }

  /** Tool output that may be returned to Gemini. Sensitive local DB contents are limited/redacted. */
  fun modelResult(name: String, result: String): String {
    if (name in setOf("recall", "recall_task")) {
      return redact(result).take(2500).ifBlank { "nothing found" }
    }
    if (name in setOf("shop_report", "shop_record", "list_tasks", "fetch_url")) {
      return "Completed on this phone. Private local contents were kept on the device."
    }
    if (name == "read_saara_self_state") return result.take(7000)
    if (name == "saara_app_control") return result.take(3500)
    if (name in setOf("device_control", "reply_notification", "send_direct_message")) return result.take(6000)
    // User explicitly asked for on-device reads/actions (contacts/messages/call log/notifications).
    // Keep output bounded but do not replace with a generic privacy placeholder.
    return redact(result).take(6000)
  }

  private fun redact(s: String): String = s
    .replace(Regex("\\b[+0-9][0-9 ()-]{7,}\\b"), "[number]")
    .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[email]")

  private fun launch(c: Context, i: Intent) {
    val ctx: Context = SaaraAccessibility.instance ?: c
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
  private fun canLaunch(c: Context) = SaaraAccessibility.instance != null || Settings.canDrawOverlays(c)
  private val LAUNCH_HINT = "Android blocks opening apps from the background. Ask the user to enable Floating bubble (Display over other apps) or Screen automation in the Saara app."

  /** The screen after an action, so the model can continue the task itself. */
  private fun screen(c: Context, waitMs: Long = 1000): String {
    val inst = SaaraAccessibility.instance ?: return ""
    if (!Prefs.on(c, "f_ui")) return ""
    Thread.sleep(waitMs)
    return " Screen now: " + inst.handle(JSONObject().put("op", "dump_small"))
  }

  @Suppress("DEPRECATION")
  private fun smsMgr(c: Context): SmsManager =
    if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

  private fun number(c: Context, target: String): String? {
    if (Regex("^[+0-9 ()-]{5,}$").matches(target)) return target.replace(" ", "")
    if (!has(c, Manifest.permission.READ_CONTACTS)) return null
    val ph = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    c.contentResolver.query(ph, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$target%"), null)?.use {
      if (it.moveToFirst()) return it.getString(0)
    }
    return null
  }

  private fun fmtTs(ms: Long): String = if (ms <= 0L) "unknown time" else SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))

  private fun readContacts(c: Context, q: String, limit: Int): String {
    if (!has(c, Manifest.permission.READ_CONTACTS)) return need("Contacts")
    val l = limit.coerceIn(1, 50)
    val out = ArrayList<String>()
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    val sel = if (q.isBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
    val args = if (q.isBlank()) null else arrayOf("%$q%", "%$q%")
    try {
      c.contentResolver.query(uri, proj, sel, args, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT $l")?.use { cur ->
        while (cur.moveToNext()) { val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty(); if (name.isNotBlank() || num.isNotBlank()) out.add("${name.ifBlank { "Unknown" }}: $num") }
      }
    } catch (_: Exception) {
      c.contentResolver.query(uri, proj, sel, args, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cur ->
        var n = 0
        while (cur.moveToNext() && n < l) { val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty(); if (name.isNotBlank() || num.isNotBlank()) { out.add("${name.ifBlank { "Unknown" }}: $num"); n++ } }
      }
    }
    return if (out.isEmpty()) "No contacts found" + if (q.isNotBlank()) " for '$q'" else "" else out.joinToString("\n")
  }

  private fun readCallLog(c: Context, target: String, limit: Int): String {
    if (!has(c, Manifest.permission.READ_CALL_LOG)) return need("Call log")
    val l = limit.coerceIn(1, 30)
    val uri = CallLog.Calls.CONTENT_URI
    val proj = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
    val sel = if (target.isBlank()) null else "${CallLog.Calls.CACHED_NAME} LIKE ? OR ${CallLog.Calls.NUMBER} LIKE ?"
    val args = if (target.isBlank()) null else arrayOf("%$target%", "%$target%")
    val out = ArrayList<String>()
    try {
      c.contentResolver.query(uri, proj, sel, args, CallLog.Calls.DATE + " DESC LIMIT $l")?.use { cur ->
        while (cur.moveToNext()) {
          val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty()
          val type = when (cur.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "call" }
          val who = if (name.isNotBlank()) "$name ($num)" else num.ifBlank { "unknown" }
          out.add("$type · $who · ${fmtTs(cur.getLong(3))} · ${cur.getLong(4)}s")
        }
      }
    } catch (_: Exception) {
      try {
        val q = android.os.Bundle().apply { putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, null); putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel); putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args); putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(CallLog.Calls.DATE)); putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING); putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, l) }
        c.contentResolver.query(uri, proj, q, null)?.use { cur ->
          while (cur.moveToNext()) {
            val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty()
            val type = when (cur.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "call" }
            val who = if (name.isNotBlank()) "$name ($num)" else num.ifBlank { "unknown" }
            out.add("$type · $who · ${fmtTs(cur.getLong(3))} · ${cur.getLong(4)}s")
          }
        }
      } catch (_: Exception) {
        c.contentResolver.query(uri, proj, sel, args, CallLog.Calls.DATE + " DESC")?.use { cur ->
          var n = 0
          while (cur.moveToNext() && n < l) {
            val name = cur.getString(0).orEmpty(); val num = cur.getString(1).orEmpty()
            val type = when (cur.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "call" }
            val who = if (name.isNotBlank()) "$name ($num)" else num.ifBlank { "unknown" }
            out.add("$type · $who · ${fmtTs(cur.getLong(3))} · ${cur.getLong(4)}s"); n++
          }
        }
      }
    }
    return if (out.isEmpty()) "No call log entries found" + if (target.isNotBlank()) " for '$target'" else "" else out.joinToString("\n")
  }

  private fun readSms(c: Context, target: String, limit: Int): String {
    if (!has(c, Manifest.permission.READ_SMS)) return need("SMS read")
    val l = limit.coerceIn(1, 30)
    val uri = Telephony.Sms.CONTENT_URI
    val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    val sel = if (target.isBlank()) null else "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?"
    val args = if (target.isBlank()) null else arrayOf("%$target%", "%$target%")
    val out = ArrayList<String>()
    try {
      c.contentResolver.query(uri, proj, sel, args, Telephony.Sms.DATE + " DESC LIMIT $l")?.use { cur ->
        while (cur.moveToNext()) {
          val addr = cur.getString(0).orEmpty(); val body = cur.getString(1).orEmpty().replace("\n", " ").take(180)
          val type = when (cur.getInt(3)) { Telephony.Sms.MESSAGE_TYPE_INBOX -> "in"; Telephony.Sms.MESSAGE_TYPE_SENT -> "out"; else -> "sms" }
          out.add("$type · ${addr.ifBlank { "unknown" }} · ${fmtTs(cur.getLong(2))} · $body")
        }
      }
    } catch (_: Exception) {
      c.contentResolver.query(uri, proj, sel, args, Telephony.Sms.DATE + " DESC")?.use { cur ->
        var n = 0
        while (cur.moveToNext() && n < l) {
          val addr = cur.getString(0).orEmpty(); val body = cur.getString(1).orEmpty().replace("\n", " ").take(180)
          val type = when (cur.getInt(3)) { Telephony.Sms.MESSAGE_TYPE_INBOX -> "in"; Telephony.Sms.MESSAGE_TYPE_SENT -> "out"; else -> "sms" }
          out.add("$type · ${addr.ifBlank { "unknown" }} · ${fmtTs(cur.getLong(2))} · $body"); n++
        }
      }
    }
    return if (out.isEmpty()) "No SMS messages found" + if (target.isNotBlank()) " for '$target'" else "" else out.joinToString("\n")
  }


  private fun reqConfirm(action: String, detail: String): String =
    "CONFIRMATION REQUIRED: $detail has NOT been done. Ask the user to confirm clearly. After confirmation, retry saara_app_control with action='$action' and confirmed=true."

  private fun syncSettingsUi() {
    try { Bus.ping() } catch (_: Exception) {}
  }

  private fun saaraAppControl(c: Context, a: JSONObject): String {
    val action = a.optString("action").trim().lowercase(Locale.ROOT)
    val confirmed = a.optBoolean("confirmed", false)
    val db = Db.get(c)
    val p = Prefs.plain(c)
    fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    val destructive = setOf("clear_chat_history", "clear_recent_session_state", "clear_voice_sample", "delete_automation_rule", "delete_reply_rule", "delete_pre_action_rule", "delete_behavior", "clean_duplicates")
    if (action in destructive && !confirmed) {
      return when (action) {
        "clear_chat_history" -> reqConfirm(action, "clear all saved chat history")
        "clear_recent_session_state" -> reqConfirm(action, "clear recent session state")
        "clear_voice_sample" -> reqConfirm(action, "clear enrolled voice sample")
        "delete_automation_rule" -> reqConfirm(action, "delete automation rule #${a.optLong("id")}")
        "delete_reply_rule" -> reqConfirm(action, "delete replying rule #${a.optLong("id")}")
        "delete_pre_action_rule" -> reqConfirm(action, "delete pre-action rule #${a.optLong("id")}")
        "delete_behavior" -> reqConfirm(action, "delete behavior #${a.optLong("id")}")
        "clean_duplicates" -> reqConfirm(action, "clean duplicate/near-duplicate memory and rules")
        else -> reqConfirm(action, action)
      }
    }

    return when (action) {
      "read_settings", "read_all", "read_status" -> SaaraSelfState.snapshot(c, a.optString("section", "all"))
      "read_clipboard" -> SaaraSelfState.readClipboard(c)

      "set_session_timeout" -> {
        val sec = a.optInt("seconds", a.optInt("value", -1)).coerceIn(0, 999)
        p.edit().putInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, sec).putBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, sec > 0).apply()
        VoiceService.instance?.refreshWakeConfig(); syncSettingsUi()
        if (sec <= 0) "Session timeout turned OFF." else "Session timeout set to ${sec}s."
      }
      "toggle_session_timeout" -> {
        val enabled = if (a.has("enabled")) a.optBoolean("enabled") else !(p.getBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, true) && p.getInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, VoiceService.DEFAULT_SESSION_TIMEOUT_SEC) > 0)
        var sec = p.getInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, VoiceService.DEFAULT_SESSION_TIMEOUT_SEC).coerceIn(0, 999)
        if (enabled && sec <= 0) sec = VoiceService.DEFAULT_SESSION_TIMEOUT_SEC
        p.edit().putBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, enabled).putInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, sec).apply()
        VoiceService.instance?.refreshWakeConfig(); syncSettingsUi()
        if (enabled) "Session timeout ON (${sec}s)." else "Session timeout OFF."
      }

      "read_language" -> "Language priority: ${p.getString("lang", "auto") ?: "auto"}"
      "set_language" -> {
        val v = a.optString("value", a.optString("text", "")).trim().lowercase(Locale.ROOT)
        val lang = when {
          v in setOf("auto","bn","hi","en","hinglish") -> v
          v.contains("bang") || v.contains("ben") -> "bn"
          v.contains("hin") -> "hi"
          v.contains("english") || v == "eng" -> "en"
          v.contains("hinglish") -> "hinglish"
          else -> return "error: invalid language. Use auto, bn, hi, en, or hinglish."
        }
        p.edit().putString("lang", lang).apply(); syncSettingsUi()
        "Language priority set to $lang. Restart Saara to fully apply live prompt behavior."
      }

      "list_wake_phrases" -> "Wake phrases: ${VoiceService.loadWakePhrases(p).joinToString(", ")}"
      "add_wake_phrase" -> {
        val phrase = a.optString("value", a.optString("text", a.optString("name", ""))).trim()
        if (phrase.isBlank()) return "error: wake phrase is required"
        val cur = VoiceService.loadWakePhrases(p)
        if (cur.none { it.equals(phrase, true) }) cur.add(phrase)
        val e = p.edit(); VoiceService.saveWakePhrases(e, cur); e.apply()
        VoiceService.instance?.refreshWakeConfig(); syncSettingsUi()
        "Wake phrase added: $phrase"
      }
      "remove_wake_phrase" -> {
        val phrase = a.optString("value", a.optString("text", a.optString("name", ""))).trim()
        if (phrase.isBlank()) return "error: wake phrase is required"
        val cur = VoiceService.loadWakePhrases(p)
        val removed = cur.removeAll { it.equals(phrase, true) }
        val e = p.edit(); VoiceService.saveWakePhrases(e, cur); e.apply()
        VoiceService.instance?.refreshWakeConfig(); syncSettingsUi()
        if (removed) "Wake phrase removed: $phrase" else "Wake phrase not found: $phrase"
      }

      "toggle_only_my_voice" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !UserVoiceProfile.onlyUserVoiceEnabled(c)
        p.edit().putBoolean("f_only_user_voice", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Only Catch My Voice ${onOff(v)}."
      }
      "toggle_verify_every_input" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !UserVoiceProfile.verifyAllInputsEnabled(c)
        p.edit().putBoolean("f_voice_verify_all", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Verify every voice input & command ${onOff(v)}."
      }
      "toggle_bypass_manual_tap" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !UserVoiceProfile.bypassOnManual(c)
        p.edit().putBoolean("f_voice_match_bypass_manual", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Bypass on manual tap / unmute ${onOff(v)}."
      }
      "toggle_ignore_media_noise" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !UserVoiceProfile.rejectMediaNoise(c)
        p.edit().putBoolean("f_voice_reject_media", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Ignore media/TV/background playback noise ${onOff(v)}."
      }
      "set_voice_strictness" -> {
        val v = a.optString("value", a.optString("text", "")).trim().lowercase(Locale.ROOT)
        val preset = when { v.startsWith("relax") -> "relaxed"; v.startsWith("strict") -> "strict"; v.startsWith("bal") -> "balanced"; else -> return "error: strictness must be Relaxed, Balanced, or Strict" }
        val pct = UserVoiceProfile.applyStrictnessPreset(c, preset)
        VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Voice Match Strictness set to ${preset.replaceFirstChar { it.uppercase() }} (${pct}%)."
      }
      "set_voice_match_threshold" -> {
        val pct = a.optInt("percent", a.optInt("value", -1)).coerceIn(0, 100)
        UserVoiceProfile.setThreshold(c, pct)
        p.edit().putString("voice_match_strictness", "custom").apply()
        VoiceService.instance?.refreshSpeakerGuardConfig(); syncSettingsUi()
        "Minimum Voice Match set to ${pct}%."
      }
      "enroll_voice" -> {
        val vs = VoiceService.instance ?: return "Saara service is not running. Start Saara, then ask to enroll voice."
        vs.enrollUserVoice(6, onProgress = { prog -> Bus.add("system", "Voice enrollment ${prog.step}/${prog.total}: ${prog.message}", true) }, onDone = { res ->
          Bus.add("system", if (res.ok) "Voice enrollment complete: ${res.message}" else "Voice enrollment failed: ${res.message}", true)
          syncSettingsUi()
        })
        "Voice enrollment started. Follow the on-screen/audio prompts and speak naturally."
      }
      "clear_voice_sample" -> {
        VoiceService.instance?.clearUserVoiceEnrollment() ?: run { UserVoiceProfile.clear(c); Bus.add("system", "Enrolled voice sample cleared.", true) }
        syncSettingsUi(); "Enrolled voice sample cleared."
      }
      "test_voice_match" -> {
        if (!UserVoiceProfile.exists(c)) return "No voice sample enrolled."
        Thread {
          try { VoiceService.instance?.suspendWakeForEnrollment(); Thread.sleep(350) } catch (_: Exception) {}
          val (pct, err) = VoiceEnrollment.quickCheck(c)
          try { VoiceService.instance?.resumeWakeAfterEnrollment() } catch (_: Exception) {}
          val th = UserVoiceProfile.threshold(c)
          val msg = if (pct == null) "Voice match test failed: ${err ?: "unknown error"}" else "Voice match ${"%.1f".format(pct)}% vs threshold ${th}%: ${if (pct >= th) "ACCEPT" else "REJECT"}"
          Bus.add("system", msg, true)
        }.start()
        "Voice match test started. Speak now for about 3 seconds."
      }

      "list_automation_rules" -> {
        val rules = db.autoRules()
        if (rules.isEmpty()) "No automation rules." else rules.joinToString("\n") { "#${it.id} ${if (it.enabled) "ON" else "OFF"} ${it.name} · trigger=${it.trigger} · condition=${it.condition} · action=${it.action}" }
      }
      "add_automation_rule" -> {
        val r = AutoRule(0, a.optString("name"), a.optBoolean("enabled", true), a.optString("trigger", "time"), a.optString("condition"), a.optString("ruleAction", a.optString("actionText", a.optString("text"))), a.optLong("cooldownMs", 0L), 0L, System.currentTimeMillis())
        if (r.action.isBlank() && r.trigger.isBlank()) return "error: automation rule needs trigger/action"
        val id = db.autoRuleAdd(r); Automation.scheduleAll(c); syncSettingsUi(); "Automation rule added #$id."
      }
      "edit_automation_rule" -> {
        val id = a.optLong("id", -1L); val ex = db.autoRules().firstOrNull { it.id == id } ?: return "error: automation rule not found"
        val r = AutoRule(id, a.optString("name", ex.name), if (a.has("enabled")) a.optBoolean("enabled") else ex.enabled, a.optString("trigger", ex.trigger), a.optString("condition", ex.condition), a.optString("ruleAction", a.optString("actionText", a.optString("text", ex.action))), if (a.has("cooldownMs")) a.optLong("cooldownMs") else ex.cooldownMs, ex.lastFiredMs, System.currentTimeMillis())
        db.autoRuleUpdate(r); Automation.scheduleAll(c); syncSettingsUi(); "Automation rule #$id updated."
      }
      "delete_automation_rule" -> { val id = a.optLong("id", -1L); db.autoRuleDelete(id); Automation.cancel(c, id); syncSettingsUi(); "Automation rule #$id deleted." }

      "toggle_auto_reply_master" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !Prefs.on(c, "f_notif", false)
        p.edit().putBoolean("f_notif", v).apply(); syncSettingsUi(); "Notification auto-reply master ${onOff(v)}."
      }
      "toggle_ai_auto_reply" -> {
        val v = if (a.has("enabled")) a.optBoolean("enabled") else !Prefs.on(c, "f_ai_reply", false)
        p.edit().putBoolean("f_ai_reply", v).apply(); syncSettingsUi(); "AI smart auto-reply (Gemini) ${onOff(v)}."
      }

      "list_reply_rules" -> {
        val rules = db.replyRules()
        if (rules.isEmpty()) "No replying rules." else rules.joinToString("\n") { "#${it.id} ${if (it.enabled) "ON" else "OFF"} ${it.name} · apps=${it.apps} · senders=${it.senders} · keywords=${it.keywords} · mode=${it.mode}" }
      }
      "add_reply_rule" -> {
        val r = ReplyRule(0, a.optString("name"), a.optBoolean("enabled", true), a.optString("apps"), a.optString("senders"), a.optString("blockedSenders"), a.optString("chatType", "any"), a.optString("keywords"), a.optString("quietStart"), a.optString("quietEnd"), a.optBoolean("screenOffOnly", false), a.optString("mode", "ai"), a.optString("instruction", a.optString("text")), System.currentTimeMillis(), a.optString("vipSenders"), a.optString("muteSenders"), a.optString("replyData"))
        val id = db.replyRuleAdd(r); syncSettingsUi(); "Replying rule added #$id."
      }
      "edit_reply_rule" -> {
        val id = a.optLong("id", -1L); val ex = db.replyRules().firstOrNull { it.id == id } ?: return "error: replying rule not found"
        val r = ReplyRule(id, a.optString("name", ex.name), if (a.has("enabled")) a.optBoolean("enabled") else ex.enabled, a.optString("apps", ex.apps), a.optString("senders", ex.senders), a.optString("blockedSenders", ex.blockedSenders), a.optString("chatType", ex.chatType), a.optString("keywords", ex.keywords), a.optString("quietStart", ex.quietStart), a.optString("quietEnd", ex.quietEnd), if (a.has("screenOffOnly")) a.optBoolean("screenOffOnly") else ex.screenOffOnly, a.optString("mode", ex.mode), a.optString("instruction", a.optString("text", ex.instruction)), System.currentTimeMillis(), a.optString("vipSenders", ex.vipSenders), a.optString("muteSenders", ex.muteSenders), a.optString("replyData", ex.replyData))
        db.replyRuleUpdate(r); syncSettingsUi(); "Replying rule #$id updated."
      }
      "delete_reply_rule" -> { val id = a.optLong("id", -1L); db.replyRuleDelete(id); syncSettingsUi(); "Replying rule #$id deleted." }

      "list_pre_action_rules" -> {
        val rules = db.rules()
        if (rules.isEmpty()) "No pre-action rules." else rules.joinToString("\n") { "#${it.id} ${if (it.enabled) "ON" else "OFF"} ${it.text}" }
      }
      "add_pre_action_rule" -> {
        val text = a.optString("text", a.optString("value", "")).trim()
        if (text.isBlank()) return "error: rule text is required"
        val id = db.ruleAdd(text); syncSettingsUi(); "Pre-action rule added #$id."
      }
      "edit_pre_action_rule" -> {
        val id = a.optLong("id", -1L); val text = a.optString("text", a.optString("value", "")).trim()
        if (id <= 0L || text.isBlank()) return "error: id and text are required"
        db.ruleUpdate(id, text); syncSettingsUi(); "Pre-action rule #$id updated."
      }
      "delete_pre_action_rule" -> { val id = a.optLong("id", -1L); db.ruleDelete(id); syncSettingsUi(); "Pre-action rule #$id deleted." }

      "clear_chat_history" -> { Bus.clear(); syncSettingsUi(); "Chat history cleared." }
      "clear_recent_session_state" -> { try { db.sessionStateClear(true) } catch (_: Exception) {}; syncSettingsUi(); "Recent session state cleared." }

      "detect_supported_models" -> SaaraSelfState.detectSupportedModels(c)
      "set_model_settings", "save_model_settings" -> {
        val fallbacks = a.optString("fallbackModels").takeIf { it.isNotBlank() }?.split('\n', ',')?.map { it.trim() }?.filter { it.isNotBlank() }
        SaaraSelfState.saveModelSettings(c,
          a.optString("primaryModel", a.optString("value", "")).takeIf { it.isNotBlank() || a.has("primaryModel") || a.has("value") },
          fallbacks,
          a.optString("autoReplyModel").takeIf { a.has("autoReplyModel") },
          a.optString("visionModel").takeIf { a.has("visionModel") },
          a.optString("complexModel").takeIf { a.has("complexModel") },
          if (a.has("autoFallback")) a.optBoolean("autoFallback") else null,
          if (a.has("smartSelection")) a.optBoolean("smartSelection") else null,
          if (a.has("retryCount")) a.optInt("retryCount") else null,
          if (a.has("retryDelayMs")) a.optInt("retryDelayMs") else null
        ).also { syncSettingsUi() }
      }

      "clean_duplicates" -> {
        val fd = db.findDuplicateFacts(); val rd = db.findDuplicateRules()
        val total = fd.sumOf { it.size - 1 } + rd.sumOf { it.size - 1 }
        if (total == 0) "No duplicates found."
        else { val rf = db.cleanDuplicateFacts(); val rr = db.cleanDuplicateRules(); syncSettingsUi(); "Cleaned ${rf + rr} duplicate entries (facts=$rf, rules=$rr)." }
      }
      "list_behaviors" -> {
        val bs = db.behaviors()
        if (bs.isEmpty()) "No recorded behaviors." else bs.joinToString("\n") { "#${it.id} ${it.name} (${BehaviorRecorder.parseSteps(it.stepsJson).size} steps)" }
      }
      "start_behavior_recording", "record_my_steps", "follow_my_steps" -> {
        if (SaaraAccessibility.instance == null) return "Enable Accessibility service to record steps."
        BehaviorRecorder.start(c); syncSettingsUi(); "Behavior recording started. Perform your steps, then say stop recording steps."
      }
      "stop_behavior_recording", "stop_recording_steps" -> {
        if (!BehaviorRecorder.isRecording()) return "No active behavior recording."
        BehaviorRecorder.stopAndSave(c) { syncSettingsUi() }; "Stopping recording and asking for behavior name."
      }
      "replay_behavior", "run_behavior" -> {
        val name = a.optString("name", a.optString("value", a.optString("text", ""))).trim()
        val id = a.optLong("id", -1L)
        val b = if (id > 0L) db.behaviors().firstOrNull { it.id == id } else db.behaviorByName(name)
        if (b == null) return "Behavior not found${if (name.isNotBlank()) ": $name" else ""}."
        BehaviorRecorder.replay(c, b); "Replaying behavior: ${b.name}"
      }
      "delete_behavior" -> { val id = a.optLong("id", -1L); db.behaviorDelete(id); syncSettingsUi(); "Behavior #$id deleted." }

      else -> "error: unknown saara_app_control action '$action'"
    }
  }

  private fun sendDirect(c: Context, a: JSONObject): String {
    val channel = a.optString("channel").trim().lowercase()
    val target = a.optString("target").trim()
    val text = a.optString("text").trim()
    if (target.isBlank() || text.isBlank()) return "error: target and text are required"
    return when (channel) {
      "sms", "text", "message" -> {
        if (!Prefs.on(c, "f_phone", true)) return off("Phone (calls & SMS)")
        if (!has(c, Manifest.permission.SEND_SMS)) return need("SMS")
        val n = number(c, target) ?: return if (has(c, Manifest.permission.READ_CONTACTS)) "contact not found: $target" else need("Contacts")
        val sm = smsMgr(c)
        sm.sendMultipartTextMessage(n, null, sm.divideMessage(text), null, null)
        "sms sent to $n"
      }
      "whatsapp", "wa", "whatsapp_business", "wa_business", "wab", "telegram", "notification" -> {
        if (!Prefs.on(c, "f_notif", false)) return off("Notification auto-reply")
        val wantPkg = when (channel) {
          "whatsapp", "wa" -> "com.whatsapp"
          "whatsapp_business", "wa_business", "wab" -> "com.whatsapp.w4b"
          "telegram" -> "org.telegram.messenger"
          else -> ""
        }
        val r = SaaraNotifications.replyLatest(target, text, wantPkg)
        if (r == "replied") r else "$r. If no recent replyable notification exists, ask user to send once or enable notification access."
      }
      else -> "unknown channel: $channel (use sms, whatsapp, whatsapp_business, telegram)"
    }
  }

  // ---------- app lookup: handles "WhatsApp Business", Hindi names, typos ----------
  private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "")
  private val ALIASES = listOf(
    "whatsappbusiness" to "com.whatsapp.w4b", "whatsappbussiness" to "com.whatsapp.w4b", "whatsappbiz" to "com.whatsapp.w4b",
    "businesswhatsapp" to "com.whatsapp.w4b", "wabusiness" to "com.whatsapp.w4b", "whatsapp4business" to "com.whatsapp.w4b", "w4b" to "com.whatsapp.w4b",
    "watsappbusiness" to "com.whatsapp.w4b", "व्हाट्सएपबिजनेस" to "com.whatsapp.w4b", "व्हाट्सएपबिज़नेस" to "com.whatsapp.w4b",
    "वॉट्सऐपबिजनेस" to "com.whatsapp.w4b", "व्हाट्सऐपबिजनेस" to "com.whatsapp.w4b", "बिजनेसव्हाट्सएप" to "com.whatsapp.w4b",
    "whatsapp" to "com.whatsapp", "watsapp" to "com.whatsapp", "whatsap" to "com.whatsapp",
    "व्हाट्सएप" to "com.whatsapp", "वॉट्सऐप" to "com.whatsapp", "व्हाट्सऐप" to "com.whatsapp"
  ).sortedByDescending { it.first.length }

  private fun findApp(pm: PackageManager, target: String): Pair<String, Intent>? {
    val t = norm(target)
    if (t.isEmpty()) return null
    // 1) well-known aliases -> exact package
    ALIASES.firstOrNull { t.contains(it.first) }?.let { (_, pkg) ->
      pm.getLaunchIntentForPackage(pkg)?.let { return Pair(pkg, it) }
      return null   // known app but not installed
    }
    // 2) score every launchable app by its label / package
    val apps: List<ResolveInfo> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    var best: ResolveInfo? = null; var bestScore = 0
    for (ri in apps) {
      val label = norm(ri.loadLabel(pm).toString()); val pkg = ri.activityInfo.packageName.lowercase()
      val s = when {
        label == t -> 100
        label.startsWith(t) -> 85
        label.contains(t) -> 75
        label.length >= 3 && t.contains(label) -> 65
        pkg.contains(t) -> 50
        else -> 0
      }
      if (s > bestScore || (s == bestScore && s > 0 && label.length < norm(best?.loadLabel(pm)?.toString() ?: "").length)) { best = ri; bestScore = s }
    }
    val b = best ?: return null
    return Pair(b.loadLabel(pm).toString(), Intent().setClassName(b.activityInfo.packageName, b.activityInfo.name))
  }

  // ---------- calls ----------
  private fun answerCall(c: Context): String {
    if (Build.VERSION.SDK_INT >= 26 && has(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
      try { c.getSystemService(TelecomManager::class.java).acceptRingingCall(); return "call answered" } catch (_: Exception) {}
    }
    if (SaaraNotifications.callAction(listOf("answer", "accept", "pick up", "receive", "जवाब", "उत्तर", "उठाएं", "स्वीकार", "रिसीव"))) return "call answered"
    val inst = SaaraAccessibility.instance
    if (inst != null && Prefs.on(c, "f_ui")) return inst.handle(JSONObject().put("op", "answer_call"))
    return need("Answer phone calls (tap Grant phone, SMS & contacts)")
  }
  private fun endCall(c: Context): String {
    if (Build.VERSION.SDK_INT >= 28 && has(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
      try { if (c.getSystemService(TelecomManager::class.java).endCall()) return "call ended" } catch (_: Exception) {}
    }
    if (SaaraNotifications.callAction(listOf("decline", "reject", "hang up", "hangup", "end call", "disconnect", "काट", "अस्वीकार", "रिजेक्ट"))) return "call ended"
    val inst = SaaraAccessibility.instance
    if (inst != null && Prefs.on(c, "f_ui")) {
      for (k in listOf("Decline", "Reject", "End call", "Hang up", "End")) {
        val r = inst.handle(JSONObject().put("op", "tap_text").put("text", k))
        if (!r.startsWith("not found") && !r.startsWith("error")) return "call ended"
      }
    }
    return need("Answer phone calls (tap Grant phone, SMS & contacts)")
  }

  private fun device(c: Context, a: JSONObject): String {
    val act = a.optString("action")
    if (act in setOf("call", "sms", "answer_call", "end_call", "reject_call")) { if (!Prefs.on(c, "f_phone")) return off("Phone (calls & SMS)") }
    else if (!Prefs.on(c, "f_device")) return off("Device controls")
    val am = c.getSystemService(AudioManager::class.java)
    when (act) {
      "read_contacts" -> return readContacts(c, a.optString("target"), a.optInt("limit", 20))
      "read_sms", "read_messages" -> return readSms(c, a.optString("target"), a.optInt("limit", 20))
      "read_call_log", "read_calls" -> return readCallLog(c, a.optString("target"), a.optInt("limit", 20))
      "torch_on", "torch_off" -> {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return "this phone has no flashlight"
        cm.setTorchMode(id, act == "torch_on"); return "ok"
      }
      "volume_up" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return "ok" }
      "volume_down" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return "ok" }
      "volume_set" -> {
        val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, a.optInt("level", 50) * mx / 100, AudioManager.FLAG_SHOW_UI); return "ok"
      }
      "wifi_panel" -> { if (!canLaunch(c)) return LAUNCH_HINT; launch(c, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); return "wifi panel opened" + screen(c) }
      "bluetooth_panel" -> { if (!canLaunch(c)) return LAUNCH_HINT; launch(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return "bluetooth settings opened" + screen(c) }
      "open_app" -> {
        if (!canLaunch(c)) return LAUNCH_HINT
        val t = a.optString("target")
        val (label, intent) = findApp(c.packageManager, t) ?: return "app not found or not installed: $t. Do not ask the user; try another spelling or find it in the app drawer with ui_action."
        launch(c, intent)
        return "opened $label." + screen(c)
      }
      "open_camera" -> {
        if (!canLaunch(c)) return LAUNCH_HINT
        launch(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return "camera opened." + screen(c)
      }
      "take_photo" -> {
        if (!canLaunch(c)) return LAUNCH_HINT
        launch(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        val inst = SaaraAccessibility.instance
        if (inst == null || !Prefs.on(c, "f_ui")) return "camera opened, but Screen automation is OFF so I cannot press the shutter. Ask the user to enable Screen automation in the Saara app."
        Thread.sleep(2200)
        val front = a.optString("text").let { it.contains("front", true) || it.contains("selfie", true) }
        if (front) { inst.handle(JSONObject().put("op", "flip_camera")); Thread.sleep(700) }
        return inst.handle(JSONObject().put("op", "shutter"))
      }
      "call" -> {
        val t = a.optString("target")
        val n = number(c, t) ?: return if (has(c, Manifest.permission.READ_CONTACTS)) "contact not found: $t" else need("Contacts")
        if (!canLaunch(c)) return LAUNCH_HINT
        return if (has(c, Manifest.permission.CALL_PHONE)) { launch(c, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)))); "calling $n" }
        else { launch(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n)))); "opened dialer; phone permission not granted so the user must press call" }
      }
      "answer_call" -> return answerCall(c)
      "end_call", "reject_call" -> return endCall(c)
      "sms" -> {
        if (!has(c, Manifest.permission.SEND_SMS)) return need("SMS")
        val t = a.optString("target")
        val n = number(c, t) ?: return if (has(c, Manifest.permission.READ_CONTACTS)) "contact not found: $t" else need("Contacts")
        val sm = smsMgr(c)
        sm.sendMultipartTextMessage(n, null, sm.divideMessage(a.optString("text")), null, null)
        return "sms sent to $n"
      }
    }
    return "unknown action: $act"
  }

  private fun ui(c: Context, a: JSONObject): String {
    if (!Prefs.on(c, "f_ui")) return off("Screen automation")
    if (a.optString("op") in setOf("copy_text", "paste") && !Prefs.on(c, "f_clipboard", true)) return off("Clipboard copy & paste")
    val inst = SaaraAccessibility.instance ?: return need("Accessibility service (enable Screen automation in the app)")
    val ex = Executors.newSingleThreadExecutor()
    val f = ex.submit(Callable { inst.handle(a) })
    return try { f.get(25, TimeUnit.SECONDS) } catch (e: TimeoutException) { f.cancel(true); "timeout" } finally { ex.shutdown() }
  }
}
