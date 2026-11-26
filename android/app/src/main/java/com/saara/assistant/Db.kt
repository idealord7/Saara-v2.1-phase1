package com.saara.assistant
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Msg(var id: Long, val role: String, var text: String, val ts: Long)
data class Fact(val id: Long, val cat: String, val text: String, val ts: Long, val lastUsed: Long = 0L, val pinned: Boolean = false, val source: String = "")
data class ShopRow(val id: Long, val item: String, val kind: String, val price: Double, val qty: Double, val party: String, val note: String, val ts: Long)
data class TaskRow(val id: Long, val whenMs: Long, val text: String, val repeatMs: Long)
data class TaskStep(val id: Long, val goal: String, val tool: String, val args: String, val result: String, val ok: Boolean, val ts: Long)
data class Rule(val id: Long, val text: String, val enabled: Boolean, val ts: Long)
data class ActionVerification(val id: Long, val goal: String, val tool: String, val args: String, val result: String, val ok: Boolean, val verified: Boolean, val retryCount: Int, val note: String, val ts: Long)
data class ReplyRule(val id: Long, val name: String, val enabled: Boolean, val apps: String, val senders: String, val blockedSenders: String, val chatType: String, val keywords: String, val quietStart: String, val quietEnd: String, val screenOffOnly: Boolean, val mode: String, val instruction: String, val ts: Long, val vipSenders: String = "", val muteSenders: String = "")
data class AutoRule(val id: Long, val name: String, val enabled: Boolean, val trigger: String, val condition: String, val action: String, val cooldownMs: Long, val lastFiredMs: Long, val ts: Long)
data class MemoryFact(val id: Long, val cat: String, val text: String, val ts: Long, val lastUsed: Long = 0L, val pinned: Boolean = false, val source: String = "")
data class SessionState(val sessionId: String, val status: String, val activeGoal: String, val lastStep: String, val pending: String, val lastError: String, val updatedTs: Long)

