package com.saara.assistant

import android.content.ContentValues
import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.util.UUID

data class KnowledgeItem(
  val id: String = UUID.randomUUID().toString(),
  val title: String = "",
  val category: String = "",
  val content: String = "",
  val tags: List<String> = emptyList(),
  val updatedAt: Long = System.currentTimeMillis(),
)
data class MemoryItem(
  val id: String = UUID.randomUUID().toString(),
  val summary: String = "",
  val contextApp: String = "",
  val importance: Int = 1,
  val createdAt: Long = System.currentTimeMillis(),
)
data class TaskPlaybook(
  val id: String = UUID.randomUUID().toString(),
  val taskName: String = "",
  val triggerPattern: String = "",
  val steps: List<String> = emptyList(),
  val successCount: Int = 0,
  val requiresConfirmation: Boolean = false,
  val updatedAt: Long = System.currentTimeMillis(),
)
data class MistakeLesson(
  val id: String = UUID.randomUUID().toString(),
  val mistakeSummary: String = "",
  val whatHappened: String = "",
  val wrongBehavior: String = "",
  val correctRule: String = "",
  val occurrenceCount: Int = 1,
  val active: Boolean = true,
  val updatedAt: Long = System.currentTimeMillis(),
)
data class CloudScheduledTask(
  val id: String = UUID.randomUUID().toString(),
  val title: String = "",
  val instruction: String = "",
  val scheduleType: String = "once", // once|hourly|daily|weekly
  val nextRunAt: Long = 0L,
  val status: String = "pending", // pending|done|cancelled
  val updatedAt: Long = System.currentTimeMillis(),
)

object CloudMemory {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var fs: FirebaseFirestore? = null
  private fun db(): FirebaseFirestore = fs ?: FirebaseFirestore.getInstance().also { fs = it }

  private fun col(uid: String, name: String) = db().collection("users").document(uid).collection(name)

  private fun <T> withUid(ctx: Context, onError: ((Exception)->Unit)? = null, block: suspend (String) -> T) {
    AuthManager.ensureSignedIn({ uid ->
      scope.launch {
        try { block(uid) } catch (e: Exception) { Crash.save(ctx, e, "CloudMemory"); onError?.invoke(e) }
      }
    }, { e -> onError?.invoke(e) })
  }

  private fun mapKnowledge(i: KnowledgeItem) = hashMapOf("id" to i.id, "title" to i.title, "category" to i.category, "content" to i.content, "tags" to i.tags, "updatedAt" to i.updatedAt)
  private fun mapMemory(i: MemoryItem) = hashMapOf("id" to i.id, "summary" to i.summary, "contextApp" to i.contextApp, "importance" to i.importance, "createdAt" to i.createdAt)
  private fun mapPlaybook(i: TaskPlaybook) = hashMapOf("id" to i.id, "taskName" to i.taskName, "triggerPattern" to i.triggerPattern, "steps" to i.steps, "successCount" to i.successCount, "requiresConfirmation" to i.requiresConfirmation, "updatedAt" to i.updatedAt)
  private fun mapMistake(i: MistakeLesson) = hashMapOf("id" to i.id, "mistakeSummary" to i.mistakeSummary, "whatHappened" to i.whatHappened, "wrongBehavior" to i.wrongBehavior, "correctRule" to i.correctRule, "occurrenceCount" to i.occurrenceCount, "active" to i.active, "updatedAt" to i.updatedAt)
  private fun mapScheduled(i: CloudScheduledTask) = hashMapOf("id" to i.id, "title" to i.title, "instruction" to i.instruction, "scheduleType" to i.scheduleType, "nextRunAt" to i.nextRunAt, "status" to i.status, "updatedAt" to i.updatedAt)

