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
import android.provider.ContactsContract
import android.provider.MediaStore
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Everything Saara can do on the phone, natively (no Termux needed). */
object Tools {
  fun declarations(): JSONArray = JSONArray("""[{"functionDeclarations":[
{"name":"device_control","description":"Phone controls. action is one of: torch_on, torch_off, volume_up, volume_down, volume_set (level 0-100), open_app (target = app name, e.g. WhatsApp, WhatsApp Business, Instagram, Camera), open_camera, take_photo (opens the camera AND presses the shutter by itself; text = front for a selfie), call (target = contact name or number), answer_call (answers the ringing incoming call), end_call, reject_call, sms (target + text), wifi_panel, bluetooth_panel (opens the system panel; then use ui_action tap_text to flip the switch). open_app returns the new screen contents. Ask once only for destructive/irreversible steps or genuinely unclear targets; clear calls, messages, app opens, taps and typing can proceed.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"},"target":{"type":"STRING"},"text":{"type":"STRING"},"level":{"type":"NUMBER"},"confirmed":{"type":"BOOLEAN"}},"required":["action"]}},
{"name":"ui_action","description":"Control the screen via accessibility. EVERY op returns the new screen contents, so you can keep going without asking again. op is one of: dump_ui (read all screen elements with bounds), tap_text (text = visible label, content description or view id), tap_id (id = view id suffix), tap_xy (x,y from bounds), double_tap (text or x,y), long_press (text or x,y), type (text; ADDS to what is already in the field, set replace=true to overwrite), clear_text, select_all, copy_text (text = text to copy, or blank to copy the focused selection), paste (into the focused field), enter (keyboard Enter/Search/Send), swipe (direction up/down/left/right, or x,y,x2,y2), scroll_down, scroll_up, wait (ms), wait_text (text, timeout ms), back, home, recents, notifications, shutter (press the camera shutter), flip_camera. Ask once only for destructive/irreversible steps or genuinely unclear targets; clear calls, messages, app opens, taps and typing can proceed.","parameters":{"type":"OBJECT","properties":{"op":{"type":"STRING"},"text":{"type":"STRING"},"id":{"type":"STRING"},"x":{"type":"NUMBER"},"y":{"type":"NUMBER"},"x2":{"type":"NUMBER"},"y2":{"type":"NUMBER"},"direction":{"type":"STRING"},"ms":{"type":"NUMBER"},"timeout":{"type":"NUMBER"},"replace":{"type":"BOOLEAN"},"confirmed":{"type":"BOOLEAN"}},"required":["op"]}},
{"name":"remember","description":"Save something you learned about the user so you keep improving: a preference, habit, routine, person/relationship, a correction, or HOW they like tasks done. Call it quietly whenever the user reveals such a thing; do not announce it. category = preference | habit | routine | person | correction | command_style | shop | general.","parameters":{"type":"OBJECT","properties":{"fact":{"type":"STRING"},"category":{"type":"STRING"}},"required":["fact"]}},
{"name":"recall","description":"Search everything you have learned about the user (older than the list in your instructions). query = keywords.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
{"name":"remember_task","description":"Save which tool steps worked for a repeated mobile task so future attempts improve. goal = the task, method = concise working steps. Never include contacts, messages, ledger data, URLs, or screen text.","parameters":{"type":"OBJECT","properties":{"goal":{"type":"STRING"},"method":{"type":"STRING"}},"required":["goal","method"]}},
{"name":"recall_task","description":"Recall which tool steps worked or failed for a similar mobile task. Stored locally on this phone.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
{"name":"request_screen_share","description":"Ask Android for screen-capture permission and start live screen sharing when a task needs to see the current screen. The user sees a permission prompt and must tap Allow. Sharing requested this way stops automatically when the task ends.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"stop_screen_share","description":"Stop live screen sharing when the task no longer needs to see the screen.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"shop_record","description":"Shop-owner ledger. kind = purchase (price the user BOUGHT stock at), sale (price SOLD at), cancellation (cancelled / returned order, price = refunded unit price), price (list or MRP price note). item, price per unit, qty, party = supplier or customer name, note.","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"},"kind":{"type":"STRING"},"price":{"type":"NUMBER"},"qty":{"type":"NUMBER"},"party":{"type":"STRING"},"note":{"type":"STRING"}},"required":["item","kind","price"]}},
{"name":"shop_report","description":"Read the shop ledger: purchase / sale prices, quantities, cancellations and margin per item. item optional (empty = all items).","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"}}}},
{"name":"schedule_task","description":"Schedule a task or reminder for later. text = what to do/say when it fires. Give in_minutes OR at (HH:mm 24h, or yyyy-MM-dd HH:mm). repeat = none | hourly | daily | weekly.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"},"in_minutes":{"type":"NUMBER"},"at":{"type":"STRING"},"repeat":{"type":"STRING"},"confirmed":{"type":"BOOLEAN"}},"required":["text"]}},
{"name":"list_tasks","description":"List scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"cancel_task","description":"Cancel a scheduled task by id. Cancellation needs confirmation.","parameters":{"type":"OBJECT","properties":{"id":{"type":"NUMBER"},"confirmed":{"type":"BOOLEAN"}},"required":["id"]}},
{"name":"reply_notification","description":"Reply to an incoming notification (WhatsApp, WhatsApp Business, Telegram ...) by its key. If the reply text or recipient is unclear, ask first; otherwise send.","parameters":{"type":"OBJECT","properties":{"key":{"type":"STRING"},"text":{"type":"STRING"},"confirmed":{"type":"BOOLEAN"}},"required":["key","text"]}},
{"name":"fetch_url","description":"Fetch a public webpage or Google Sheet shared as 'Anyone with the link' and return its readable text.","parameters":{"type":"OBJECT","properties":{"url":{"type":"STRING"}},"required":["url"]}}
]}]""")

