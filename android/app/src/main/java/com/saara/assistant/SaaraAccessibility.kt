package com.saara.assistant
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Visual grounding + touch automation. Every action returns the new screen so the AI can keep going without asking the user. */
class SaaraAccessibility : AccessibilityService() {
  companion object { @Volatile var instance: SaaraAccessibility? = null }
  override fun onServiceConnected() { instance = this }
  override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
  override fun onInterrupt() {}
  override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }

  private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
  private fun sw(): Int = resources.displayMetrics.widthPixels
  private fun sh(): Int = resources.displayMetrics.heightPixels
  private fun bounds(n: AccessibilityNodeInfo): Rect { val b = Rect(); n.getBoundsInScreen(b); return b }

  /** Runs one UI operation (blocking, call from a worker thread). Always returns a text result for the model. */
  fun handle(m: JSONObject): String {
    return try { dispatch(m) } catch (e: Exception) { "error: ${e.message}" }
  }

  private fun dispatch(m: JSONObject): String {
    val w = sw().toFloat(); val h = sh().toFloat()
    when (val op = m.optString("op")) {
      "dump_ui" -> return dump(12000)
      "dump_small" -> return dump(3000)
      "tap_text" -> {
        val t = m.optString("text")
        if (t.isBlank()) return "error: text is required"
        return if (tapSmart(t)) settled("tapped '$t'")
        else "not found: '$t'. Call dump_ui, try another label, scroll_down, or use tap_xy with bounds."
      }
      "tap_xy" -> { if (!m.has("x") || !m.has("y")) return "error: x and y are required"; tapXY(m.optDouble("x").toFloat(), m.optDouble("y").toFloat()); return settled("tapped xy") }
      "long_press" -> {
        val t = m.optString("text")
        if (t.isNotBlank()) {
          val n = allRoots().firstNotNullOfOrNull { r -> find(r) { hay(it).contains(t, true) && it.isVisibleToUser }.firstOrNull() }
            ?: return "not found: '$t'"
          val b = bounds(n); gesture(b.exactCenterX(), b.exactCenterY(), b.exactCenterX(), b.exactCenterY(), 800)
        } else gesture(m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), 800)
        return settled("long pressed")
      }
      "swipe" -> {
        if (m.has("x2")) gesture(m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), m.optDouble("x2").toFloat(), m.optDouble("y2").toFloat(), 350)
        else when (m.optString("direction").lowercase()) {
          "up" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.30f, 350)
          "down" -> gesture(w / 2, h * 0.30f, w / 2, h * 0.75f, 350)
          "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 350)
          "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 350)
          else -> return "error: direction must be up/down/left/right or give x,y,x2,y2"
        }
        return settled("swiped")
      }
      "type" -> return typeText(m.optString("text"), m.optBoolean("replace", false))
      "clear_text" -> return typeText("", true)
      "enter" -> return imeEnter()
      "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); return settled("back") }
      "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); return settled("home") }
      "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); return settled("recents opened") }
      "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return settled("notification panel opened") }
      "scroll_down" -> { if (!scrollNode(true)) gesture(w / 2, h * 0.75f, w / 2, h * 0.30f, 350); return settled("scrolled down") }
      "scroll_up" -> { if (!scrollNode(false)) gesture(w / 2, h * 0.30f, w / 2, h * 0.75f, 350); return settled("scrolled up") }
      "wait" -> { sleep(m.optLong("ms", 1000).coerceIn(100, 10000)); return "waited. Screen now: " + dump(3000) }
      "shutter" -> return shutter()
      "flip_camera" -> return flipCamera()
      "answer_call" -> return answerCallUi()
      else -> return "unknown op: $op"
    }
  }

  /** Short pause so the UI settles, then returns what is on screen now. */
  private fun settled(msg: String): String { sleep(700); return "$msg. Screen now: " + dump(3000) }

  // ---------- reading the screen ----------
  private fun root(): AccessibilityNodeInfo? {
    repeat(6) { rootInActiveWindow?.let { return it }; sleep(200) }
    return null
  }
  private fun allRoots(): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>()
    rootInActiveWindow?.let { out.add(it) }
    try { windows?.forEach { w -> w.root?.let { r -> if (out.none { it == r }) out.add(r) } } } catch (_: Exception) {}
    return out
  }
  private fun hay(n: AccessibilityNodeInfo): String =
    listOf(n.text, n.contentDescription, n.viewIdResourceName, n.hintText).joinToString(" ") { it?.toString() ?: "" }

  private fun find(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo> = ArrayList(), f: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    if (f(n)) out.add(n)
    for (i in 0 until n.childCount) n.getChild(i)?.let { find(it, out, f) }
    return out
  }

  /** Compact UI tree with bounds so Gemini can ground icons/buttons. t=text/desc, id=view id, c=clickable, e=editable, b=l,t,r,b */
  private fun dump(limit: Int): String {
    val r = root() ?: return "screen not readable right now (no active window). Try again, or call home."
    val a = JSONArray()
    var count = 0
    fun walk(n: AccessibilityNodeInfo) {
      if (count > 400 || !n.isVisibleToUser) return
      val t = (n.text ?: n.contentDescription)?.toString()?.take(80)
      if (!t.isNullOrBlank() || n.isClickable || n.isEditable) {
        val b = bounds(n)
        a.put(JSONObject().put("t", t ?: "").put("id", n.viewIdResourceName?.substringAfter('/') ?: "")
          .put("c", n.isClickable).put("e", n.isEditable).put("b", "${b.left},${b.top},${b.right},${b.bottom}"))
        count++
      }
      for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
    }
    walk(r)
    return "app=${r.packageName} screen=${sw()}x${sh()} " + a.toString().take(limit)
  }

  // ---------- tapping ----------
  /** Self-healing: text/description/id/hint match on every window; click the clickable ancestor, else tap the bounds centre. */
  private fun tapSmart(t: String): Boolean {
    for (r in allRoots()) {
      var cands: List<AccessibilityNodeInfo> = r.findAccessibilityNodeInfosByText(t) ?: emptyList()
      if (cands.isEmpty()) cands = find(r) { hay(it).contains(t, true) }
      for (n in cands.sortedByDescending { it.isVisibleToUser }) if (clickNode(n)) return true
    }
    return false
  }
  private fun clickNode(n: AccessibilityNodeInfo): Boolean {
    var p: AccessibilityNodeInfo? = n
    while (p != null && !p.isClickable) p = p.parent
    if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val b = bounds(n)
    if (!b.isEmpty && n.isVisibleToUser) { tapXY(b.exactCenterX(), b.exactCenterY()); return true }
    return false
  }
  private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
    val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
    val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
    sleep(dur + 150)
    return ok
  }
  private fun tapXY(x: Float, y: Float) { gesture(x, y, x, y, 80) }

  private fun scrollNode(forward: Boolean): Boolean {
    val r = root() ?: return false
    val n = find(r) { it.isScrollable && it.isVisibleToUser }.maxByOrNull { val b = bounds(it); b.width() * b.height() } ?: return false
    return n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
  }

  // ---------- typing (ADDS to existing text unless replace=true) ----------
  private fun inputNode(): AccessibilityNodeInfo? {
    for (r in allRoots()) r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
    for (r in allRoots()) find(r) { it.isEditable && it.isVisibleToUser }.firstOrNull()?.let {
      it.performAction(AccessibilityNodeInfo.ACTION_FOCUS); it.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(300); return it
    }
    return null
  }
  private fun select(n: AccessibilityNodeInfo, s: Int, e: Int) {
    n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
      putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
    })
  }
  private fun typeText(text: String, replace: Boolean): String {
    val n = inputNode() ?: return "no text field found. Tap the text field first (tap_text or tap_xy), then call type again."
    val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
    // If the model re-sent the whole text (it starts with what is already there), treat it as the full new value; otherwise append.
    val full = if (replace || cur.isEmpty() || text.startsWith(cur)) text else cur + text
    var ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
    })
    if (ok) select(n, full.length, full.length)
    else {
      // Fallback for fields that refuse SET_TEXT: use the clipboard.
      val replacing = full == text
      select(n, if (replacing) 0 else cur.length, cur.length)
      ok = if (text.isEmpty()) n.performAction(AccessibilityNodeInfo.ACTION_CUT) else {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("saara", text))
        n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
      }
    }
    return if (ok) settled("typed; field now contains: '${full.take(150)}'") else "error: this field would not accept text"
  }
  private fun imeEnter(): String {
    val n = inputNode() ?: return "no text field focused"
    if (Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return settled("enter pressed")
    return "enter is not supported here; tap the Send/Search button with tap_text instead"
  }

  // ---------- camera ----------
  private fun shutter(): String {
    val w = sw(); val h = sh()
    val keys = listOf("shutter", "take photo", "take picture", "take a photo", "capture", "center_button", "photo_button")
    for (r in allRoots()) for (k in keys) {
      for (n in find(r) { it.isVisibleToUser && hay(it).contains(k, true) && bounds(it).centerY() > h * 0.55 })
        if (clickNode(n)) return settled("shutter pressed (matched '$k'). Photo taken")
    }
    // Round/square clickable button near the bottom centre = shutter on almost every camera app.
    for (r in allRoots()) {
      val c = find(r) { n ->
        val b = bounds(n)
        n.isVisibleToUser && n.isClickable && b.width() >= w * 0.12 && b.width() <= w * 0.4 &&
          b.height() >= b.width() * 3 / 4 && b.height() <= b.width() * 4 / 3 &&
          b.centerY() > h * 0.6 && abs(b.centerX() - w / 2) < w * 0.15
      }
      val best = c.maxByOrNull { bounds(it).width() }
      if (best != null && clickNode(best)) return settled("shutter pressed (centre button). Photo taken")
    }
    tapXY(w / 2f, h * 0.87f)
    return settled("shutter button was not labelled, so I tapped the bottom-centre of the screen. Photo should be taken")
  }
  private fun flipCamera(): String {
    for (k in listOf("switch camera", "flip camera", "front camera", "selfie", "switch_camera", "camera_switch", "camera_flip", "rotate camera", "flip"))
      if (tapSmart(k)) return settled("camera switched")
    return "camera switch button not found"
  }

  // ---------- calls (last-resort UI path; Tools.answerCall tries Telecom + notification first) ----------
  private fun answerCallUi(): String {
    for (k in listOf("answer", "accept", "pick up", "receive", "jawab", "उत्तर", "उठाएं", "स्वीकार", "रिसीव")) if (tapSmart(k)) return settled("tapped '$k'")
    gesture(sw() / 2f, sh() * 0.82f, sw() / 2f, sh() * 0.35f, 350)
    return settled("answer button not found; tried the swipe-up answer gesture")
  }
}