  fun upsertKnowledge(ctx: Context, item: KnowledgeItem) { cacheKnowledge(ctx, item); withUid(ctx) { uid -> col(uid, "knowledge").document(item.id).set(mapKnowledge(item), SetOptions.merge()).await() } }
  fun upsertMemory(ctx: Context, item: MemoryItem) { cacheMemory(ctx, item); withUid(ctx) { uid -> col(uid, "memories").document(item.id).set(mapMemory(item), SetOptions.merge()).await() } }
  fun upsertPlaybook(ctx: Context, item: TaskPlaybook) { cachePlaybook(ctx, item); withUid(ctx) { uid -> col(uid, "task_playbooks").document(item.id).set(mapPlaybook(item), SetOptions.merge()).await() } }
  fun upsertMistake(ctx: Context, item: MistakeLesson) { cacheMistake(ctx, item); withUid(ctx) { uid -> col(uid, "mistakes_and_lessons").document(item.id).set(mapMistake(item), SetOptions.merge()).await() } }
  fun upsertScheduled(ctx: Context, item: CloudScheduledTask) { cacheScheduled(ctx, item); withUid(ctx) { uid -> col(uid, "scheduled_tasks").document(item.id).set(mapScheduled(item), SetOptions.merge()).await() } }

  fun delete(ctx: Context, collection: String, id: String) {
    deleteCache(ctx, collection, id)
    withUid(ctx) { uid -> col(uid, collection).document(id).delete().await() }
  }

  // ---------- local cache (SQLite) ----------
  fun ensureTables(d: android.database.sqlite.SQLiteDatabase) {
    d.execSQL("CREATE TABLE IF NOT EXISTS cloud_knowledge(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,updatedAt INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS cloud_memories(id TEXT PRIMARY KEY,summary TEXT,contextApp TEXT,importance INTEGER,createdAt INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS cloud_playbooks(id TEXT PRIMARY KEY,taskName TEXT,triggerPattern TEXT,steps TEXT,successCount INTEGER,requiresConfirmation INTEGER,updatedAt INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS cloud_mistakes(id TEXT PRIMARY KEY,mistakeSummary TEXT,whatHappened TEXT,wrongBehavior TEXT,correctRule TEXT,occurrenceCount INTEGER,active INTEGER,updatedAt INTEGER)")
    d.execSQL("CREATE TABLE IF NOT EXISTS cloud_scheduled(id TEXT PRIMARY KEY,title TEXT,instruction TEXT,scheduleType TEXT,nextRunAt INTEGER,status TEXT,updatedAt INTEGER)")
  }

