package com.saara.assistant

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Automation macro / learned action template, stored locally in Room and synced to Firestore.
 * Firestore: users/{userId}/macros/{macroId}
 */
@Entity(tableName = "macros")
data class Macro(
  @PrimaryKey val macroId: String,
  val actionText: String,
  val trigger: String = "",
  val updatedAt: Long = System.currentTimeMillis()
)
