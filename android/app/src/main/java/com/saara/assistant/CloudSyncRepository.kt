package com.saara.assistant

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Syncs local macros and learning rules with Firestore per-user.
 * Schema:
 *   users/{userId}/macros/{macroId}  -> macroId, actionText, trigger, updatedAt
 *   users/{userId}/rules/{ruleId}    -> ruleId, text, enabled, updatedAt
 *
 * Conflict resolution: latest updatedAt wins (last-write-wins by timestamp).
 */
class CloudSyncRepository private constructor(context: Context) {

  private val app = context.applicationContext
  private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  companion object {
    @Volatile private var inst: CloudSyncRepository? = null
    fun get(context: Context): CloudSyncRepository =
      inst ?: synchronized(this) { inst ?: CloudSyncRepository(context).also { inst = it } }
  }

  private fun uidOrNull(): String? = AuthManager.uid

  // ---------- macros ----------
  /** Upload/create/update a macro document. Safe to call after local save. */
  fun uploadMacro(macro: Macro) {
    val uid = uidOrNull() ?: return
    val data = hashMapOf(
      "macroId" to macro.macroId,
      "actionText" to macro.actionText,
      "trigger" to macro.trigger,
      "updatedAt" to macro.updatedAt
    )
    scope.launch {
      try {
        db.collection("users").document(uid)
          .collection("macros").document(macro.macroId)
          .set(data, SetOptions.merge()).await()
      } catch (e: Exception) {
        Crash.save(app, e, "CloudSyncRepository.uploadMacro")
      }
    }
  }

  fun deleteMacro(macroId: String) {
    val uid = uidOrNull() ?: return
    scope.launch {
      try {
        db.collection("users").document(uid)
          .collection("macros").document(macroId)
          .delete().await()
      } catch (e: Exception) {
        Crash.save(app, e, "CloudSyncRepository.deleteMacro")
      }
    }
  }

  /** Pull all remote macros and merge into local Room DB (latest updatedAt wins). */
  suspend fun downloadAndMergeMacros(): Result<Int> = runCatching {
    val uid = uidOrNull() ?: return@runCatching 0
    val localDb = MacroDb.get(app)
    val dao = localDb.macroDao()
    val snap = db.collection("users").document(uid).collection("macros").get().await()
    var applied = 0
    for (doc in snap.documents) {
      val remote = Macro(
        macroId = doc.getString("macroId") ?: doc.id,
        actionText = doc.getString("actionText") ?: "",
        trigger = doc.getString("trigger") ?: "",
        updatedAt = doc.getLong("updatedAt") ?: 0L
      )
      if (remote.macroId.isBlank()) continue
      val local = dao.getById(remote.macroId)
      if (local == null || remote.updatedAt >= local.updatedAt) {
        dao.upsert(remote)
      }
      applied++
    }
    // push local-only/newer macros up
    for (m in dao.getAll()) {
      if (m.macroId.isBlank()) continue
      val remoteDoc = snap.documents.firstOrNull { (it.getString("macroId") ?: it.id) == m.macroId }
      val remoteTs = remoteDoc?.getLong("updatedAt") ?: -1L
      if (remoteDoc == null || m.updatedAt > remoteTs) uploadMacro(m)
    }
    applied
  }

  // ---------- rules (learning rules) ----------
  fun uploadRule(rule: Rule) {
    val uid = uidOrNull() ?: return
    val data = hashMapOf(
      "ruleId" to rule.id.toString(),
      "text" to rule.text,
      "enabled" to rule.enabled,
      "updatedAt" to rule.ts
    )
    scope.launch {
      try {
        db.collection("users").document(uid)
          .collection("rules").document(rule.id.toString())
          .set(data, SetOptions.merge()).await()
      } catch (e: Exception) {
        Crash.save(app, e, "CloudSyncRepository.uploadRule")
      }
    }
  }

  fun deleteRule(ruleId: Long) {
    val uid = uidOrNull() ?: return
    scope.launch {
      try {
        db.collection("users").document(uid)
          .collection("rules").document(ruleId.toString())
          .delete().await()
      } catch (e: Exception) {
        Crash.save(app, e, "CloudSyncRepository.deleteRule")
      }
    }
  }

  /** Merge remote rules into local SQLite Db; latest ts wins. */
  suspend fun downloadAndMergeRules(): Result<Int> = runCatching {
    val uid = uidOrNull() ?: return@runCatching 0
    val local = Db.get(app)
    val snap = db.collection("users").document(uid).collection("rules").get().await()
    var applied = 0
    val remoteRules = snap.documents.mapNotNull { d ->
      val idStr = d.getString("ruleId") ?: d.id
      val id = idStr.toLongOrNull() ?: return@mapNotNull null
      Rule(id, d.getString("text") ?: "", d.getBoolean("enabled") ?: true, d.getLong("updatedAt") ?: 0L)
    }
    val localByText = local.rules().associateBy { it.text.trim() }
    for (r in remoteRules) {
      val match = localByText[r.text.trim()]
      if (match == null) {
        // insert locally; can't force id easily in existing API, so add and then adjust state
        local.ruleAdd(r.text)
        val added = local.rules().firstOrNull { it.text == r.text }
        if (added != null) {
          local.ruleSetEnabled(added.id, r.enabled)
          uploadRule(Rule(added.id, added.text, r.enabled, r.ts))
        }
      } else if (r.ts >= match.ts) {
        if (match.text != r.text) local.ruleUpdate(match.id, r.text)
        local.ruleSetEnabled(match.id, r.enabled)
      }
      applied++
    }
    // push local rules missing remotely
    for (lr in local.rules()) {
      val remote = remoteRules.firstOrNull { it.text.trim() == lr.text.trim() }
      if (remote == null || lr.ts > remote.ts) uploadRule(lr)
    }
    applied
  }

  /** Full restore: call on login/reinstall after auth completes. */
  suspend fun restoreAll(): Result<Unit> = runCatching {
    downloadAndMergeMacros().getOrThrow()
    downloadAndMergeRules().getOrThrow()
  }
}
