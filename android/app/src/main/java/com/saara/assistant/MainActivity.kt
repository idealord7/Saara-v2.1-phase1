package com.saara.assistant
import android.Manifest
import android.content.Intent
import android.os.PowerManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
  companion object { @Volatile var visible = false; private const val ASSISTANT_START_ACTION = "custom.actions.intent.START_SAARA"; private const val SAARA_SCHEME = "saara"; private const val SAARA_DEEP_LINK_HOST = "start"; private const val ASSISTANT_COMMAND = "saara_command" }

  private val BG = 0xFF131314.toInt(); private val SURF = 0xFF1E1F20.toInt(); private val SURF2 = 0xFF2A2C2E.toInt()
  private val TXT = 0xFFE3E3E3.toInt(); private val SUB = 0xFF9AA0A6.toInt(); private val ACC = 0xFF4FA3F7.toInt(); private val ACC2 = 0xFF1A73E8.toInt()
  private val GOOD = 0xFF81C995.toInt(); private val WARN = 0xFFFDD663.toInt(); private val BAD = 0xFFF28B82.toInt()

  private val updaters = ArrayList<() -> Unit>()
  private val settingsUpdaters = ArrayList<() -> Unit>()
  private val pages = ArrayList<View>()
  private val navItems = ArrayList<Pair<TextView, TextView>>()
  private var curTab = 0
  private lateinit var statusTv: TextView
  private lateinit var startBtn: TextView
  private lateinit var chatList: LinearLayout
  private lateinit var chatScroll: ScrollView
  private lateinit var emptyView: View
  private lateinit var muteChip: TextView
  private lateinit var camChip: TextView
  private lateinit var flipChip: TextView
  private lateinit var screenChip: TextView
  private lateinit var learnBox: LinearLayout
  private lateinit var shopBox: LinearLayout
  private var learnSearchQuery = ""
  private var learnFilter = "All"
  private val learnSelectedFactIds = linkedSetOf<Long>()
  private val learnSelectedRuleIds = linkedSetOf<Long>()
  private val learnSelectedBehaviorIds = linkedSetOf<Long>()
  private val learnCollapsed = hashSetOf<String>()
  private val learnFilterChips = linkedMapOf<String, TextView>()
  private lateinit var taskBox: LinearLayout
  private lateinit var contactsBox: LinearLayout
  private lateinit var contactsSearch: EditText
  private var settingsRoot: LinearLayout? = null
  private var settingsScroll: ScrollView? = null
  private var settingsContent: LinearLayout? = null
  private var settingsSearchInput: EditText? = null
  private var lastSettingsRebuild = 0L
  private var settingsSearchQuery = ""
  private var settingsCategory = "All"
  private var wakePhrasesCollapsed = true
  private val settingsCategoryOrder = listOf("All", "Voice & Wake", "AI & Models", "Auto-Reply & Rules", "Screen & Overlay", "Memory & System")
  private val settingsCategoryChips = LinkedHashMap<String, TextView>()
  private val shown = HashMap<Long, TextView>()
  private var lastId = 0L
  private var firstId = Long.MAX_VALUE
  private var noMore = false
  private var loadingOlder = false
  private var pendingStart = false
  private var pendingCam = false
  private var assistantLaunch = false
  private var pendingAssistantText: String? = null
  private var pendingAssistantWake = false
  private var camFront = false
  private var lastToggle = 0L
  private val fetchedModels = ArrayList<String>()
  // Contact provider I/O must never run on Android's main/UI thread.
  private val contactsExecutor = Executors.newSingleThreadExecutor()
  private val contactsHandler = Handler(Looper.getMainLooper())
  private val contactsGeneration = AtomicInteger(0)
  private val contactsDebounce = Runnable { loadContactsInBackground() }
  private val knowledgeExecutor = Executors.newSingleThreadExecutor()
  private val busListener: () -> Unit = { try { refresh() } catch (_: Exception) {} }

  // ---------- small UI helpers ----------
  private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
  private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
  private fun bg(color: Int, r: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
  private fun tv(t: String, size: Float = 14f, color: Int = TXT, bold: Boolean = false): TextView {
    val v = TextView(this); v.text = t; v.textSize = size; v.setTextColor(color)
    if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
    return v
  }
  private fun btn(label: String, color: Int = ACC2, f: () -> Unit): Button {
    val b = Button(this); b.text = label; b.isAllCaps = false; b.setTextColor(Color.WHITE); b.background = bg(color, 22)
    b.setOnClickListener { f() }
    return b
  }
  private fun smallBtn(label: String, color: Int = SURF2, f: () -> Unit): Button {
    val b = Button(this); b.text = label; b.isAllCaps = false; b.textSize = 11f
    b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
    b.setPadding(dp(10), dp(5), dp(10), dp(5)); b.setTextColor(Color.WHITE); b.background = bg(color, 12)
    b.setOnClickListener { f() }
    return b
  }
  private fun chip(label: String, f: () -> Unit): TextView {
    val t = tv(label, 13f); t.setPadding(dp(14), dp(8), dp(14), dp(8)); t.background = bg(SURF2, 18); t.gravity = Gravity.CENTER
    t.setOnClickListener { f() }
    return t
  }
  private fun card(parent: LinearLayout, padH: Int = 16, padV: Int = 14, topMargin: Int = 12, radius: Int = 20): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL; c.background = bg(SURF, radius); c.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(topMargin)
    parent.addView(c, lp)
    return c
  }
  private fun edit(hint: String, pw: Boolean = false): EditText {
    val e = EditText(this); e.hint = hint; e.setTextColor(TXT); e.setHintTextColor(0xFF6F7580.toInt()); e.textSize = 14f
    if (pw) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    return e
  }
  private fun gap(parent: LinearLayout, h: Int = 8) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
  private fun page(): Pair<ScrollView, LinearLayout> {
    val sv = ScrollView(this); sv.setBackgroundColor(BG)
    val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setPadding(dp(16), dp(8), dp(16), dp(24))
    sv.addView(root); return sv to root
  }
  private fun heading(parent: LinearLayout, t: String, sub: String = "") {
    val h = tv(t, 22f, bold = true); h.setPadding(0, dp(10), 0, 0); parent.addView(h)
    if (sub.isNotEmpty()) parent.addView(tv(sub, 13f, SUB))
  }
  private fun hhmm(ms: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
  private fun money(v: Double) = "₹" + (if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v))

  private fun a11yOn(): Boolean {
    val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return s.contains(packageName)
  }
  private fun notifOn() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
  private fun svc(a: String) = Intent(this, VoiceService::class.java).setAction(a)

  private fun isAssistantLaunch(i: Intent?): Boolean {
    if (i == null) return false
    val action = i.action
    if (action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND || action == ASSISTANT_START_ACTION) return true
    if (action == Intent.ACTION_VIEW) {
      val d = i.data ?: return false
      return d.scheme?.lowercase() == SAARA_SCHEME && d.host == SAARA_DEEP_LINK_HOST
    }
    return false
  }

  private fun handleIntentActions(i: Intent?) {
    if (i?.getBooleanExtra("request_screen_share", false) == true) {
      i.removeExtra("request_screen_share")
      window.decorView.post { toggleScreen() }
    }
  }

  private fun startAssistantFlow(i: Intent? = null) {
    val cmd = i?.let { it.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { s -> s.isNotEmpty() } ?: it.getStringExtra(VoiceService.ASSISTANT_COMMAND)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    if (cmd != null) pendingAssistantText = cmd
    pendingAssistantWake = true
    if (!has(Manifest.permission.RECORD_AUDIO)) {
      pendingStart = true
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
      return
    }
    startSaara()
  }

  // ---------- screen skeleton ----------
  override fun onCreate(b: Bundle?) {
    try {
      super.onCreate(b)
      assistantLaunch = isAssistantLaunch(intent)
      onCreateInner(b)
      if (assistantLaunch) { assistantLaunch = false; startAssistantFlow(intent) }
      handleIntentActions(intent)
    } catch (t: Throwable) {
      Crash.save(this, t, "MainActivity.onCreate")
      throw t
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)
    assistantLaunch = isAssistantLaunch(intent)
    if (assistantLaunch) startAssistantFlow(intent)
    handleIntentActions(intent)
  }

  private fun onCreateInner(b: Bundle?) {
    try {
      Bus.init(this); migrateFacts(this)
      window.statusBarColor = BG; window.navigationBarColor = SURF
      val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(BG)
      setContentView(root)

    // top bar: logo, name + status, start/stop
    val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL; top.setPadding(dp(16), dp(34), dp(16), dp(8))
    val logo = ImageView(this); logo.setImageResource(R.drawable.logo_symbol); logo.scaleType = ImageView.ScaleType.CENTER_CROP
    logo.background = bg(0xFFF8FAF9.toInt(), 20); logo.clipToOutline = true
    top.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)))
    val names = LinearLayout(this); names.orientation = LinearLayout.VERTICAL; names.setPadding(dp(12), 0, 0, 0)
    names.addView(tv("Saara", 20f, bold = true)); statusTv = tv("Stopped", 12f, SUB); names.addView(statusTv)
    top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
    startBtn = tv("Start", 14f, Color.WHITE, true); startBtn.setPadding(dp(18), dp(9), dp(18), dp(9)); startBtn.setOnClickListener { toggle() }
    top.addView(startBtn)
    root.addView(top)

    val frame = FrameLayout(this)
    root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
    pages.add(buildChat()); pages.add(buildLearning()); pages.add(buildAutomation()); pages.add(buildSettings())
    pages.forEach { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }

    // bottom navigation
    val nav = LinearLayout(this); nav.setBackgroundColor(SURF); nav.setPadding(0, dp(6), 0, dp(6))
    val items = listOf("💬" to "Chat", "🧠" to "Learning", "⏰" to "Tasks", "⚙️" to "Settings")
    items.forEachIndexed { i, (e, l) ->
      val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER
      val ic = tv(e, 20f); ic.gravity = Gravity.CENTER; val lb = tv(l, 11f, SUB); lb.gravity = Gravity.CENTER
      col.addView(ic); col.addView(lb); navItems.add(ic to lb)
      col.setOnClickListener { select(i) }
      nav.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }
    root.addView(nav)
    select(0)
    } catch (t: Throwable) {
      Crash.save(this, t, "MainActivity.onCreateInner")
      try { android.app.AlertDialog.Builder(this).setTitle("Saara startup error").setMessage(t.stackTraceToString().take(2000)).setPositiveButton("OK", null).show() } catch (_: Exception) {}
    }
  }

  private fun select(i: Int) {
    curTab = i
    pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    navItems.forEachIndexed { k, (ic, lb) -> lb.setTextColor(if (k == i) ACC else SUB); ic.alpha = if (k == i) 1f else 0.6f }
    when (i) { 1 -> { rebuildLearning() }; 2 -> rebuildTasks(); 3 -> { rebuildSettings(force = true); refresh() } }
  }

  // ---------- CHAT ----------
  private fun buildChat(): View {
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setBackgroundColor(BG)
    val fr = FrameLayout(this)
    chatScroll = ScrollView(this)
    chatList = LinearLayout(this); chatList.orientation = LinearLayout.VERTICAL; chatList.setPadding(dp(14), dp(8), dp(14), dp(12))
    chatScroll.addView(chatList)
    chatScroll.setOnScrollChangeListener { _, _, y, _, _ -> if (y == 0 && !noMore && !loadingOlder && shown.isNotEmpty()) loadOlder() }
    fr.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
    val e = LinearLayout(this); e.orientation = LinearLayout.VERTICAL; e.gravity = Gravity.CENTER; emptyView = e
    val big = ImageView(this); big.setImageResource(R.drawable.logo_full); big.alpha = 0.95f
    e.addView(big, LinearLayout.LayoutParams(dp(200), dp(200)))
    val hi = tv("Hi, I'm Saara", 22f, bold = true); hi.gravity = Gravity.CENTER; e.addView(hi)
    val sub = tv("Tap Start to connect Gemini. Say \"Hey Sara\" or \"سارا\" to activate; then speak your commands.", 13f, SUB); sub.gravity = Gravity.CENTER; sub.setPadding(dp(24), dp(6), dp(24), 0); e.addView(sub)
    fr.addView(e, FrameLayout.LayoutParams(-1, -1))
    box.addView(fr, LinearLayout.LayoutParams(-1, 0, 1f))

    // share / mic chips
    val chips = LinearLayout(this); chips.setPadding(dp(12), dp(4), dp(12), dp(4))
    muteChip = chip("🎤 Mic: ON") { VoiceService.instance?.toggleMic() ?: toast("Tap Start first to connect Saara") }
    camChip = chip("📷 Camera") { toggleCam() }
    flipChip = chip("🔄") { camFront = !camFront; startService(svc("CAM_START").putExtra("front", camFront)) }
    screenChip = chip("🖥 Screen") { toggleScreen() }
    for (c in listOf(muteChip, camChip, flipChip, screenChip)) { val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); chips.addView(c, lp) }
    val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; hs.addView(chips)
    box.addView(hs)

    // input pill
    val ir = LinearLayout(this); ir.gravity = Gravity.CENTER_VERTICAL; ir.setPadding(dp(12), dp(4), dp(12), dp(10))
    val input = edit("Message Saara…"); input.maxLines = 4; input.background = bg(SURF2, 26); input.setPadding(dp(18), dp(10), dp(18), dp(10))
    input.imeOptions = EditorInfo.IME_ACTION_SEND; input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    val send = tv("➤", 20f, Color.WHITE, true); send.gravity = Gravity.CENTER; send.background = bg(ACC2, 24)
    val doSend = {
      val t = input.text.toString().trim()
      if (t.isNotEmpty()) {
        val s = VoiceService.instance
        if (s == null) toast("Tap Start first")
        else if (extractHttpUrl(t) != null) {
          input.text.clear()
          fetchAndSaveUrl(extractHttpUrl(t)!!)
        } else {
          s.sendText(t)
          input.text.clear()
        }
      }
    }
    send.setOnClickListener { doSend() }
    input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
    ir.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
    val slp = LinearLayout.LayoutParams(dp(48), dp(48)); slp.leftMargin = dp(8); ir.addView(send, slp)
    box.addView(ir)
    return box
  }

  private fun fmt(m: Msg): String {
    val body = when (m.role) { "tool" -> "⚙ " + m.text.take(180); else -> m.text }
    val stamp = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(m.ts))
    return body + "  ·  " + stamp
  }

  private fun dateLabel(ts: Long): TextView {
    val v = tv(SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date(ts)), 12f, SUB)
    v.gravity = Gravity.CENTER; v.setPadding(0, dp(12), 0, dp(2)); return v
  }
  private fun sameDay(a: Long, b: Long): Boolean = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(a)) == SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(b))

  private fun msgRow(m: Msg): Pair<View, TextView> {
    val t = tv(fmt(m), 15f)
    val maxW = (resources.displayMetrics.widthPixels * 0.80).toInt()
    when (m.role) {
      "you" -> {
        t.background = bg(SURF2, 20); t.setPadding(dp(14), dp(10), dp(14), dp(10)); t.maxWidth = maxW
        val row = LinearLayout(this); row.gravity = Gravity.END; row.addView(t); return row to t
      }
      "saara" -> {
        val row = LinearLayout(this); row.gravity = Gravity.TOP
        val av = ImageView(this); av.setImageResource(R.drawable.logo_symbol); av.background = bg(0xFFF8FAF9.toInt(), 12); av.clipToOutline = true
        row.addView(av, LinearLayout.LayoutParams(dp(24), dp(24)))
        t.setPadding(dp(10), 0, 0, 0); t.textSize = 16f; t.maxWidth = maxW + dp(20)
        row.addView(t); return row to t
      }
      "tool" -> {
        t.textSize = 12f; t.setTextColor(SUB); t.background = bg(SURF, 12); t.setPadding(dp(10), dp(5), dp(10), dp(5))
        val row = LinearLayout(this); row.addView(t); return row to t
      }
      else -> {
        val summary = m.text.startsWith("Done:") || m.text.startsWith("Couldn't complete") || m.text.startsWith("✅")
        if (summary) {
          t.textSize = 13f; t.setTextColor(TXT); t.background = bg(SURF, 14); t.setPadding(dp(12), dp(8), dp(12), dp(8)); t.maxWidth = maxW
          val row = LinearLayout(this); row.gravity = Gravity.START; row.addView(t); return row to t
        }
        t.textSize = 12f; t.setTextColor(SUB); t.gravity = Gravity.CENTER
        val row = LinearLayout(this); row.gravity = Gravity.CENTER; row.addView(t); return row to t
      }
    }
  }
  private fun rowLp(): LinearLayout.LayoutParams { val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); return lp }

  private fun loadOlder() {
    loadingOlder = true
    val older = Bus.older(firstId, 40)
    if (older.isEmpty()) { noMore = true; loadingOlder = false; return }
    val oldH = chatList.height
    val ordered = older.reversed()
    for (idx in ordered.indices.reversed()) {
      val m = ordered[idx]
      val nextNewer = ordered.getOrNull(idx + 1) ?: Bus.msgs.firstOrNull { it.id > m.id }
      val (row, t) = msgRow(m); chatList.addView(row, 0, rowLp()); shown[m.id] = t
      if (nextNewer == null || !sameDay(m.ts, nextNewer.ts)) chatList.addView(dateLabel(m.ts), 0, rowLp())
      if (m.id < firstId) firstId = m.id
    }
    chatScroll.post { chatScroll.post { chatScroll.scrollTo(0, maxOf(0, chatList.height - oldH)); loadingOlder = false } }
  }

  private fun toggleCam() {
    if (!Prefs.on(this, "f_share", true)) { toast("Camera & screen sharing is switched off in Settings → App features."); return }
    if (!Bus.running) { toast("Tap Start first, then share the camera."); return }
    if (Bus.camOn) { startService(svc("CAM_STOP")); return }
    if (!has(Manifest.permission.CAMERA)) { pendingCam = true; requestPermissions(arrayOf(Manifest.permission.CAMERA), 3); return }
    if (Prefs.on(this, "f_overlay", true) && !Settings.canDrawOverlays(this)) {
      AlertDialog.Builder(this).setTitle("Allow camera status overlay?")
        .setMessage("To keep the live camera-sharing indicator visible over other apps, allow Saara to display over other apps.")
        .setPositiveButton("Allow") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        .setNegativeButton("Continue without overlay") { _, _ -> startService(svc("CAM_START").putExtra("front", camFront)) }.show()
      return
    }
    startService(svc("CAM_START").putExtra("front", camFront))
  }
  @Suppress("DEPRECATION")
  private fun toggleScreen() {
    if (!Prefs.on(this, "f_share", true)) { toast("Camera & screen sharing is switched off in Settings → App features."); return }
    if (!Bus.running) { toast("Tap Start first, then share the screen."); return }
    if (Bus.screenOn) { startService(svc("SCREEN_STOP")); return }
    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(mpm.createScreenCaptureIntent(), 10)
  }
  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 10) {
      if (resultCode == RESULT_OK && data != null) startService(svc("SCREEN_START").putExtra("code", resultCode).putExtra("data", data))
      else toast("Screen sharing was cancelled.")
    }
  }

  // ---------- FEATURES ----------
  private fun feature(parent: LinearLayout, title: String, desc: String, key: String, def: Boolean, ok: () -> Boolean,
                      grantLabel: String, grant: () -> Unit, extra: ((LinearLayout) -> Unit)? = null) {
    val c = card(parent)
    val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
    val titles = LinearLayout(this); titles.orientation = LinearLayout.VERTICAL
    titles.addView(tv(title, 16f, bold = true)); titles.addView(tv(desc, 12f, SUB))
    val sw = Switch(this)
    sw.isChecked = Prefs.on(this, key, def)
      sw.setOnCheckedChangeListener { _, v ->
        Prefs.plain(this).edit().putBoolean(key, v).apply()
        if (key == "f_bubble" || key == "f_overlay") {
          val vs = VoiceService.instance
          if (key == "f_bubble" && v) vs?.onExplicitTrigger() else vs?.refreshBubble()
        }
        if (key == "f_calls") VoiceService.instance?.refreshCalls()
      }
    top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(sw)
    c.addView(top)
    val st = tv("", 12f)
    c.addView(st)
    val g = btn(grantLabel, SURF2) { grant() }
    val glp = LinearLayout.LayoutParams(-1, -2); glp.topMargin = dp(8)
    if (grantLabel.isNotEmpty()) c.addView(g, glp)
    extra?.invoke(c)
    addSettingsUpdater {
      val good = ok()
      st.text = if (good) (if (grantLabel.isEmpty()) "✓ Ready" else "✓ Permission granted") else "⚠ Permission needed"
      st.setTextColor(if (good) GOOD else WARN)
      g.visibility = if (good) View.GONE else View.VISIBLE
    }
  }

  private fun addFeatures(root: LinearLayout) {
    fun addCategory(cat: String, title: String, sub: String, blocks: List<Pair<Array<String>, (LinearLayout) -> Unit>>) {
      if (!settingsCatAllowed(cat)) return
      val matching = blocks.filter { (_, _) -> true }.filter { (kw, _) -> settingsQueryMatch(*kw) }
      if (matching.isEmpty()) return
      heading(root, title, sub)
      for ((_, build) in matching) {
        val tmp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        build(tmp)
        while (tmp.childCount > 0) { val v = tmp.getChildAt(0); tmp.removeViewAt(0); root.addView(v) }
      }
    }

    // ---- VOICE & WAKE ----
    addCategory("Voice & Wake", "Voice & Wake", "Wake phrases, speaker focus, language, barge-in, and confirmations.", listOf(
      arrayOf("wake phrase session timeout listen activation follow-up commands hey saara okay saara", "wake", "phrase", "session", "timeout") to { r ->
        val cw = card(r)
        cw.addView(tv("Wake phrases & session timeout", 16f, bold = true))
        cw.addView(tv("Saara listens continuously but ignores speech until a wake phrase is heard. After activation, follow-up commands work without repeating the wake phrase until the session times out.", 12f, SUB))
        val wakeList = LinearLayout(this); wakeList.orientation = LinearLayout.VERTICAL
        val wakeHeader = LinearLayout(this); wakeHeader.gravity = Gravity.CENTER_VERTICAL
        val wakeCount = tv("", 12f, SUB)
        val wakeToggle = tv("", 12f, ACC, true); wakeToggle.setPadding(dp(10), dp(6), dp(0), dp(6))
        fun renderWakePhrases() {
          wakeList.removeAllViews()
          val phrases = VoiceService.loadWakePhrases(Prefs.plain(this))
          wakeCount.text = "${phrases.size} phrase(s)"
          wakeToggle.text = if (wakePhrasesCollapsed) "Show" else "Hide"
          val show = if (wakePhrasesCollapsed) phrases.take(4) else phrases
          for ((idx, ph) in phrases.withIndex()) {
            if (wakePhrasesCollapsed && idx >= 4) continue
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, dp(4), 0, dp(4))
            row.addView(tv(ph, 14f), LinearLayout.LayoutParams(0, -2, 1f))
            val del = tv("Remove", 12f, BAD); del.setPadding(dp(10), dp(6), dp(0), dp(6))
            del.setOnClickListener {
              val cur = VoiceService.loadWakePhrases(Prefs.plain(this))
              if (idx in cur.indices) { cur.removeAt(idx); val e = Prefs.plain(this).edit(); VoiceService.saveWakePhrases(e, cur); e.apply(); renderWakePhrases(); VoiceService.instance?.refreshWakeConfig(); toast("Wake phrase removed") }
            }
            row.addView(del); wakeList.addView(row)
          }
          if (phrases.isEmpty()) wakeList.addView(tv("No wake phrases set (defaults will be used).", 12f, SUB))
          else if (wakePhrasesCollapsed && phrases.size > 4) wakeList.addView(tv("+ ${phrases.size - 4} more… tap Show to edit all.", 12f, SUB))
        }
        wakeToggle.setOnClickListener { wakePhrasesCollapsed = !wakePhrasesCollapsed; renderWakePhrases() }
        wakeHeader.addView(tv("Configured wake phrases", 13f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        wakeHeader.addView(wakeCount); wakeHeader.addView(wakeToggle)
        cw.addView(wakeHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        renderWakePhrases()
        cw.addView(wakeList)
        val addRow = LinearLayout(this); addRow.gravity = Gravity.CENTER_VERTICAL
        val addIn = edit("Add wake phrase (e.g. okay saara)"); addIn.setSingleLine(true)
        val addBtn = btn("Add", ACC2) {
          val v = addIn.text.toString().trim()
          if (v.isNotEmpty()) { val cur = VoiceService.loadWakePhrases(Prefs.plain(this)); if (cur.none { it.equals(v, true) }) cur.add(v); val e = Prefs.plain(this).edit(); VoiceService.saveWakePhrases(e, cur); e.apply(); addIn.text.clear(); renderWakePhrases(); VoiceService.instance?.refreshWakeConfig(); toast("Wake phrase added") }
        }
        addRow.addView(addIn, LinearLayout.LayoutParams(0, -2, 1f))
        addRow.addView(addBtn, LinearLayout.LayoutParams(-2, -2))
        cw.addView(addRow)
        cw.addView(btn("Reset default wake phrases", SURF2) {
          val e = Prefs.plain(this).edit(); VoiceService.saveWakePhrases(e, VoiceService.DEFAULT_WAKE_PHRASES); e.apply(); renderWakePhrases(); VoiceService.instance?.refreshWakeConfig(); toast("Defaults restored")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val timeoutTitle = tv("", 14f, TXT)
        val timeoutSeek = SeekBar(this); timeoutSeek.max = 999
        timeoutSeek.progress = Prefs.plain(this).getInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, VoiceService.DEFAULT_SESSION_TIMEOUT_SEC).coerceIn(0, 999)
        val timeoutBtn = tv("", 13f, Color.WHITE, true)
        fun paintTimeout() {
          val on = Prefs.plain(this).getBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, true)
          val sec = timeoutSeek.progress
          val effectiveOff = !on || sec <= 0
          timeoutBtn.text = if (effectiveOff) "Turn ON session timeout" else "Turn OFF session timeout"
          timeoutBtn.background = bg(if (!effectiveOff) ACC2 else SURF2, 18)
          timeoutBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
          timeoutSeek.alpha = if (!effectiveOff) 1f else 0.45f
          timeoutTitle.text = if (effectiveOff) "Session timeout: OFF (0–999s). Active session stays on until you stop/mute Saara." else "Session timeout: ${sec}s (0–999). After this silence, wake phrase is required again."
        }
        paintTimeout()
        timeoutBtn.setOnClickListener {
          val curOn = Prefs.plain(this).getBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, true) && timeoutSeek.progress > 0
          if (curOn) { Prefs.plain(this).edit().putBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, false).apply(); toast("Session timeout OFF") }
          else {
            val sec = if (timeoutSeek.progress <= 0) VoiceService.DEFAULT_SESSION_TIMEOUT_SEC else timeoutSeek.progress
            timeoutSeek.progress = sec
            Prefs.plain(this).edit().putBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, true).putInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, sec).apply()
            toast("Session timeout ON")
          }
          paintTimeout(); VoiceService.instance?.refreshWakeConfig()
        }
        timeoutSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
          override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) { val enabled = progress > 0; Prefs.plain(this@MainActivity).edit().putInt(VoiceService.PREF_SESSION_TIMEOUT_SEC, progress).putBoolean(VoiceService.PREF_SESSION_TIMEOUT_ENABLED, enabled).apply(); paintTimeout(); VoiceService.instance?.refreshWakeConfig() }
          }
          override fun onStartTrackingTouch(seekBar: SeekBar?) {}
          override fun onStopTrackingTouch(seekBar: SeekBar?) { toast("Session timeout saved") }
        })
        val tlp = LinearLayout.LayoutParams(-1, -2); tlp.topMargin = dp(12)
        cw.addView(timeoutTitle, tlp); cw.addView(timeoutBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }); cw.addView(timeoutSeek)
      },

      arrayOf("user only voice speaker focus match enroll re-enroll clear sample test voice match now strictness threshold always on verify bypass manual tap ignore media noise", "voice", "speaker", "enroll", "match", "microphone") to { r ->
        val uv = card(r)
        uv.addView(tv("User-Only Voice / Speaker Focus", 16f, bold = true))
        uv.addView(tv("When enabled, Saara accepts wake/commands only when voice matches your enrolled local voice sample. All matching stays on-device.", 12f, SUB))
        val uvStatus = tv(UserVoiceProfile.statusSummary(this), 12f, SUB); uv.addView(uvStatus)
        val uvLive = tv("", 12f, SUB); uv.addView(uvLive)

        fun swRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
          val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
          row.addView(tv(title, 14f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
          val sw = Switch(this); sw.isChecked = checked; sw.setOnCheckedChangeListener { _, v -> onChange(v) }
          row.addView(sw); uv.addView(row); return sw
        }
        swRow("Only Catch My Voice", UserVoiceProfile.onlyUserVoiceEnabled(this)) { v ->
          Prefs.plain(this).edit().putBoolean("f_only_user_voice", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); uvStatus.text = UserVoiceProfile.statusSummary(this); toast(if (v) "Only My Voice ON" else "Only My Voice OFF")
        }
        swRow("Verify every voice input & command (Always-On)", UserVoiceProfile.verifyAllInputsEnabled(this)) { v ->
          Prefs.plain(this).edit().putBoolean("f_voice_verify_all", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig(); uvStatus.text = UserVoiceProfile.statusSummary(this); toast(if (v) "Always-On voice verification ON" else "Wake-only verification")
        }
        swRow("Bypass on manual tap / unmute", UserVoiceProfile.bypassOnManual(this)) { v ->
          Prefs.plain(this).edit().putBoolean("f_voice_match_bypass_manual", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig()
        }
        swRow("Ignore media/TV/background playback noise", UserVoiceProfile.rejectMediaNoise(this)) { v ->
          Prefs.plain(this).edit().putBoolean("f_voice_reject_media", v).apply(); VoiceService.instance?.refreshSpeakerGuardConfig()
        }

        uv.addView(tv("Voice Match Strictness preset", 13f, SUB))
        val strictRow = LinearLayout(this)
        fun paintStrict() {
          val cur = UserVoiceProfile.strictnessPreset(this)
          for (i in 0 until strictRow.childCount) { val c = strictRow.getChildAt(i) as? TextView ?: continue; val tag = c.tag as? String ?: continue; val sel = tag == cur; c.background = bg(if (sel) ACC2 else SURF2, 18); c.setTextColor(if (sel) Color.WHITE else TXT) }
        }
        listOf("relaxed" to "Relaxed", "balanced" to "Balanced", "strict" to "Strict").forEach { (k, label) ->
          val ch = chip(label) { val v = UserVoiceProfile.applyStrictnessPreset(this, k); paintStrict(); uvStatus.text = UserVoiceProfile.statusSummary(this); VoiceService.instance?.refreshSpeakerGuardConfig(); toast("$label strictness: ${v}%"); refresh() }
          ch.tag = k; val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); strictRow.addView(ch, lp)
        }
        uv.addView(strictRow); paintStrict()

        val thLabel = tv("", 14f, TXT); val thSeek = SeekBar(this); thSeek.max = 100; thSeek.progress = UserVoiceProfile.threshold(this)
        fun paintTh() { val v = thSeek.progress.coerceIn(0, 100); thLabel.text = "Minimum Voice Match: ${v}%"; uvStatus.text = UserVoiceProfile.statusSummary(this) }
        paintTh()
        thSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
          override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { val v = progress.coerceIn(0, 100); UserVoiceProfile.setThreshold(this@MainActivity, v); Prefs.plain(this@MainActivity).edit().putString("voice_match_strictness", "custom").apply(); paintTh(); paintStrict(); VoiceService.instance?.refreshSpeakerGuardConfig() } }
          override fun onStartTrackingTouch(seekBar: SeekBar?) {}
          override fun onStopTrackingTouch(seekBar: SeekBar?) { toast("Voice match threshold saved: ${thSeek.progress}%") }
        })
        uv.addView(thLabel)
        uv.addView(tv("Voice is accepted only when match confidence is at or above this percentage. 50% means matchScore >= 50. Strict preset is 80%; you can still drag to 100% manually.", 11f, SUB))
        uv.addView(thSeek)

        val enrollRow = LinearLayout(this); enrollRow.orientation = LinearLayout.HORIZONTAL
        enrollRow.addView(btn("Enroll / Re-enroll", ACC2) {
          val dlg = AlertDialog.Builder(this).setTitle("Enroll My Voice").setMessage("Preparing…").setCancelable(false).setNegativeButton("Close", null).show()
          val vs = VoiceService.instance
          if (vs != null) {
            vs.enrollUserVoice(6, onProgress = { prog -> runOnUiThread { if (dlg.isShowing) dlg.setMessage("Step ${prog.step}/${prog.total}: ${prog.message}") } }, onDone = { res -> runOnUiThread { if (dlg.isShowing) dlg.setMessage(if (res.ok) res.message else "Failed: ${res.message}"); dlg.setCancelable(true); uvStatus.text = UserVoiceProfile.statusSummary(this); paintTh(); paintStrict(); refresh() } })
          } else {
            Thread { val res = VoiceEnrollment.enroll(applicationContext, 6) { prog -> runOnUiThread { if (dlg.isShowing) dlg.setMessage("Step ${prog.step}/${prog.total}: ${prog.message}") } }; runOnUiThread { if (dlg.isShowing) dlg.setMessage(if (res.ok) res.message else "Failed: ${res.message}"); dlg.setCancelable(true); uvStatus.text = UserVoiceProfile.statusSummary(this); paintTh(); paintStrict(); refresh() } }.start()
          }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        enrollRow.addView(btn("Clear Sample", SURF2) {
          AlertDialog.Builder(this).setMessage("Clear enrolled voice sample?").setPositiveButton("Clear") { _, _ -> UserVoiceProfile.clear(this); uvStatus.text = UserVoiceProfile.statusSummary(this); VoiceService.instance?.clearUserVoiceEnrollment(); toast("Voice sample cleared") }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        uv.addView(enrollRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        uv.addView(btn("Test voice match now", SURF2) {
          if (!UserVoiceProfile.exists(this)) { toast("Enroll your voice first"); return@btn }
          val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
          if (perm == PackageManager.PERMISSION_DENIED) { toast("Microphone permission denied"); return@btn }
          val dlg = AlertDialog.Builder(this).setTitle("Voice Match Test").setMessage("Recording ~3s… speak now.").setCancelable(false).setNegativeButton("Close", null).show()
          val vs = VoiceService.instance
          Thread {
            try { vs?.suspendWakeForEnrollment(); Thread.sleep(450) } catch (_: Exception) {}
            val (pct, err) = VoiceEnrollment.quickCheck(applicationContext)
            try { vs?.resumeWakeAfterEnrollment() } catch (_: Exception) {}
            runOnUiThread {
              val th = UserVoiceProfile.threshold(this)
              val msg = if (pct == null) "Could not capture/check voice: ${err ?: "unknown error"}" else "Match: ${"%.1f".format(pct)}% · Threshold: ${th}% · " + if (pct >= th) "ACCEPT" else "REJECT"
              if (dlg.isShowing) { dlg.setMessage(msg); dlg.setCancelable(true) }
              Bus.add("system", "Voice match test: $msg", true)
            }
          }.start()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addSettingsUpdater { uvLive.text = if (Bus.running) VoiceService.instance?.speakerStatus() ?: "Service not running" else "Start Saara to see live speaker-match diagnostics." }
      },

      arrayOf("language priority bengali hindi english hinglish auto speak", "language", "bangla") to { r ->
        val cl = card(r)
        cl.addView(tv("Language priority", 16f, bold = true))
        cl.addView(tv("First-priority languages: Bengali, Hindi, English. Pick how Saara should speak by default.", 12f, SUB))
        val opts = listOf("auto" to "Auto", "bn" to "বাংলা", "hi" to "हिन्दी", "en" to "English", "hinglish" to "Hinglish")
        val chips = HashMap<String, TextView>()
        val paint = { val cur = Prefs.plain(this).getString("lang", "auto"); for ((k, v) in chips) { v.background = bg(if (k == cur) ACC2 else SURF2, 18); v.setTextColor(if (k == cur) Color.WHITE else TXT) } }
        val row = LinearLayout(this); row.setPadding(0, dp(10), 0, 0)
        for ((k, l) in opts) { val ch = chip(l) { Prefs.plain(this).edit().putString("lang", k).apply(); paint(); toast("Saved. Stop and Start Saara to apply.") }; chips[k] = ch; val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); row.addView(ch, lp) }
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; hs.addView(row); cl.addView(hs); paint()
      },

      arrayOf("barge in interruption stop speech", "barge", "interrupt") to { r ->
        feature(r, "Barge-in interruption", "Stop Saara speech when user speaks a new command.", "f_barge_in", true, { true }, "", {})
      },

      arrayOf("confirm calls sms messages whatsapp telegram reply before sending", "confirm", "confirmation", "call", "sms", "message") to { r ->
        feature(r, "Confirm before calls", "Ask before placing calls.", "confirm_calls", true, { true }, "", {})
        feature(r, "Confirm before SMS/messages", "Ask before sending SMS/WhatsApp/Telegram/replies.", "confirm_messages", true, { true }, "", {})
      }
    ))

    // ---- AI & MODELS ----
    addCategory("AI & Models", "AI & Models", "Gemini connection, model selection, fallback, and retry behavior.", listOf(
      arrayOf("model auto fallback live primary fast lite task smart vision complex retry delay 503 429 timeout detect supported models save", "model", "fallback", "retry", "503", "gemini model") to { r ->
        val mc = card(r)
        mc.addView(tv("Model & Auto-Fallback", 16f, bold = true))
        mc.addView(tv("Automatic model switching on 503/busy/rate-limit and task-aware model selection.", 12f, SUB))
        val autoSw = Switch(this); autoSw.text = "Auto-switch model on error / busy (503, 429, timeout)"; autoSw.isChecked = Prefs.on(this, "f_auto_model_fallback", true)
        autoSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("f_auto_model_fallback", v).apply(); toast(if (v) "Auto model fallback ON" else "Auto model fallback OFF") }
        mc.addView(autoSw)
        val smartSw = Switch(this); smartSw.text = "Smart task-based model selection"; smartSw.isChecked = Prefs.on(this, "f_smart_model", true)
        smartSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("f_smart_model", v).apply(); toast(if (v) "Smart model selection ON" else "Smart model selection OFF") }
        mc.addView(smartSw)
        val status = tv("Status: ${ModelRouter.lastStatus(this).ifBlank { "No model fallback event yet." }}", 12f, SUB); status.setPadding(0, dp(8), 0, dp(8)); mc.addView(status)

        fun labeledEdit(label: String, hint: String, value: String): EditText {
          mc.addView(tv(label, 12f, SUB))
          val e = edit(hint); e.setText(value); mc.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }); return e
        }
        val primary = labeledEdit("Live / Primary model", "e.g. gemini-3.1-flash-live-preview", ModelRouter.primaryModel(this))
        val arModel = labeledEdit("Fast / Lite auto-reply model (task)", "e.g. gemini-3.5-flash-lite", Prefs.plain(this).getString("model_auto_reply", ModelRouter.DEFAULT_TEXT_FAST.first()) ?: "")
        val visionModel = labeledEdit("Vision / screen analysis model (task)", "e.g. gemini-3.5-flash-lite", Prefs.plain(this).getString("model_vision", ModelRouter.DEFAULT_VISION.first()) ?: "")
        val complexModel = labeledEdit("Task / Smart complex reasoning model", "e.g. gemini-3.8-flash", Prefs.plain(this).getString("model_complex", ModelRouter.DEFAULT_TEXT_CAPABLE.first()) ?: "")
        mc.addView(tv("Fallback model order (one per line, used on 503/busy/error)", 12f, SUB))
        val fb = edit("One model per line"); fb.minLines = 3; fb.setText(ModelRouter.fallbackModels(this).joinToString("\n")); mc.addView(fb)

        fun seekInt(title:String, key:String, def:Int, min:Int, max:Int, suffix:String) {
          val lbl = tv("", 12f, TXT)
          fun paint() { lbl.text = "$title: ${Prefs.plain(this).getInt(key, def)}$suffix" }
          paint(); mc.addView(lbl)
          val sk = SeekBar(this); sk.max = max - min; sk.progress = (Prefs.plain(this).getInt(key, def) - min).coerceIn(0, max-min)
          sk.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { Prefs.plain(this@MainActivity).edit().putInt(key, min + progress).apply(); paint() } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
          })
          mc.addView(sk)
        }
        seekInt("Retry count before fallback/fail", "model_retry_count", 2, 0, 5, "")
        seekInt("Retry delay", "model_retry_delay_ms", 1200, 250, 10000, " ms")

        val rowBtns = LinearLayout(this); rowBtns.orientation = LinearLayout.HORIZONTAL
        rowBtns.addView(btn("Save model settings", ACC2) {
          val p = Prefs.plain(this).edit()
          p.putString("model_primary", primary.text.toString().trim())
          p.putString("model", primary.text.toString().trim().ifBlank { ModelRouter.primaryModel(this) })
          p.putString("model_auto_reply", arModel.text.toString().trim())
          p.putString("model_vision", visionModel.text.toString().trim())
          p.putString("model_complex", complexModel.text.toString().trim())
          p.apply()
          ModelRouter.saveFallbackModels(this, fb.text.toString().split('\n', ','))
          toast("Model settings saved. Restart Saara for live model changes.")
          status.text = "Status: ${ModelRouter.lastStatus(this).ifBlank { "Saved." }}"
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        rowBtns.addView(btn("Detect supported models", SURF2) {
          Thread {
            val models = ModelRouter.fetchSupportedModels(this)
            runOnUiThread {
              if (models.isEmpty()) toast("No models detected (check API key/network)")
              else {
                ModelRouter.saveSupportedCache(this, models)
                val cur = fb.text.toString().split('\n').map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
                models.filter { it.contains("flash", true) || it.contains("pro", true) }.take(8).forEach { cur.add(it) }
                fb.setText(cur.joinToString("\n"))
                toast("Detected ${models.size} models; supported cache updated")
              }
            }
          }.start()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        mc.addView(rowBtns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
      },

      arrayOf("gemini connection api key live model voice save settings fetch models pick model get free api key", "api", "key", "gemini", "connection", "voice") to { r ->
        val c3 = card(r)
        c3.addView(tv("Gemini connection", 16f, bold = true))
        c3.addView(tv("Connect your Gemini API key, choose a live model, and select Saara's voice.", 12f, SUB))
        val saved = !(Prefs.secure(this).getString("apiKey", "") ?: "").isBlank()
        val key = edit(if (saved) "API key saved ✓ (type a new one to replace)" else "Paste Gemini API key", true)
        c3.addView(tv("Gemini API key", 12f, SUB)); c3.addView(key)
        val model = edit("Live model"); model.setText(Prefs.plain(this).getString("model", "gemini-3.1-flash-live-preview"))
        var voiceName = Prefs.plain(this).getString("voice", "Aoede") ?: "Aoede"
        c3.addView(tv("Live / Primary model", 12f, SUB)); c3.addView(model)
        val pick = btn("Pick model from list", SURF2) {
          val all = (MODELS + fetchedModels).distinct()
          AlertDialog.Builder(this).setTitle("Live models").setItems(all.toTypedArray()) { _, w -> model.setText(all[w]) }.show()
        }
        val fetch = btn("Fetch live models for my key", SURF2) {
          val k = key.text.toString().trim().ifBlank { Prefs.secure(this).getString("apiKey", "") ?: "" }
          if (k.isBlank()) toast("Add your API key first") else fetchModels(k) { list -> if (list.isEmpty()) toast("No live models found (or key invalid)") else { fetchedModels.clear(); fetchedModels.addAll(list); toast("${list.size} model(s) found. Tap 'Pick model from list'.") } }
        }
        val lp1 = LinearLayout.LayoutParams(-1, -2); lp1.topMargin = dp(8)
        c3.addView(pick, lp1); c3.addView(fetch, lp1)
        val voiceBtn = btn("Voice: $voiceName  ▾", SURF2) {}
        voiceBtn.setOnClickListener { AlertDialog.Builder(this).setTitle("Voice").setItems(VOICES.toTypedArray()) { _, w -> voiceName = VOICES[w]; voiceBtn.text = "Voice: $voiceName  ▾" }.show() }
        c3.addView(voiceBtn, lp1)
        val sb = btn("Save settings") {
          Prefs.plain(this).edit().putString("model", model.text.toString().trim()).putString("voice", voiceName).apply()
          if (key.text.isNotBlank()) { Prefs.secure(this).edit().putString("apiKey", key.text.toString().trim()).apply(); key.text.clear(); key.hint = "API key saved ✓ (type a new one to replace)" }
          toast("Saved. Stop and Start Saara to apply.")
        }
        c3.addView(sb, lp1)
        c3.addView(btn("Get free Gemini API key", SURF2) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))) }, lp1)
      },

      arrayOf("offline 503 fallback retry network backoff queue pending tasks", "offline", "503", "retry", "network") to { r ->
        feature(r, "Offline/503 fallback retry", "Auto-retry transient network/503 errors with backoff and queue pending tasks.", "f_offline_retry", true, { true }, "", {})
      }
    ))

    // ---- AUTO-REPLY & RULES ----
    addCategory("Auto-Reply & Rules", "Auto-Reply & Rules", "Notification replies, rules, automation, and logs.", listOf(
      arrayOf("notification auto reply master ai smart auto reply gemini group fallback sender names system instructions replying rules logs manage view", "auto-reply", "reply", "notification", "rules", "logs", "sender") to { r ->
        feature(r, "Notification auto-reply master", "Read incoming notifications and auto-reply with rules/AI. Works with Notification Access.", "f_notif", false, { notifOn() },
          "Enable Notification access", { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { c ->
          val aiRow = LinearLayout(this); aiRow.gravity = Gravity.CENTER_VERTICAL
          aiRow.addView(tv("AI smart auto-reply (Gemini)", 14f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
          val aiSw = Switch(this); aiSw.isChecked = Prefs.on(this, "f_ai_reply", false)
          aiSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("f_ai_reply", v).apply() }
          aiRow.addView(aiSw); c.addView(aiRow)

          val grpRow = LinearLayout(this); grpRow.gravity = Gravity.CENTER_VERTICAL
          grpRow.addView(tv("Reply in group chats (fallback)", 14f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
          val grpSw = Switch(this); grpSw.isChecked = Prefs.on(this, "reply_groups", false)
          grpSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("reply_groups", v).apply() }
          grpRow.addView(grpSw); c.addView(grpRow)

          c.addView(tv("Fallback sender names (CSV, when no rules match)", 12f, SUB))
          val names = edit("e.g. Mom, +9198..., WhatsApp"); names.setText(Prefs.plain(this).getString("reply_names", ""))
          names.doAfterTextChanged { Prefs.plain(this).edit().putString("reply_names", it.toString()).apply() }; c.addView(names)

          c.addView(tv("AI system instructions", 12f, SUB))
          val prompt = edit("Custom instructions for auto-replies"); prompt.minLines = 3
          prompt.setText(Prefs.plain(this).getString("ai_reply_prompt", null) ?: AiAutoReply.defaultPrompt())
          prompt.doAfterTextChanged { Prefs.plain(this).edit().putString("ai_reply_prompt", it.toString()).apply() }; c.addView(prompt)

          c.addView(btn("Manage Replying Rules (per-app/sender/quiet/chat/keywords)", SURF2) { showReplyRulesDialog() }, LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
          c.addView(btn("View auto-reply logs", SURF2) { showAutoReplyLogs() }, LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        }
      },

      arrayOf("automation triggers rules post-task verification self-learning lessons mistake-to-rule open automation manager condition action", "automation", "trigger", "task", "verification", "lesson") to { r ->
        feature(r, "Post-task verification loop", "After UI/tool actions, verify screen/result and suggest retry path on failure.", "f_post_verify", true, { true }, "", {})
        feature(r, "Task self-learning / lessons", "Remember what worked/failed and store guardrail lessons from repeated mistakes.", "learn_tasks", true, { true }, "", {})
        feature(r, "Mistake-to-rule self-correction", "Convert repeated failures into actionable guardrail lessons/rules.", "f_mistake_lessons", true, { true }, "", {})
        val autoBtnCard = card(r)
        autoBtnCard.addView(tv("Automation rules & triggers", 16f, bold = true))
        autoBtnCard.addView(tv("Triggers: time, screen_on, screen_off, battery, notification, missed_call, active_app. Conditions: battery<20, screen off, notification contains otp, etc.", 12f, SUB))
        autoBtnCard.addView(btn("Open Automation Manager", ACC2) { select(2) }, LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
      }
    ))

    // ---- SCREEN & OVERLAY ----
    addCategory("Screen & Overlay", "Screen & Overlay", "Screen automation, sharing, overlay, clipboard, and OCR actions.", listOf(
      arrayOf("screen automation accessibility camera screen sharing live caption overlay floating bubble clipboard copy paste screenask ocr summarize autofill extract", "screen", "overlay", "bubble", "clipboard", "ocr", "accessibility", "camera", "share") to { r ->
        feature(r, "Screen automation (Accessibility)", "Read screen, tap/type/scroll in other apps.", "f_ui", true, { a11yOn() }, "Enable Accessibility", { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        feature(r, "Camera & screen sharing", "Allow live camera/screen frames and one-shot screen capture/OCR.", "f_share", true, { has(Manifest.permission.CAMERA) }, "Allow camera", { requestPermissions(arrayOf(Manifest.permission.CAMERA), 3) })
        feature(r, "Live caption overlay", "Compact status/caption pill over other apps. When bubble is ON, caption stays beside bubble.", "f_overlay", true, { Settings.canDrawOverlays(this) }, "Allow overlay", { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        feature(r, "Floating bubble", "Messenger-style chat head. Tap to open panel; drag to X to hide. When ON, Saara shows it on every start/trigger.", "f_bubble", false, { Settings.canDrawOverlays(this) }, "Allow overlay", { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { c ->
          c.addView(tv("Bubble size", 12f, SUB))
          val sizeLabel = tv("", 13f, TXT)
          val sizeSeek = SeekBar(this); sizeSeek.max = 32; sizeSeek.progress = (Prefs.plain(this).getInt("bubble_size_dp", 58).coerceIn(44, 76) - 44)
          fun paintSize() { sizeLabel.text = "Size: ${44 + sizeSeek.progress}dp" }
          paintSize(); c.addView(sizeLabel); c.addView(sizeSeek)
          sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { Prefs.plain(this@MainActivity).edit().putInt("bubble_size_dp", 44 + progress).apply(); paintSize(); VoiceService.instance?.refreshBubbleCustomization() } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
          })
          val edgeRow = LinearLayout(this); edgeRow.gravity = Gravity.CENTER_VERTICAL
          edgeRow.addView(tv("Swipe bubble to edge to hide; tap or drag inward to reveal", 14f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
          val edgeSw = Switch(this); edgeSw.isChecked = Prefs.on(this, "bubble_edge_hide", false)
          edgeSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("bubble_edge_hide", v).apply(); VoiceService.instance?.refreshBubbleCustomization() }
          edgeRow.addView(edgeSw); c.addView(edgeRow)
        }
        feature(r, "Clipboard copy & paste", "Allow UI automation copy/paste helpers.", "f_clipboard", true, { true }, "", {})
        val scr = card(r)
        scr.addView(tv("ScreenAsk / OCR quick actions", 16f, bold = true))
        scr.addView(tv("Use when you want Saara to summarize or extract structured fields from the current screen.", 12f, SUB))
        val rowScr = LinearLayout(this); rowScr.orientation = LinearLayout.HORIZONTAL
        rowScr.addView(btn("Summarize screen", SURF2) { if (Bus.running) startActivity(Intent(this, ScreenAskActivity::class.java).putExtra("mode","capture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else toast("Start Saara first") }, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        rowScr.addView(btn("OCR / Autofill extract", SURF2) { if (Bus.running) startActivity(Intent(this, ScreenAskActivity::class.java).putExtra("mode","ocr").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else toast("Start Saara first") }, LinearLayout.LayoutParams(0,-2,1f))
        scr.addView(rowScr)
      }
    ))

    // ---- MEMORY & SYSTEM ----
    addCategory("Memory & System", "Memory & System", "Permissions, memory, cloud sync, and chat/session data.", listOf(
      arrayOf("permissions system readiness notification listener accessibility overlay battery optimization phone sms contacts call log", "permission", "ready", "battery", "accessibility", "overlay") to { r ->
        val ready = card(r)
        fun statusRow(label: String, ok: Boolean, actionLabel: String, action: () -> Unit): View {
          val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, dp(6), 0, dp(6))
          val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
          col.addView(tv(label, 14f, TXT, true))
          col.addView(tv(if (ok) "Ready" else "Action needed", 12f, if (ok) GOOD else WARN))
          row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
          if (!ok) row.addView(btn(actionLabel, SURF2, action), LinearLayout.LayoutParams(-2, -2))
          return row
        }
        var readyBuilt = false
        addSettingsUpdater {
          try {
            if (!readyBuilt || ready.childCount == 0) {
              ready.removeAllViews()
              ready.addView(tv("Permissions & System Readiness", 16f, bold = true))
              ready.addView(tv("Live status and one-tap shortcuts for required system access.", 12f, SUB))
              ready.addView(statusRow("Notification Listener", notifOn(), "Open") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
              ready.addView(statusRow("Accessibility Service", a11yOn(), "Open") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
              ready.addView(statusRow("Overlay permission", Settings.canDrawOverlays(this), "Open") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
              val pm = getSystemService(PowerManager::class.java)
              val battOk = try { pm?.isIgnoringBatteryOptimizations(packageName) == true } catch (_: Exception) { false }
              ready.addView(statusRow("Battery optimization exemption", battOk, "Open") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
              })
              ready.addView(statusRow("Phone/SMS/Contacts/Call log", has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.SEND_SMS) && has(Manifest.permission.READ_SMS) && has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.READ_CALL_LOG), "Grant") {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE), 2)
              })
              readyBuilt = true
            }
          } catch (_: Exception) {}
        }
      },

      arrayOf("memory pruning retention ledger open memory inspector fact prune days ledger rows cloud sync firebase firestore", "memory", "prune", "ledger", "cloud", "sync", "inspector") to { r ->
        feature(r, "Memory pruning (3D+ low-use)", "Auto-prune old unused, unpinned facts.", "f_mem_prune", true, { true }, "", {})
        val memCard = card(r)
        memCard.addView(tv("Memory retention & ledger", 16f, bold = true))
        fun seekSetting(title:String, key:String, def:Int, min:Int, max:Int, suffix:String) {
          val label = tv("", 13f, TXT)
          fun paint(){ label.text = "$title: ${Prefs.plain(this).getInt(key,def)}$suffix" }
          paint(); memCard.addView(label)
          val sk = SeekBar(this); sk.max=max-min; sk.progress=(Prefs.plain(this).getInt(key,def)-min).coerceIn(0,max-min)
          sk.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if(fromUser){ Prefs.plain(this@MainActivity).edit().putInt(key,min+progress).apply(); paint() } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
          }); memCard.addView(sk)
        }
        seekSetting("Fact prune days", "mem_prune_days", 3, 1, 30, " days")
        seekSetting("Ledger recent rows", "ledger_recent_n", 10, 5, 50, " rows")
        memCard.addView(btn("Open Memory Inspector", SURF2) { select(1) }, LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val cloud = card(r)
        cloud.addView(tv("Cloud sync (Firebase/Firestore)", 16f, bold = true))
        cloud.addView(tv("Current build: no Firebase/Firestore dependency detected in this repo. Memory remains local-first. Add Firebase config/dependencies to enable cloud sync controls.", 12f, SUB))
        cloud.addView(tv("Status: Not configured", 13f, WARN))
      },

      arrayOf("chat history session continuity resume recent session task clear chat history clear recent session state", "chat", "history", "session", "continuity", "clear") to { r ->
        val c4 = card(r)
        c4.addView(tv("Chat history & session continuity", 16f, bold = true))
        c4.addView(tv("Old chats stay on-device and load as you scroll up. Recent session context can resume after reconnect/restart.", 12f, SUB))
        val contRow = LinearLayout(this); contRow.gravity = Gravity.CENTER_VERTICAL
        contRow.addView(tv("Resume recent session/task", 14f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
        val contSw = Switch(this); contSw.isChecked = Prefs.on(this, "f_session_continuity", true)
        contSw.setOnCheckedChangeListener { _, v -> Prefs.plain(this).edit().putBoolean("f_session_continuity", v).apply(); toast(if (v) "Session continuity ON" else "Session continuity OFF") }
        contRow.addView(contSw); c4.addView(contRow)
        val st = try { Db.get(this).sessionState() } catch (_: Exception) { null }
        val recent = try { Db.get(this).chatRecent(8) } catch (_: Exception) { emptyList() }
        val summary = buildString {
          append("Saved turns: ").append(recent.size).append(" recent loaded")
          if (st != null && st.updatedTs > 0) {
            append("\nStatus: ").append(st.status)
            if (st.activeGoal.isNotBlank()) append("\nActive goal: ").append(st.activeGoal.take(120))
            if (st.pending.isNotBlank()) append("\nPending: ").append(st.pending.take(120))
            if (st.lastError.isNotBlank()) append("\nLast issue: ").append(st.lastError.take(120))
          }
        }
        c4.addView(tv(summary, 12f, SUB))
        val lp1 = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        c4.addView(btn("Clear chat history", SURF2) {
          AlertDialog.Builder(this).setMessage("Delete all saved chats? Learned memory and shop data stay.").setPositiveButton("Delete") { _, _ -> Bus.clear(); Bus.add("system", "Chat history cleared.", true); refresh() }.setNegativeButton("Cancel", null).show()
        }, lp1)
        c4.addView(btn("Clear recent session state", SURF2) {
          AlertDialog.Builder(this).setMessage("Clear recent session state? This removes active/pending session continuity but keeps chat history.").setPositiveButton("Clear") { _, _ ->
            try { Db.get(this).sessionStateClear(true) } catch (_: Exception) {}
            Bus.add("system", "Recent session state cleared.", true); toast("Recent session state cleared"); refresh()
          }.setNegativeButton("Cancel", null).show()
        }, lp1)
      }
    ))

    // Footer
    if (settingsSearchQuery.isBlank() && settingsCategory == "All") {
      val c5 = card(root); c5.gravity = Gravity.CENTER_HORIZONTAL; c5.background = bg(SURF, 20)
      val img = ImageView(this); img.setImageResource(R.drawable.logo_full)
      c5.addView(img, LinearLayout.LayoutParams(dp(120), dp(120)))
      c5.addView(tv("Saara AI Agent", 16f, TXT, true).apply { gravity = Gravity.CENTER })
      c5.addView(tv("Your on-device assistant settings, organized and ready.", 12f, SUB).apply { gravity = Gravity.CENTER })
    }
  }

  private fun buildAutomation(): View {
    val (sv, root) = page()
    heading(root, "Tasks & Automation", "Condition + Trigger + Action rules, plus scheduled reminders.")
    val autoCard = card(root)
    autoCard.addView(tv("Automation rules", 16f, bold = true))
    autoCard.addView(tv("Triggers: time, screen_on, screen_off, battery, notification, missed_call, active_app. Condition examples: battery<20, screen off, notification contains otp. Action: text Saara should execute.", 12f, SUB))
    autoCard.addView(btn("Add automation rule", ACC2) { showAutoRuleEditor(null) { rebuildTasks() } }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
    taskBox = card(root)
    return sv
  }

  private fun showAutoRuleEditor(existing: AutoRule?, onDone: () -> Unit) {
    val db = Db.get(this)
    val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setPadding(dp(12), dp(8), dp(12), dp(8))
    fun e(h:String,v:String="") = edit(h).apply { setText(v) }
    val name = e("Rule name", existing?.name ?: "")
    val trigger = e("Trigger: time/screen_on/screen_off/battery/notification/missed_call/active_app", existing?.trigger ?: "time")
    val condition = e("Condition (e.g. battery<20, screen off, notification contains bank)", existing?.condition ?: "")
    val action = e("Action (Saara command/text)", existing?.action ?: "")
    val cooldown = e("Cooldown minutes", ((existing?.cooldownMs ?: 0L)/60000L).toString())
    val enabled = Switch(this); enabled.text="Enabled"; enabled.isChecked = existing?.enabled ?: true
    listOf<View>(name,trigger,condition,action,cooldown,enabled).forEach { root.addView(it) }
    AlertDialog.Builder(this).setTitle(if (existing==null) "Add automation rule" else "Edit automation rule").setView(ScrollView(this).apply{addView(root)})
      .setPositiveButton("Save") { _, _ ->
        val r = AutoRule(existing?.id ?: 0, name.text.toString(), enabled.isChecked, trigger.text.toString(), condition.text.toString(), action.text.toString(), (cooldown.text.toString().toLongOrNull() ?: 0L)*60000L, existing?.lastFiredMs ?: 0L, System.currentTimeMillis())
        if (existing==null) db.autoRuleAdd(r) else db.autoRuleUpdate(r)
        Automation.scheduleAll(this); onDone()
      }.setNegativeButton("Cancel", null).show()
  }

  private fun showReplyRulesDialog() {
    val db = Db.get(this)
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(12), dp(8), dp(12), dp(8))
    fun refresh() {
      box.removeAllViews()
      box.addView(tv("Replying Rules control where/when Saara replies. First matching block/quiet/screen-off rule can stop replies; matching allow rules add instructions.", 12f, SUB))
      box.addView(btn("Add rule", ACC2) { showReplyRuleEditor(null) { showReplyRulesDialog() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
      val rules = db.replyRules()
      if (rules.isEmpty()) box.addView(tv("No rules yet. Fallback uses sender filter + group toggle.", 13f, SUB))
      for (r in rules) {
        val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL; card.background = bg(SURF2, 14); card.setPadding(dp(10), dp(8), dp(10), dp(8))
        val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
        top.addView(tv(r.name.ifBlank { "Rule ${r.id}" }, 14f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this); sw.isChecked = r.enabled; sw.setOnCheckedChangeListener { _, v -> db.replyRuleSetEnabled(r.id, v) }
        top.addView(sw); card.addView(top)
        card.addView(tv("apps=${r.apps.ifBlank { "any" }} · senders=${r.senders.ifBlank { "any" }} · vip=${r.vipSenders.ifBlank { "none" }} · mute=${r.muteSenders.ifBlank { "none" }} · chat=${r.chatType} · mode=${r.mode}", 11f, SUB))
        card.addView(tv("keywords=${r.keywords.ifBlank { "any" }} · quiet=${r.quietStart.ifBlank { "--" }}-${r.quietEnd.ifBlank { "--" }} · screenOffOnly=${r.screenOffOnly}", 11f, SUB))
        if (r.instruction.isNotBlank()) card.addView(tv("instruction: ${r.instruction}", 11f, SUB))
        if (r.replyData.isNotBlank()) card.addView(tv("reply data: configured (${r.replyData.take(60).replace("\n", " ")}${if (r.replyData.length > 60) "…" else ""})", 11f, ACC))
        val actions = LinearLayout(this)
        actions.addView(btn("Edit", SURF) { showReplyRuleEditor(r) { showReplyRulesDialog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(btn("Delete", SURF) { db.replyRuleDelete(r.id); refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
      }
    }
    refresh()
    val sv = ScrollView(this); sv.addView(box)
    AlertDialog.Builder(this).setTitle("Replying Rules").setView(sv).setPositiveButton("Close", null).show()
  }

  private fun showReplyRuleEditor(existing: ReplyRule?, onDone: () -> Unit) {
    val db = Db.get(this)
    val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setPadding(dp(12), dp(8), dp(12), dp(8))
    fun e(h: String, v: String = "") = edit(h).apply { setText(v) }
    val name = e("Rule name", existing?.name ?: "")
    val apps = e("Apps CSV: whatsapp,whatsapp_business,instagram,telegram (empty=any)", existing?.apps ?: "")
    val senders = e("Allow senders/contacts CSV (empty=any)", existing?.senders ?: "")
    val blocked = e("Blocked senders/contacts CSV", existing?.blockedSenders ?: "")
    val vip = e("VIP senders CSV (extra careful, can bypass draft-only)", existing?.vipSenders ?: "")
    val mute = e("Mute senders CSV (never auto-reply)", existing?.muteSenders ?: "")
    val chatType = e("Chat type: any/direct/group", existing?.chatType ?: "any")
    val keywords = e("Keywords CSV (empty=any)", existing?.keywords ?: "")
    val quietStart = e("Quiet start HH:mm (optional)", existing?.quietStart ?: "")
    val quietEnd = e("Quiet end HH:mm (optional)", existing?.quietEnd ?: "")
    val instruction = e("Per-rule reply instruction (optional)", existing?.instruction ?: "")
    val replyData = e("Per-rule reply data (optional)", existing?.replyData ?: ""); replyData.minLines = 3
    root.addView(tv("Saara checks this data first when replying for this rule. Put facts, FAQs, prices, availability, or answers here.", 11f, SUB))
    val mode = e("Mode: ai/block", existing?.mode ?: "ai")
    val screenOff = Switch(this); screenOff.text = "Only when screen is off"; screenOff.isChecked = existing?.screenOffOnly == true
    val enabled = Switch(this); enabled.text = "Enabled"; enabled.isChecked = existing?.enabled ?: true
    listOf<View>(name, apps, senders, blocked, vip, mute, chatType, keywords, quietStart, quietEnd, instruction, replyData, mode, screenOff, enabled).forEach { root.addView(it) }
    AlertDialog.Builder(this).setTitle(if (existing == null) "Add Replying Rule" else "Edit Replying Rule").setView(ScrollView(this).apply { addView(root) })
      .setPositiveButton("Save") { _, _ ->
        val r = ReplyRule(existing?.id ?: 0, name.text.toString(), enabled.isChecked, apps.text.toString(), senders.text.toString(), blocked.text.toString(), chatType.text.toString(), keywords.text.toString(), quietStart.text.toString(), quietEnd.text.toString(), screenOff.isChecked, mode.text.toString(), instruction.text.toString(), System.currentTimeMillis(), vip.text.toString(), mute.text.toString(), replyData.text.toString())
        if (existing == null) db.replyRuleAdd(r) else db.replyRuleUpdate(r)
        onDone()
      }
      .setNegativeButton("Cancel", null).show()
  }

  private fun showAutoReplyLogs() {
    AiAutoReply.loadPersisted(this)
    SaaraNotifications.requestRebindIfPossible(this)
    val status = buildString {
      append("Notification access: ").append(if (notifOn()) "ON" else "OFF")
      append("\nListener connected: ").append(if (SaaraNotifications.isConnected()) "YES" else "NO")
      append("\nNotification auto-reply: ").append(if (Prefs.on(this@MainActivity, "f_notif", false)) "ON" else "OFF")
      append("\nAI smart auto-reply: ").append(if (Prefs.on(this@MainActivity, "f_ai_reply", false)) "ON" else "OFF")
      val key = Prefs.secure(this@MainActivity).getString("apiKey", "") ?: ""
      append("\nGemini API key: ").append(if (key.isBlank()) "MISSING" else "SET")
    }
    val logs = AiAutoReply.logs()
    val body = if (logs.isEmpty()) "No auto-reply activity yet.\n\nTip: enable Notification access, Notification auto-reply, and AI smart auto-reply; set Gemini API key in Settings." else logs.joinToString("\n\n————————————\n\n")
    val msg = "$status\n\n—— LOGS ——\n$body"
    val sv = ScrollView(this)
    val t = tv(msg, 13f, TXT); t.setPadding(dp(16), dp(12), dp(16), dp(12)); t.setTextIsSelectable(true)
    sv.addView(t)
    AlertDialog.Builder(this).setTitle("Auto-reply logs (latest first)").setView(sv)
      .setPositiveButton("Close", null)
      .setNeutralButton("Copy") { _, _ ->
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Saara auto-reply logs", msg))
        toast("Logs copied")
      }
      .setNegativeButton("Clear") { _, _ -> AiAutoReply.clearLogs(); toast("Logs cleared") }
      .show()
  }

  private fun rebuildTasks() {
    taskBox.removeAllViews()
    val db = Db.get(this)
    taskBox.addView(tv("Automation rules", 15f, bold = true))
    val ars = try { db.autoRules() } catch (_: Exception) { emptyList() }
    if (ars.isEmpty()) taskBox.addView(tv("No automation rules yet.", 13f, SUB))
    for (r in ars) {
      val row = LinearLayout(this); row.orientation = LinearLayout.VERTICAL; row.background = bg(SURF2, 12); row.setPadding(dp(10),dp(8),dp(10),dp(8))
      val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
      top.addView(tv(r.name.ifBlank { "Rule ${r.id}" }, 14f, TXT, true), LinearLayout.LayoutParams(0,-2,1f))
      val sw = Switch(this); sw.isChecked = r.enabled; sw.setOnCheckedChangeListener { _,v -> db.autoRuleSetEnabled(r.id,v); Automation.scheduleAll(this); rebuildTasks() }
      top.addView(sw); row.addView(top)
      row.addView(tv("trigger=${r.trigger} · condition=${r.condition.ifBlank{"any"}} · cooldown=${r.cooldownMs/60000}m", 11f, SUB))
      row.addView(tv("action: ${r.action}", 12f, TXT))
      val acts = LinearLayout(this)
      acts.addView(btn("Edit", SURF) { showAutoRuleEditor(r) { rebuildTasks() } }, LinearLayout.LayoutParams(0,-2,1f))
      acts.addView(btn("Delete", SURF) { db.autoRuleDelete(r.id); Automation.cancel(this,r.id); rebuildTasks() }, LinearLayout.LayoutParams(0,-2,1f))
      row.addView(acts)
      taskBox.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) })
    }
    gap(taskBox, 10)
    taskBox.addView(tv("Scheduled tasks", 15f, bold = true))
    val ts = db.tasks()
    if (ts.isEmpty()) { taskBox.addView(tv("No scheduled tasks yet.", 13f, SUB)); return }
    for (t in ts) {
      val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
      val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
      col.addView(tv(t.text, 14f)); col.addView(tv(hhmm(t.whenMs) + if (t.repeatMs > 0) "  •  repeats" else "", 12f, SUB))
      r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
      val x = tv("✕", 18f, BAD); x.setPadding(dp(12), dp(6), dp(4), dp(6)); x.setOnClickListener { Scheduler.cancel(this, t.id); rebuildTasks() }
      r.addView(x); taskBox.addView(r); gap(taskBox, 6)
    }
  }

  // ---------- LEARNING ----------
  private fun buildLearning(): View {
    val (sv, root) = page()
    heading(root, "Memory & Knowledge", "Search, clean duplicates, manage rules/lessons, and record/replay behaviors.")
    val search = edit("Search memory, rules, lessons, behaviors...")
    search.setSingleLine(true); search.setText(learnSearchQuery); search.setSelection(search.text?.length ?: 0)
    search.doAfterTextChanged { val q = it?.toString() ?: ""; if (q != learnSearchQuery) { learnSearchQuery = q; rebuildLearning() } }
    root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
    learnFilterChips.clear()
    val filters = listOf("All", "Knowledge/Memory", "Rules", "Lessons", "Behaviors")
    for (f in filters) {
      val c = chip(f) { if (learnFilter != f) { learnFilter = f; paintLearnFilterChips(); rebuildLearning() } }
      learnFilterChips[f] = c
      val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); lp.topMargin = dp(8); chipsRow.addView(c, lp)
    }
    val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; hs.addView(chipsRow); root.addView(hs)
    paintLearnFilterChips()
    learnBox = LinearLayout(this); learnBox.orientation = LinearLayout.VERTICAL; root.addView(learnBox)
    rebuildLearning()
    return sv
  }

  private fun hour12(h: Int) = (if (h % 12 == 0) 12 else h % 12).toString() + (if (h < 12) " AM" else " PM")

  private fun learnMatches(text: String): Boolean {
    val q = learnSearchQuery.trim().lowercase(Locale.getDefault())
    return q.isBlank() || text.lowercase(Locale.getDefault()).contains(q)
  }

  private fun paintLearnFilterChips() {
    for ((f, v) in learnFilterChips) {
      val sel = f == learnFilter
      v.background = bg(if (sel) ACC2 else SURF2, 18)
      v.setTextColor(if (sel) Color.WHITE else TXT)
    }
  }

  private fun rebuildLearning() {
    if (!::learnBox.isInitialized) return
    learnBox.removeAllViews()
    val db = Db.get(this)
    val factsAll = try { db.facts() } catch (_: Exception) { emptyList() }
    val rulesAll = try { db.rules() } catch (_: Exception) { emptyList() }
    val behaviorsAll = try { db.behaviors() } catch (_: Exception) { emptyList() }
    val lessons = factsAll.filter { it.cat.equals("mistakes_and_lessons", true) }
    val knowledge = factsAll.filter { !it.cat.equals("mistakes_and_lessons", true) }
    val dupFacts = try { db.findDuplicateFacts().sumOf { (it.size - 1).coerceAtLeast(0) } } catch (_: Exception) { 0 }
    val dupRules = try { db.findDuplicateRules().sumOf { (it.size - 1).coerceAtLeast(0) } } catch (_: Exception) { 0 }
    val dupCount = dupFacts + dupRules

    val sum = card(learnBox, padH = 12, padV = 10, topMargin = 8, radius = 14)
    sum.addView(tv("Summary", 14f, bold = true))
    sum.addView(tv("Knowledge ${knowledge.size} · Rules ${rulesAll.size} · Lessons ${lessons.size} · Behaviors ${behaviorsAll.size}", 12f, TXT))
    sum.addView(tv("Duplicates: $dupCount${if (dupCount > 0) " (facts $dupFacts, rules $dupRules)" else ""}", 11f, if (dupCount > 0) WARN else GOOD))
    val sumRow = LinearLayout(this); sumRow.orientation = LinearLayout.HORIZONTAL
    sumRow.addView(smallBtn("Clean Duplicates", if (dupCount > 0) ACC2 else SURF) {
      val fDup = db.findDuplicateFacts(); val rDup = db.findDuplicateRules()
      val total = fDup.sumOf { it.size - 1 } + rDup.sumOf { it.size - 1 }
      if (total == 0) { toast("No duplicates found"); return@smallBtn }
      val preview = buildString {
        append("Remove/merge $total duplicate entries?\n\n")
        fDup.take(3).forEach { g -> append("• Fact: ").append(g.first().text.take(70)).append(" (+${g.size - 1})\n") }
        rDup.take(3).forEach { g -> append("• Rule: ").append(g.first().text.take(70)).append(" (+${g.size - 1})\n") }
      }
      AlertDialog.Builder(this).setTitle("Clean Duplicates").setMessage(preview)
        .setPositiveButton("Clean") { _, _ -> val rf = db.cleanDuplicateFacts(); val rr = db.cleanDuplicateRules(); toast("Cleaned ${rf + rr} duplicates"); rebuildLearning() }
        .setNegativeButton("Cancel", null).show()
    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
    sumRow.addView(smallBtn("Batch Delete", SURF) {
      val fc = learnSelectedFactIds.size; val rc = learnSelectedRuleIds.size; val bc = learnSelectedBehaviorIds.size
      val total = fc + rc + bc
      if (total == 0) { toast("Select items to delete"); return@smallBtn }
      AlertDialog.Builder(this).setMessage("Delete $total selected item(s)?").setPositiveButton("Delete") { _, _ ->
        learnSelectedFactIds.forEach { db.factDelete(it) }; learnSelectedRuleIds.forEach { db.ruleDelete(it) }; learnSelectedBehaviorIds.forEach { db.behaviorDelete(it) }
        learnSelectedFactIds.clear(); learnSelectedRuleIds.clear(); learnSelectedBehaviorIds.clear(); rebuildLearning()
      }.setNegativeButton("Cancel", null).show()
    }, LinearLayout.LayoutParams(-2, -2))
    sum.addView(sumRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    fun sectionCard(key: String, title: String, subtitle: String, count: Int, body: (LinearLayout) -> Unit) {
      val c = card(learnBox, padH = 12, padV = 10, topMargin = 8, radius = 14)
      val header = LinearLayout(this); header.gravity = Gravity.CENTER_VERTICAL
      header.addView(tv("$title ($count)", 15f, TXT, true), LinearLayout.LayoutParams(0, -2, 1f))
      val toggle = tv(if (learnCollapsed.contains(key)) "Show" else "Hide", 11f, ACC, true); toggle.setPadding(dp(8), dp(2), dp(0), dp(2))
      header.addView(toggle); c.addView(header)
      val subTv = tv(subtitle, 11f, SUB); subTv.setPadding(0, 0, 0, dp(4)); c.addView(subTv)
      val bodyBox = LinearLayout(this); bodyBox.orientation = LinearLayout.VERTICAL
      if (!learnCollapsed.contains(key)) body(bodyBox) else bodyBox.addView(tv("Collapsed", 11f, SUB))
      c.addView(bodyBox)
      toggle.setOnClickListener { if (learnCollapsed.contains(key)) learnCollapsed.remove(key) else learnCollapsed.add(key); rebuildLearning() }
    }

    val showKnowledge = learnFilter == "All" || learnFilter == "Knowledge/Memory"
    val showRules = learnFilter == "All" || learnFilter == "Rules"
    val showLessons = learnFilter == "All" || learnFilter == "Lessons"
    val showBehaviors = learnFilter == "All" || learnFilter == "Behaviors"

    if (showKnowledge) {
      val list = knowledge.filter { learnMatches(it.text + " " + it.cat) }
      sectionCard("knowledge", "Knowledge / Memory", "Facts and preferences. Select items for batch delete.", list.size) { box ->
        val addRow = LinearLayout(this); addRow.orientation = LinearLayout.HORIZONTAL; addRow.gravity = Gravity.CENTER_VERTICAL
        val ti = edit("Teach Saara (e.g. I prefer Bengali after 9 PM)"); ti.textSize = 13f
        addRow.addView(ti, LinearLayout.LayoutParams(0, -2, 1f))
        val add = smallBtn("Add", ACC2) { val t = ti.text.toString().trim(); if (t.isNotEmpty()) { db.factAdd("preference", t); rebuildLearning() } }
        addRow.addView(add, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        box.addView(addRow)
        shopBox = LinearLayout(this); shopBox.orientation = LinearLayout.VERTICAL
        box.addView(shopBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        rebuildShop()
        for (f in list.take(60)) {
          val row = LinearLayout(this); row.orientation = LinearLayout.VERTICAL; row.setPadding(dp(8), dp(6), dp(8), dp(6)); row.background = bg(SURF2, 10)
          val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
          val cb = CheckBox(this); cb.isChecked = learnSelectedFactIds.contains(f.id); cb.scaleX = 0.9f; cb.scaleY = 0.9f; cb.setOnCheckedChangeListener { _, v -> if (v) learnSelectedFactIds.add(f.id) else learnSelectedFactIds.remove(f.id) }
          top.addView(cb)
          top.addView(tv((if (f.pinned) "📌 " else "") + f.text.take(140), 13f, TXT), LinearLayout.LayoutParams(0, -2, 1f))
          row.addView(top)
          val meta = tv("${f.cat} · ${hhmm(f.ts)}${if (f.source.isNotBlank()) " · ${f.source}" else ""}", 10f, SUB)
          meta.setPadding(dp(36), 0, 0, 0); row.addView(meta)
          val acts = LinearLayout(this); acts.orientation = LinearLayout.HORIZONTAL; acts.setPadding(dp(36), dp(4), 0, 0)
          val aLp = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
          acts.addView(smallBtn("Edit", SURF) { val input = edit("Edit memory"); input.setText(f.text); AlertDialog.Builder(this).setTitle("Edit memory").setView(input).setPositiveButton("Save") { _, _ -> db.factUpdate(f.id, f.cat, input.text.toString()); rebuildLearning() }.setNegativeButton("Cancel", null).show() }, aLp)
          acts.addView(smallBtn(if (f.pinned) "Unpin" else "Pin", SURF) { db.factSetPinned(f.id, !f.pinned); rebuildLearning() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
          acts.addView(smallBtn("Delete", SURF) { db.factDelete(f.id); rebuildLearning() }, LinearLayout.LayoutParams(-2, -2))
          row.addView(acts)
          box.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        }
        if (list.size > 60) box.addView(tv("Showing 60 of ${list.size}. Use search to narrow.", 11f, SUB))
      }
    }

    if (showRules) {
      val list = rulesAll.filter { learnMatches(it.text) }
      sectionCard("rules", "Rules", "Saara follows enabled rules before actions/tools/replies.", list.size) { box ->
        val addRow = LinearLayout(this); addRow.orientation = LinearLayout.HORIZONTAL; addRow.gravity = Gravity.CENTER_VERTICAL
        val ri = edit("Add a rule, e.g. Ask before sending SMS"); ri.textSize = 13f
        addRow.addView(ri, LinearLayout.LayoutParams(0, -2, 1f))
        addRow.addView(smallBtn("Add", ACC2) { val t = ri.text.toString().trim(); if (t.isNotEmpty()) { db.ruleAdd(t); rebuildLearning() } }, LinearLayout.LayoutParams(-2,-2).apply { leftMargin = dp(6) })
        box.addView(addRow)
        for (r in list.take(60)) {
          val row = LinearLayout(this); row.orientation = LinearLayout.VERTICAL; row.setPadding(dp(8), dp(6), dp(8), dp(6)); row.background = bg(SURF2, 10)
          val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
          val cb = CheckBox(this); cb.isChecked = learnSelectedRuleIds.contains(r.id); cb.scaleX = 0.9f; cb.scaleY = 0.9f; cb.setOnCheckedChangeListener { _, v -> if (v) learnSelectedRuleIds.add(r.id) else learnSelectedRuleIds.remove(r.id) }
          top.addView(cb)
          top.addView(tv(r.text.take(160), 13f, TXT), LinearLayout.LayoutParams(0,-2,1f))
          val sw = Switch(this); sw.isChecked = r.enabled; sw.scaleX = 0.85f; sw.scaleY = 0.85f; sw.setOnCheckedChangeListener { _, v -> db.ruleSetEnabled(r.id, v) }
          top.addView(sw); row.addView(top)
          val acts = LinearLayout(this); acts.orientation = LinearLayout.HORIZONTAL; acts.setPadding(dp(36), dp(4), 0, 0)
          acts.addView(smallBtn("Edit", SURF) { val input = edit("Rule"); input.setText(r.text); AlertDialog.Builder(this).setTitle("Edit rule").setView(input).setPositiveButton("Save") { _, _ -> db.ruleUpdate(r.id, input.text.toString()); rebuildLearning() }.setNegativeButton("Cancel", null).show() }, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(6) })
          acts.addView(smallBtn("Delete", SURF) { db.ruleDelete(r.id); rebuildLearning() }, LinearLayout.LayoutParams(-2,-2))
          row.addView(acts)
          box.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        }
      }
    }

    if (showLessons) {
      val list = lessons.filter { learnMatches(it.text) }
      sectionCard("lessons", "Lessons / Guardrails", "Mistakes and lessons Saara should avoid repeating.", list.size) { box ->
        for (f in list.take(60)) {
          val row = LinearLayout(this); row.orientation = LinearLayout.VERTICAL; row.setPadding(dp(8), dp(6), dp(8), dp(6)); row.background = bg(SURF2, 10)
          val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
          val cb = CheckBox(this); cb.isChecked = learnSelectedFactIds.contains(f.id); cb.scaleX = 0.9f; cb.scaleY = 0.9f; cb.setOnCheckedChangeListener { _, v -> if (v) learnSelectedFactIds.add(f.id) else learnSelectedFactIds.remove(f.id) }
          top.addView(cb); top.addView(tv(f.text.take(160), 13f, TXT), LinearLayout.LayoutParams(0,-2,1f)); row.addView(top)
          val meta = tv(hhmm(f.ts), 10f, SUB); meta.setPadding(dp(36), 0, 0, 0); row.addView(meta)
          val acts = LinearLayout(this); acts.orientation = LinearLayout.HORIZONTAL; acts.setPadding(dp(36), dp(4), 0, 0)
          acts.addView(smallBtn("Edit", SURF) { val input = edit("Lesson"); input.setText(f.text); AlertDialog.Builder(this).setTitle("Edit lesson").setView(input).setPositiveButton("Save") { _, _ -> db.factUpdate(f.id, "mistakes_and_lessons", input.text.toString()); rebuildLearning() }.setNegativeButton("Cancel", null).show() }, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(6) })
          acts.addView(smallBtn("Delete", SURF) { db.factDelete(f.id); rebuildLearning() }, LinearLayout.LayoutParams(-2,-2))
          row.addView(acts)
          box.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        }
        if (list.isEmpty()) box.addView(tv("No lessons yet.", 11f, SUB))
      }
    }

    if (showBehaviors) {
      val list = behaviorsAll.filter { learnMatches(it.name + " " + it.stepsJson) }
      sectionCard("behaviors", "Behaviors / Recorded Steps", "Record, edit, replay, or delete user workflows.", list.size) { box ->
        val recBtn = smallBtn(if (BehaviorRecorder.isRecording()) "Stop Recording" else "Record User Steps", if (BehaviorRecorder.isRecording()) 0xFFB3261E.toInt() else ACC2) {
          if (BehaviorRecorder.isRecording()) { BehaviorRecorder.stopAndSave(this) { rebuildLearning() } } else { BehaviorRecorder.start(this); rebuildLearning() }
        }
        box.addView(recBtn, LinearLayout.LayoutParams(-2,-2))
        box.addView(tv(if (BehaviorRecorder.isRecording()) "Recording... perform actions, then tap Stop." else "Tap Record, perform steps, then Stop to save.", 10f, SUB))
        for (b in list.take(40)) {
          val row = LinearLayout(this); row.orientation = LinearLayout.VERTICAL; row.setPadding(dp(8), dp(6), dp(8), dp(6)); row.background = bg(SURF2, 10)
          val top = LinearLayout(this); top.gravity = Gravity.CENTER_VERTICAL
          val cb = CheckBox(this); cb.isChecked = learnSelectedBehaviorIds.contains(b.id); cb.scaleX = 0.9f; cb.scaleY = 0.9f; cb.setOnCheckedChangeListener { _, v -> if (v) learnSelectedBehaviorIds.add(b.id) else learnSelectedBehaviorIds.remove(b.id) }
          top.addView(cb); top.addView(tv(b.name, 13f, TXT, true), LinearLayout.LayoutParams(0,-2,1f)); row.addView(top)
          val steps = BehaviorRecorder.parseSteps(b.stepsJson)
          val meta = tv("${steps.size} steps · ${hhmm(b.updatedTs)}", 10f, SUB); meta.setPadding(dp(36), 0, 0, 0); row.addView(meta)
          val acts = LinearLayout(this); acts.orientation = LinearLayout.HORIZONTAL; acts.setPadding(dp(36), dp(4), 0, 0)
          acts.addView(smallBtn("Run / Replay", ACC2) { BehaviorRecorder.replay(this, b) }, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(6) })
          acts.addView(smallBtn("Edit", SURF) { BehaviorRecorder.showEditDialog(this, b) { rebuildLearning() } }, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(6) })
          acts.addView(smallBtn("Delete", SURF) { db.behaviorDelete(b.id); rebuildLearning() }, LinearLayout.LayoutParams(-2,-2))
          row.addView(acts)
          box.addView(row, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        }
        if (list.isEmpty()) box.addView(tv("No recorded behaviors yet.", 11f, SUB))
      }
    }

    if (showKnowledge) {
      val conflicts = try { db.factConflicts() } catch (_: Exception) { emptyList() }
      if (conflicts.isNotEmpty()) {
        sectionCard("conflicts", "Conflicts", "Keep the correct fact and remove stale duplicates.", conflicts.size) { box ->
          for (grp in conflicts.take(4)) {
            grp.forEachIndexed { idx, f ->
              val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, dp(4), 0, dp(4))
              row.addView(tv((if (idx==0) "NEW: " else "OLD: ") + "[${f.cat}] ${f.text.take(100)}", 11f, if (idx==0) GOOD else TXT), LinearLayout.LayoutParams(0, -2, 1f))
              row.addView(smallBtn(if (idx==0) "Keep" else "Keep this", SURF2) { db.factResolveKeep(f.id); rebuildLearning() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
              box.addView(row)
            }
          }
        }
      }
      val steps = try { db.taskSteps(15) } catch (_: Exception) { emptyList() }
      if (steps.isNotEmpty() && learnFilter == "All") {
        sectionCard("taskmethods", "Task methods", "Recent on-device task steps that worked/failed.", steps.size) { box ->
          for (s in steps.take(8)) box.addView(tv((if (s.ok) "✓ " else "✗ ") + s.goal.take(80) + " · " + s.tool, 11f, if (s.ok) GOOD else WARN))
          box.addView(smallBtn("Clear task learning", SURF2) { db.taskStepsClear(); rebuildLearning() }, LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(6) })
        }
      }
    }
  }

  // ---------- URL IMPORT ----------
  private fun extractHttpUrl(text: String): String? = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']')

  private fun fetchAndSaveUrl(url: String) {
    toast("Fetching link locally. Content will stay on this device…")
    knowledgeExecutor.execute {
      val result = Tools.fetchUrlText(url)
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (result.startsWith("error:")) {
          Bus.add("system", result, true); toast(result.take(220))
        } else {
          Db.get(this).factAdd("web_source", result.take(12000))
          Db.get(this).factAdd("web_source", "Imported URL: $url")
          Bus.add("system", "Link fetched and saved locally (${result.length} characters). No page content was sent to Gemini.", true)
          toast("Fetched and saved on this device")
          rebuildLearning()
        }
      }
    }
  }

  // ---------- SHOP ----------
  private fun buildShop(): View {
    val (sv, root) = page()
    heading(root, "Knowledge & Google Sheets", "Saved locally on this phone. Public webpages and Sheets links can be imported; private Sheets need Google sign-in.")
    shopBox = LinearLayout(this); shopBox.orientation = LinearLayout.VERTICAL
    root.addView(shopBox)
    return sv
  }

  private fun rebuildShop() {
    if (!::shopBox.isInitialized) return
    shopBox.removeAllViews()
    val sheets = LinearLayout(this); sheets.orientation = LinearLayout.VERTICAL; sheets.background = bg(SURF2, 10)
    sheets.setPadding(dp(10), dp(8), dp(10), dp(8))
    sheets.addView(tv("Google Sheets / Web import", 13f, TXT, true))
    sheets.addView(tv("Import public links; private Sheets need OAuth.", 10f, SUB))
    val url = edit("Website or Google Sheets URL"); url.textSize = 12f
    url.setText(Prefs.plain(this).getString("knowledge_sheet_url", ""))
    sheets.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
    row.addView(smallBtn("Save link", SURF) {
      val value = url.text.toString().trim()
      if (!value.startsWith("https://") && !value.startsWith("http://")) toast("Enter a valid http:// or https:// URL")
      else { Prefs.plain(this).edit().putString("knowledge_sheet_url", value).apply(); Db.get(this).factAdd("web_source", "Saved source URL: $value"); toast("Link saved locally. Fetching its data now…"); rebuildLearning(); fetchAndSaveUrl(value) }
    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
    row.addView(smallBtn("Fetch & save", ACC2) {
      val value = url.text.toString().trim()
      if (!value.startsWith("https://") && !value.startsWith("http://")) { toast("Enter a valid http:// or https:// URL"); return@smallBtn }
      Prefs.plain(this).edit().putString("knowledge_sheet_url", value).apply()
      fetchAndSaveUrl(value)
    }, LinearLayout.LayoutParams(-2, -2))
    sheets.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    shopBox.addView(sheets)
  }


  // ---------- CONTACTS ----------
  private fun buildContacts(): View {
    val (sv, root) = page()
    heading(root, "Contacts", "People saved on this phone, including Google contacts synced to Android.")
    val permission = card(root)
    permission.addView(tv("Contact access", 16f, bold = true))
    permission.addView(tv("Saara reads contacts only after you grant Android permission. Gmail message history and email-based enrichment require a separate Google OAuth connection and are not enabled in this build.", 12f, SUB))
    permission.addView(btn(if (has(Manifest.permission.READ_CONTACTS)) "Refresh contacts" else "Grant contacts access") {
      if (has(Manifest.permission.READ_CONTACTS)) rebuildContacts() else requestContactsAccess()
    })
    contactsSearch = edit("Search name, phone or email")
    contactsSearch.background = bg(SURF2, 18); contactsSearch.setPadding(dp(12), dp(8), dp(12), dp(8))
    root.addView(contactsSearch, LinearLayout.LayoutParams(-1, -2))
    contactsSearch.doAfterTextChanged { scheduleContactsRefresh() }
    contactsBox = LinearLayout(this); contactsBox.orientation = LinearLayout.VERTICAL
    root.addView(contactsBox)
    return sv
  }

  private data class ContactEntry(val name: String, val phone: String, val email: String)
  private data class MutableContact(var name: String = "Unknown", var phone: String = "", var email: String = "")

  private fun requestContactsAccess() {
    if (has(Manifest.permission.READ_CONTACTS)) { rebuildContacts(); return }
    val prefs = getSharedPreferences("saara_permissions", MODE_PRIVATE)
    val askedBefore = prefs.getBoolean("contacts_asked", false)
    if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
      AlertDialog.Builder(this).setTitle("Contacts permission is disabled")
        .setMessage("Allow Contacts permission in Android App Settings so Saara can display your phone contacts.")
        .setPositiveButton("Open settings") { _, _ ->
          startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("Cancel", null).show()
      return
    }
    prefs.edit().putBoolean("contacts_asked", true).apply()
    toast("Requesting Contacts permission…")
    try { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 4) }
    catch (e: Exception) { toast("Could not open permission prompt. Open App info → Permissions → Contacts and allow it."); startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
  }

  private fun rebuildContacts() {
    if (!::contactsBox.isInitialized) return
    contactsBox.removeAllViews()
    if (!has(Manifest.permission.READ_CONTACTS)) {
      contactsBox.addView(tv("Contacts permission is not granted. Tap 'Grant contacts access' above.", 13f, WARN)); return
    }
    contactsBox.addView(tv("Loading contacts…", 13f, SUB))
    scheduleContactsRefresh()
  }

  private fun scheduleContactsRefresh() {
    if (!::contactsBox.isInitialized) return
    if (!has(Manifest.permission.READ_CONTACTS)) { rebuildContacts(); return }
    contactsHandler.removeCallbacks(contactsDebounce)
    contactsGeneration.incrementAndGet()
    contactsHandler.postDelayed(contactsDebounce, 250L)
  }

  private fun loadContactsInBackground() {
    if (!::contactsBox.isInitialized) return
    if (!has(Manifest.permission.READ_CONTACTS)) {
      contactsHandler.post { if (::contactsBox.isInitialized) { contactsBox.removeAllViews(); contactsBox.addView(tv("Contacts permission is not granted. Tap 'Grant contacts access' above.", 13f, WARN)) } }
      return
    }
    val generation = contactsGeneration.get()
    val query = if (::contactsSearch.isInitialized) contactsSearch.text.toString().trim().lowercase(Locale.ROOT) else ""
    contactsExecutor.execute {
      val grouped = LinkedHashMap<String, MutableContact>()
      var error: String? = null
      try {
        contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
          arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null,
          ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { c ->
          val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
          val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
          while (c.moveToNext()) {
            val id = c.getString(idCol) ?: continue
            grouped[id] = MutableContact(c.getString(nameCol) ?: "Unknown", "", "")
          }
        }
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
          val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
          val valCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
          while (c.moveToNext()) { val id = c.getString(idCol) ?: continue; val item = grouped[id] ?: continue; if (item.phone.isBlank()) item.phone = c.getString(valCol) ?: "" }
        }
        contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
          arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { c ->
          val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
          val valCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
          while (c.moveToNext()) { val id = c.getString(idCol) ?: continue; val item = grouped[id] ?: continue; if (item.email.isBlank()) item.email = c.getString(valCol) ?: "" }
        }
      } catch (e: Exception) { error = e.message ?: "permission unavailable" }
      val entries = grouped.values.map { ContactEntry(it.name, it.phone, it.email) }.filter {
        query.isBlank() || it.name.lowercase(Locale.ROOT).contains(query) || it.phone.lowercase(Locale.ROOT).contains(query) || it.email.lowercase(Locale.ROOT).contains(query)
      }
      contactsHandler.post {
        if (isFinishing || isDestroyed || generation != contactsGeneration.get() || !::contactsBox.isInitialized) return@post
        contactsBox.removeAllViews()
        if (error != null) { contactsBox.addView(tv("Could not read contacts: $error. Open Android App Settings and allow Contacts.", 13f, BAD)); return@post }
        if (entries.isEmpty()) { contactsBox.addView(tv("No matching contacts found. Check whether contacts are synced to this phone.", 13f, SUB)); return@post }
        contactsBox.addView(tv("${entries.size} contact(s) · showing up to 100 · stored/read locally", 12f, SUB))
        entries.take(100).forEach { person ->
          val c = card(contactsBox)
          c.addView(tv(person.name, 16f, bold = true))
          if (person.phone.isNotBlank()) c.addView(tv("☎  ${person.phone}", 13f, SUB))
          if (person.email.isNotBlank()) c.addView(tv("✉  ${person.email}", 13f, SUB))
          val actions = LinearLayout(this); actions.gravity = Gravity.CENTER_VERTICAL
          if (person.phone.isNotBlank()) {
            actions.addView(btn("Call", SURF2) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(person.phone)))) }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(btn("Message", SURF2) { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(person.phone)))) }, LinearLayout.LayoutParams(0, -2, 1f))
          }
          if (person.email.isNotBlank()) actions.addView(btn("Email", SURF2) { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(person.email)))) }, LinearLayout.LayoutParams(0, -2, 1f))
          c.addView(actions)
        }
      }
    }
  }

  // ---------- SETTINGS ----------
  private val VOICES = listOf("Aoede", "Kore", "Leda", "Zephyr", "Callirrhoe", "Autonoe", "Despina", "Puck", "Charon", "Fenrir", "Orus")
  private val MODELS = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-live-2.5-flash-preview")

  private fun addSettingsUpdater(u: () -> Unit) { settingsUpdaters.add(u); updaters.add(u) }

  private fun settingsCatAllowed(cat: String): Boolean = settingsCategory == "All" || settingsCategory == cat

  private fun settingsQueryMatch(vararg parts: String?): Boolean {
    val q = settingsSearchQuery.trim().lowercase(Locale.getDefault())
    if (q.isBlank()) return true
    return parts.any { it?.lowercase(Locale.getDefault())?.contains(q) == true }
  }

  private fun rebuildSettings(force: Boolean = false, keepScroll: Boolean = true) {
    val now = SystemClock.elapsedRealtime()
    if (!force && now - lastSettingsRebuild < 1200L) return
    val content = settingsContent ?: return
    lastSettingsRebuild = now
    val y = settingsScroll?.scrollY ?: 0
    updaters.removeAll { settingsUpdaters.contains(it) }
    settingsUpdaters.clear()
    content.removeAllViews()
    addFeatures(content)
    if (settingsSearchQuery.isNotBlank() || settingsCategory != "All") {
      var hasVisible = false
      for (i in 0 until content.childCount) if (content.getChildAt(i).visibility == View.VISIBLE) { hasVisible = true; break }
      if (!hasVisible) {
        val c = card(content)
        c.addView(tv("No settings found", 16f, bold = true))
        c.addView(tv("Try a different search or category filter.", 13f, SUB))
      }
    }
    settingsScroll?.post { settingsScroll?.scrollTo(0, if (keepScroll) y else 0) }
  }

  private fun buildSettings(): View {
    val (sv, root) = page()
    settingsScroll = sv
    settingsRoot = root
    heading(root, "Settings")
    root.addView(tv("Search and filter all settings instantly.", 13f, SUB))

    val searchCard = card(root)
    val search = edit("Search settings (wake, model, reply, clipboard, memory...)")
    search.setSingleLine(true)
    search.imeOptions = EditorInfo.IME_ACTION_DONE
    search.setText(settingsSearchQuery)
    search.setSelection(search.text?.length ?: 0)
    search.doAfterTextChanged {
      val q = it?.toString() ?: ""
      if (q != settingsSearchQuery) {
        settingsSearchQuery = q
        rebuildSettings(force = true, keepScroll = false)
      }
    }
    settingsSearchInput = search
    searchCard.addView(search)

    val chipsRow = LinearLayout(this); chipsRow.orientation = LinearLayout.HORIZONTAL
    settingsCategoryChips.clear()
    for (cat in settingsCategoryOrder) {
      val ch = chip(cat) {
        if (settingsCategory != cat) {
          settingsCategory = cat
          paintSettingsCategoryChips()
          rebuildSettings(force = true, keepScroll = false)
        }
      }
      val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); lp.bottomMargin = dp(4)
      chipsRow.addView(ch, lp)
      settingsCategoryChips[cat] = ch
    }
    val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
    hs.addView(chipsRow)
    val hslp = LinearLayout.LayoutParams(-1, -2); hslp.topMargin = dp(10)
    searchCard.addView(hs, hslp)
    paintSettingsCategoryChips()

    settingsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(settingsContent, LinearLayout.LayoutParams(-1, -2))
    rebuildSettings(force = true)
    return sv
  }

  private fun paintSettingsCategoryChips() {
    for ((cat, v) in settingsCategoryChips) {
      val sel = cat == settingsCategory
      v.background = bg(if (sel) ACC2 else SURF2, 18)
      v.setTextColor(if (sel) Color.WHITE else TXT)
    }
  }

  private fun filteredSection(parent: LinearLayout, cat: String, vararg keywords: String, build: (LinearLayout) -> Unit) {
    if (!settingsCatAllowed(cat)) return
    if (!settingsQueryMatch(*keywords)) return
    val tmp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    build(tmp)
    while (tmp.childCount > 0) {
      val v = tmp.getChildAt(0); tmp.removeViewAt(0); parent.addView(v)
    }
  }



  private fun fetchModels(key: String, done: (List<String>) -> Unit) {
    Thread {
      val out = ArrayList<String>()
      try {
        val r = OkHttpClient().newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$key").build()).execute()
        val body = r.body?.string() ?: ""
        val arr = JSONObject(body).optJSONArray("models")
        if (arr != null) for (i in 0 until arr.length()) {
          val m = arr.getJSONObject(i); val methods = m.optJSONArray("supportedGenerationMethods")
          var live = false
          if (methods != null) for (k in 0 until methods.length()) if (methods.getString(k) == "bidiGenerateContent") live = true
          if (live) out.add(m.optString("name").removePrefix("models/"))
        }
      } catch (_: Exception) {}
      runOnUiThread { done(out) }
    }.start()
  }

  // ---------- start / stop / permissions ----------
  private fun toggle() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastToggle < 900L) return
    lastToggle = now
    startBtn.isEnabled = false
    startBtn.postDelayed({ startBtn.isEnabled = true }, 900L)
    val vs = VoiceService.instance
    if (Bus.assistantOn) {
      if (vs != null) vs.setAssistantEnabled(false) else startService(svc("STOP"))
      return
    }
    if (Bus.running && vs != null) {
      vs.setAssistantEnabled(true)
      return
    }
    pendingAssistantWake = true
    if (!has(Manifest.permission.RECORD_AUDIO)) {
      pendingStart = true
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
      return
    }
    startSaara()
  }

  private fun startSaara() {
    if ((Prefs.secure(this).getString("apiKey", "") ?: "").isBlank()) { toast("Paste your Gemini API key in Settings and tap Save"); select(3); return }
    val i = Intent(this, VoiceService::class.java)
    val cmd = pendingAssistantText
    if (cmd != null) i.putExtra(VoiceService.ASSISTANT_COMMAND, cmd)
    if (pendingAssistantWake) i.putExtra(VoiceService.ASSISTANT_WAKE, true)
    pendingAssistantText = null
    pendingAssistantWake = false
    startForegroundService(i)
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (pendingStart && has(Manifest.permission.RECORD_AUDIO)) { pendingStart = false; startSaara() }
    if (pendingCam && has(Manifest.permission.CAMERA)) { pendingCam = false; if (Bus.running) startService(svc("CAM_START").putExtra("front", camFront)) }
    if (requestCode == 5) {
      if (has(Manifest.permission.READ_PHONE_STATE)) { toast("Incoming call announcements enabled"); VoiceService.instance?.refreshCalls() }
      else toast("Phone permission was not granted, so call announcements stay off")
    }
    if (requestCode == 4) {
      if (has(Manifest.permission.READ_CONTACTS)) { toast("Contacts access granted"); rebuildContacts() }
      else {
        toast("Contacts permission was not granted")
        if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
          contactsHandler.post { if (!isFinishing) AlertDialog.Builder(this).setMessage("Enable Contacts permission in Android App Settings to load your contacts.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            .setNegativeButton("Later", null).show() }
        }
      }
    }
    refresh()
  }

  // ---------- live refresh ----------
  private fun refresh() {
    if (curTab == 3) rebuildSettings()
    for (u in updaters) try { u() } catch (_: Exception) {}
    statusTv.text = Bus.status + (if (Bus.camOn) "  •  camera live" else "") + (if (Bus.screenOn) "  •  screen live" else "")
    startBtn.text = if (Bus.assistantOn) "Stop" else "Start"
    startBtn.background = bg(if (Bus.assistantOn) 0xFFB3261E.toInt() else ACC2, 20)
    val micOn = VoiceService.instance?.isMicOn() ?: false
    muteChip.text = if (micOn) "🎤 Mic: ON" else "🎤 Mic: OFF"
    muteChip.background = bg(if (micOn) ACC2 else SURF2, 18)
    camChip.text = if (Bus.camOn) "📷 Camera: ON" else "📷 Camera"
    camChip.background = bg(if (Bus.camOn) ACC2 else SURF2, 18)
    flipChip.visibility = if (Bus.camOn) View.VISIBLE else View.GONE
    screenChip.text = if (Bus.screenOn) (if (Bus.screenAuto) "🖥 Screen: AUTO" else "🖥 Screen: ON") else "🖥 Screen"
    screenChip.background = bg(if (Bus.screenOn) ACC2 else SURF2, 18)

    val snap = synchronized(Bus) { ArrayList(Bus.msgs) }
    if (snap.isEmpty() && chatList.childCount > 0) { chatList.removeAllViews(); shown.clear(); lastId = 0; firstId = Long.MAX_VALUE; noMore = false }
    val nearBottom = chatScroll.scrollY + chatScroll.height >= chatList.height - dp(140)
    var changed = false
    for (m in snap) {
      if (Bus.isChatNoise(m.role, m.text)) continue
      val t = shown[m.id]
      if (t != null) { val s = fmt(m); if (t.text.toString() != s) { t.text = s; changed = true } }
      else if (m.id > lastId) {
        val prev = snap.lastOrNull { it.id < m.id && !Bus.isChatNoise(it.role, it.text) }
        if (prev == null || !sameDay(prev.ts, m.ts)) chatList.addView(dateLabel(m.ts), rowLp())
        val (row, tvw) = msgRow(m); chatList.addView(row, rowLp()); shown[m.id] = tvw
        lastId = m.id; if (m.id < firstId) firstId = m.id; changed = true
      }
    }
    emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    if (changed && nearBottom) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
  }

  override fun onResume() {
    super.onResume(); visible = true; Bus.listen(busListener); refresh()
    SaaraNotifications.requestRebindIfPossible(this)
    VoiceService.instance?.refreshBubble()
    if (curTab == 3) rebuildSettings(force = true)
    Bus.ping()
  }
  override fun onPause() { visible = false; Bus.unlisten(busListener); super.onPause() }
  override fun onDestroy() { contactsHandler.removeCallbacks(contactsDebounce); contactsExecutor.shutdownNow(); knowledgeExecutor.shutdownNow(); super.onDestroy() }
}
