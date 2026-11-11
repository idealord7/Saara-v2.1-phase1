package com.saara.assistant

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MacroDao {
  @Query("SELECT * FROM macros ORDER BY updatedAt DESC")
  suspend fun getAll(): List<Macro>

  @Query("SELECT * FROM macros WHERE macroId = :id LIMIT 1")
  suspend fun getById(id: String): Macro?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(macro: Macro)

  @Query("DELETE FROM macros WHERE macroId = :id")
  suspend fun deleteById(id: String)
}
