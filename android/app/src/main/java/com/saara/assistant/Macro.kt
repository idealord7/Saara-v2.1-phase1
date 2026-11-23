package com.saara.assistant

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Automation macro / learned action template, stored locally in Room and synced to Firestore.
 * Firestore: users/{userId}/macros/{macroId}
 */
@Entity(tableName = "macros")
data class Macro(
  @PrimaryKey
  @ColumnInfo(name = "macroId")
  val macroId: String,
  @ColumnInfo(name = "actionText")
  val actionText: String,
  @ColumnInfo(name = "trigger")
  val trigger: String,
  @ColumnInfo(name = "updatedAt")
  val updatedAt: Long,
)
