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
      "list_fields" -> return listFieldsResult()
      "tap_text" -> {
        val t = m.optString("text")
        if (t.isBlank()) return "error: text is required"
        return if (tapTextSmart(t)) settled("tapped '$t'")
        else "not found: '$t' after retries. Call dump_ui or list_fields, try a shorter label, scroll_down, or use tap_xy with bounds."
      }
      "tap_id" -> {
        val id = m.optString("id").trim()
        if (id.isBlank()) return "error: id is required"
        return if (tapIdSmart(id)) settled("tapped id '$id'")
        else "not found: id '$id' after retries. Call dump_ui or list_fields and use the short id shown there."
      }
      "tap_xy" -> { if (!m.has("x") || !m.has("y")) return "error: x and y are required"; tapXY(m.optDouble("x").toFloat(), m.optDouble("y").toFloat()); return settled("tapped xy") }
      "tap_key" -> return tapKey(m.optString("text").ifBlank { m.optString("key") })
      "double_tap" -> {
        val t = m.optString("text")
        if (t.isNotBlank()) {
          val n = nodeForText(t) ?: return "not found: '$t'"
          val b = bounds(n); doubleTap(b.exactCenterX(), b.exactCenterY())
        } else doubleTap(m.optDouble("x").toFloat(), m.optDouble("y").toFloat())
        return settled("double tapped")
      }
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
      "type" -> return typeAction(m, clear = false)
      "clear_text" -> return typeAction(m, clear = true)
      "select_all" -> return selectAllAction(m)
      "copy_text" -> return copyAction(m)
      "paste" -> return pasteAction(m)
      "wait_text" -> return waitText(m.optString("text"), m.optLong("timeout", 4000))
      "enter" -> return enterAction(m)
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
  private fun settled(msg: String): String { sleep(550); return "$msg. Screen now: " + dump(3000) }

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

  // ---------- structured editable fields (for accurate multi-field form filling) ----------
  private data class FieldInfo(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val resId: String,
    val shortId: String,
    val desc: String,
    val hint: String,
    val value: String,
    val label: String,
    val bounds: Rect,
    val focused: Boolean
  )
  private data class LabelSource(val text: String, val bounds: Rect)

  private fun collectEditableFields(): List<FieldInfo> {
    val roots = allRoots()
    if (roots.isEmpty()) return emptyList()
    val nodes = ArrayList<AccessibilityNodeInfo>()
    fun walk(n: AccessibilityNodeInfo) {
      if (!n.isVisibleToUser) return
      if (n.isEditable) nodes.add(n)
      for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
    }
    roots.forEach { walk(it) }
    val sorted = nodes.distinctBy { System.identityHashCode(it) }
      .sortedWith(compareBy({ bounds(it).top }, { bounds(it).left }))
    val labelSources = collectLabelSources(roots)
    return sorted.mapIndexed { idx, n ->
      val b = bounds(n)
      val res = n.viewIdResourceName ?: ""
      val hint = n.hintText?.toString()?.trim().orEmpty()
      val raw = n.text?.toString()?.trim().orEmpty()
      val showingHint = try { if (Build.VERSION.SDK_INT >= 26) n.isShowingHintText else false } catch (_: Exception) { false }
      val value = if (showingHint) "" else raw
      val desc = n.contentDescription?.toString()?.trim().orEmpty()
      FieldInfo(idx, n, res, res.substringAfterLast('/'), desc, hint, value, nearbyLabelFor(b, labelSources, hint, desc), b, n.isFocused || n.isAccessibilityFocused)
    }
  }

  private fun collectLabelSources(roots: List<AccessibilityNodeInfo>): List<LabelSource> {
    val out = ArrayList<LabelSource>()
    fun walk(n: AccessibilityNodeInfo) {
      if (!n.isVisibleToUser) return
      if (!n.isEditable) {
        val t = ((n.text ?: n.contentDescription)?.toString() ?: "").trim().replace("\n", " ")
        if (t.isNotEmpty() && t.length <= 120) out.add(LabelSource(t, bounds(n)))
      }
      for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
    }
    roots.forEach { walk(it) }
    return out
  }

  private fun nearbyLabelFor(fb: Rect, sources: List<LabelSource>, hint: String, desc: String): String {
    var best = ""
    var bestScore = Int.MAX_VALUE
    for (s in sources) {
      val sb = s.bounds
      if (sb.isEmpty || fb.isEmpty) continue
      val verticalGap = fb.top - sb.bottom
      val horizontalGap = fb.left - sb.right
      val horizOverlap = kotlin.math.min(fb.right, sb.right) - kotlin.math.max(fb.left, sb.left)
      val vertOverlap = kotlin.math.min(fb.bottom, sb.bottom) - kotlin.math.max(fb.top, sb.top)
      val score = when {
        verticalGap in -24..160 && horizOverlap > -40 -> kotlin.math.max(0, verticalGap) + kotlin.math.abs(sb.left - fb.left) / 4
        horizontalGap in -24..160 && vertOverlap > -24 -> kotlin.math.max(0, horizontalGap) + kotlin.math.abs(sb.top - fb.top) / 4 + 20
        sb.top >= fb.top - 40 && sb.bottom <= fb.bottom + 40 && sb.left < fb.left + 20 -> (fb.left - sb.right).coerceAtLeast(0) + 40
        else -> Int.MAX_VALUE
      }
      if (score < bestScore) { bestScore = score; best = s.text }
    }
    if (bestScore != Int.MAX_VALUE) return best
    return hint.ifBlank { desc }
  }

  private fun listFieldsResult(): String {
    val fields = collectEditableFields()
    val app = root()?.packageName?.toString() ?: "unknown"
    val arr = JSONArray()
    for (f in fields) {
      arr.put(JSONObject()
        .put("index", f.index)
        .put("id", f.shortId)
        .put("resource_id", f.resId)
        .put("label", f.label)
        .put("hint", f.hint)
        .put("desc", f.desc)
        .put("value", f.value)
        .put("focused", f.focused)
        .put("bounds", "${f.bounds.left},${f.bounds.top},${f.bounds.right},${f.bounds.bottom}"))
    }
    return "app=$app screen=${sw()}x${sh()} editable_fields=${fields.size} fields=" + arr.toString()
  }

  private sealed class FieldResolution {
    data class Ok(val field: FieldInfo) : FieldResolution()
    data class Fail(val message: String) : FieldResolution()
  }

  private fun resolveFieldTarget(m: JSONObject, fields: List<FieldInfo>): FieldResolution {
    val hasIndex = m.has("field_index")
    val byIndex = if (hasIndex) m.optInt("field_index", -1) else -1
    val byId = listOf(m.optString("field_id"), m.optString("target_id")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val byLabel = listOf(m.optString("field_label"), m.optString("label"), m.optString("target"), m.optString("field")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    if (fields.isEmpty()) return FieldResolution.Fail("no editable field found on screen. " + dump(3000))

    if (hasIndex) {
      val f = fields.firstOrNull { it.index == byIndex }
      return if (f != null) FieldResolution.Ok(f) else FieldResolution.Fail("field_index $byIndex not found. " + listFieldsResult())
    }
    if (byId.isNotEmpty()) {
      val q = byId.trimStart('/')
      val matches = fields.filter {
        val sid = it.shortId
        sid == q || sid.equals(q, true) || it.resId == q || it.resId.endsWith(q) || sid.contains(q, true)
      }
      val exact = matches.filter { it.shortId.equals(q, true) || it.resId.endsWith("/$q") || it.resId == q }
      val pick = if (exact.size == 1) exact else matches
      return when {
        pick.size == 1 -> FieldResolution.Ok(pick[0])
        pick.size > 1 -> FieldResolution.Fail("AMBIGUOUS_FIELD: field_id '$byId' matches multiple fields: " + summarizeFields(pick) + ". Ask user to clarify or specify field_index. " + listFieldsResult())
        else -> FieldResolution.Fail("field_id '$byId' not found. " + listFieldsResult())
      }
    }
    if (byLabel.isNotBlank()) {
      val q = byLabel.trim()
      val scored = fields.map { f ->
        val haystack = listOf(f.label, f.hint, f.desc, f.shortId, f.value).joinToString(" ")
        val rank = when {
          f.label.equals(q, true) || f.hint.equals(q, true) || f.desc.equals(q, true) || f.shortId.equals(q, true) -> 0
          f.label.startsWith(q, true) || f.hint.startsWith(q, true) || f.desc.startsWith(q, true) -> 1
          haystack.contains(q, true) -> 2
          q.length >= 3 && (f.label.contains(q, true) || f.hint.contains(q, true)) -> 2
          else -> 9
        }
        f to rank
      }.filter { it.second < 9 }.sortedBy { it.second }
      val bestRank = scored.firstOrNull()?.second
      val best = if (bestRank != null) scored.filter { it.second == bestRank }.map { it.first } else emptyList()
      return when {
        best.size == 1 -> FieldResolution.Ok(best[0])
        best.size > 1 -> FieldResolution.Fail("AMBIGUOUS_FIELD: '$byLabel' could match multiple fields: " + summarizeFields(best) + ". STOP and ask the user to clarify which field to use (or specify field_index). Do not guess. " + listFieldsResult())
        else -> FieldResolution.Fail("No field matching '$byLabel'. " + listFieldsResult())
      }
    }

    if (fields.size == 1) return FieldResolution.Ok(fields[0])
    return FieldResolution.Fail("MULTIPLE_FIELDS: ${fields.size} editable fields are visible. You MUST target the exact field using field_index, field_id, or field_label before typing. Do not guess or dump data into one field. " + listFieldsResult())
  }

  private fun summarizeFields(fs: List<FieldInfo>): String = fs.joinToString("; ") { f ->
    val name = f.label.ifBlank { f.hint.ifBlank { f.desc.ifBlank { f.shortId.ifBlank { "-" } } } }
    "#${f.index}[id=${f.shortId.ifBlank { "-" }} label='$name' value='${f.value.take(30)}' bounds=${f.bounds.left},${f.bounds.top},${f.bounds.right},${f.bounds.bottom}]"
  }
  private fun describeField(f: FieldInfo): String {
    val name = f.label.ifBlank { f.hint.ifBlank { f.desc.ifBlank { "-" } } }
    return "field#${f.index}(id=${f.shortId.ifBlank { "-" }}, label='$name', hint='${f.hint.ifBlank { "-" }}', desc='${f.desc.ifBlank { "-" }}', value='${f.value.take(60)}', focused=${f.focused}, bounds=${f.bounds.left},${f.bounds.top},${f.bounds.right},${f.bounds.bottom})"
  }

  private fun focusField(f: FieldInfo) {
    try { f.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Exception) {}
    try { f.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
    sleep(250)
  }

  private data class SetTextResult(val ok: Boolean, val finalValue: String)
  private fun performSetText(n: AccessibilityNodeInfo, text: String, replace: Boolean): SetTextResult {
    val cur = try {
      if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) "" else (n.text?.toString() ?: "")
    } catch (_: Exception) { n.text?.toString() ?: "" }
    val full = if (replace || cur.isEmpty() || text.startsWith(cur)) text else cur + text
    var ok = try {
      n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
      })
    } catch (_: Exception) { false }
    if (ok) select(n, full.length, full.length)
    else {
      val replacing = full == text
      try { select(n, if (replacing) 0 else cur.length, cur.length) } catch (_: Exception) {}
      ok = try {
        if (text.isEmpty()) n.performAction(AccessibilityNodeInfo.ACTION_CUT)
        else {
          (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("saara", text))
          n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
      } catch (_: Exception) { false }
      if (!ok && !replace && text.isNotEmpty()) {
        try {
          select(n, 0, (n.text?.length ?: cur.length))
          (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("saara", full))
          ok = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {}
      }
    }
    val finalVal = try { n.text?.toString() ?: full } catch (_: Exception) { full }
    return SetTextResult(ok, finalVal)
  }

  private fun typeAction(m: JSONObject, clear: Boolean): String {
    val fields = collectEditableFields()
    val field = when (val r = resolveFieldTarget(m, fields)) {
      is FieldResolution.Ok -> r.field
      is FieldResolution.Fail -> return r.message
    }
    focusField(field)
    val pre = "Target verified: ${describeField(field)}."
    val text = if (clear) "" else m.optString("text")
    val replace = m.optBoolean("replace", false) || clear
    val allowBulk = m.optBoolean("allow_bulk", false)

    if (!clear && !allowBulk && fields.size > 1) {
      val lines = text.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
      if (lines.size > 1) {
        return "BLOCKED_BULK: refusing to enter multi-line/multi-value text into a single field while ${fields.size} editable fields are visible. Enter values one field at a time: target each field (field_index/field_id/field_label), verify, and type only that field's value. If the user explicitly wants this exact multi-line text in this single targeted field, retry with allow_bulk=true. $pre " + listFieldsResult()
      }
    }

    val result = performSetText(field.node, text, replace)
    val name = field.label.ifBlank { field.hint.ifBlank { field.shortId.ifBlank { "#${field.index}" } } }
    return if (result.ok) {
      val entered = if (clear) "(cleared)" else "'${text.take(120)}'"
      settled("$pre Entered $entered into field '$name' [index ${field.index}]. Field now contains: '${result.finalValue.take(150)}'.")
    } else {
      "$pre error: this field would not accept text. " + listFieldsResult()
    }
  }

  private fun selectAllAction(m: JSONObject): String {
    val fields = collectEditableFields()
    val field = when (val r = resolveFieldTarget(m, fields)) { is FieldResolution.Ok -> r.field; is FieldResolution.Fail -> return r.message }
    focusField(field)
    val len = try { (field.node.text?.toString() ?: "").length } catch (_: Exception) { 0 }
    if (len == 0) return "Target verified: ${describeField(field)}. The field is empty."
    select(field.node, 0, len)
    return settled("Target verified: ${describeField(field)}. Selected all text in this field.")
  }

  private fun copyAction(m: JSONObject): String {
    val text = m.optString("text")
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    if (text.isNotBlank()) {
      cm.setPrimaryClip(ClipData.newPlainText("Saara", text))
      return "copied ${text.length} character(s) to the clipboard"
    }
    val fields = collectEditableFields()
    val field = when {
      m.has("field_index") || m.optString("field_id").isNotBlank() || m.optString("field_label").isNotBlank() || m.optString("label").isNotBlank() || m.optString("target").isNotBlank() || m.optString("field").isNotBlank() ->
        when (val r = resolveFieldTarget(m, fields)) { is FieldResolution.Ok -> r.field; is FieldResolution.Fail -> return r.message }
      else -> fields.firstOrNull { it.focused } ?: fields.singleOrNull() ?: return "no focused/unique field. Specify field_index/field_id/field_label, or provide text to copy. " + listFieldsResult()
    }
    focusField(field)
    return try {
      if (field.node.performAction(AccessibilityNodeInfo.ACTION_COPY)) "copied the selected text from ${describeField(field)}"
      else "could not copy from ${describeField(field)}. Select text first, or provide text to copy."
    } catch (_: Exception) { "could not copy from ${describeField(field)}" }
  }

  private fun pasteAction(m: JSONObject): String {
    val fields = collectEditableFields()
    val field = when (val r = resolveFieldTarget(m, fields)) { is FieldResolution.Ok -> r.field; is FieldResolution.Fail -> return r.message }
    focusField(field)
    return try {
      if (field.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) settled("Target verified: ${describeField(field)}. Pasted from clipboard into this field.")
      else "Target verified: ${describeField(field)}. This field does not accept paste."
    } catch (_: Exception) { "Target verified: ${describeField(field)}. Paste failed." }
  }

  private fun enterAction(m: JSONObject): String {
    val fields = collectEditableFields()
    val field = when (val r = resolveFieldTarget(m, fields)) { is FieldResolution.Ok -> r.field; is FieldResolution.Fail -> return r.message }
    focusField(field)
    if (Build.VERSION.SDK_INT >= 30) {
      try { if (field.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return settled("Target verified: ${describeField(field)}. Enter pressed.") } catch (_: Exception) {}
    }
    for (k in listOf("Send", "Search", "Go", "Done", "Next")) if (tapSmart(k)) return settled("Target verified: ${describeField(field)}. Pressed '$k'.")
    return "Target verified: ${describeField(field)}. Enter is not supported here; tap the Send/Search button with tap_text instead."
  }

  // ---------- tapping ----------
  /** Retries after a short settle, then falls back to a clickable node or its view id. */
  private fun tapTextSmart(t: String): Boolean {
    repeat(2) { attempt ->
      if (tapSmart(t)) return true
      if (attempt == 0) sleep(350)
    }
    val n = nodeForText(t) ?: return false
    if (clickNode(n)) return true
    val id = n.viewIdResourceName?.substringAfter('/') ?: return false
    return id.isNotBlank() && tapId(id)
  }

  private fun tapIdSmart(id: String): Boolean {
    repeat(2) { attempt ->
      if (tapId(id)) return true
      if (attempt == 0) sleep(350)
    }
    return false
  }

  /** Self-healing: text/description/id/hint match on every window; click the clickable ancestor, else tap the bounds centre. */
  private fun tapSmart(t: String): Boolean {
    val q = t.trim()
    if (q.isEmpty()) return false
    for (r in allRoots()) {
      val cands = LinkedHashMap<Int, AccessibilityNodeInfo>()
      (r.findAccessibilityNodeInfosByText(q) ?: emptyList()).forEach { cands[System.identityHashCode(it)] = it }
      find(r) { hay(it).contains(q, true) }.forEach { cands[System.identityHashCode(it)] = it }
      val sorted = cands.values.asSequence()
        .filter { it.isVisibleToUser && !bounds(it).isEmpty }
        .sortedWith(compareBy<AccessibilityNodeInfo>(
          { matchRank(it, q) },
          { if (it.isClickable) 0 else if (clickableAncestor(it) != null) 1 else 2 },
          { -bounds(it).width() * bounds(it).height() }
        ))
        .toList()
      for (n in sorted) if (clickNode(n)) return true
    }
    return false
  }

  private fun tapId(id: String): Boolean {
    val q = id.trim().trimStart('@')
    if (q.isEmpty()) return false
    for (r in allRoots()) {
      val cands = find(r) {
        val vid = it.viewIdResourceName ?: return@find false
        val short = vid.substringAfterLast('/')
        it.isVisibleToUser && (short == q || short.equals(q, true) || vid.endsWith(q) || short.contains(q, true))
      }.sortedWith(compareBy<AccessibilityNodeInfo>(
        { if ((it.viewIdResourceName ?: "").substringAfterLast('/') == q) 0 else 1 },
        { if (it.isClickable) 0 else if (clickableAncestor(it) != null) 1 else 2 }
      ))
      for (n in cands) if (clickNode(n)) return true
    }
    return false
  }

  private fun matchRank(n: AccessibilityNodeInfo, q: String): Int {
    val text = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
    val id = n.viewIdResourceName ?: ""
    return when {
      text.equals(q, true) -> 0
      text.startsWith(q, true) -> 1
      text.contains(q, true) -> 2
      id.substringAfterLast('/').equals(q, true) -> 3
      id.contains(q, true) -> 4
      else -> 5
    }
  }

  private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var p = n.parent
    while (p != null) { if (p.isClickable) return p; p = p.parent }
    return null
  }

  private fun nodeForText(t: String): AccessibilityNodeInfo? {
    val q = t.trim()
    for (r in allRoots()) {
      val cands = LinkedHashMap<Int, AccessibilityNodeInfo>()
      (r.findAccessibilityNodeInfosByText(q) ?: emptyList()).forEach { cands[System.identityHashCode(it)] = it }
      find(r) { hay(it).contains(q, true) }.forEach { cands[System.identityHashCode(it)] = it }
      cands.values.filter { it.isVisibleToUser && !bounds(it).isEmpty }
        .minByOrNull { matchRank(it, q) }?.let { return it }
    }
    return null
  }

  private fun clickNode(n: AccessibilityNodeInfo): Boolean {
    var p: AccessibilityNodeInfo? = n
    while (p != null && !p.isClickable) p = p.parent
    if (p != null && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val b = bounds(n)
    return !b.isEmpty && tapXY(b.exactCenterX(), b.exactCenterY())
  }
  private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
    val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
    val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build(), null, null)
    sleep(dur + 150)
    return ok
  }
  private fun clampX(x: Float) = x.coerceIn(2f, (sw() - 2).coerceAtLeast(2).toFloat())
  private fun clampY(y: Float) = y.coerceIn(2f, (sh() - 2).coerceAtLeast(2).toFloat())
  private fun tapXY(x: Float, y: Float): Boolean {
    val cx = clampX(x); val cy = clampY(y)
    val ok = gesture(cx, cy, cx + 1f, cy + 1f, 70)
    sleep(60)
    return ok
  }
  private fun doubleTap(x: Float, y: Float) { tapXY(x, y); sleep(90); tapXY(x, y) }

  private fun waitText(t: String, timeoutMs: Long): String {
    val q = t.trim()
    if (q.isEmpty()) return "error: text is required"
    val timeout = timeoutMs.coerceIn(500, 15000)
    val end = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < end) {
      if (nodeForText(q) != null) return settled("found '$q'")
      sleep(250)
    }
    return "not found: '$q' after ${timeout}ms. Screen now: " + dump(3000)
  }

  private fun tapKey(raw: String): String {
    val k = raw.trim()
    if (k.isBlank()) return "error: key/text is required"
    val key = k.lowercase()
    val candidates = when (key) {
      "space", " " -> listOf("space", "spacebar")
      "enter", "return", "go", "search", "done", "send" -> listOf(k, "enter", "return", "go", "search", "done", "send")
      "backspace", "delete", "del" -> listOf("backspace", "delete", "del")
      else -> listOf(k, key.uppercase())
    }
    for (c in candidates) if (tapTextSmart(c)) return settled("tapped key '$k'")
    // Fallback: find smallest visible clickable node whose text/desc exactly matches key (keyboard keys)
    for (r in allRoots()) {
      val n = find(r) {
        if (!it.isVisibleToUser) return@find false
        val b = bounds(it); if (b.isEmpty || b.width() > sw() * 0.35) return@find false
        val t = ((it.text ?: it.contentDescription)?.toString() ?: "").trim()
        t.equals(k, true) || (k.length == 1 && t.equals(k, true))
      }.minByOrNull { val b = bounds(it); b.width() * b.height() }
      if (n != null && clickNode(n)) return settled("tapped key '$k'")
    }
    return "not found: keyboard key '$k'. Use dump_ui to locate key bounds and tap_xy center, or type text directly into the field."
  }

  private fun scrollNode(forward: Boolean): Boolean {
    val r = root() ?: return false
    val n = find(r) { it.isScrollable && it.isVisibleToUser }.maxByOrNull { val b = bounds(it); b.width() * b.height() } ?: return false
    return n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
  }
  private fun select(n: AccessibilityNodeInfo, s: Int, e: Int) {
    try {
      n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
      })
    } catch (_: Exception) {}
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
