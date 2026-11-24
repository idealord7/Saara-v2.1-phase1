package com.saara.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import java.util.Locale

object Automation {
  private fun pi(c: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(c, (100000 + id).toInt(),
    Intent(c, AutomationReceiver::class.java).putExtra("rule_id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  fun scheduleAll(c: Context) { try { Db.get(c).autoRules().filter { it.enabled }.forEach { schedule(c, it) } } catch (_: Exception) {} }
  fun schedule(c: Context, r: AutoRule) {
    cancel(c, r.id)
    if (!r.enabled) return
    val t = r.trigger.lowercase(Locale.ROOT)
    if (t.startsWith("time") || t == "schedule") {
      val mins = Regex("(\\d+)").find(r.condition)?.groupValues?.getOrNull(1)?.toLongOrNull()
      val at = Regex("(\\d{1,2}:\\d{2})").find(r.condition)?.groupValues?.getOrNull(1)
      val whenMs = when {
        at != null -> {
          val cal = java.util.Calendar.getInstance()
          cal.set(java.util.Calendar.HOUR_OF_DAY, at.substringBefore(':').toInt())
          cal.set(java.util.Calendar.MINUTE, at.substringAfter(':').toInt())
          cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
          if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
          cal.timeInMillis
        }
        mins != null -> System.currentTimeMillis() + mins * 60_000L
        else -> System.currentTimeMillis() + 60_000L
      }
      val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi(c, r.id))
    }
    // Event triggers are evaluated on broadcast/call/notification hooks.
  }
  fun cancel(c: Context, id: Long) { try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, id)) } catch (_: Exception) {} }

  fun onEvent(c: Context, trigger: String, payload: JSONObject = JSONObject()) {
    val rules = try { Db.get(c).autoRules().filter { it.enabled } } catch (_: Exception) { emptyList() }
    val now = System.currentTimeMillis()
    for (r in rules) {
      val trig = r.trigger.lowercase(Locale.ROOT)
      if (!trig.contains(trigger.lowercase(Locale.ROOT)) && trig != "any_event") continue
      if (r.cooldownMs > 0 && now - r.lastFiredMs < r.cooldownMs) continue
      if (!matchesCondition(c, r.condition, payload)) continue
      fire(c, r, "event:$trigger")
    }
  }

  private fun matchesCondition(c: Context, condRaw: String, payload: JSONObject): Boolean {
    val cond = condRaw.lowercase(Locale.ROOT)
    if (cond.isBlank()) return true
    if (cond.contains("screen on")) return (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    if (cond.contains("screen off")) return !(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    Regex("battery\\s*([<>=]+)\\s*(\\d+)").find(cond)?.let { m ->
      val op = m.groupValues[1]; val v = m.groupValues[2].toIntOrNull() ?: return@let
      val level = batteryPct(c)
      return when (op) { "<=" -> level <= v; ">=" -> level >= v; "<" -> level < v; ">" -> level > v; "==","=" -> level == v; else -> false }
    }
    if (cond.contains("active app")) {
      val want = cond.substringAfter("active app").replace("=", " ").trim().trim('"', '\'')
      val cur = (payload.optString("pkg") + " " + payload.optString("app")).lowercase(Locale.ROOT)
      if (want.isNotBlank()) return cur.contains(want)
    }
    if (cond.contains("missed call")) return payload.optString("event").contains("missed", true)
    if (cond.contains("notification")) {
      val want = cond.substringAfter("notification").replace("contains", " ").replace("=", " ").trim()
      return want.isBlank() || payload.optString("text").lowercase(Locale.ROOT).contains(want) || payload.optString("pkg").lowercase(Locale.ROOT).contains(want)
    }
    return true
  }

  private fun batteryPct(c: Context): Int {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
  }

  fun fire(c: Context, r: AutoRule, reason: String) {
    try { Db.get(c).autoRuleMarkFired(r.id) } catch (_: Exception) {}
    Bus.add("system", "Automation fired: ${r.name.ifBlank { "rule ${r.id}" }} ($reason)", true)
    val action = r.action.trim()
    if (action.isBlank()) return
    val delivered = VoiceService.instance?.runScheduled(action) == true
    if (!delivered) {
      // Queue for next active session and show notification.
      try { Db.get(c).factAdd("automation_queue", "Pending: $action", "automation") } catch (_: Exception) {}
      Scheduler.notify(c, "Automation pending: ${r.name.ifBlank { action.take(60) }}")
      Bus.add("system", "Automation queued (offline/service inactive): $action", true)
    }
    if (r.trigger.lowercase(Locale.ROOT).contains("time")) schedule(c, Db.get(c).autoRules().firstOrNull { it.id == r.id } ?: r)
  }
}

class AutomationReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    val id = i.getLongExtra("rule_id", -1L)
    if (id <= 0L) return
    val r = try { Db.get(c).autoRules().firstOrNull { it.id == id } } catch (_: Exception) { null } ?: return
    if (r.enabled) Automation.fire(c, r, "time")
  }
}