  private fun w(ctx: Context) = Db.get(ctx).writableDatabase
  private fun r(ctx: Context) = Db.get(ctx).readableDatabase
  private fun tagsToJson(tags: List<String>): String = JSONArray(tags).toString()
  private fun jsonToTags(s: String?): List<String> = try { val a = JSONArray(s ?: "[]"); List(a.length()) { i -> a.optString(i) }.filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
  private fun stepsToJson(steps: List<String>): String = JSONArray(steps).toString()
  private fun jsonToSteps(s: String?): List<String> = jsonToTags(s)

  fun cacheKnowledge(ctx: Context, i: KnowledgeItem) { ensureTables(w(ctx)); w(ctx).insertWithOnConflict("cloud_knowledge", null, ContentValues().apply { put("id",i.id); put("title",i.title); put("category",i.category); put("content",i.content); put("tags",tagsToJson(i.tags)); put("updatedAt",i.updatedAt) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
  fun cacheMemory(ctx: Context, i: MemoryItem) { ensureTables(w(ctx)); w(ctx).insertWithOnConflict("cloud_memories", null, ContentValues().apply { put("id",i.id); put("summary",i.summary); put("contextApp",i.contextApp); put("importance",i.importance); put("createdAt",i.createdAt) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
  fun cachePlaybook(ctx: Context, i: TaskPlaybook) { ensureTables(w(ctx)); w(ctx).insertWithOnConflict("cloud_playbooks", null, ContentValues().apply { put("id",i.id); put("taskName",i.taskName); put("triggerPattern",i.triggerPattern); put("steps",stepsToJson(i.steps)); put("successCount",i.successCount); put("requiresConfirmation",if(i.requiresConfirmation)1 else 0); put("updatedAt",i.updatedAt) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
  fun cacheMistake(ctx: Context, i: MistakeLesson) { ensureTables(w(ctx)); w(ctx).insertWithOnConflict("cloud_mistakes", null, ContentValues().apply { put("id",i.id); put("mistakeSummary",i.mistakeSummary); put("whatHappened",i.whatHappened); put("wrongBehavior",i.wrongBehavior); put("correctRule",i.correctRule); put("occurrenceCount",i.occurrenceCount); put("active",if(i.active)1 else 0); put("updatedAt",i.updatedAt) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }
  fun cacheScheduled(ctx: Context, i: CloudScheduledTask) { ensureTables(w(ctx)); w(ctx).insertWithOnConflict("cloud_scheduled", null, ContentValues().apply { put("id",i.id); put("title",i.title); put("instruction",i.instruction); put("scheduleType",i.scheduleType); put("nextRunAt",i.nextRunAt); put("status",i.status); put("updatedAt",i.updatedAt) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) }

  private fun deleteCache(ctx: Context, collection: String, id: String) {
    val table = when(collection){ "knowledge"->"cloud_knowledge"; "memories"->"cloud_memories"; "task_playbooks"->"cloud_playbooks"; "mistakes_and_lessons"->"cloud_mistakes"; "scheduled_tasks"->"cloud_scheduled"; else->return }
    ensureTables(w(ctx)); w(ctx).delete(table, "id=?", arrayOf(id))
  }

  fun listKnowledge(ctx: Context, limit: Int = 100): List<KnowledgeItem> { ensureTables(r(ctx)); val out=mutableListOf<KnowledgeItem>(); r(ctx).rawQuery("SELECT id,title,category,content,tags,updatedAt FROM cloud_knowledge ORDER BY updatedAt DESC LIMIT ?", arrayOf(limit.toString())).use { c-> while(c.moveToNext()) out.add(KnowledgeItem(c.getString(0),c.getString(1)?:"",c.getString(2)?:"",c.getString(3)?:"",jsonToTags(c.getString(4)),c.getLong(5))) }; return out }
  fun listMemories(ctx: Context, limit: Int = 100): List<MemoryItem> { ensureTables(r(ctx)); val out=mutableListOf<MemoryItem>(); r(ctx).rawQuery("SELECT id,summary,contextApp,importance,createdAt FROM cloud_memories ORDER BY createdAt DESC LIMIT ?", arrayOf(limit.toString())).use { c-> while(c.moveToNext()) out.add(MemoryItem(c.getString(0),c.getString(1)?:"",c.getString(2)?:"",c.getInt(3),c.getLong(4))) }; return out }
  fun listPlaybooks(ctx: Context, limit: Int = 100): List<TaskPlaybook> { ensureTables(r(ctx)); val out=mutableListOf<TaskPlaybook>(); r(ctx).rawQuery("SELECT id,taskName,triggerPattern,steps,successCount,requiresConfirmation,updatedAt FROM cloud_playbooks ORDER BY updatedAt DESC LIMIT ?", arrayOf(limit.toString())).use { c-> while(c.moveToNext()) out.add(TaskPlaybook(c.getString(0),c.getString(1)?:"",c.getString(2)?:"",jsonToSteps(c.getString(3)),c.getInt(4),c.getInt(5)!=0,c.getLong(6))) }; return out }
  fun listMistakes(ctx: Context, limit: Int = 100): List<MistakeLesson> { ensureTables(r(ctx)); val out=mutableListOf<MistakeLesson>(); r(ctx).rawQuery("SELECT id,mistakeSummary,whatHappened,wrongBehavior,correctRule,occurrenceCount,active,updatedAt FROM cloud_mistakes ORDER BY updatedAt DESC LIMIT ?", arrayOf(limit.toString())).use { c-> while(c.moveToNext()) out.add(MistakeLesson(c.getString(0),c.getString(1)?:"",c.getString(2)?:"",c.getString(3)?:"",c.getString(4)?:"",c.getInt(5),c.getInt(6)!=0,c.getLong(7))) }; return out }
  fun listScheduled(ctx: Context, limit: Int = 100): List<CloudScheduledTask> { ensureTables(r(ctx)); val out=mutableListOf<CloudScheduledTask>(); r(ctx).rawQuery("SELECT id,title,instruction,scheduleType,nextRunAt,status,updatedAt FROM cloud_scheduled ORDER BY nextRunAt ASC LIMIT ?", arrayOf(limit.toString())).use { c-> while(c.moveToNext()) out.add(CloudScheduledTask(c.getString(0),c.getString(1)?:"",c.getString(2)?:"",c.getString(3)?:"once",c.getLong(4),c.getString(5)?:"pending",c.getLong(6))) }; return out }

  // ---------- sync ----------
  suspend fun syncAll(ctx: Context): Result<Unit> = runCatching {
    val uid = AuthManager.uid ?: return@runCatching
    pullKnowledge(ctx, uid); pullMemories(ctx, uid); pullPlaybooks(ctx, uid); pullMistakes(ctx, uid); pullScheduled(ctx, uid)
  }

  private suspend fun pullKnowledge(ctx: Context, uid: String) {
    val snap = col(uid,"knowledge").get().await()
    for (d in snap.documents) { val i = KnowledgeItem(d.getString("id")?:d.id,d.getString("title")?:"",d.getString("category")?:"",d.getString("content")?:"",(d.get("tags") as? List<*>)?.mapNotNull{it?.toString()}?:emptyList(),d.getLong("updatedAt")?:0L); cacheKnowledge(ctx,i) }
    for (i in listKnowledge(ctx,500)) { col(uid,"knowledge").document(i.id).set(mapKnowledge(i), SetOptions.merge()).await() }
  }
  private suspend fun pullMemories(ctx: Context, uid: String) {
    val snap = col(uid,"memories").get().await()
    for (d in snap.documents) { val i = MemoryItem(d.getString("id")?:d.id,d.getString("summary")?:"",d.getString("contextApp")?:"",(d.getLong("importance")?:1L).toInt(),d.getLong("createdAt")?:0L); cacheMemory(ctx,i) }
    for (i in listMemories(ctx,500)) { col(uid,"memories").document(i.id).set(mapMemory(i), SetOptions.merge()).await() }
  }
  private suspend fun pullPlaybooks(ctx: Context, uid: String) {
    val snap = col(uid,"task_playbooks").get().await()
    for (d in snap.documents) { val i = TaskPlaybook(d.getString("id")?:d.id,d.getString("taskName")?:"",d.getString("triggerPattern")?:"",(d.get("steps") as? List<*>)?.mapNotNull{it?.toString()}?:emptyList(),(d.getLong("successCount")?:0L).toInt(),d.getBoolean("requiresConfirmation")?:false,d.getLong("updatedAt")?:0L); cachePlaybook(ctx,i) }
    for (i in listPlaybooks(ctx,500)) { col(uid,"task_playbooks").document(i.id).set(mapPlaybook(i), SetOptions.merge()).await() }
  }
  private suspend fun pullMistakes(ctx: Context, uid: String) {
    val snap = col(uid,"mistakes_and_lessons").get().await()
    for (d in snap.documents) { val i = MistakeLesson(d.getString("id")?:d.id,d.getString("mistakeSummary")?:"",d.getString("whatHappened")?:"",d.getString("wrongBehavior")?:"",d.getString("correctRule")?:"",(d.getLong("occurrenceCount")?:1L).toInt(),d.getBoolean("active")?:true,d.getLong("updatedAt")?:0L); cacheMistake(ctx,i) }
    for (i in listMistakes(ctx,500)) { col(uid,"mistakes_and_lessons").document(i.id).set(mapMistake(i), SetOptions.merge()).await() }
  }
  private suspend fun pullScheduled(ctx: Context, uid: String) {
    val snap = col(uid,"scheduled_tasks").get().await()
    for (d in snap.documents) { val i = CloudScheduledTask(d.getString("id")?:d.id,d.getString("title")?:"",d.getString("instruction")?:"",d.getString("scheduleType")?:"once",d.getLong("nextRunAt")?:0L,d.getString("status")?:"pending",d.getLong("updatedAt")?:0L); cacheScheduled(ctx,i) }
    for (i in listScheduled(ctx,500)) { col(uid,"scheduled_tasks").document(i.id).set(mapScheduled(i), SetOptions.merge()).await() }
  }
}
