package com.saara.assistant
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory live chat + status, mirrored into SQLite so history survives restarts. */
object Bus {
  val msgs = ArrayList<Msg>()
  @Volatile var running = false
  @Volatile private var st = "Stopped"
  val status: String get() = st
  @Volatile var camOn = false
  @Volatile var screenOn = false
  private var lastRole = ""
  private var ended = true
  private var db: Db? = null
  private var fallbackId = 1_000_000_000_000L
  private var loaded = false
  private val ls = CopyOnWriteArrayList<() -> Unit>()

  fun init(c: Context) {
    synchronized(this) {
      if (db == null) db = Db.get(c)
      if (!loaded) { loaded = true; msgs.addAll(db!!.chatBefore(0, 60)); ended = true }
    }
  }
  fun listen(f: () -> Unit) { ls.add(f) }
  fun unlisten(f: () -> Unit) { ls.remove(f) }

  fun add(role: String, text: String, fresh: Boolean = false) {
    synchronized(this) {
      val last = msgs.lastOrNull()
      if (!fresh && role == lastRole && !ended && last != null && last.role == role) {
        last.text += text
        try { db?.chatUpdate(last.id, last.text) } catch (_: Exception) {}
      } else {
        val now = System.currentTimeMillis()
        val id = try { db?.chatInsert(role, text, now) ?: ++fallbackId } catch (_: Exception) { ++fallbackId }
        msgs.add(Msg(id, role, text, now))
      }
      lastRole = role; ended = false
      if (msgs.size > 300) msgs.removeAt(0)
    }
    fire()
  }
  fun endTurn() { synchronized(this) { ended = true } }
  fun setStatus(s: String) { st = s; fire() }
  fun ping() = fire()
  fun clear() { synchronized(this) { msgs.clear(); lastRole = ""; ended = true; try { db?.chatClear() } catch (_: Exception) {} }; fire() }
  fun older(beforeId: Long, n: Int): List<Msg> = try { db?.chatBefore(beforeId, n) ?: emptyList() } catch (_: Exception) { emptyList() }
  private fun fire() { Handler(Looper.getMainLooper()).post { ls.forEach { it() } } }
}