  fun shopReport(c: Context, item: String): String {
    val rows = Db.get(c).shopRows(item, 2000)
    if (rows.isEmpty()) return "no ledger entries" + (if (item.isNotBlank()) " for $item" else "")
    val sb = StringBuilder()
    for ((name, list) in rows.groupBy { it.item.lowercase() }) {
      val p = list.filter { it.kind == "purchase" }; val s = list.filter { it.kind == "sale" }; val x = list.filter { it.kind == "cancellation" }
      val pq = p.sumOf { it.qty }; val sq = s.sumOf { it.qty }
      val avgP = if (pq > 0) p.sumOf { it.price * it.qty } / pq else 0.0
      val avgS = if (sq > 0) s.sumOf { it.price * it.qty } / sq else 0.0
      sb.append(name).append(": bought ").append(pq).append(" (avg ").append("%.2f".format(avgP)).append(", last ").append(p.firstOrNull()?.price ?: "-")
        .append("); sold ").append(sq).append(" (avg ").append("%.2f".format(avgS)).append(", last ").append(s.firstOrNull()?.price ?: "-")
        .append("); cancelled ").append(x.sumOf { it.qty })
      if (pq > 0 && sq > 0) sb.append("; margin/unit ").append("%.2f".format(avgS - avgP))
      val pr = list.firstOrNull { it.kind == "price" }; if (pr != null) sb.append("; listed price ").append(pr.price)
      sb.append("\n")
    }
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
    val result = try {
      when (name) {
        "device_control" -> { Db.get(c).usage("action", a.optString("action")); if (a.optString("action") == "open_app") Db.get(c).usage("app", a.optString("target")); device(c, a) }
        "ui_action" -> ui(c, a)
        "remember" -> { Db.get(c).factAdd(a.optString("category", "general"), a.optString("fact")); "saved" }
        "recall" -> Db.get(c).recall(a.optString("query")).joinToString("\n") { "[${it.cat}] ${it.text}" }.ifBlank { "nothing found" }
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
        "reply_notification" -> if (!Prefs.on(c, "f_notif", false)) off("Notification auto-reply") else SaaraNotifications.reply(a.optString("key"), a.optString("text"))
        "fetch_url" -> fetchUrlText(a.optString("url"))
        else -> "unknown tool"
      }
    } catch (e: Exception) { "error: ${e.message}" }
    if (name !in readOnly && Prefs.on(c, "learn_tasks", true)) {
      try { TaskLearning.note(c, Bus.lastUser, name, a, result) } catch (_: Exception) {}
    }
    return result
  }

  /** Tool output that may be returned to Gemini. Private local databases never go back to the model. */
  fun modelResult(name: String, result: String): String {
    if (name in setOf("recall", "recall_task", "shop_report", "shop_record", "list_tasks", "fetch_url")) {
      return "Completed on this phone. Private local contents were kept on the device."
    }
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
