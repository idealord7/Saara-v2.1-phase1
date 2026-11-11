package com.saara.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single entry point for macro CRUD.
 * Writes locally to Room first, then pushes to Firestore for cloud backup.
 * Deletes are synced in both directions.
 */
class MacroRepository private constructor(context: Context) {

  private val app = context.applicationContext
  private val dao = MacroDb.get(app).macroDao()
  private val sync = CloudSyncRepository.get(app)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  companion object {
    @Volatile private var inst: MacroRepository? = null
    fun get(context: Context): MacroRepository =
      inst ?: synchronized(this) { inst ?: MacroRepository(context).also { inst = it } }
  }

  suspend fun getAll(): List<Macro> = withContext(Dispatchers.IO) { dao.getAll() }
  suspend fun getById(id: String): Macro? = withContext(Dispatchers.IO) { dao.getById(id) }

  /** Save or update a macro locally and sync to cloud. */
  suspend fun save(actionText: String, trigger: String = "", existingId: String? = null): Macro {
    val macro = Macro(
      macroId = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
      actionText = actionText.trim(),
      trigger = trigger.trim(),
      updatedAt = System.currentTimeMillis()
    )
    withContext(Dispatchers.IO) { dao.upsert(macro) }
    sync.uploadMacro(macro)
    return macro
  }

  suspend fun upsert(macro: Macro) {
    val stamped = macro.copy(updatedAt = if (macro.updatedAt > 0) macro.updatedAt else System.currentTimeMillis())
    withContext(Dispatchers.IO) { dao.upsert(stamped) }
    sync.uploadMacro(stamped)
  }

  suspend fun delete(macroId: String) {
    withContext(Dispatchers.IO) { dao.deleteById(macroId) }
    sync.deleteMacro(macroId)
  }

  /** Restore from cloud on login/reinstall, merging with local (latest updatedAt wins). */
  fun restoreOnLogin(onDone: ((Result<Unit>) -> Unit)? = null) {
    scope.launch {
      val r = sync.restoreAll()
      onDone?.let { withContext(Dispatchers.Main) { it(r) } }
    }
  }
}