/** Local SQLite store: chat history, learned facts, shop ledger, usage stats, scheduled tasks, task steps. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "saara.db", null, 11) {
  private val appCtx = c.applicationContext
  companion object {
    @Volatile private var inst: Db? = null
    fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c).also { inst = it } }
  }
  override fun onCreate(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE learned(id INTEGER PRIMARY KEY AUTOINCREMENT, cat TEXT, text TEXT, ts INTEGER, last_used INTEGER, pinned INTEGER DEFAULT 0, source TEXT DEFAULT '')")
    d.execSQL("CREATE TABLE categories(name TEXT PRIMARY KEY)")
    d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('general'), ('personal'), ('preferences'), ('work'), ('shop'), ('mistakes_and_lessons')")
    d.execSQL("CREATE TABLE shop(id INTEGER PRIMARY KEY AUTOINCREMENT, item TEXT, kind TEXT, price REAL, qty REAL, party TEXT, note TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, name TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, whenms INTEGER, text TEXT, repeatms INTEGER)")
    createTaskSteps(d)
    createSessionTables(d)
  }
  override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
    if (o < 2) {
      d.execSQL("CREATE TABLE IF NOT EXISTS categories(name TEXT PRIMARY KEY)")
      d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('general'), ('preference'), ('work'), ('personal')")
    }
    if (o < 3) createTaskSteps(d)
    if (o < 6) d.execSQL("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, enabled INTEGER, ts INTEGER)")
    if (o < 8) {
      d.execSQL("CREATE TABLE IF NOT EXISTS reply_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, apps TEXT, senders TEXT, blocked_senders TEXT, chat_type TEXT, keywords TEXT, quiet_start TEXT, quiet_end TEXT, screen_off_only INTEGER, mode TEXT, instruction TEXT, ts INTEGER)")
      d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('shop')")
      try { d.execSQL("ALTER TABLE learned ADD COLUMN last_used INTEGER") } catch (_: Exception) {}
      try { d.execSQL("UPDATE learned SET last_used=ts WHERE last_used IS NULL OR last_used=0") } catch (_: Exception) {}
    }
    if (o < 9) {
      try { d.execSQL("ALTER TABLE reply_rules ADD COLUMN confidence_threshold INTEGER DEFAULT 75") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE reply_rules ADD COLUMN rate_limit_per_hour INTEGER DEFAULT 6") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE reply_rules ADD COLUMN loop_window_min INTEGER DEFAULT 10") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE reply_rules ADD COLUMN vip_senders TEXT DEFAULT ''") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE reply_rules ADD COLUMN mute_senders TEXT DEFAULT ''") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE learned ADD COLUMN pinned INTEGER DEFAULT 0") } catch (_: Exception) {}
      try { d.execSQL("ALTER TABLE learned ADD COLUMN source TEXT DEFAULT ''") } catch (_: Exception) {}
      try { d.execSQL("CREATE TABLE IF NOT EXISTS auto_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, trigger TEXT, condition TEXT, action TEXT, cooldown_ms INTEGER, last_fired_ms INTEGER, ts INTEGER)") } catch (_: Exception) {}
      try { d.execSQL("CREATE TABLE IF NOT EXISTS reply_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT, notif_key TEXT, sender TEXT, pkg TEXT, incoming TEXT, draft TEXT, reason TEXT, ts INTEGER)") } catch (_: Exception) {}
      try { d.execSQL("CREATE TABLE IF NOT EXISTS action_verifications(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, verified INTEGER, retry_count INTEGER, note TEXT, ts INTEGER)") } catch (_: Exception) {}
      d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('mistakes_and_lessons')")
    }
    if (o < 10) createSessionTables(d)
    if (o < 11) {
      // v11: remove confidence/rate/loop from reply_rules. Rebuild table for schema cleanliness.
      try { d.execSQL("ALTER TABLE reply_rules RENAME TO reply_rules_old") } catch (_: Exception) {}
      d.execSQL("CREATE TABLE IF NOT EXISTS reply_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, apps TEXT, senders TEXT, blocked_senders TEXT, chat_type TEXT, keywords TEXT, quiet_start TEXT, quiet_end TEXT, screen_off_only INTEGER, mode TEXT, instruction TEXT, ts INTEGER, vip_senders TEXT DEFAULT '', mute_senders TEXT DEFAULT '')")
      try {
        d.execSQL("INSERT INTO reply_rules(id,name,enabled,apps,senders,blocked_senders,chat_type,keywords,quiet_start,quiet_end,screen_off_only,mode,instruction,ts,vip_senders,mute_senders) SELECT id,name,enabled,apps,senders,blocked_senders,chat_type,keywords,quiet_start,quiet_end,screen_off_only,mode,instruction,ts,COALESCE(vip_senders,''),COALESCE(mute_senders,'') FROM reply_rules_old")
      } catch (_: Exception) {}
      try { d.execSQL("DROP TABLE IF EXISTS reply_rules_old") } catch (_: Exception) {}
      try { d.execSQL("DROP TABLE IF EXISTS reply_drafts") } catch (_: Exception) {}
      d.execSQL("CREATE TABLE IF NOT EXISTS reply_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT, notif_key TEXT, sender TEXT, pkg TEXT, incoming TEXT, draft TEXT, reason TEXT, ts INTEGER)")
    }
  }
  private fun createTaskSteps(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE IF NOT EXISTS task_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, ts INTEGER)")
    d.execSQL("CREATE INDEX IF NOT EXISTS idx_task_steps_goal ON task_steps(goal)")
    d.execSQL("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, enabled INTEGER, ts INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS reply_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, apps TEXT, senders TEXT, blocked_senders TEXT, chat_type TEXT, keywords TEXT, quiet_start TEXT, quiet_end TEXT, screen_off_only INTEGER, mode TEXT, instruction TEXT, ts INTEGER, vip_senders TEXT DEFAULT '', mute_senders TEXT DEFAULT '')")
    d.execSQL("CREATE TABLE IF NOT EXISTS auto_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, trigger TEXT, condition TEXT, action TEXT, cooldown_ms INTEGER, last_fired_ms INTEGER, ts INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS reply_drafts(id INTEGER PRIMARY KEY AUTOINCREMENT, notif_key TEXT, sender TEXT, pkg TEXT, incoming TEXT, draft TEXT, reason TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS action_verifications(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, verified INTEGER, retry_count INTEGER, note TEXT, ts INTEGER)")
  }

  private fun createSessionTables(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE IF NOT EXISTS session_state(id INTEGER PRIMARY KEY, session_id TEXT, status TEXT, active_goal TEXT, last_step TEXT, pending TEXT, last_error TEXT, updated_ts INTEGER)")
    d.execSQL("INSERT OR IGNORE INTO session_state(id,session_id,status,active_goal,last_step,pending,last_error,updated_ts) VALUES (1,'','idle','','','','',0)")
  }

  // ---------- chat ----------
  fun chatInsert(role: String, text: String, ts: Long): Long = writableDatabase.insert("chat", null,
    ContentValues().apply { put("role", role); put("text", text); put("ts", ts) })
  fun chatUpdate(id: Long, text: String) { writableDatabase.update("chat", ContentValues().apply { put("text", text) }, "id=?", arrayOf(id.toString())) }
  /** Newest [n] messages older than [beforeId] (0 = from the very end), returned oldest-first. */
  fun chatBefore(beforeId: Long, n: Int): List<Msg> {
    val out = ArrayList<Msg>()
    val where = if (beforeId > 0) "id<?" else null
    val args = if (beforeId > 0) arrayOf(beforeId.toString()) else null
    readableDatabase.query("chat", arrayOf("id", "role", "text", "ts"), where, args, null, null, "id DESC", n.toString()).use {
      while (it.moveToNext()) out.add(Msg(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3)))
    }
    out.reverse(); return out
  }
  fun chatRecent(n: Int = 40): List<Msg> = chatBefore(0, n.coerceIn(1, 200))
  fun chatClear() { writableDatabase.delete("chat", null, null) }

  // ---------- session continuity ----------
  fun sessionState(): SessionState {
    readableDatabase.rawQuery("SELECT session_id,status,active_goal,last_step,pending,last_error,updated_ts FROM session_state WHERE id=1", null).use {
      if (it.moveToFirst()) return SessionState(it.getString(0) ?: "", it.getString(1) ?: "idle", it.getString(2) ?: "", it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "", it.getLong(6))
    }
    return SessionState("", "idle", "", "", "", "", 0L)
  }
  fun sessionStateSave(sessionId: String? = null, status: String? = null, activeGoal: String? = null, lastStep: String? = null, pending: String? = null, lastError: String? = null) {
    val cur = sessionState()
    val cv = ContentValues().apply {
      put("id", 1)
      put("session_id", (sessionId ?: cur.sessionId).take(120))
      put("status", (status ?: cur.status).ifBlank { "idle" }.take(40))
      put("active_goal", (activeGoal ?: cur.activeGoal).take(400))
      put("last_step", (lastStep ?: cur.lastStep).take(600))
      put("pending", (pending ?: cur.pending).take(600))
      put("last_error", (lastError ?: cur.lastError).take(500))
      put("updated_ts", System.currentTimeMillis())
    }
    writableDatabase.insertWithOnConflict("session_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
  }
  fun sessionStateClear(keepSessionId: Boolean = false) {
    val sid = if (keepSessionId) sessionState().sessionId else ""
    sessionStateSave(sessionId = sid, status = "idle", activeGoal = "", lastStep = "", pending = "", lastError = "")
  }
  fun recentSessionPrompt(limit: Int = 12): String {
    if (!Prefs.on(appCtx, "f_session_continuity", true)) return ""
    val st = sessionState()
    val msgs = chatRecent(limit).filter { it.role == "you" || it.role == "saara" || it.role == "system" }
    val now = System.currentTimeMillis()
    val recent = st.updatedTs > 0 && now - st.updatedTs < 6L * 60 * 60 * 1000
    val hasActive = recent && (st.activeGoal.isNotBlank() || st.pending.isNotBlank() || st.lastError.isNotBlank())
    if (msgs.isEmpty() && !hasActive) return ""
    val sb = StringBuilder("\nRECENT SESSION CONTINUITY (local, private; use to continue seamlessly without asking user to repeat):\n")
    if (recent && st.sessionId.isNotBlank()) sb.append("Session: ").append(st.sessionId.take(40)).append(" · status=").append(st.status).append('\n')
    if (hasActive) {
      if (st.activeGoal.isNotBlank()) sb.append("Active goal: ").append(compactFactText(st.activeGoal, 180)).append('\n')
      if (st.lastStep.isNotBlank()) sb.append("Last completed/attempted step: ").append(compactFactText(st.lastStep, 180)).append('\n')
      if (st.pending.isNotBlank()) sb.append("Pending/next step: ").append(compactFactText(st.pending, 180)).append('\n')
      if (st.lastError.isNotBlank()) sb.append("Last interruption/error: ").append(compactFactText(st.lastError, 180)).append('\n')
      sb.append("Resume rule: continue from pending/next step unless user changed topic. On reconnect/network error, acknowledge briefly and resume.\n")
    }
    if (msgs.isNotEmpty()) {
      sb.append("Recent chat turns:\n")
      msgs.takeLast(limit).forEach { m ->
        val role = when (m.role) { "you" -> "User"; "saara" -> "Saara"; "system" -> "System"; else -> m.role }
        sb.append("- ").append(role).append(": ").append(compactFactText(m.text, 160)).append('\n')
      }
    }
    return sb.toString().take(2600)
  }

  // ---------- learned facts ----------
  private fun compactFactText(text: String, max: Int = 180): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
      .replace(Regex("^(user|the user|i)\\s+(likes|prefers|wants|said|is|has)\\s+", RegexOption.IGNORE_CASE), "")
      .trim(' ', '-', ':', '.', '•')
    if (clean.isBlank()) return ""
    return clean.take(max).trim()
  }
  private fun normalizeCat(cat: String): String {
    val c = cat.trim().lowercase()
    return when {
      c.isBlank() -> "general"
      c.contains("personal") || c.contains("person") || c.contains("profile") -> "personal"
      c.contains("work") || c.contains("office") || c.contains("biz") || c.contains("business") -> "work"
      c.contains("shop") || c.contains("ledger") || c.contains("product") || c.contains("price") -> "shop"
      c.contains("prefer") || c.contains("habit") || c.contains("routine") || c.contains("style") || c.contains("like") -> "preferences"
      else -> c.replace(Regex("[^a-z0-9_ -]"), "").trim().ifBlank { "general" }
    }
  }
  private fun factHead(text: String): String {
    val t = compactFactText(text, 220).lowercase()
    val key = t.substringBefore(":").substringBefore(" - ").substringBefore("=").trim()
    val head = key.split(Regex("\\s+")).take(4).joinToString(" ")
    return head.ifBlank { t.split(Regex("\\s+")).take(3).joinToString(" ") }
  }
  fun factAdd(cat: String, text: String, source: String = "user") {
    val t = compactFactText(text); if (t.isEmpty()) return
    val category = normalizeCat(cat)
    val now = System.currentTimeMillis()
    val d = writableDatabase
    categoryAdd(category)
    val existing = facts().firstOrNull { it.cat == category && (it.text.equals(t, true) || factHead(it.text) == factHead(t)) }
    if (existing != null) {
      val newer = now >= existing.ts
      val merged = if (newer || existing.text.length < 24) t else existing.text
      d.update("learned", ContentValues().apply {
        put("cat", category); put("text", merged); put("ts", maxOf(now, existing.ts)); put("last_used", now)
        put("pinned", if (existing.pinned) 1 else 0); put("source", source.ifBlank { existing.source })
      }, "id=?", arrayOf(existing.id.toString()))
      return
    }
    d.insert("learned", null, ContentValues().apply { put("cat", category); put("text", t); put("ts", now); put("last_used", now); put("pinned", 0); put("source", source) })
  }
  private fun Fact.rowTs(): Long = if (ts > 0) ts else 0L
  private fun Fact.rowLastUsed(): Long = if (lastUsed > 0) lastUsed else ts
  fun facts(): List<Fact> {
    val out = ArrayList<Fact>()
    readableDatabase.rawQuery("SELECT id,cat,text,ts,COALESCE(last_used,ts,0),COALESCE(pinned,0),COALESCE(source,'') FROM learned ORDER BY ts DESC", null).use {
      while (it.moveToNext()) out.add(Fact(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3), it.getLong(4), it.getInt(5) == 1, it.getString(6) ?: ""))
    }
    return out
  }
  fun factDelete(id: Long) { writableDatabase.delete("learned", "id=?", arrayOf(id.toString())) }
  fun factUpdate(id: Long, cat: String, text: String) {
    val clean = compactFactText(text); if (clean.isEmpty()) return
    val category = normalizeCat(cat.ifBlank { "general" })
    categoryAdd(category)
    val now = System.currentTimeMillis()
    val existing = facts().firstOrNull { it.id == id }
    // conflict resolver: if another fact has same head in same category, keep newest and drop older duplicate
    facts().filter { it.id != id && normalizeCat(it.cat) == category && factHead(it.text) == factHead(clean) }.forEach { dup ->
      if (dup.ts <= now) factDelete(dup.id)
    }
    writableDatabase.update("learned", ContentValues().apply {
      put("cat", category); put("text", clean); put("ts", now); put("last_used", now)
      put("pinned", if (existing?.pinned == true) 1 else 0); put("source", existing?.source ?: "user")
    }, "id=?", arrayOf(id.toString()))
  }
  fun factSetPinned(id: Long, pinned: Boolean) { writableDatabase.update("learned", ContentValues().apply { put("pinned", if (pinned) 1 else 0) }, "id=?", arrayOf(id.toString())) }
  fun factConflicts(): List<List<Fact>> {
    return facts().groupBy { normalizeCat(it.cat) + "|" + factHead(it.text) }.values.filter { it.size > 1 }.map { g -> g.sortedByDescending { it.ts } }
  }
  fun factResolveKeep(keepId: Long) {
    val keep = facts().firstOrNull { it.id == keepId } ?: return
    facts().filter { it.id != keepId && normalizeCat(it.cat) == normalizeCat(keep.cat) && factHead(it.text) == factHead(keep.text) }.forEach { factDelete(it.id) }
  }
  fun categoryAdd(name: String) {
    val clean = normalizeCat(name); if (clean.isNotEmpty()) writableDatabase.insertWithOnConflict("categories", null, ContentValues().apply { put("name", clean) }, SQLiteDatabase.CONFLICT_IGNORE)
  }
  fun categories(): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
    facts().map { it.cat }.distinct().forEach { if (!out.contains(it)) out.add(it) }
    for (d in listOf("general", "personal", "preferences", "work", "shop", "mistakes_and_lessons")) if (!out.contains(d)) out.add(d)
    return out.distinct().sorted()
  }
  fun categoryRename(old: String, new: String) {
    val n = normalizeCat(new); if (n.isEmpty()) return
    categoryAdd(n); writableDatabase.update("learned", ContentValues().apply { put("cat", n) }, "cat=?", arrayOf(old))
    if (old !in setOf("general", "preference", "preferences", "work", "personal", "shop", "mistakes_and_lessons")) writableDatabase.delete("categories", "name=?", arrayOf(old))
  }
  fun categoryDelete(name: String) {
    writableDatabase.update("learned", ContentValues().apply { put("cat", "general") }, "cat=?", arrayOf(name))
    if (name !in setOf("general", "preference", "preferences", "work", "personal", "shop", "mistakes_and_lessons")) writableDatabase.delete("categories", "name=?", arrayOf(name))
  }
  fun recall(q: String, limit: Int = 12): List<Fact> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
    val now = System.currentTimeMillis()
    val pruneDays = try { Prefs.plain(appCtx).getInt("mem_prune_days", 3).coerceIn(1, 30) } catch (_: Exception) { 3 }
    val pruneEnabled = try { Prefs.on(appCtx, "f_mem_prune", true) } catch (_: Exception) { true }
    val cutoff = now - pruneDays.toLong() * 24L * 60 * 60 * 1000
    if (pruneEnabled) try { writableDatabase.delete("learned", "COALESCE(pinned,0)=0 AND ts < ? AND (last_used IS NULL OR last_used < ?)", arrayOf(cutoff.toString(), cutoff.toString())) } catch (_: Exception) {}
    val all = facts().sortedWith(compareByDescending<Fact> { it.pinned }.thenByDescending { it.ts }.thenByDescending { it.lastUsed })
    val selected = if (words.isEmpty()) all.take(limit)
      else all.map { f -> f to words.count { f.text.lowercase().contains(it) || f.cat.contains(it) } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<Fact,Int>> { it.second * 10 + (if (it.first.pinned) 5 else 0) + (if (it.first.ts > cutoff) 2 else 0) }.thenByDescending { it.first.ts })
        .take(limit).map { it.first }
    selected.forEach { f -> try { writableDatabase.update("learned", ContentValues().apply { put("last_used", now) }, "id=?", arrayOf(f.id.toString())) } catch (_: Exception) {} }
    return selected
  }
  fun factsPrompt(q: String = "", limit: Int = 18): String {
    val picked = recall(q, limit)
    if (picked.isEmpty()) return ""
    val sb = StringBuilder("\nKNOWN USER FACTS (concise, categorized; use only if relevant):\n")
    picked.groupBy { normalizeCat(it.cat) }.toSortedMap().forEach { (cat, items) ->
      sb.append(cat.uppercase()).append(":\n")
      items.take(6).forEachIndexed { i, f -> sb.append(i + 1).append(". ").append(compactFactText(f.text, 140)).append('\n') }
    }
    return sb.toString().take(2200)
  }

  // ---------- usage stats ----------
  fun usage(kind: String, name: String) {
    if (name.isBlank()) return
    writableDatabase.insert("usage", null, ContentValues().apply { put("kind", kind); put("name", name.lowercase().take(60)); put("ts", System.currentTimeMillis()) })
  }
  fun topUsage(kind: String, n: Int): List<Pair<String, Int>> {
    val out = ArrayList<Pair<String, Int>>()
    readableDatabase.rawQuery("SELECT name, COUNT(*) c FROM usage WHERE kind=? GROUP BY name ORDER BY c DESC LIMIT ?", arrayOf(kind, n.toString())).use {
      while (it.moveToNext()) out.add(it.getString(0) to it.getInt(1))
    }
    return out
  }
  fun usageHours(): IntArray {
    val h = IntArray(24)
    val cal = java.util.Calendar.getInstance()
    readableDatabase.query("usage", arrayOf("ts"), "kind='command'", null, null, null, null).use {
      while (it.moveToNext()) { cal.timeInMillis = it.getLong(0); h[cal.get(java.util.Calendar.HOUR_OF_DAY)]++ }
    }
    return h
  }

  // ---------- shop ledger ----------
  fun shopAdd(item: String, kind: String, price: Double, qty: Double, party: String, note: String): Long =
    writableDatabase.insert("shop", null, ContentValues().apply {
      put("item", item.trim()); put("kind", kind.trim().lowercase()); put("price", price); put("qty", qty)
      put("party", party.trim()); put("note", note.trim()); put("ts", System.currentTimeMillis())
    })
  fun shopDelete(id: Long) { writableDatabase.delete("shop", "id=?", arrayOf(id.toString())) }
  fun shopRows(item: String = "", limit: Int = 300): List<ShopRow> {
    val out = ArrayList<ShopRow>()
    val where = if (item.isBlank()) null else "LOWER(item) LIKE ?"
    val args = if (item.isBlank()) null else arrayOf("%" + item.trim().lowercase() + "%")
    readableDatabase.query("shop", arrayOf("id", "item", "kind", "price", "qty", "party", "note", "ts"), where, args, null, null, "ts DESC", limit.toString()).use {
      while (it.moveToNext()) out.add(ShopRow(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getDouble(3), it.getDouble(4),
        it.getString(5) ?: "", it.getString(6) ?: "", it.getLong(7)))
    }
    return out
  }
  fun shopSummary(item: String = "", recent: Int = 10): Pair<List<ShopRow>, String> {
    val rows = shopRows(item, 5000)
    val lim = try { Prefs.plain(appCtx).getInt("ledger_recent_n", recent).coerceIn(1, 100) } catch (_: Exception) { recent }
    val recentRows = rows.take(lim)
    val older = (rows.size - recentRows.size).coerceAtLeast(0)
    val summary = if (older > 0) "User has ${rows.size} total shop records; showing $recent most recent, $older older records summarized." else "Showing ${rows.size} shop records."
    return recentRows to summary
  }

  // ---------- scheduled tasks ----------
  fun taskAdd(whenMs: Long, text: String, repeatMs: Long): Long = writableDatabase.insert("tasks", null,
    ContentValues().apply { put("whenms", whenMs); put("text", text); put("repeatms", repeatMs) })
  fun taskUpdateWhen(id: Long, whenMs: Long) { writableDatabase.update("tasks", ContentValues().apply { put("whenms", whenMs) }, "id=?", arrayOf(id.toString())) }
  fun taskDelete(id: Long) { writableDatabase.delete("tasks", "id=?", arrayOf(id.toString())) }
  fun tasks(): List<TaskRow> {
    val out = ArrayList<TaskRow>()
    readableDatabase.query("tasks", arrayOf("id", "whenms", "text", "repeatms"), null, null, null, null, "whenms ASC").use {
      while (it.moveToNext()) out.add(TaskRow(it.getLong(0), it.getLong(1), it.getString(2) ?: "", it.getLong(3)))
    }
    return out
  }
  fun task(id: Long): TaskRow? = tasks().firstOrNull { it.id == id }


  // ---------- rules (applied before actions) ----------
  fun rules(): List<Rule> {
    val out = mutableListOf<Rule>()
    val c = readableDatabase.rawQuery("SELECT id,text,enabled,ts FROM rules ORDER BY id ASC", null)
    c.use { while (it.moveToNext()) out.add(Rule(it.getLong(0), it.getString(1), it.getInt(2) == 1, it.getLong(3))) }
    return out
  }
  fun ruleAdd(text: String): Long {
    val t = text.trim().take(500); if (t.isEmpty()) return -1L
    val ts = System.currentTimeMillis()
    return writableDatabase.insert("rules", null, ContentValues().apply { put("text", t); put("enabled", 1); put("ts", ts) })
  }
  fun ruleUpdate(id: Long, text: String) {
    val t = text.trim().take(500); if (t.isEmpty()) return
    val ts = System.currentTimeMillis()
    writableDatabase.update("rules", ContentValues().apply { put("text", t); put("ts", ts) }, "id=?", arrayOf(id.toString()))
  }
  fun ruleSetEnabled(id: Long, enabled: Boolean) {
    val ts = System.currentTimeMillis()
    writableDatabase.update("rules", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("ts", ts) }, "id=?", arrayOf(id.toString()))
  }
  fun ruleDelete(id: Long) {
    writableDatabase.delete("rules", "id=?", arrayOf(id.toString()))
  }
  fun rulesPrompt(max: Int = 40): String {
    val rs = rules().filter { it.enabled && it.text.isNotBlank() }.take(max)
    val lessons = recall("mistake lesson guardrail failure", 6).filter { it.cat == "mistakes_and_lessons" }
    if (rs.isEmpty() && lessons.isEmpty()) return ""
    val sb = StringBuilder("\nUSER RULES (must follow before action/tool/response):\n")
    rs.forEachIndexed { i: Int, r: Rule -> sb.append(i + 1).append(". ").append(compactFactText(r.text, 220)).append('\n') }
    if (lessons.isNotEmpty()) {
      sb.append("LESSONS / GUARDRAILS (avoid repeating mistakes):\n")
      lessons.forEachIndexed { i, f -> sb.append(i + 1).append(". ").append(compactFactText(f.text, 180)).append('\n') }
    }
    return sb.toString().take(2800)
  }

  // ---------- replying rules ----------
  fun replyRules(): List<ReplyRule> {
    val out = mutableListOf<ReplyRule>()
    readableDatabase.rawQuery("SELECT id,name,enabled,apps,senders,blocked_senders,chat_type,keywords,quiet_start,quiet_end,screen_off_only,mode,instruction,ts,COALESCE(vip_senders,''),COALESCE(mute_senders,'') FROM reply_rules ORDER BY id ASC", null).use {
      while (it.moveToNext()) out.add(ReplyRule(it.getLong(0), it.getString(1) ?: "", it.getInt(2) == 1, it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "", it.getString(6) ?: "any", it.getString(7) ?: "", it.getString(8) ?: "", it.getString(9) ?: "", it.getInt(10) == 1, it.getString(11) ?: "ai", it.getString(12) ?: "", it.getLong(13), it.getString(14) ?: "", it.getString(15) ?: ""))
    }
    return out
  }
  fun replyRuleAdd(r: ReplyRule): Long = writableDatabase.insert("reply_rules", null, replyRuleCv(r))
  fun replyRuleUpdate(r: ReplyRule) { writableDatabase.update("reply_rules", replyRuleCv(r), "id=?", arrayOf(r.id.toString())) }
  fun replyRuleDelete(id: Long) { writableDatabase.delete("reply_rules", "id=?", arrayOf(id.toString())) }
  fun replyRuleSetEnabled(id: Long, enabled: Boolean) { writableDatabase.update("reply_rules", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("ts", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString())) }
  private fun replyRuleCv(r: ReplyRule) = ContentValues().apply {
    put("name", r.name.trim().take(80)); put("enabled", if (r.enabled) 1 else 0); put("apps", r.apps.trim()); put("senders", r.senders.trim())
    put("blocked_senders", r.blockedSenders.trim()); put("chat_type", r.chatType.trim().ifBlank { "any" }); put("keywords", r.keywords.trim())
    put("quiet_start", r.quietStart.trim()); put("quiet_end", r.quietEnd.trim()); put("screen_off_only", if (r.screenOffOnly) 1 else 0)
    put("mode", r.mode.trim().ifBlank { "ai" }); put("instruction", r.instruction.trim().take(500)); put("ts", System.currentTimeMillis())
    put("vip_senders", r.vipSenders.trim()); put("mute_senders", r.muteSenders.trim())
  }

  // ---------- task-step memory ----------
  fun taskStepAdd(goal: String, tool: String, args: String, result: String, ok: Boolean): Long {
    val d = writableDatabase
    val id = d.insert("task_steps", null, ContentValues().apply {
      put("goal", goal.trim()); put("tool", tool.trim()); put("args", args.trim()); put("result", result.trim())
      put("ok", if (ok) 1 else 0); put("ts", System.currentTimeMillis())
    })
    try {
      d.execSQL("DELETE FROM task_steps WHERE id NOT IN (SELECT id FROM task_steps ORDER BY id DESC LIMIT 300)")
    } catch (_: Exception) {}
    return id
  }
  fun taskSteps(limit: Int = 80): List<TaskStep> {
    val out = ArrayList<TaskStep>()
    readableDatabase.query("task_steps", arrayOf("id", "goal", "tool", "args", "result", "ok", "ts"), null, null, null, null, "id DESC", limit.toString()).use {
      while (it.moveToNext()) out.add(TaskStep(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "",
        it.getString(4) ?: "", it.getInt(5) != 0, it.getLong(6)))
    }
    return out
  }
  fun taskStepsClear() { writableDatabase.delete("task_steps", null, null) }
  fun taskStepsFor(q: String, limit: Int = 12): List<TaskStep> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
    val recent = taskSteps(300)
    if (words.isEmpty()) return recent.take(limit)
    return recent.map { s -> s to words.count { s.goal.lowercase().contains(it) || s.tool.lowercase().contains(it) || s.result.lowercase().contains(it) } }
      .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
  }

  // ---------- auto rules ----------
  fun autoRules(): List<AutoRule> {
    val out = mutableListOf<AutoRule>()
    readableDatabase.rawQuery("SELECT id,name,enabled,trigger,condition,action,cooldown_ms,last_fired_ms,ts FROM auto_rules ORDER BY id ASC", null).use {
      while (it.moveToNext()) out.add(AutoRule(it.getLong(0), it.getString(1) ?: "", it.getInt(2) == 1, it.getString(3) ?: "time", it.getString(4) ?: "", it.getString(5) ?: "", it.getLong(6), it.getLong(7), it.getLong(8)))
    }
    return out
  }
  fun autoRuleAdd(r: AutoRule): Long = writableDatabase.insert("auto_rules", null, autoRuleCv(r))
  fun autoRuleUpdate(r: AutoRule) { writableDatabase.update("auto_rules", autoRuleCv(r), "id=?", arrayOf(r.id.toString())) }
  fun autoRuleDelete(id: Long) { writableDatabase.delete("auto_rules", "id=?", arrayOf(id.toString())) }
  fun autoRuleSetEnabled(id: Long, enabled: Boolean) { writableDatabase.update("auto_rules", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("ts", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString())) }
  fun autoRuleMarkFired(id: Long, ts: Long = System.currentTimeMillis()) { writableDatabase.update("auto_rules", ContentValues().apply { put("last_fired_ms", ts) }, "id=?", arrayOf(id.toString())) }
  private fun autoRuleCv(r: AutoRule) = ContentValues().apply {
    put("name", r.name.trim().take(80)); put("enabled", if (r.enabled) 1 else 0); put("trigger", r.trigger.trim().ifBlank { "time" })
    put("condition", r.condition.trim().take(500)); put("action", r.action.trim().take(800))
    put("cooldown_ms", r.cooldownMs.coerceAtLeast(0L)); put("last_fired_ms", r.lastFiredMs); put("ts", System.currentTimeMillis())
  }



  // ---------- post-task verification ----------
  fun actionVerificationAdd(goal: String, tool: String, args: String, result: String, ok: Boolean, verified: Boolean, retryCount: Int, note: String): Long {
    val id = writableDatabase.insert("action_verifications", null, ContentValues().apply {
      put("goal", goal.take(220)); put("tool", tool.take(80)); put("args", args.take(400)); put("result", result.take(800))
      put("ok", if (ok) 1 else 0); put("verified", if (verified) 1 else 0); put("retry_count", retryCount); put("note", note.take(300)); put("ts", System.currentTimeMillis())
    })
    try { writableDatabase.execSQL("DELETE FROM action_verifications WHERE id NOT IN (SELECT id FROM action_verifications ORDER BY id DESC LIMIT 200)") } catch (_: Exception) {}
    return id
  }
  fun actionVerifications(limit: Int = 40): List<ActionVerification> {
    val out = mutableListOf<ActionVerification>()
    readableDatabase.rawQuery("SELECT id,goal,tool,args,result,ok,verified,retry_count,note,ts FROM action_verifications ORDER BY id DESC LIMIT ${limit.coerceIn(1,200)}", null).use {
      while (it.moveToNext()) out.add(ActionVerification(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "", it.getString(4) ?: "", it.getInt(5) == 1, it.getInt(6) == 1, it.getInt(7), it.getString(8) ?: "", it.getLong(9)))
    }
    return out
  }
}

