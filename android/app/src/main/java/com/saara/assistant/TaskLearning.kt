package com.saara.assistant

import android.content.Context
import org.json.JSONObject

/** Learns which on-device tool steps worked or failed for mobile tasks. Stored only in the local SQLite DB. */
object TaskLearning {
  private val number = Regex("\\b[+0-9][0-9 ()-]{7,}\\b")
  private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
  private val sensitiveKeys = setOf("text", "target", "url", "key", "query", "fact", "note", "party", "item", "method", "id")
  private val usefulKeys = setOf("action", "op", "direction", "front", "replace", "ms", "level")

  private fun clean(text: String, max: Int): String = text.substringBefore("Screen now:")
    .replace(number, "[number]").replace(email, "[email]")
    .replace(Regex("\\s+"), " ").trim().take(max)

  private fun safeArgs(args: JSONObject): String {
    val out = JSONObject()
    for (key in args.keys()) {
      if (key.lowercase() in sensitiveKeys) continue
      if (key.lowercase() in usefulKeys) out.put(key, args.opt(key))
    }
    if (args.optString("action") in setOf("open_app", "open_camera")) {
      val target = args.optString("target")
      if (target.isNotBlank() && !target.any { it.isDigit() }) out.put("target", target.take(40))
    }
    return out.toString().take(300)
  }

  private fun isSuccess(result: String): Boolean {
    val low = result.lowercase()
    return !low.startsWith("error") && !low.startsWith("timeout") && !low.startsWith("unknown") &&
      !low.contains("permission missing") && !low.contains("is switched off") && !low.contains("not found") &&
      !low.contains("could not") && !low.contains("was cancelled")
  }

  fun note(c: Context, goal: String, tool: String, args: JSONObject, result: String) {
    val g = clean(goal, 180)
    if (g.isBlank() || tool.isBlank()) return
    Db.get(c).taskStepAdd(g, tool.take(40), safeArgs(args), clean(result, 700), isSuccess(result))
  }

  fun remember(c: Context, goal: String, method: String): String {
    val g = clean(goal.ifBlank { Bus.lastUser }, 180)
    val m = clean(method, 500)
    if (g.isBlank() || m.isBlank()) return "nothing to remember"
    Db.get(c).taskStepAdd(g, "remember_task", "", m, true)
    return "saved task method"
  }

  fun recall(c: Context, goal: String): String {
    val rows = Db.get(c).taskStepsFor(goal, 12)
    if (rows.isEmpty()) return "nothing found"
    return rows.joinToString("\n") { s ->
      val state = if (s.ok) "worked" else "failed"
      val args = if (s.args.isBlank() || s.args == "{}") "" else " ${s.args}"
      "- ${s.goal} | ${s.tool}$args: $state - ${s.result.take(180)}"
    }.take(3500)
  }

  /** Short, non-personal task-method hints injected into the live session. */
  fun prompt(c: Context): String {
    val rows = Db.get(c).taskSteps(120)
    if (rows.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("LOCAL TASK MEMORY (tool steps only; no contacts, messages, ledger, or screen text):\n")
    for ((goal, list) in rows.groupBy { it.goal }.entries.take(8)) {
      val worked = list.filter { it.ok && it.tool != "remember_task" }.map { it.tool }.distinct().take(6)
      val failed = list.filter { !it.ok }.map { "${it.tool}: ${it.result.take(90)}" }.distinct().take(3)
      val manual = list.firstOrNull { it.tool == "remember_task" }?.result
      sb.append("- ").append(goal.take(120))
      if (worked.isNotEmpty()) sb.append(" | worked: ").append(worked.joinToString(" then "))
      if (failed.isNotEmpty()) sb.append(" | avoid: ").append(failed.joinToString("; "))
      if (!manual.isNullOrBlank()) sb.append(" | method: ").append(manual.take(180))
      sb.append("\n")
    }
    return sb.toString().take(3000)
  }
}
