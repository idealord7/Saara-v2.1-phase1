package com.saara.assistant
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Msg(var id: Long, val role: String, var text: String, val ts: Long)
data class Fact(val id: Long, val cat: String, val text: String, val ts: Long)
data class ShopRow(val id: Long, val item: String, val kind: String, val price: Double, val qty: Double, val party: String, val note: String, val ts: Long)
data class TaskRow(val id: Long, val whenMs: Long, val text: String, val repeatMs: Long)
data class TaskStep(val id: Long, val goal: String, val tool: String, val args: String, val result: String, val ok: Boolean, val ts: Long)
data class Rule(val id: Long, val text: String, val enabled: Boolean, val ts: Long)
data class ReplyRule(val id: Long, val name: String, val enabled: Boolean, val apps: String, val senders: String, val blockedSenders: String, val chatType: String, val keywords: String, val quietStart: String, val quietEnd: String, val screenOffOnly: Boolean, val mode: String, val instruction: String, val ts: Long)

/** Local SQLite store: chat history, learned facts, shop ledger, usage stats, scheduled tasks, task steps. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "saara.db", null, 8) {
  private val appCtx = c.applicationContext
  companion object {
    @Volatile private var inst: Db? = null
    fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c).also { inst = it } }
  }
  override fun onCreate(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE learned(id INTEGER PRIMARY KEY AUTOINCREMENT, cat TEXT, text TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE categories(name TEXT PRIMARY KEY)")
    d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('general'), ('preference'), ('work'), ('personal'), ('shop')")
    d.execSQL("CREATE TABLE shop(id INTEGER PRIMARY KEY AUTOINCREMENT, item TEXT, kind TEXT, price REAL, qty REAL, party TEXT, note TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE usage(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, name TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, whenms INTEGER, text TEXT, repeatms INTEGER)")
    createTaskSteps(d)
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
  }
  private fun createTaskSteps(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE IF NOT EXISTS task_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, ts INTEGER)")
    d.execSQL("CREATE INDEX IF NOT EXISTS idx_task_steps_goal ON task_steps(goal)")
    d.execSQL("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, enabled INTEGER, ts INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS reply_rules(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, enabled INTEGER, apps TEXT, senders TEXT, blocked_senders TEXT, chat_type TEXT, keywords TEXT, quiet_start TEXT, quiet_end TEXT, screen_off_only INTEGER, mode TEXT, instruction TEXT, ts INTEGER)")
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
  fun chatClear() { writableDatabase.delete("chat", null, null) }

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
  fun factAdd(cat: String, text: String) {
    val t = compactFactText(text); if (t.isEmpty()) return
    val category = normalizeCat(cat)
    val now = System.currentTimeMillis()
    val d = writableDatabase
    categoryAdd(category)
    val existing = facts().firstOrNull { it.cat == category && (it.text.equals(t, true) || factHead(it.text) == factHead(t)) }
    if (existing != null) {
      d.update("learned", ContentValues().apply { put("cat", category); put("text", t); put("ts", now); put("last_used", now) }, "id=?", arrayOf(existing.id.toString()))
      return
    }
    d.insert("learned", null, ContentValues().apply { put("cat", category); put("text", t); put("ts", now); put("last_used", now) })
  }
  fun facts(): List<Fact> {
    val out = ArrayList<Fact>()
    readableDatabase.query("learned", arrayOf("id", "cat", "text", "ts"), null, null, null, null, "ts DESC").use {
      while (it.moveToNext()) out.add(Fact(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3)))
    }
    return out
  }
  fun factDelete(id: Long) { writableDatabase.delete("learned", "id=?", arrayOf(id.toString())) }
  fun factUpdate(id: Long, cat: String, text: String) {
    val clean = compactFactText(text); if (clean.isEmpty()) return
    val category = normalizeCat(cat.ifBlank { "general" })
    categoryAdd(category)
    writableDatabase.update("learned", ContentValues().apply { put("cat", category); put("text", clean); put("ts", System.currentTimeMillis()); put("last_used", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
  }
  fun categoryAdd(name: String) {
    val clean = normalizeCat(name); if (clean.isNotEmpty()) writableDatabase.insertWithOnConflict("categories", null, ContentValues().apply { put("name", clean) }, SQLiteDatabase.CONFLICT_IGNORE)
  }
  fun categories(): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
    facts().map { it.cat }.distinct().forEach { if (!out.contains(it)) out.add(it) }
    for (d in listOf("general", "personal", "preferences", "work", "shop")) if (!out.contains(d)) out.add(d)
    return out.distinct().sorted()
  }
  fun categoryRename(old: String, new: String) {
    val n = normalizeCat(new); if (n.isEmpty()) return
    categoryAdd(n); writableDatabase.update("learned", ContentValues().apply { put("cat", n) }, "cat=?", arrayOf(old))
    if (old !in setOf("general", "preference", "preferences", "work", "personal", "shop")) writableDatabase.delete("categories", "name=?", arrayOf(old))
  }
  fun categoryDelete(name: String) {
    writableDatabase.update("learned", ContentValues().apply { put("cat", "general") }, "cat=?", arrayOf(name))
    if (name !in setOf("general", "preference", "preferences", "work", "personal", "shop")) writableDatabase.delete("categories", "name=?", arrayOf(name))
  }
  fun recall(q: String, limit: Int = 12): List<Fact> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
    val now = System.currentTimeMillis()
    val cutoff = now - 3L * 24 * 60 * 60 * 1000
    try { writableDatabase.delete("learned", "ts < ? AND (last_used IS NULL OR last_used < ?)", arrayOf(cutoff.toString(), cutoff.toString())) } catch (_: Exception) {}
    val all = facts()
    val selected = if (words.isEmpty()) all.take(limit)
      else all.map { f -> f to words.count { f.text.lowercase().contains(it) || f.cat.contains(it) } }
        .filter { it.second > 0 }.sortedByDescending { it.second * 10 + (if (it.first.ts > cutoff) 2 else 0) }.take(limit).map { it.first }
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
    val recentRows = rows.take(recent)
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
    if (rs.isEmpty()) return ""
    val sb = StringBuilder("\nUSER RULES (must follow before action/tool/response):\n")
    rs.forEachIndexed { i: Int, r: Rule -> sb.append(i + 1).append(". ").append(compactFactText(r.text, 220)).append('\n') }
    return sb.toString()
  }

  // ---------- replying rules ----------
  fun replyRules(): List<ReplyRule> {
    val out = mutableListOf<ReplyRule>()
    readableDatabase.rawQuery("SELECT id,name,enabled,apps,senders,blocked_senders,chat_type,keywords,quiet_start,quiet_end,screen_off_only,mode,instruction,ts FROM reply_rules ORDER BY id ASC", null).use {
      while (it.moveToNext()) out.add(ReplyRule(it.getLong(0), it.getString(1) ?: "", it.getInt(2) == 1, it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "", it.getString(6) ?: "any", it.getString(7) ?: "", it.getString(8) ?: "", it.getString(9) ?: "", it.getInt(10) == 1, it.getString(11) ?: "ai", it.getString(12) ?: "", it.getLong(13)))
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
    return recent.map { s -> s to words.count { s.goal.lowercase().contains(it) } }
      .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
  }
}
