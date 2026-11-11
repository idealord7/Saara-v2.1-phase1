package com.saara.assistant

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for automation macros.
 * Synced to Firestore via CloudSyncRepository under users/{uid}/macros/{macroId}.
 */
@Database(entities = [Macro::class], version = 1, exportSchema = false)
abstract class MacroDb : RoomDatabase() {
  abstract fun macroDao(): MacroDao

  companion object {
    @Volatile private var inst: MacroDb? = null
    fun get(context: Context): MacroDb = inst ?: synchronized(this) {
      inst ?: Room.databaseBuilder(context.applicationContext, MacroDb::class.java, "saara_macros.db")
        .fallbackToDestructiveMigration()
        .build().also { inst = it }
    }
  }
}
