package com.saara.assistant
import android.Manifest
import android.content.Intent
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
  companion object { @Volatile var visible = false }

  private val BG = 0xFF131314.toInt(); private val SURF = 0xFF1E1F20.toInt(); private val SURF2 = 0xFF2A2C2E.toInt()
  private val TXT = 0xFFE3E3E3.toInt(); private val SUB = 0xFF9AA0A6.toInt(); private val ACC = 0xFF4FA3F7.toInt(); private val ACC2 = 0xFF1A73E8.toInt()
  private val GOOD = 0xFF81C995.toInt(); private val WARN = 0xFFFDD663.toInt(); private val BAD = 0xFFF28B82.toInt()

  private val updaters = ArrayList<() -> Unit>()
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
  private lateinit var taskBox: LinearLayout
  private lateinit var contactsBox: LinearLayout
  private lateinit var contactsSearch: EditText
  private val shown = HashMap<Long, TextView>()
  private var lastId = 0L
  private var firstId = Long.MAX_VALUE
  private var noMore = false
  private var loadingOlder = false
  private var pendingStart = false
  private var pendingCam = false
  private var camFront = false
  private var lastToggle = 0L
  private val fetchedModels = ArrayList<String>()
  // Contact provider I/O must never run on Android's main/UI thread.
  private val contactsExecutor = Executors.newSingleThreadExecutor()
  private val contactsHandler = Handler(Looper.getMainLooper())
  private val contactsGeneration = AtomicInteger(0)
  private val contactsDebounce = Runnable { loadContactsInBackground() }
  private val knowledgeExecutor = Executors.newSingleThreadExecutor()
  private val busListener: () -> Unit = { refresh() }

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
  private fun chip(label: String, f: () -> Unit): TextView {
    val t = tv(label, 13f); t.setPadding(dp(14), dp(8), dp(14), dp(8)); t.background = bg(SURF2, 18); t.gravity = Gravity.CENTER
    t.setOnClickListener { f() }
    return t
  }
  private fun card(parent: LinearLayout): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL; c.background = bg(SURF, 20); c.setPadding(dp(16), dp(14), dp(16), dp(14))
    val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12)
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

  // ---------- screen skeleton ----------
  override fun onCreate(b: Bundle?) {
    try {
      super.onCreate(b)
      onCreateInner(b)
    } catch (t: Throwable) {
      Crash.save(this, t, "MainActivity.onCreate")
      throw t
    }
  }

  private fun onCreateInner(b: Bundle?) {
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
    pages.add(buildChat()); pages.add(buildLearning()); pages.add(buildAutomation()); pages.add(buildContacts()); pages.add(buildSettings())
    pages.forEach { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }

    // bottom navigation
    val nav = LinearLayout(this); nav.setBackgroundColor(SURF); nav.setPadding(0, dp(6), 0, dp(6))
    val items = listOf("💬" to "Chat", "🧠" to "Learning", "⏰" to "Tasks", "👥" to "Contacts", "⚙️" to "Settings")
    items.forEachIndexed { i, (e, l) ->
      val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER
      val ic = tv(e, 20f); ic.gravity = Gravity.CENTER; val lb = tv(l, 11f, SUB); lb.gravity = Gravity.CENTER
      col.addView(ic); col.addView(lb); navItems.add(ic to lb)
      col.setOnClickListener { select(i) }
      nav.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }
    root.addView(nav)
    select(0)
  }

  private fun select(i: Int) {
    curTab = i
    pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    navItems.forEachIndexed { k, (ic, lb) -> lb.setTextColor(if (k == i) ACC else SUB); ic.alpha = if (k == i) 1f else 0.6f }
    when (i) { 1 -> { rebuildLearning(); rebuildShop() }; 2 -> rebuildTasks(); 3 -> rebuildContacts(); 4 -> refresh() }
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
    val sub = tv("Tap Start to connect Gemini. General questions go to Gemini automatically; personal device records stay on this phone.", 13f, SUB); sub.gravity = Gravity.CENTER; sub.setPadding(dp(24), dp(6), dp(24), 0); e.addView(sub)
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
        } else if ((Prefs.on(this, "privacy_mode", true) && looksPersonal(t)) || Db.get(this).recall(t, 5).any { it.cat == "web_source" }) {
          input.text.clear()
          answerFromLocalKnowledge(t)
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
      if (key == "f_bubble" || key == "f_overlay") VoiceService.instance?.refreshBubble()
      if (key == "wake_phrase") VoiceService.instance?.refreshWake()
      if (key == "auto_sleep") VoiceService.instance?.refreshSleep()
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
    updaters.add {
      val good = ok()
      st.text = if (good) (if (grantLabel.isEmpty()) "✓ Ready" else "✓ Permission granted") else "⚠ Permission needed"
      st.setTextColor(if (good) GOOD else WARN)
      g.visibility = if (good) View.GONE else View.VISIBLE
    }
  }

  private fun addFeatures(root: LinearLayout) {
    heading(root, "App features", "Manage Saara permissions and device capabilities here.")
    feature(root, "Device controls", "Torch, volume, open apps, camera photo, Wi-Fi / Bluetooth panels", "f_device", true, { true }, "", {})
    feature(root, "Phone: calls & SMS", "Call, answer / end calls and send texts by voice", "f_phone", true,
      { has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.SEND_SMS) && has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.ANSWER_PHONE_CALLS) },
      "Grant phone, SMS, contacts & answer calls", { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS), 2) })
    feature(root, "Screen automation", "Read the screen, tap, type, scroll in any app", "f_ui", true, { a11yOn() },
      "Enable in Accessibility settings", {
        toast("Turn on Saara. If Android says 'Restricted setting': App info → ⋮ → Allow restricted settings.")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      })
    feature(root, "Camera & screen sharing", "Send live camera or screen frames to Saara while sharing is ON, so she can complete tasks", "f_share", true,
      { has(Manifest.permission.CAMERA) }, "Allow camera", { requestPermissions(arrayOf(Manifest.permission.CAMERA), 3) })
    feature(root, "Wake phrase", "Say \"Hey Saara\" to wake her. After waking, the Gemini mic stays ON without toggling; when you stop talking she quietly returns to the wake phrase.", "wake_phrase", true,
      { has(Manifest.permission.RECORD_AUDIO) }, "Allow microphone", { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1) })
    feature(root, "Quiet wake listening", "Briefly silences the phone's recognizer start beep while it restarts. Playing music, alarms and calls are left alone.", "quiet_wake", true, { true }, "", {})
    feature(root, "Sleep when idle", "After about 90 seconds without a user turn, return to the wake phrase so Saara does not listen to background conversation.", "auto_sleep", true, { true }, "", {})
    feature(root, "Incoming call announcements", "Announce the caller, ask answer or reject, and turn on the loudspeaker when no Bluetooth headset is connected.", "f_calls", true,
      { has(Manifest.permission.READ_PHONE_STATE) && has(Manifest.permission.ANSWER_PHONE_CALLS) },
      "Grant phone & call info", { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS), 5) })
    feature(root, "Clipboard copy & paste", "Copy text and paste it into fields during screen tasks", "f_clipboard", true,
      { true }, "", {})
    feature(root, "Task self-learning", "Remember which screen steps worked or failed for repeated tasks. Stored on this phone; only short tool-step hints are shared to plan similar tasks.", "learn_tasks", true,
      { true }, "", {})
    feature(root, "Live caption overlay", "Transparent text over other apps showing what Saara hears, says and does", "f_overlay", true,
      { Settings.canDrawOverlays(this) }, "Allow display over other apps",
      { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
    feature(root, "Notification auto-reply", "Auto-reply to WhatsApp / WhatsApp Business / Telegram messages. Saara must be started.", "f_notif", false, { notifOn() },
      "Enable Notification access", { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { c ->
      val e = edit("Only these names (comma separated). Leave empty = reply to everyone")
      e.setText(Prefs.plain(this).getString("reply_names", ""))
      e.doAfterTextChanged { Prefs.plain(this).edit().putString("reply_names", it.toString()).apply() }
      c.addView(e)
    }
    feature(root, "Floating bubble", "Button over every app. Also lets Saara open apps from the background", "f_bubble", false,
      { Settings.canDrawOverlays(this) }, "Allow display over other apps",
      { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
  }

  private fun buildAutomation(): View {
    val (sv, root) = page()
    heading(root, "Tasks & Automation", "Time-based reminders are supported. Advanced event triggers are not yet implemented.")
    taskBox = card(root)
    return sv
  }

  private fun rebuildTasks() {
    taskBox.removeAllViews()
    val ts = Db.get(this).tasks()
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
    heading(root, "What Saara learned", "Preferences, habits and corrections she keeps in memory to serve you better.")
    learnBox = LinearLayout(this); learnBox.orientation = LinearLayout.VERTICAL
    root.addView(learnBox)
    shopBox = LinearLayout(this); shopBox.orientation = LinearLayout.VERTICAL
    root.addView(shopBox)
    return sv
  }

  private fun hour12(h: Int) = (if (h % 12 == 0) 12 else h % 12).toString() + (if (h < 12) " AM" else " PM")

  private fun rebuildLearning() {
    learnBox.removeAllViews()
    val db = Db.get(this)
    val facts = db.facts(); val apps = db.topUsage("app", 5); val acts = db.topUsage("action", 6); val hours = db.usageHours()
    val sum = card(learnBox)
    sum.addView(tv("Summary", 16f, bold = true))
    val sb = StringBuilder()
    if (facts.isEmpty() && apps.isEmpty() && acts.isEmpty()) sb.append("Nothing learned yet. Just talk to Saara — she notes your preferences, habits and corrections on her own.")
    else {
      sb.append("I remember ${facts.size} thing(s) about you")
      val cats = facts.groupBy { it.cat }.map { "${it.key} ${it.value.size}" }
      if (cats.isNotEmpty()) sb.append(" (").append(cats.joinToString(", ")).append(")")
      sb.append(".")
      if (apps.isNotEmpty()) sb.append("\nApps you ask for most: ").append(apps.joinToString(", ") { "${it.first} ×${it.second}" })
      if (acts.isNotEmpty()) sb.append("\nFrequent actions: ").append(acts.joinToString(", ") { "${it.first.replace('_', ' ')} ×${it.second}" })
      val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
      if (hours[peak] > 0) sb.append("\nYou talk to me most around ").append(hour12(peak)).append(".")
    }
    val st = tv(sb.toString(), 14f); st.setPadding(0, dp(6), 0, 0); sum.addView(st)
    val ask = btn("🗣  Ask Saara to explain") {
      val factsNow = Db.get(this).facts().take(12)
      val localSummary = if (factsNow.isEmpty()) "No saved learning facts yet." else factsNow.joinToString("\n") { "• ${it.cat}: ${it.text}" }
      Bus.add("you", "Show my saved learning", true)
      Bus.add("saara", "Your saved learning (read locally):\n$localSummary", true)
      select(0)
    }
    val alp = LinearLayout.LayoutParams(-1, -2); alp.topMargin = dp(10); sum.addView(ask, alp)

    val steps = db.taskSteps(20)
    if (steps.isNotEmpty()) {
      val tc = card(learnBox)
      tc.addView(tv("Task methods (${steps.size})", 16f, bold = true))
      tc.addView(tv("Which on-device steps worked or failed during screen tasks. Stored only on this phone.", 12f, SUB))
      for (s in steps.take(10)) {
        val line = (if (s.ok) "✓ " else "✗ ") + s.goal.take(90) + "  ·  " + s.tool
        tc.addView(tv(line, 13f, if (s.ok) GOOD else WARN))
      }
      tc.addView(btn("Clear task learning", SURF2) { Db.get(this).taskStepsClear(); rebuildLearning() })
    }

    val categoryCard = card(learnBox)
    categoryCard.addView(tv("Manage categories", 16f, bold = true))
    val newCat = edit("New category name")
    categoryCard.addView(newCat)
    categoryCard.addView(btn("Add category") { db.categoryAdd(newCat.text.toString()); rebuildLearning() })
    val renameCat = edit("Existing category to rename")
    val renamed = edit("New category name")
    categoryCard.addView(renameCat); categoryCard.addView(renamed)
    categoryCard.addView(btn("Rename category") { db.categoryRename(renameCat.text.toString(), renamed.text.toString()); rebuildLearning() })

    val teach = card(learnBox)
    teach.addView(tv("Teach Saara", 16f, bold = true))
    val ti = edit("e.g. I prefer replies in Bengali after 9 PM"); teach.addView(ti)
    val tb = btn("Add to memory") {
      val t = ti.text.toString().trim()
      if (t.isNotEmpty()) { db.factAdd("preference", t); rebuildLearning() }
    }
    val tlp = LinearLayout.LayoutParams(-1, -2); tlp.topMargin = dp(6); teach.addView(tb, tlp)

    if (facts.isNotEmpty()) {
      for ((cat, list) in facts.groupBy { it.cat }) {
        val c = card(learnBox)
        c.addView(tv(cat.replaceFirstChar { it.uppercase() } + "  (${list.size})", 15f, ACC, true))
        for (f in list) {
          val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(0, dp(6), 0, 0)
          val content = LinearLayout(this); content.orientation = LinearLayout.VERTICAL
          content.addView(tv(f.text, 14f))
          val actions = LinearLayout(this); actions.gravity = Gravity.CENTER_VERTICAL
          val move = Spinner(this); move.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, db.categories())
          val selected = db.categories().indexOf(f.cat).coerceAtLeast(0); move.setSelection(selected)
          actions.addView(move, LinearLayout.LayoutParams(0, dp(42), 1f))
          actions.addView(btn("Move / Edit") {
            val input = edit("Edit preference", false); input.setText(f.text)
            AlertDialog.Builder(this).setTitle("Edit preference").setView(input)
              .setPositiveButton("Save") { _, _ -> db.factUpdate(f.id, move.selectedItem?.toString() ?: f.cat, input.text.toString()); rebuildLearning() }
              .setNegativeButton("Cancel", null).show()
          })
          content.addView(actions); r.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
          val x = tv("✕", 16f, SUB); x.setPadding(dp(12), dp(4), dp(2), dp(4))
          x.setOnClickListener {
            AlertDialog.Builder(this).setMessage("Make Saara forget this?\n\n" + f.text).setPositiveButton("Forget") { _, _ -> db.factDelete(f.id); rebuildLearning() }.setNegativeButton("Cancel", null).show()
          }
          r.addView(x); c.addView(r)
        }
      }
    }
  }

  // ---------- LOCAL PRIVACY / URL IMPORT ----------
  private fun extractHttpUrl(text: String): String? = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']')

  private fun looksPersonal(text: String): Boolean {
    val q = text.lowercase(Locale.ROOT)
    return listOf("my contact", "my contacts", "my phone", "phone number", "my address", "home address", "bank account", "account number", "call history", "call log", "sms history", "my messages", "message from", "my email", "my whatsapp", "my saved learning", "my knowledge", "my sheet", "my spreadsheet", "my call", "আমার নম্বর", "আমার কন্টাক্ট", "আমার মেসেজ", "আমার কল", "আমার ঠিকানা", "মেসেজ থেকে", "কল হিস্ট্রি", "मेरा नंबर", "मेरे संपर्क", "मेरे मैसेज", "मेरी कॉल हिस्ट्री", "मेरा पता").any { q.contains(it) } ||
      Regex("\\b[+0-9][0-9 ()-]{7,}\\b").containsMatchIn(text) ||
      Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(text)
  }

  private fun answerFromLocalKnowledge(query: String) {
    Bus.add("you", query, true)
    val matches = Db.get(this).recall(query, 8)
    val answer = if (matches.isEmpty()) "I checked Saara's on-device Knowledge and Learning database. No matching saved information was found. Nothing was sent to Gemini." else
      "I found this on your device:\n" + matches.joinToString("\n") { "• [${it.cat}] ${it.text}" } + "\n\nThis information stayed on this phone."
    Bus.add("saara", answer, true)
  }

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
          if (::shopBox.isInitialized) rebuildShop()
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
    shopBox.removeAllViews()
    val db = Db.get(this)
    val manual = card(shopBox)
    manual.addView(tv("Add knowledge", 16f, bold = true))
    val categories = db.categories()
    val cat = Spinner(this)
    cat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
    manual.addView(cat)
    val fact = edit("Enter product price, business detail or preference")
    manual.addView(fact)
    manual.addView(btn("Save knowledge") {
      val value = fact.text.toString().trim()
      if (value.isBlank()) toast("Enter some information first")
      else { db.factAdd(cat.selectedItem?.toString() ?: "general", value); toast("Saved in Learning"); rebuildShop() }
    })
    val sheets = card(shopBox)
    sheets.addView(tv("Google Sheets connection", 16f, bold = true))
    sheets.addView(tv("Public webpages and Sheets shared as 'Anyone with the link' can be fetched. Private Sheets are not accessible until Google OAuth is configured.", 13f, SUB))
    val url = edit("Website or Google Sheets URL")
    url.setText(Prefs.plain(this).getString("knowledge_sheet_url", ""))
    sheets.addView(url)
    sheets.addView(btn("Save link", SURF2) {
      val value = url.text.toString().trim()
      if (!value.startsWith("https://") && !value.startsWith("http://")) toast("Enter a valid http:// or https:// URL")
      else { Prefs.plain(this).edit().putString("knowledge_sheet_url", value).apply(); Db.get(this).factAdd("web_source", "Saved source URL: $value"); toast("Link saved locally. Fetching its data now…"); rebuildShop(); fetchAndSaveUrl(value) }
    })
    sheets.addView(btn("Fetch & save data") {
      val value = url.text.toString().trim()
      if (!value.startsWith("https://") && !value.startsWith("http://")) { toast("Enter a valid http:// or https:// URL"); return@btn }
      Prefs.plain(this).edit().putString("knowledge_sheet_url", value).apply()
      fetchAndSaveUrl(value)
    })
    val facts = db.facts()
    heading(shopBox, "Saved knowledge (${facts.size})")
    for (f in facts.take(100)) {
      val c = card(shopBox)
      c.addView(tv(f.cat.uppercase(), 11f, ACC, true))
      c.addView(tv(f.text, 14f))
      c.addView(tv(hhmm(f.ts), 11f, SUB))
    }
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

  private fun buildSettings(): View {
    val (sv, root) = page()
    heading(root, "Settings")

    val privacy = card(root)
    val privacyOn = Prefs.on(this, "privacy_mode", true)
    val privacyLabel = tv("Privacy mode · " + if (privacyOn) "ON" else "OFF", 16f, if (privacyOn) GOOD else WARN, true)
    privacy.addView(privacyLabel)
    privacy.addView(tv("ON: Saara locally filters likely personal requests before sending text. OFF: ordinary typed prompts go to Gemini directly. In BOTH modes, contacts, SMS, call history, chat history, Learning, Knowledge, Sheet contents and confidential records are never attached or uploaded. While the mic is ON, microphone audio is streamed to Gemini for live voice replies; mute it anytime. Live camera and screen frames are sent to Gemini only while sharing is ON, regardless of privacy mode; manual sharing stays on until you stop it, while sharing Saara requests for a task stops automatically when that task ends. While Screen automation is ON, tool results may include current screen text so Saara can finish the task; private databases stay local. Task self-learning stores tool steps on this phone and may send only short task-step hints with numbers and emails redacted; it never sends contacts, messages, ledger data, or screen text.", 12f, SUB))
    lateinit var privacyToggle: Button
    privacyToggle = btn(if (privacyOn) "Turn privacy mode OFF" else "Turn privacy mode ON", SURF2) {
      val next = !Prefs.on(this, "privacy_mode", true)
      Prefs.plain(this).edit().putBoolean("privacy_mode", next).apply()
      privacyLabel.text = "Privacy mode · " + if (next) "ON" else "OFF"
      privacyLabel.setTextColor(if (next) GOOD else WARN)
      privacyToggle.text = if (next) "Turn privacy mode OFF" else "Turn privacy mode ON"
      toast(if (next) "Privacy mode ON. Personal requests are filtered locally." else "Privacy mode OFF. Ordinary typed prompts go to Gemini; local databases remain private.")
    }
    privacy.addView(privacyToggle)
    addFeatures(root)

    // language priority
    val cl = card(root)
    cl.addView(tv("Language priority", 16f, bold = true))
    cl.addView(tv("First-priority languages: Bengali, Hindi, English. Pick how Saara should speak by default.", 12f, SUB))
    val opts = listOf("auto" to "Auto", "bn" to "বাংলা", "hi" to "हिन्दी", "en" to "English", "hinglish" to "Hinglish")
    val chips = HashMap<String, TextView>()
    val paint = {
      val cur = Prefs.plain(this).getString("lang", "auto")
      for ((k, v) in chips) { v.background = bg(if (k == cur) ACC2 else SURF2, 18); v.setTextColor(if (k == cur) Color.WHITE else TXT) }
    }
    val row = LinearLayout(this); row.setPadding(0, dp(10), 0, 0)
    for ((k, l) in opts) {
      val ch = chip(l) { Prefs.plain(this).edit().putString("lang", k).apply(); paint(); toast("Saved. Stop and Start Saara to apply.") }
      chips[k] = ch; val lp = LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); row.addView(ch, lp)
    }
    val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false; hs.addView(row); cl.addView(hs); paint()

    // gemini
    val c3 = card(root)
    c3.addView(tv("Gemini connection", 16f, bold = true))
    val saved = !(Prefs.secure(this).getString("apiKey", "") ?: "").isBlank()
    val key = edit(if (saved) "API key saved ✓ (type a new one to replace)" else "Paste Gemini API key", true)
    val model = edit("Model"); model.setText(Prefs.plain(this).getString("model", "gemini-3.1-flash-live-preview"))
    var voiceName = Prefs.plain(this).getString("voice", "Aoede") ?: "Aoede"
    c3.addView(key); c3.addView(tv("Live model", 12f, SUB)); c3.addView(model)
    val pick = btn("Pick model from list", SURF2) {
      val all = (MODELS + fetchedModels).distinct()
      AlertDialog.Builder(this).setTitle("Live models").setItems(all.toTypedArray()) { _, w -> model.setText(all[w]) }.show()
    }
    val fetch = btn("🔄  Fetch live models for my key", SURF2) {
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
    val kb = btn("Get Free API Key / Muft API Key Prapt Karein", SURF2) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))) }
    c3.addView(kb, lp1)

    // offline wake word
    val cw = card(root)
    cw.addView(tv("Offline wake word · \"Hey Saara\"", 16f, bold = true))
    cw.addView(tv("Fully offline engines: openWakeWord (default) or Picovoice Porcupine. openWakeWord needs melspectrogram.tflite + embedding_model.tflite in android/app/src/main/assets/openwakeword/. Then either add a trained hey_saara.tflite, or tap “Train on this phone” to learn your voice on-device — no PC needed. For Porcupine, add an AccessKey and keywords/hey_saara.ppn. Without these Saara uses the phone's speech recognizer (which may use the network).", 12f, SUB))
    val pvSaved = !(Prefs.secure(this).getString("pvAccessKey", "") ?: "").isBlank()
    val pvKey = edit(if (pvSaved) "Picovoice AccessKey saved ✓ (type a new one to replace)" else "Paste Picovoice AccessKey", true)
    cw.addView(pvKey)
    val pvSave = btn("Save wake word key") {
      if (pvKey.text.isNotBlank()) {
        Prefs.secure(this).edit().putString("pvAccessKey", pvKey.text.toString().trim()).apply()
        pvKey.text.clear()
        pvKey.hint = "Picovoice AccessKey saved ✓ (type a new one to replace)"
        toast("Saved. Stop and Start Saara to apply.")
        VoiceService.instance?.refreshWake()
      } else toast("Paste your Picovoice AccessKey first")
    }
    cw.addView(pvSave, lp1)
    val pvGet = btn("Get free Picovoice AccessKey", SURF2) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.picovoice.ai/"))) }
    cw.addView(pvGet, lp1)
    val engineOrder = listOf("auto", "openwakeword", "porcupine", "speech")
    val engineNames = mapOf(
      "auto" to "Auto",
      "openwakeword" to "openWakeWord (offline)",
      "porcupine" to "Porcupine (offline)",
      "speech" to "Phone recognizer"
    )
    fun currentEngine() = Prefs.plain(this).getString("wake_engine", "auto") ?: "auto"
    lateinit var engineBtn: Button
    engineBtn = btn("Wake engine: ${engineNames[currentEngine()]}", SURF2) {
      val next = engineOrder[(engineOrder.indexOf(currentEngine()).coerceAtLeast(0) + 1) % engineOrder.size]
      Prefs.plain(this).edit().putString("wake_engine", next).apply()
      engineBtn.text = "Wake engine: ${engineNames[next]}"
      toast("Wake engine: ${engineNames[next]}. Stop and Start Saara to apply.")
      VoiceService.instance?.refreshWake()
    }
    cw.addView(engineBtn, lp1)
    val trainedModel = WakeVerifier.exists(this)
    val trainBtn = btn(if (trainedModel) "Retrain \"Hey Saara\" on this phone" else "Train \"Hey Saara\" on this phone", SURF2) {
      if (!has(Manifest.permission.RECORD_AUDIO)) {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        toast("Allow Microphone, then tap again.")
        return@btn
      }
      AlertDialog.Builder(this)
        .setTitle("Train on this phone")
        .setMessage("You will say \"Hey Saara\" 5 times, then any other phrase 5 times.\n\nTakes about 35 seconds. Saara pauses the microphone while recording. Use a quiet room and speak normally.")
        .setPositiveButton("Start") { _, _ -> startEnrollment() }
        .setNegativeButton("Cancel", null)
        .show()
    }
    cw.addView(trainBtn, lp1)
    if (trainedModel) {
      val delBtn = btn("Delete trained voice model", SURF2) {
        WakeVerifier.delete(this)
        toast("Trained voice model deleted.")
        VoiceService.instance?.refreshWake()
      }
      cw.addView(delBtn, lp1)
    }

    // data
    val c4 = card(root)
    c4.addView(tv("Chat history", 16f, bold = true))
    c4.addView(tv("Old chats are kept on this phone and load as you scroll up in the Chat tab.", 12f, SUB))
    val clr = btn("Clear chat history", SURF2) {
      AlertDialog.Builder(this).setMessage("Delete all saved chats? Learned memory and shop data stay.").setPositiveButton("Delete") { _, _ -> Bus.clear() }.setNegativeButton("Cancel", null).show()
    }
    c4.addView(clr, lp1)

    // about
    val c5 = card(root); c5.gravity = Gravity.CENTER_HORIZONTAL
    val img = ImageView(this); img.setImageResource(R.drawable.logo_full)
    c5.addView(img, LinearLayout.LayoutParams(dp(120), dp(120)))
    val ab = tv("Saara AI Agent", 14f, SUB); ab.gravity = Gravity.CENTER; c5.addView(ab)
    return sv
  }

  private fun startEnrollment() {
    VoiceService.instance?.suspendWakeForEnrollment()
    val enrollment = WakeEnrollment(this)
    enrollment.train(
      onPrompt = { msg -> Bus.add("system", msg, true) },
      onDone = { msg -> VoiceService.instance?.resumeWakeAfterEnrollment(); Bus.add("system", msg, true) }
    )
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
    if (Bus.running) { startService(svc("STOP")); return }
    if (!has(Manifest.permission.RECORD_AUDIO)) {
      pendingStart = true
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
      return
    }
    startSaara()
  }

  private fun startSaara() {
    if ((Prefs.secure(this).getString("apiKey", "") ?: "").isBlank()) { toast("Paste your Gemini API key in Settings and tap Save"); select(4); return }
    startForegroundService(Intent(this, VoiceService::class.java))
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (pendingStart && has(Manifest.permission.RECORD_AUDIO)) { pendingStart = false; startSaara() }
    if (requestCode == 1 && has(Manifest.permission.RECORD_AUDIO)) VoiceService.instance?.refreshWake()
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
    updaters.forEach { it() }
    statusTv.text = Bus.status + (if (Bus.camOn) "  •  camera live" else "") + (if (Bus.screenOn) "  •  screen live" else "")
    startBtn.text = if (Bus.running) "Stop" else "Start"
    startBtn.background = bg(if (Bus.running) 0xFFB3261E.toInt() else ACC2, 20)
    val micOn = VoiceService.instance?.isMicOn() ?: false
    val wakeOn = VoiceService.instance?.isWakeOn() ?: false
    muteChip.text = when { micOn -> "🎤 Mic: ON"; wakeOn -> "🎤 Wake: ON"; else -> "🎤 Mic: OFF" }
    muteChip.background = bg(if (micOn || wakeOn) ACC2 else SURF2, 18)
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
      val t = shown[m.id]
      if (t != null) { val s = fmt(m); if (t.text.toString() != s) { t.text = s; changed = true } }
      else if (m.id > lastId) {
        val prev = snap.lastOrNull { it.id < m.id }
        if (prev == null || !sameDay(prev.ts, m.ts)) chatList.addView(dateLabel(m.ts), rowLp())
        val (row, tvw) = msgRow(m); chatList.addView(row, rowLp()); shown[m.id] = tvw
        lastId = m.id; if (m.id < firstId) firstId = m.id; changed = true
      }
    }
    emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    if (changed && nearBottom) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
  }

  override fun onResume() { super.onResume(); visible = true; Bus.listen(busListener); refresh(); if (curTab == 3 && has(Manifest.permission.READ_CONTACTS)) scheduleContactsRefresh(); Bus.ping() }
  override fun onPause() { visible = false; Bus.unlisten(busListener); super.onPause() }
  override fun onDestroy() { contactsHandler.removeCallbacks(contactsDebounce); contactsExecutor.shutdownNow(); knowledgeExecutor.shutdownNow(); super.onDestroy() }
}
