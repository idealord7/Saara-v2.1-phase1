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

/** Local SQLite store: chat history, learned facts, shop ledger, usage stats, scheduled tasks, task steps. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "saara.db", null, 6) {
  private val appCtx = c.applicationContext
  companion object {
    @Volatile private var inst: Db? = null
    fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c).also { inst = it } }
  }
  override fun onCreate(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE learned(id INTEGER PRIMARY KEY AUTOINCREMENT, cat TEXT, text TEXT, ts INTEGER)")
    d.execSQL("CREATE TABLE categories(name TEXT PRIMARY KEY)")
    d.execSQL("INSERT OR IGNORE INTO categories(name) VALUES ('general'), ('preference'), ('work'), ('personal')")
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
  }
  private fun createTaskSteps(d: SQLiteDatabase) {
    d.execSQL("CREATE TABLE IF NOT EXISTS task_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, tool TEXT, args TEXT, result TEXT, ok INTEGER, ts INTEGER)")
    d.execSQL("CREATE INDEX IF NOT EXISTS idx_task_steps_goal ON task_steps(goal)")
    d.execSQL("CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, enabled INTEGER, ts INTEGER)")
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
  fun factAdd(cat: String, text: String) {
    val t = text.trim(); if (t.isEmpty()) return
    val d = writableDatabase
    d.query("learned", arrayOf("id"), "text=?", arrayOf(t), null, null, null).use { if (it.moveToFirst()) return }
    d.insert("learned", null, ContentValues().apply { put("cat", cat.ifBlank { "general" }.lowercase()); put("text", t); put("ts", System.currentTimeMillis()) })
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
    val clean = text.trim(); if (clean.isEmpty()) return
    val category = cat.trim().ifBlank { "general" }.lowercase()
    categoryAdd(category)
    writableDatabase.update("learned", ContentValues().apply { put("cat", category); put("text", clean) }, "id=?", arrayOf(id.toString()))
  }
  fun categoryAdd(name: String) {
    val clean = name.trim().lowercase(); if (clean.isNotEmpty()) writableDatabase.insertWithOnConflict("categories", null, ContentValues().apply { put("name", clean) }, SQLiteDatabase.CONFLICT_IGNORE)
  }
  fun categories(): List<String> {
    val out = mutableListOf<String>()
    readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
    facts().map { it.cat }.distinct().forEach { if (!out.contains(it)) out.add(it) }
    return out.distinct().sorted()
  }
  fun categoryRename(old: String, new: String) {
    val n = new.trim().lowercase(); if (n.isEmpty()) return
    categoryAdd(n); writableDatabase.update("learned", ContentValues().apply { put("cat", n) }, "cat=?", arrayOf(old))
    if (old != "general" && old != "preference" && old != "work" && old != "personal") writableDatabase.delete("categories", "name=?", arrayOf(old))
  }
  fun categoryDelete(name: String) {
    writableDatabase.update("learned", ContentValues().apply { put("cat", "general") }, "cat=?", arrayOf(name))
    if (name != "general" && name != "preference" && name != "work" && name != "personal") writableDatabase.delete("categories", "name=?", arrayOf(name))
  }
  fun recall(q: String, limit: Int = 12): List<Fact> {
    val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
    if (words.isEmpty()) return facts().take(limit)
    return facts().map { f -> f to words.count { f.text.lowercase().contains(it) || f.cat.contains(it) } }
      .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
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
    val id = writableDatabase.insert("rules", null, ContentValues().apply { put("text", t); put("enabled", 1); put("ts", ts) })
    if (id > 0) syncRule(Rule(id, t, true, ts))
    return id
  }
  fun ruleUpdate(id: Long, text: String) {
    val t = text.trim().take(500); if (t.isEmpty()) return
    val ts = System.currentTimeMillis()
    writableDatabase.update("rules", ContentValues().apply { put("text", t); put("ts", ts) }, "id=?", arrayOf(id.toString()))
    val r = rules().firstOrNull { it.id == id }
    if (r != null) syncRule(r.copy(text = t, ts = ts)) else syncRule(Rule(id, t, true, ts))
  }
  fun ruleSetEnabled(id: Long, enabled: Boolean) {
    val ts = System.currentTimeMillis()
    writableDatabase.update("rules", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("ts", ts) }, "id=?", arrayOf(id.toString()))
    rules().firstOrNull { it.id == id }?.let { syncRule(it) }
  }
  fun ruleDelete(id: Long) {
    writableDatabase.delete("rules", "id=?", arrayOf(id.toString()))
    try { CloudSyncRepository.get(appCtx).deleteRule(id) } catch (_: Exception) {}
  }
  fun rulesPrompt(max: Int = 40): String {
    val rs = rules().filter { it.enabled && it.text.isNotBlank() }.take(max)
    if (rs.isEmpty()) return ""
    val sb = StringBuilder("\nUser rules (must follow before taking any action/tool/response):\n")
    rs.forEachIndexed { i: Int, r: Rule -> sb.append(i + 1).append(". ").append(r.text.replace('\n', ' ').take(220)).append('\n') }
    return sb.toString()
  }

  private fun syncRule(r: Rule) {
    try { CloudSyncRepository.get(appCtx).uploadRule(r) } catch (_: Exception) {}
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
