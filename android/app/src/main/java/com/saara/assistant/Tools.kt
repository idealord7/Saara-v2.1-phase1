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
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Everything Saara can do on the phone, natively (no Termux needed). */
object Tools {
  fun declarations(): JSONArray = JSONArray("""[{"functionDeclarations":[
{"name":"device_control","description":"Phone controls. action is one of: torch_on, torch_off, volume_up, volume_down, volume_set (level 0-100), open_app (target = app name, e.g. WhatsApp, WhatsApp Business, Instagram, Camera), open_camera, take_photo (opens the camera AND presses the shutter by itself; text = front for a selfie), call (target = contact name or number), answer_call (answers the ringing incoming call), end_call, reject_call, sms (target + text), wifi_panel, bluetooth_panel (opens the system panel; then use ui_action tap_text to flip the switch). open_app returns the new screen contents.","parameters":{"type":"OBJECT","properties":{"action":{"type":"STRING"},"target":{"type":"STRING"},"text":{"type":"STRING"},"level":{"type":"NUMBER"}},"required":["action"]}},
{"name":"ui_action","description":"Control the screen via accessibility. EVERY op returns the new screen contents, so you can keep going without asking the user. op is one of: dump_ui (read all screen elements with bounds), tap_text (text = visible label, content description or view id), tap_xy (x,y from bounds), long_press (text or x,y), type (text; ADDS to what is already in the field, set replace=true to overwrite), clear_text, enter (keyboard Enter/Search/Send), swipe (direction up/down/left/right, or x,y,x2,y2), scroll_down, scroll_up, wait (ms), back, home, recents, notifications, shutter (press the camera shutter), flip_camera.","parameters":{"type":"OBJECT","properties":{"op":{"type":"STRING"},"text":{"type":"STRING"},"x":{"type":"NUMBER"},"y":{"type":"NUMBER"},"x2":{"type":"NUMBER"},"y2":{"type":"NUMBER"},"direction":{"type":"STRING"},"ms":{"type":"NUMBER"},"replace":{"type":"BOOLEAN"}},"required":["op"]}},
{"name":"remember","description":"Save something you learned about the user so you keep improving: a preference, habit, routine, person/relationship, a correction, or HOW they like tasks done. Call it quietly whenever the user reveals such a thing; do not announce it. category = preference | habit | routine | person | correction | command_style | shop | general.","parameters":{"type":"OBJECT","properties":{"fact":{"type":"STRING"},"category":{"type":"STRING"}},"required":["fact"]}},
{"name":"recall","description":"Search everything you have learned about the user (older than the list in your instructions). query = keywords.","parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}},"required":["query"]}},
{"name":"shop_record","description":"Shop-owner ledger. kind = purchase (price the user BOUGHT stock at), sale (price SOLD at), cancellation (cancelled / returned order, price = refunded unit price), price (list or MRP price note). item, price per unit, qty, party = supplier or customer name, note.","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"},"kind":{"type":"STRING"},"price":{"type":"NUMBER"},"qty":{"type":"NUMBER"},"party":{"type":"STRING"},"note":{"type":"STRING"}},"required":["item","kind","price"]}},
{"name":"shop_report","description":"Read the shop ledger: purchase / sale prices, quantities, cancellations and margin per item. item optional (empty = all items).","parameters":{"type":"OBJECT","properties":{"item":{"type":"STRING"}}}},
{"name":"schedule_task","description":"Schedule a task or reminder for later. text = what to do/say when it fires. Give in_minutes OR at (HH:mm 24h, or yyyy-MM-dd HH:mm). repeat = none | hourly | daily | weekly.","parameters":{"type":"OBJECT","properties":{"text":{"type":"STRING"},"in_minutes":{"type":"NUMBER"},"at":{"type":"STRING"},"repeat":{"type":"STRING"}},"required":["text"]}},
{"name":"list_tasks","description":"List scheduled tasks.","parameters":{"type":"OBJECT","properties":{}}},
{"name":"cancel_task","description":"Cancel a scheduled task by id.","parameters":{"type":"OBJECT","properties":{"id":{"type":"NUMBER"}},"required":["id"]}},
{"name":"reply_notification","description":"Reply to an incoming notification (WhatsApp, WhatsApp Business, Telegram ...) by its key.","parameters":{"type":"OBJECT","properties":{"key":{"type":"STRING"},"text":{"type":"STRING"}},"required":["key","text"]}}
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

  fun off(n: String) = "$n is switched OFF in the Saara app. Ask the user to turn it on in the Features section of the app."
  fun need(n: String) = "Android permission missing: $n. Ask the user to tap the Grant button for it in the Saara app."
  private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

  fun run(c: Context, name: String, a: JSONObject): String = try {
    when (name) {
      "device_control" -> { Db.get(c).usage("action", a.optString("action")); if (a.optString("action") == "open_app") Db.get(c).usage("app", a.optString("target")); device(c, a) }
      "ui_action" -> ui(c, a)
      "remember" -> { Db.get(c).factAdd(a.optString("category", "general"), a.optString("fact")); "saved" }
      "recall" -> Db.get(c).recall(a.optString("query")).joinToString("\n") { "[${it.cat}] ${it.text}" }.ifBlank { "nothing found" }
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
      else -> "unknown tool"
    }
  } catch (e: Exception) { "error: ${e.message}" }

  private fun launch(c: Context, i: Intent) {
    val ctx: Context = SaaraAccessibility.instance ?: c
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
  private fun canLaunch(c: Context) = SaaraAccessibility.instance != null || Settings.canDrawOverlays(c)
  private val LAUNCH_HINT = "Android blocks opening apps from the background. Ask the user to enable Floating bubble (Display over other apps) or Screen automation in the Saara app."

  /** The screen after an action, so the model can continue the task itself. */
  private fun screen(c: Context, waitMs: Long = 1300): String {
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
    val inst = SaaraAccessibility.instance ?: return need("Accessibility service (enable Screen automation in the app)")
    val ex = Executors.newSingleThreadExecutor()
    val f = ex.submit(Callable { inst.handle(a) })
    return try { f.get(25, TimeUnit.SECONDS) } catch (e: TimeoutException) { f.cancel(true); "timeout" } finally { ex.shutdown() }
  }
}
