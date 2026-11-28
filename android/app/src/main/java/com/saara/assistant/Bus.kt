package com.saara.assistant
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory live chat + status, mirrored into SQLite so history survives restarts. */
object Bus {
  val msgs = ArrayList<Msg>()
  @Volatile var running = false
  @Volatile var assistantOn = false
  @Volatile private var st = "Stopped"
  val status: String get() = st
  @Volatile var camOn = false
  @Volatile var screenOn = false
  /** True when screen sharing was requested by Saara for one task and should stop itself when the task ends. */
  @Volatile var screenAuto = false
  @Volatile var lastUser = ""
  /** Increments only when a new user turn starts, not when a transcription chunk is appended. */
  @Volatile var userSeq = 0
  private var lastRole = ""
  private var ended = true
  private var db: Db? = null
  private var fallbackId = 1_000_000_000_000L
  private var loaded = false
  private val ls = CopyOnWriteArrayList<() -> Unit>()

  private val NOISE_PREFIXES = listOf(
    "voice rejected:", "connecting…", "connecting...", "connected · listening for wake phrase",
    "disconnected (", "woke up — listening", "woke up - listening", "stopped.",
    "microphone muted", "microphone unmuted", "resuming after ", "microphone on. listening now.",
    "session paused.", "session closed.", "listening paused after timeout",
    "barge-in detected", "wake word heard", "interrupted.", "gemini connected (",
    "voice capture paused for enrollment", "floating bubble hidden for this session"
  )
  private val NOISE_CONTAINS = listOf(
    "voice rejected:", "below_threshold", "wake_audio", "live_audio", "media_playback",
    "retrying with model fallback"
  )
  fun isChatNoise(role: String, text: String): Boolean {
    if (role == "tool") return true
    if (role != "system") return false
    val t = text.trim().lowercase()
    if (t.isEmpty()) return false
    if (NOISE_PREFIXES.any { t.startsWith(it) || t == it.removeSuffix(".") }) return true
    if (NOISE_CONTAINS.any { t.contains(it) }) return true
    if (t.startsWith("connecting") && t.length < 80) return true
    if (t.startsWith("connected") && t.contains("wake")) return true
    if (t.startsWith("disconnected") && t.length < 120) return true
    return false
  }
  fun diag(tag: String, text: String) {
    try { android.util.Log.i("SaaraDiag", "[$tag] $text") } catch (_: Exception) {}
  }

  fun init(c: Context) {
    synchronized(this) {
      if (db == null) db = Db.get(c)
      if (!loaded) {
        loaded = true
        msgs.addAll(db!!.chatBefore(0, 120).filterNot { isChatNoise(it.role, it.text) }.takeLast(60))
        ended = true
        lastUser = msgs.lastOrNull { it.role == "you" }?.text ?: ""
      }
    }
  }
  fun listen(f: () -> Unit) { ls.add(f) }
  fun unlisten(f: () -> Unit) { ls.remove(f) }

  fun add(role: String, text: String, fresh: Boolean = false) {
    if (isChatNoise(role, text)) { diag(role, text); return }
    synchronized(this) {
      val last = msgs.lastOrNull()
      if (!fresh && role == lastRole && !ended && last != null && last.role == role) {
        last.text += text
        try { db?.chatUpdate(last.id, last.text) } catch (_: Exception) {}
      } else {
        val now = System.currentTimeMillis()
        val id = try { db?.chatInsert(role, text, now) ?: ++fallbackId } catch (_: Exception) { ++fallbackId }
        msgs.add(Msg(id, role, text, now))
        if (role == "you") userSeq++
      }
      lastRole = role; ended = false
      if (role == "you") lastUser = msgs.lastOrNull { it.role == "you" }?.text ?: text
      while (msgs.size > 300) msgs.removeAt(0)
    }
    fire()
  }
  fun endTurn() { synchronized(this) { ended = true } }
  fun setStatus(s: String) { st = s; fire() }
  fun setStatusCompactIfNeeded(s: String) { if (st != s) { st = s; fire() } }
  fun ping() = fire()
  fun clear() { synchronized(this) { msgs.clear(); lastRole = ""; lastUser = ""; userSeq++; ended = true; TaskControl.clear(); try { db?.chatClear(); db?.sessionStateClear(true) } catch (_: Exception) {} }; fire() }
  fun older(beforeId: Long, n: Int): List<Msg> = try {
    val out = ArrayList<Msg>(); var cursor = beforeId; var tries = 0
    while (out.size < n && tries < 6) {
      tries++
      val batch = db?.chatBefore(cursor, n) ?: break
      if (batch.isEmpty()) break
      for (m in batch) { if (!isChatNoise(m.role, m.text)) out.add(m) }
      cursor = batch.first().id
      if (batch.size < n) break
    }
    out.take(n)
  } catch (_: Exception) { emptyList() }
  private fun fire() { Handler(Looper.getMainLooper()).post { ls.forEach { it() } } }
}
