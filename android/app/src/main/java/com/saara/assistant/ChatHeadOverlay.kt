package com.saara.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Messenger-style chat head overlay: draggable bubble, close target, and expandable floating panel.
 * Runs as TYPE_APPLICATION_OVERLAY views owned by VoiceService.
 */
class ChatHeadOverlay(
  private val ctx: Context,
  private val callbacks: Callbacks,
) {
  interface Callbacks {
    fun onOpenApp()
    fun onSendText(text: String)
    fun onMicToggle()
    fun onScreenToggle()
    fun onAssistantToggle()
    fun onCloseRequested() // user dropped on X - hide overlay, keep service/background wake
    fun isMicOn(): Boolean
    fun isScreenOn(): Boolean
    fun isActive(): Boolean
    fun isSpeaking(): Boolean
    fun isAssistantOn(): Boolean
    fun statusText(): String
    fun recentMessages(limit: Int): List<Msg>
    fun onAnchorChanged() {}
  }

  private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val main = Handler(Looper.getMainLooper())
  private val d = ctx.resources.displayMetrics.density
  private val sw get() = ctx.resources.displayMetrics.widthPixels
  private val sh get() = ctx.resources.displayMetrics.heightPixels

  private var bubbleView: View? = null
  private var bubbleLp: WindowManager.LayoutParams? = null
  private var closeView: View? = null
  private var closeLp: WindowManager.LayoutParams? = null
  private var panelView: View? = null
  private var panelLp: WindowManager.LayoutParams? = null

  private var expanded = false
  private var dragging = false
  private var overClose = false
  private var closeAnimator: ValueAnimator? = null
  private var snapAnimator: ValueAnimator? = null

  private val bubbleSize get() = dp(Prefs.plain(ctx).getInt("bubble_size_dp", 58).coerceIn(44, 76))
  private val closeSize = dp(64)
  private val closeHit = dp(90)
  private val panelMargin = dp(8)
  private var edgeHidden = false
  private val edgeHidePeek = dp(18)
  private val edgeHideRun = Runnable { }
  private fun scheduleEdgeHide(delay: Long = 2500L) { main.removeCallbacks(edgeHideRun) }
  fun isDocked(): Boolean = edgeHidden
  private val panelWidth get() = min(sw - panelMargin * 2, dp(360))
  private val panelHeight get() = min((sh * 0.52f).toInt(), dp(420))

  private var chatList: LinearLayout? = null
  private var chatScroll: ScrollView? = null
  private var inputField: EditText? = null
  private var statusDot: View? = null
  private var statusLabel: TextView? = null
  private var micBtn: TextView? = null
  private var screenBtn: TextView? = null
  private var assistantBtn: TextView? = null
  private var customizeView: LinearLayout? = null
  private var bubbleStatusRing: GradientDrawable? = null
  private var bubbleDot: View? = null

  private val msgViews = HashMap<Long, TextView>()
  private var lastRenderedId = 0L

  fun show() {
    if (!Settings.canDrawOverlays(ctx)) return
    main.post {
      if (bubbleView == null) createBubble()
      refreshStatus()
      renderMessages()
      callbacks.onAnchorChanged()
    }
  }

  fun applyCustomization() {
    main.post {
      val bv = bubbleView ?: return@post
      val lp = bubbleLp ?: return@post
      val size = bubbleSize
      lp.width = size; lp.height = size
      lp.x = clamp(lp.x, -(size - edgeHidePeek), sw - edgeHidePeek)
      lp.y = clamp(lp.y, dp(24), sh - size - dp(24))
      val bg = bv as? ViewGroup
      bg?.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(size, size)
      (bg?.getChildAt(0) as? ImageView)?.clipToOutline = true
      val dotSize = dp(14)
      bg?.getChildAt(1)?.layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(2); bottomMargin = dp(2) }
      try { wm.updateViewLayout(bv, lp) } catch (_: Exception) {}
      if (edgeHidden && Prefs.on(ctx, "bubble_edge_hide", false)) snapToEdge(lp.x, lp.y, hideEdge = true) else { edgeHidden = false; snapToEdge(lp.x, lp.y, hideEdge = false) }
    }
  }

  fun anchorForOverlay(): Overlay.Anchor? {
    val lp = bubbleLp ?: return null
    val onRight = lp.x + bubbleSize / 2 >= sw / 2
    return Overlay.Anchor(lp.x, lp.y, bubbleSize, onRight)
  }

  fun hide() {
    main.post {
      main.removeCallbacks(edgeHideRun)
      removePanel()
      removeClose()
      removeBubble()
      expanded = false
      edgeHidden = false
    }
  }

  fun isExpanded(): Boolean = expanded

  fun onBusUpdate() {
    main.post {
      refreshStatus()
      if (expanded) renderMessages()
    }
  }

  fun refreshStatus() {
    val assistantOn = try { callbacks.isAssistantOn() } catch (_: Exception) { Bus.assistantOn }
    val speaking = callbacks.isSpeaking()
    val active = callbacks.isActive()
    val micOn = callbacks.isMicOn()
    val screenOn = callbacks.isScreenOn()
    val stRaw = callbacks.statusText()
    val st = stRaw.lowercase()
    val color = when {
      !assistantOn -> 0xFF9AA0A6.toInt()
      st.contains("503") || st.contains("retry") -> 0xFFF9AB00.toInt()
      st.contains("execut") -> 0xFF1A73E8.toInt()
      st.contains("thinking") || st.contains("connect") -> 0xFF9334E6.toInt()
      speaking -> 0xFF4FA3F7.toInt()
      active || micOn || st.contains("listen") -> 0xFF34A853.toInt()
      else -> 0xFF9AA0A6.toInt()
    }
    bubbleStatusRing?.setStroke(dp(3), color)
    bubbleDot?.background = oval(color)
    statusLabel?.text = if (assistantOn) stRaw else "Off · tap toggle to start"
    statusDot?.background = oval(color)
    micBtn?.text = if (micOn) "🎤" else "🔇"
    micBtn?.alpha = if (micOn && assistantOn) 1f else 0.65f
    screenBtn?.alpha = if (screenOn) 1f else 0.55f
    screenBtn?.background = bg(if (screenOn) 0xFF1A73E8.toInt() else 0xFF2A2C2E.toInt(), 18)
    assistantBtn?.text = if (assistantOn) "ON" else "OFF"
    assistantBtn?.setTextColor(Color.WHITE)
    assistantBtn?.background = bg(if (assistantOn) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt(), 14)
  }

  private fun createBubble() {
    val size = bubbleSize
    val root = FrameLayout(ctx)
    val ring = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(0xFFF1F3F4.toInt())
      setStroke(dp(3), 0xFF9AA0A6.toInt())
    }
    bubbleStatusRing = ring
    val img = ImageView(ctx).apply {
      setImageResource(R.drawable.logo_symbol)
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = ring
      clipToOutline = true
    }
    root.addView(img, FrameLayout.LayoutParams(size, size))
    val dot = View(ctx).apply { background = oval(0xFF9AA0A6.toInt()) }
    bubbleDot = dot
    val dotSize = dp(14)
    root.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.BOTTOM or Gravity.END).apply {
      rightMargin = dp(2); bottomMargin = dp(2)
    })

    val sp = Prefs.plain(ctx)
    val savedX = sp.getInt("bubble_x", dp(8))
    val savedY = sp.getInt("bubble_y", (sh * 0.35f).toInt())
    val lp = overlayLp(size, size).apply {
      gravity = Gravity.TOP or Gravity.START
      x = clamp(savedX, -dp(10), sw - size + dp(10))
      y = clamp(savedY, dp(24), sh - size - dp(24))
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    attachBubbleTouch(root, lp)
    try {
      wm.addView(root, lp); bubbleView = root; bubbleLp = lp
      edgeHidden = false; snapToEdge(lp.x, lp.y, hideEdge = false)
    } catch (_: Exception) {}
  }

  private fun attachBubbleTouch(v: View, lp: WindowManager.LayoutParams) {
    var downRawX = 0f; var downRawY = 0f
    var startX = 0; var startY = 0
    var moved = false
    var revealedFromEdge = false
    v.setOnTouchListener { _, e ->
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downRawX = e.rawX; downRawY = e.rawY
          main.removeCallbacks(edgeHideRun)
          snapAnimator?.cancel()
          revealedFromEdge = false
          if (edgeHidden) {
            val onLeft = Prefs.plain(ctx).getBoolean("bubble_docked_left", lp.x < sw / 2)
            lp.x = if (onLeft) dp(4) else sw - bubbleSize - dp(4)
            edgeHidden = false
            updateBubbleLayout()
            callbacks.onAnchorChanged()
            revealedFromEdge = true
          }
          startX = lp.x; startY = lp.y
          moved = false; dragging = false; overClose = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (e.rawX - downRawX).toInt()
          val dy = (e.rawY - downRawY).toInt()
          if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
            moved = true; dragging = true
            main.removeCallbacks(edgeHideRun)
            if (expanded) collapsePanel()
            showCloseTarget()
          }
          if (moved) {
            lp.x = clamp(startX + dx, -dp(10), sw - bubbleSize + dp(10))
            lp.y = clamp(startY + dy, dp(24), sh - bubbleSize - dp(24))
            updateBubbleLayout()
            updateCloseHover(e.rawX, e.rawY)
            callbacks.onAnchorChanged()
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (dragging) {
            val onClose = isOverClose(e.rawX, e.rawY)
            hideCloseTarget()
            dragging = false
            if (onClose) {
              callbacks.onCloseRequested()
            } else {
              val edgeDockEnabled = Prefs.on(ctx, "bubble_edge_hide", false)
              val nearLeft = lp.x <= dp(28)
              val nearRight = lp.x >= sw - bubbleSize - dp(28)
              snapToEdge(lp.x, lp.y, hideEdge = edgeDockEnabled && (nearLeft || nearRight))
            }
          } else {
            hideCloseTarget()
            if (revealedFromEdge) {
              // Tap on docked bubble revealed it; keep it visible until user swipes to edge.
              callbacks.onAnchorChanged()
            } else {
              togglePanel()
              if (expanded) main.removeCallbacks(edgeHideRun)
            }
          }
          true
        }
        else -> false
      }
    }
  }

  private fun togglePanel() {
    if (expanded) collapsePanel() else expandPanel()
  }

  private fun expandPanel() {
    if (!Settings.canDrawOverlays(ctx)) return
    if (panelView == null) createPanel()
    val p = panelView ?: return
    main.removeCallbacks(edgeHideRun)
    if (edgeHidden) revealEdgeIfHidden()
    p.visibility = View.VISIBLE
    expanded = true
    positionPanel()
    renderMessages()
    refreshStatus()
    // Hide caption overlay while expanded to reduce clutter
    VoiceService.instance?.overlayHide()
  }

  private fun collapsePanel() {
    hideKeyboard()
    panelView?.visibility = View.GONE
    panelLp?.let { lp ->
      lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
      try { panelView?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) {}
    }
    expanded = false
    scheduleEdgeHide()
  }

  private fun createPanel() {
    val root = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      background = GradientDrawable().apply {
        cornerRadius = dpF(20)
        setColor(0xF21E1F20.toInt())
        setStroke(dp(1), 0x334FA3F7)
      }
      elevation = dpF(8)
      setPadding(0, 0, 0, 0)
    }

    // Header
    val header = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(12), dp(10), dp(8), dp(8))
      background = GradientDrawable().apply {
        cornerRadii = floatArrayOf(dpF(20), dpF(20), dpF(20), dpF(20), 0f, 0f, 0f, 0f)
        setColor(0xFF252729.toInt())
      }
    }
    val avatar = ImageView(ctx).apply {
      setImageResource(R.drawable.logo_symbol)
      scaleType = ImageView.ScaleType.CENTER_CROP
      background = oval(0xFFF1F3F4.toInt())
      clipToOutline = true
    }
    header.addView(avatar, LinearLayout.LayoutParams(dp(34), dp(34)))
    val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
    titleCol.addView(TextView(ctx).apply { text = "Saara"; setTextColor(0xFFE3E3E3.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
    val stRow = LinearLayout(ctx)
    stRow.orientation = LinearLayout.HORIZONTAL
    stRow.gravity = Gravity.CENTER_VERTICAL
    val dot = View(ctx)
    dot.background = oval(0xFF9AA0A6.toInt())
    statusDot = dot
    stRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
    val st = TextView(ctx).apply { text = "Ready"; setTextColor(0xFF9AA0A6.toInt()); textSize = 11f; setPadding(dp(5), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    statusLabel = st
    stRow.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    titleCol.addView(stRow)
    header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val aBtn = TextView(ctx).apply {
      textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
      setPadding(dp(10), dp(6), dp(10), dp(6))
      setOnClickListener { callbacks.onAssistantToggle(); refreshStatus() }
    }
    assistantBtn = aBtn
    val aLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    aLp.leftMargin = dp(6)
    header.addView(aBtn, aLp)

    val gearBtn = headerBtn("⚙") { toggleCustomizePanel() }
    val openBtn = headerBtn("⤢") { callbacks.onOpenApp(); collapsePanel() }
    val minBtn = headerBtn("—") { collapsePanel() }
    header.addView(gearBtn)
    header.addView(openBtn)
    header.addView(minBtn)
    root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Customize panel
    val cust = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      visibility = View.GONE
      setPadding(dp(12), dp(8), dp(12), dp(8))
      setBackgroundColor(0xFF252729.toInt())
    }
    cust.addView(TextView(ctx).apply { text = "Bubble customization"; setTextColor(0xFFE3E3E3.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD })
    val sizeLabel = TextView(ctx).apply { setTextColor(0xFF9AA0A6.toInt()); textSize = 12f }
    val sizeSeek = SeekBar(ctx).apply { max = 32; progress = (Prefs.plain(ctx).getInt("bubble_size_dp", 58).coerceIn(44, 76) - 44) }
    fun paintSize() { val v = 44 + sizeSeek.progress; sizeLabel.text = "Bubble size: ${v}dp" }
    paintSize()
    sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (fromUser) { Prefs.plain(ctx).edit().putInt("bubble_size_dp", 44 + progress).apply(); paintSize(); applyCustomization() }
      }
      override fun onStartTrackingTouch(seekBar: SeekBar?) {}
      override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
    cust.addView(sizeLabel); cust.addView(sizeSeek)
    val edgeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    edgeRow.addView(TextView(ctx).apply { text = "Swipe to edge to hide"; setTextColor(0xFFE3E3E3.toInt()); textSize = 13f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val edgeSw = Switch(ctx).apply {
      isChecked = Prefs.on(ctx, "bubble_edge_hide", false)
      setOnCheckedChangeListener { _, v -> Prefs.plain(ctx).edit().putBoolean("bubble_edge_hide", v).apply(); applyCustomization() }
    }
    edgeRow.addView(edgeSw)
    cust.addView(edgeRow)
    customizeView = cust
    root.addView(cust, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Chat area
    val scroll = ScrollView(ctx).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
    val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
    scroll.addView(list)
    chatScroll = scroll; chatList = list
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    // Input row
    val inputRow = LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(10), dp(6), dp(10), dp(10))
    }
    val mic = TextView(ctx).apply {
      text = "🎤"; textSize = 18f; gravity = Gravity.CENTER
      background = bg(0xFF2A2C2E.toInt(), 18)
      setOnClickListener { callbacks.onMicToggle(); refreshStatus() }
    }
    micBtn = mic
    inputRow.addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)))

    val screen = TextView(ctx).apply {
      text = "🖥"; textSize = 16f; gravity = Gravity.CENTER
      background = bg(0xFF2A2C2E.toInt(), 18)
      setOnClickListener { callbacks.onScreenToggle(); refreshStatus() }
    }
    screenBtn = screen
    val sLp = LinearLayout.LayoutParams(dp(40), dp(40)); sLp.leftMargin = dp(6)
    inputRow.addView(screen, sLp)

    val photo = TextView(ctx).apply {
      text = "🖼"; textSize = 16f; gravity = Gravity.CENTER
      background = bg(0xFF2A2C2E.toInt(), 18)
      setOnClickListener {
        try { ctx.startActivity(Intent(ctx, CameraVisionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
      }
    }
    val pLp = LinearLayout.LayoutParams(dp(40), dp(40)); pLp.leftMargin = dp(6)
    inputRow.addView(photo, pLp)

    val input = EditText(ctx).apply {
      hint = "Message Saara…"; setHintTextColor(0xFF6F7580.toInt()); setTextColor(0xFFE3E3E3.toInt())
      textSize = 14f; maxLines = 3
      background = bg(0xFF2A2C2E.toInt(), 18)
      setPadding(dp(12), dp(8), dp(12), dp(8))
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      imeOptions = EditorInfo.IME_ACTION_SEND
      isFocusable = true; isFocusableInTouchMode = true
      setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendFromInput(); true } else false }
      setOnFocusChangeListener { _, hasFocus -> setPanelFocusable(hasFocus) }
      setOnClickListener { setPanelFocusable(true); requestFocus(); showKeyboard(this) }
    }
    inputField = input
    val inLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) }
    inputRow.addView(input, inLp)

    val send = TextView(ctx).apply {
      text = "➤"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
      background = bg(0xFF1A73E8.toInt(), 18)
      setOnClickListener { sendFromInput() }
    }
    inputRow.addView(send, LinearLayout.LayoutParams(dp(40), dp(40)))
    root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val lp = overlayLp(panelWidth, panelHeight).apply {
      gravity = Gravity.TOP or Gravity.START
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
      softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    positionPanelLp(lp)
    try { wm.addView(root, lp); panelView = root; panelLp = lp } catch (_: Exception) { panelView = null; panelLp = null }
  }

  private fun headerBtn(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
    this.text = text; textSize = 16f; setTextColor(0xFFE3E3E3.toInt()); gravity = Gravity.CENTER
    setPadding(dp(10), dp(6), dp(10), dp(6))
    background = bg(0xFF333538.toInt(), 12)
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.leftMargin = dp(6)
    layoutParams = lp
    setOnClickListener { onClick() }
  }

  private fun toggleCustomizePanel() {
    val v = customizeView ?: return
    v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
  }

  private fun sendFromInput() {
    val t = inputField?.text?.toString()?.trim().orEmpty()
    if (t.isEmpty()) return
    inputField?.text?.clear()
    callbacks.onSendText(t)
    renderMessages()
  }

  private fun setPanelFocusable(focusable: Boolean) {
    val v = panelView ?: return
    val lp = panelLp ?: return
    val wantFocusable = focusable
    val currentlyFocusable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
    if (wantFocusable == currentlyFocusable) {
      if (wantFocusable) showKeyboard(inputField)
      return
    }
    lp.flags = if (wantFocusable) {
      (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    } else {
      (lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    if (wantFocusable) {
      inputField?.post {
        inputField?.requestFocus()
        showKeyboard(inputField)
      }
    } else {
      hideKeyboard()
    }
  }

  private fun showKeyboard(et: EditText?) {
    et ?: return
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    et.postDelayed({ try { imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) {} }, 60)
  }

  private fun hideKeyboard() {
    val et = inputField ?: return
    try {
      val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(et.windowToken, 0)
    } catch (_: Exception) {}
  }

  private fun renderMessages() {
    val list = chatList ?: return
    val msgs = callbacks.recentMessages(30)
    if (msgs.isEmpty()) {
      if (list.childCount == 0) {
        list.addView(TextView(ctx).apply {
          text = "Say \"Hey Saara\" or type a message."
          setTextColor(0xFF9AA0A6.toInt()); textSize = 13f; gravity = Gravity.CENTER
          setPadding(0, dp(24), 0, dp(24))
        })
      }
      return
    }
    // Simple full rerender for correctness with small list
    list.removeAllViews(); msgViews.clear(); lastRenderedId = 0L
    for (m in msgs) {
      val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (m.role == "you") Gravity.END else Gravity.START }
      val tv = TextView(ctx).apply {
        text = fmtMsg(m); textSize = 13f; maxLines = 6
        setTextColor(if (m.role == "tool") 0xFF9AA0A6.toInt() else 0xFFE3E3E3.toInt())
        background = bg(when (m.role) { "you" -> 0xFF1A73E8.toInt(); "saara" -> 0xFF333538.toInt(); else -> 0xFF252729.toInt() }, 14)
        setPadding(dp(10), dp(7), dp(10), dp(7))
      }
      msgViews[m.id] = tv
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      lp.topMargin = dp(6); lp.bottomMargin = dp(2)
      lp.marginStart = if (m.role == "you") dp(36) else 0
      lp.marginEnd = if (m.role == "you") 0 else dp(36)
      row.addView(tv, lp)
      list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      lastRenderedId = max(lastRenderedId, m.id)
    }
    chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
  }

  private fun fmtMsg(m: Msg): String {
    val t = m.text.replace("\n", " ").trim()
    return when (m.role) { "tool" -> "⚙ ${t.take(120)}"; "you" -> t.take(240); else -> t.take(300) }
  }

  private fun positionPanel() {
    val lp = panelLp ?: return
    positionPanelLp(lp)
    try { panelView?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) {}
  }

  private fun positionPanelLp(lp: WindowManager.LayoutParams) {
    val bLp = bubbleLp
    val pw = panelWidth; val ph = panelHeight
    lp.width = pw; lp.height = ph
    val bx = bLp?.x ?: dp(8)
    val by = bLp?.y ?: (sh * 0.35f).toInt()
    val onRight = bx > sw / 2
    lp.x = if (onRight) sw - pw - panelMargin else panelMargin
    val desiredY = by - ph - dp(8)
    lp.y = clamp(if (desiredY < dp(40)) by + bubbleSize + dp(8) else desiredY, dp(40), sh - ph - dp(40))
  }

  // ---- close target ----
  private fun showCloseTarget() {
    if (closeView != null) { closeView?.visibility = View.VISIBLE; return }
    val size = closeSize
    val v = FrameLayout(ctx)
    val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202124.toInt()); setStroke(dp(2), 0xFFE3E3E3.toInt()) }
    val circle = TextView(ctx).apply {
      text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = bg
    }
    v.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
    val lp = overlayLp(closeHit + dp(20), closeHit + dp(20)).apply {
      gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
      y = dp(40)
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
    try { wm.addView(v, lp); closeView = v; closeLp = lp } catch (_: Exception) {}
  }

  private fun hideCloseTarget() {
    overClose = false
    closeAnimator?.cancel()
    closeView?.visibility = View.GONE
    closeView?.scaleX = 1f; closeView?.scaleY = 1f
  }

  private fun removeClose() { closeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; closeView = null; closeLp = null }

  private fun updateCloseHover(rawX: Float, rawY: Float) {
    val nowOver = isOverClose(rawX, rawY)
    if (nowOver == overClose) return
    overClose = nowOver
    val v = closeView ?: return
    closeAnimator?.cancel()
    val target = if (nowOver) 1.25f else 1f
    closeAnimator = ValueAnimator.ofFloat(v.scaleX, target).apply {
      duration = 120
      addUpdateListener { val s = it.animatedValue as Float; v.scaleX = s; v.scaleY = s }
      start()
    }
    val circle = (v as? FrameLayout)?.getChildAt(0)
    (circle?.background as? GradientDrawable)?.setColor(if (overClose) 0xE6D93025.toInt() else 0xCC202124.toInt())
  }

  private fun isOverClose(rawX: Float, rawY: Float): Boolean {
    val cx = sw / 2f
    val cy = sh - dp(40) - (closeHit + dp(20)) / 2f
    return abs(rawX - cx) < closeHit / 2f && abs(rawY - cy) < closeHit / 2f
  }

  // ---- helpers ----
  private fun snapToEdge(curX: Int, curY: Int, hideEdge: Boolean = false) {
    val lp = bubbleLp ?: return
    val onLeft = curX + bubbleSize / 2 < sw / 2
    val revealedX = if (onLeft) dp(4) else sw - bubbleSize - dp(4)
    val hiddenX = if (onLeft) -(bubbleSize - edgeHidePeek) else sw - edgeHidePeek
    val targetX = if (hideEdge) hiddenX else revealedX
    val targetY = clamp(curY, dp(24), sh - bubbleSize - dp(24))
    edgeHidden = hideEdge
    snapAnimator?.cancel()
    snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 180
      val sx = lp.x; val sy = lp.y
      addUpdateListener {
        val f = it.animatedValue as Float
        lp.x = (sx + (targetX - sx) * f).toInt()
        lp.y = (sy + (targetY - sy) * f).toInt()
        updateBubbleLayout()
        callbacks.onAnchorChanged()
      }
      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          Prefs.plain(ctx).edit().putInt("bubble_x", revealedX).putInt("bubble_y", lp.y).putBoolean("bubble_docked_left", onLeft).apply()
          callbacks.onAnchorChanged()
          if (!hideEdge) scheduleEdgeHide()
        }
      })
      start()
    }
  }

  private fun revealEdgeIfHidden() {
    if (!edgeHidden) return
    val lp = bubbleLp ?: return
    val onLeft = Prefs.plain(ctx).getBoolean("bubble_docked_left", lp.x < sw / 2)
    snapToEdge(if (onLeft) 0 else sw, lp.y, hideEdge = false)
  }

  private fun updateBubbleLayout() {
    val v = bubbleView ?: return; val lp = bubbleLp ?: return
    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    if (expanded) positionPanel()
  }

  private fun removeBubble() { bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; bubbleView = null; bubbleLp = null; bubbleStatusRing = null; bubbleDot = null }
  private fun removePanel() {
    hideKeyboard()
    panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    panelView = null; panelLp = null; chatList = null; chatScroll = null; inputField = null
    statusDot = null; statusLabel = null; micBtn = null; screenBtn = null; assistantBtn = null; customizeView = null; msgViews.clear()
  }

  private fun overlayLp(w: Int, h: Int): WindowManager.LayoutParams {
    val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    return WindowManager.LayoutParams(w, h, type, 0, PixelFormat.TRANSLUCENT)
  }

  private fun bg(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dpF(r) }
  private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
  private fun dp(v: Int): Int = (v * d).toInt()
  private fun dpF(v: Int): Float = v * d
  private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
}
