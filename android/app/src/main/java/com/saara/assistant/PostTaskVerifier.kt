package com.saara.assistant

import android.content.Context
import org.json.JSONObject

object PostTaskVerifier {
  fun verify(c: Context, goal: String, tool: String, args: String, result: String, ok: Boolean): String {
    val db = try { Db.get(c) } catch (_: Exception) { return result }
    var verified = ok
    var note = if (ok) "ok" else "tool reported failure"
    try {
      if (tool == "ui_action") {
        val op = try { JSONObject(args).optString("op") } catch (_: Exception) { "" }
        val inst = SaaraAccessibility.instance
        if (inst != null) {
          val screen = inst.handle(JSONObject().put("op", "dump_small"))
          if (!ok) {
            val alt = suggestAlt(op)
            if (alt != null) note = "retry suggestion: $alt; screen=${screen.take(220)}"
            verified = false
          } else {
            // basic verification: if action claimed tapped/typed but screen dump failed, mark uncertain
            verified = !screen.startsWith("error") && !screen.startsWith("screen not readable")
            note = if (verified) "verified by screen dump" else "screen unreadable after action"
          }
        }
      } else if (tool == "device_control") {
        verified = ok
        note = if (ok) "device action returned ok" else "device action needs fallback: $result"
      } else if (tool == "reply_notification" || tool == "send_direct_message") {
        verified = result == "replied" || result.contains("sent", true)
        note = if (verified) "delivery confirmed by tool result" else "delivery not confirmed: $result"
      }
    } catch (e: Exception) {
      verified = false
      note = "verify error: ${e.message}"
    }
    try { db.actionVerificationAdd(goal, tool, args, result, ok, verified, 0, note) } catch (_: Exception) {}
    return if (verified || ok) result else "$result\n[VERIFY] $note"
  }

  private fun suggestAlt(op: String): String? = when (op) {
    "tap_text" -> "try tap_id/tap_key or dump_ui+tap_xy"
    "tap_id" -> "try tap_text or dump_ui+tap_xy"
    "tap_xy" -> "try tap_text/tap_id with dump_ui bounds"
    "type", "clear_text", "paste" -> "verify focused field with list_fields, focus then retry; use copy_text/paste fallback"
    "scroll_down", "scroll_up" -> "try swipe gesture or dump_ui to find target"
    else -> "dump_ui and choose alternate path"
  }
}
