package com.saara.assistant
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
  fun secure(c: Context): SharedPreferences = EncryptedSharedPreferences.create(c, "saara",
    MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  fun plain(c: Context): SharedPreferences = c.getSharedPreferences("saara_settings", Context.MODE_PRIVATE)
  fun on(c: Context, k: String, def: Boolean = true): Boolean = plain(c).getBoolean(k, def)
}

/** Language priority chosen in Settings: auto | bn | hi | en | hinglish. Bengali, Hindi and English always stay first-priority. */
fun langRule(c: Context): String {
  val order = "Bengali (বাংলা), Hindi (हिन्दी) and English are your first-priority languages: understand all three, including mixed speech (Hinglish / Banglish), and never ask the user to repeat just because the language changed. "
  return order + when (Prefs.plain(c).getString("lang", "auto")) {
    "bn" -> "SPEAK BENGALI by default (natural spoken Bengali). Switch only if the user clearly speaks another language."
    "hi" -> "SPEAK HINDI by default (natural spoken Hindi, Devanagari). Switch only if the user clearly speaks another language."
    "en" -> "SPEAK ENGLISH by default. Switch only if the user clearly speaks another language."
    "hinglish" -> "SPEAK HINGLISH (Hindi + English mix) by default. Switch only if the user clearly speaks another language."
    else -> "Detect the language the user is speaking (Bengali, Hindi, English or a mix) and ALWAYS reply in that same language; if unsure use Hinglish."
  }
}

/** One-time move of the old single 'facts' text blob into the SQLite memory. */
fun migrateFacts(c: Context) {
  val p = Prefs.plain(c)
  val old = p.getString("facts", "") ?: ""
  if (old.isNotBlank()) { val d = Db.get(c); old.split("\n").forEach { d.factAdd("general", it) } }
  if (p.contains("facts")) p.edit().remove("facts").apply()
}
