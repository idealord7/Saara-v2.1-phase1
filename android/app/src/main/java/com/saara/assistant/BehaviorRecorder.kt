package com.saara.assistant

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object BehaviorRecorder {
  data class Step(
    val pkg: String,
    val cls: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val action: String,
    val ts: Long
  )

  @Volatile private var recording = false
  private val steps = ArrayList<Step>()
  private val main = Handler(Looper.getMainLooper())
  private var lastSig = ""
  private var lastTs = 0L

  fun isRecording(): Boolean = recording

  fun start(ctx: Context) {
    synchronized(steps) { steps.clear() }
    lastSig = ""; lastTs = 0L
    recording = true
    Bus.add("system", "Behavior recording started. Perform your steps, then stop recording.", true)
    Toast.makeText(ctx, "Recording started", Toast.LENGTH_SHORT).show()
  }

  fun stopAndSave(ctx: Context, onDone: () -> Unit = {}) {
    if (!recording) { onDone(); return }
    recording = false
    val snap = synchronized(steps) { ArrayList(steps) }
    if (snap.isEmpty()) {
      Bus.add("system", "Recording stopped. No steps captured.", true)
      Toast.makeText(ctx, "No steps captured", Toast.LENGTH_SHORT).show()
      onDone(); return
    }
    try {
      val input = EditText(ctx).apply { hint = "Behavior name"; setText("Behavior ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}") }
      AlertDialog.Builder(ctx).setTitle("Save recorded behavior").setView(input)
        .setPositiveButton("Save") { _, _ ->
          val name = input.text.toString().trim().ifBlank { "Recorded behavior" }
          val id = Db.get(ctx).behaviorAdd(name, toJson(snap).toString())
          Bus.add("system", "Saved behavior #$id: $name (${snap.size} steps)", true)
          Toast.makeText(ctx, "Behavior saved", Toast.LENGTH_SHORT).show()
          onDone()
        }
        .setNegativeButton("Discard") { _, _ -> onDone() }
        .setOnCancelListener { onDone() }
        .show()
    } catch (_: Exception) {
      val name = "Behavior ${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
      val id = Db.get(ctx).behaviorAdd(name, toJson(snap).toString())
      Bus.add("system", "Saved behavior #$id: $name (${snap.size} steps)", true)
      onDone()
    }
  }

  fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!recording || event == null) return
    val action = when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_CLICKED -> "click"
      AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_click"
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "set_text"
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
      else -> return
    }
    val src = try { event.source } catch (_: Exception) { null }
    val pkg = event.packageName?.toString().orEmpty()
    val cls = event.className?.toString().orEmpty()
    val viewId = try { src?.viewIdResourceName?.substringAfter('/') ?: "" } catch (_: Exception) { "" }
    val text = try { (src?.text ?: event.text?.joinToString(" ") ?: "").toString().trim().take(120) } catch (_: Exception) { "" }
    val desc = try { (src?.contentDescription ?: "").toString().trim().take(120) } catch (_: Exception) { "" }
    try { src?.recycle() } catch (_: Exception) {}
    if (pkg.isBlank() && text.isBlank() && desc.isBlank() && viewId.isBlank()) return
    val sig = "$action|$pkg|$cls|$viewId|$text|$desc"
    val now = System.currentTimeMillis()
    if (sig == lastSig && now - lastTs < 1200) return
    lastSig = sig; lastTs = now
    synchronized(steps) {
      if (action == "set_text" && steps.lastOrNull()?.let { it.action == "set_text" && it.viewId == viewId && it.pkg == pkg } == true) {
        steps[steps.lastIndex] = Step(pkg, cls, viewId, text, desc, action, now)
      } else {
        steps.add(Step(pkg, cls, viewId, text, desc, action, now))
      }
    }
  }

  fun parseSteps(json: String): List<Step> {
    val out = ArrayList<Step>()
    try {
      val arr = JSONArray(json)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(Step(o.optString("pkg"), o.optString("cls"), o.optString("viewId"), o.optString("text"), o.optString("desc"), o.optString("action"), o.optLong("ts")))
      }
    } catch (_: Exception) {}
    return out
  }

  fun toJson(steps: List<Step>): JSONArray {
    val arr = JSONArray()
    for (s in steps) arr.put(JSONObject().apply {
      put("pkg", s.pkg); put("cls", s.cls); put("viewId", s.viewId); put("text", s.text); put("desc", s.desc); put("action", s.action); put("ts", s.ts)
    })
    return arr
  }

  fun showEditDialog(ctx: Context, b: Behavior, onDone: () -> Unit) {
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 8) }
    val name = EditText(ctx).apply { hint = "Behavior name"; setText(b.name) }
    root.addView(name)
    val steps = parseSteps(b.stepsJson).toMutableList()
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun render() {
      list.removeAllViews()
      for ((idx, s) in steps.withIndex()) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        row.addView(TextView(ctx).apply { text = "${idx + 1}. ${s.action} ${s.text.ifBlank { s.desc.ifBlank { s.viewId } }}"; textSize = 14f })
        row.addView(TextView(ctx).apply { text = "${s.pkg} ${s.cls}".trim(); textSize = 11f })
        val del = TextView(ctx).apply { text = "Delete step"; textSize = 12f; setPadding(0, 6, 0, 0); setOnClickListener { if (idx in steps.indices) { steps.removeAt(idx); render() } } }
        row.addView(del)
        list.addView(row)
      }
      if (steps.isEmpty()) list.addView(TextView(ctx).apply { text = "No steps"; textSize = 13f })
    }
    render()
    val sv = ScrollView(ctx); sv.addView(list)
    root.addView(TextView(ctx).apply { text = "Steps (delete unwanted steps, then save)"; textSize = 12f })
    root.addView(sv)
    AlertDialog.Builder(ctx).setTitle("Edit behavior").setView(root)
      .setPositiveButton("Save") { _, _ ->
        Db.get(ctx).behaviorUpdate(b.id, name = name.text.toString().trim().ifBlank { b.name }, stepsJson = toJson(steps).toString())
        onDone()
      }
      .setNegativeButton("Cancel", null).show()
  }

  fun replay(ctx: Context, b: Behavior) {
    val steps = parseSteps(b.stepsJson)
    if (steps.isEmpty()) { Toast.makeText(ctx, "No steps to replay", Toast.LENGTH_SHORT).show(); return }
    val acc = SaaraAccessibility.instance
    if (acc == null) { Toast.makeText(ctx, "Enable Accessibility service to replay steps", Toast.LENGTH_LONG).show(); return }
    Bus.add("system", "Replaying behavior: ${b.name} (${steps.size} steps)", true)
    Thread {
      var ok = 0
      for ((i, s) in steps.withIndex()) {
        try { Thread.sleep(650) } catch (_: Exception) {}
        val done = runStep(acc, s)
        if (done) ok++ else Bus.add("system", "Replay step ${i + 1} failed: ${s.action} ${s.text.ifBlank { s.desc.ifBlank { s.viewId } }}", true)
      }
      main.post { Toast.makeText(ctx, "Replay finished: $ok/${steps.size} steps", Toast.LENGTH_LONG).show() }
      Bus.add("system", "Replay finished: ${b.name} ($ok/${steps.size})", true)
    }.start()
  }

  private fun runStep(acc: SaaraAccessibility, s: Step): Boolean {
    return try {
      when (s.action) {
        "click", "long_click" -> {
          val node = findNode(acc, s) ?: return false
          val r = android.graphics.Rect(); node.getBoundsInScreen(r)
          if (s.action == "long_click") acc.handle(JSONObject().put("op", "long_press").put("x", r.exactCenterX().toDouble()).put("y", r.exactCenterY().toDouble()))
          else acc.handle(JSONObject().put("op", "tap_xy").put("x", r.exactCenterX().toDouble()).put("y", r.exactCenterY().toDouble()))
          true
        }
        "set_text" -> {
          val node = findNode(acc, s) ?: return false
          val r = android.graphics.Rect(); node.getBoundsInScreen(r)
          acc.handle(JSONObject().put("op", "tap_xy").put("x", r.exactCenterX().toDouble()).put("y", r.exactCenterY().toDouble()))
          try { Thread.sleep(250) } catch (_: Exception) {}
          acc.handle(JSONObject().put("op", "type").put("text", s.text).put("field_id", s.viewId))
          true
        }
        "scroll" -> { acc.handle(JSONObject().put("op", "scroll_down")); true }
        else -> false
      }
    } catch (_: Exception) { false }
  }

  private fun findNode(acc: SaaraAccessibility, s: Step): AccessibilityNodeInfo? {
    val root = try { acc.rootInActiveWindow } catch (_: Exception) { null } ?: return null
    fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      try {
        val id = n.viewIdResourceName?.substringAfter('/') ?: ""
        val t = (n.text ?: "").toString()
        val d = (n.contentDescription ?: "").toString()
        val match = (s.viewId.isNotBlank() && id == s.viewId) || (s.text.isNotBlank() && t.equals(s.text, true)) || (s.desc.isNotBlank() && d.equals(s.desc, true))
        if (match && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; val f = walk(c); if (f != null) return f }
      } catch (_: Exception) {}
      return null
    }
    return walk(root)
  }
}