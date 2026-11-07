package com.saara.assistant

import org.json.JSONObject
import java.util.Locale

/** Confirmation, interruption and destructive-action guard for every phone/screen action. */
object TaskControl {
  private const val READ_ONLY_NONE = ""

  private val STOP = Regex(
    "^(stop|stop it|stop now|stop it now|please stop|please stop it|stop stop|stop karo|stop kar do|wait|wait a moment|wait please|wait a second|wait a minute|hold on|hold on a second|hold|ruko|ruk jao|ruk|zara ruko|abhi ruko|thoda ruko|ek minute|ek second|bas|bas karo|bas bas|tham jao|thum jao|chhodo|chhod do|abhi nahi|never mind|nevermind|cancel that|cancel it|" +
      "थामो|थाम|रुको|ज़रा रुको|अभी रुको|रुक जाओ|बस|बस करो|ठहरो|ठहर जाओ|एक मिनट|छोड़ो|छोड़ दो|बंद करो|বন্ধ করো|বন্ধ|থামো|থাম|রুকো|এক মিনিট|রাখো)\\s*[.!।]*$",
    RegexOption.IGNORE_CASE
  )
  private val NEGATIVE = Regex(
    "^(no|nope|nah|nahi|na|mat|don't|do not|not now|never mind|nevermind|decline|reject|" +
      "नहीं|मत|रद्द|না|নাহ|না)\\s*[.!।]*$",
    RegexOption.IGNORE_CASE
  )
  private val AFFIRMATIVE_LATIN = Regex(
    "\\b(yes|yeah|yep|yup|ok|okay|sure|go ahead|do it|proceed|confirm|confirmed|haan|han|ha|theek|thik|karo|kar do|kariye|kijiye|chalo|chalega|chalta hai|bilkul|zaroor|ji)\\b",
    RegexOption.IGNORE_CASE
  )
  private val AFFIRMATIVE_INDIC = listOf(
    "হ্যাঁ", "হা", "ঠিক", "করো", "করো", "করে দিন", "চালাও", "আগে", "বিলকুল", "একদম",
    "हाँ", "हां", "ठीक", "करो", "कर दो", "करिए", "कीजिए", "चलो", "चलिए", "बिल्कुल", "ज़रूर", "जरूर"
  )
  private val READ_ONLY_OPS = setOf("dump_ui", "dump_small", "wait", "back", "home", "recents", "notifications")
  private val GATED_DEVICE = setOf(
    "open_app", "open_camera", "take_photo", "call", "answer_call", "end_call", "reject_call", "sms",
    "wifi_panel", "bluetooth_panel"
  )

  @Volatile private var askedSeq = -1
  @Volatile private var approvedSeq = -1
  @Volatile private var requested = ""

  private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

  fun isStop(text: String): Boolean = STOP.matches(normalize(text))
  fun isNegative(text: String): Boolean = NEGATIVE.matches(normalize(text))

  fun isAffirmative(text: String): Boolean {
    val n = normalize(text)
    if (n.isBlank()) return false
    if (AFFIRMATIVE_LATIN.containsMatchIn(n)) return true
    return AFFIRMATIVE_INDIC.any { n.contains(it) }
  }

  /** A short description of the action that needs confirmation, or null for read-only work. */
  fun confirmationFor(name: String, a: JSONObject): String? = when (name) {
    "device_control" -> {
      val act = a.optString("action")
      if (act !in GATED_DEVICE) null else when (act) {
        "call" -> "call ${a.optString("target").ifBlank { "the contact" }}"
        "sms" -> "send an SMS to ${a.optString("target").ifBlank { "the contact" }}"
        "take_photo" -> "take a photo"
        "answer_call" -> "answer the incoming call"
        "end_call", "reject_call" -> "end or reject the call"
        "open_app" -> "open ${a.optString("target").ifBlank { "the app" }}"
        "open_camera" -> "open the camera"
        "wifi_panel" -> "open the Wi-Fi panel"
        "bluetooth_panel" -> "open Bluetooth settings"
        else -> null
      }
    }
    "ui_action" -> {
      val op = a.optString("op")
      if (op in READ_ONLY_OPS) null else {
        val detail = when {
          a.optString("text").isNotBlank() -> " on \"${a.optString("text").take(80)}\""
          a.optString("id").isNotBlank() -> " on id ${a.optString("id").take(80)}"
          a.has("x") || a.has("y") -> " at (${a.optDouble("x").toInt()}, ${a.optDouble("y").toInt()})"
          else -> ""
        }
        "perform screen action '$op'$detail"
      }
    }
    "reply_notification" -> "send a reply to a notification"
    "schedule_task" -> "schedule a task: ${a.optString("text").take(100)}"
    "cancel_task" -> "cancel scheduled task #${a.optLong("id")}"
    else -> null
  }

  fun isApproved(): Boolean {
    if (approvedSeq == Bus.userSeq) return true
    if (isStop(Bus.lastUser) || isNegative(Bus.lastUser)) {
      clear()
      return false
    }
    // The model may ask for confirmation in speech without calling a tool first.
    // A clear yes/haan/karo in the current user turn is therefore enough to approve.
    if (isAffirmative(Bus.lastUser)) {
      approvedSeq = Bus.userSeq
      askedSeq = -1
      return true
    }
    if (askedSeq >= 0 && Bus.userSeq > askedSeq) clear()
    return false
  }

  fun request(summary: String): String {
    askedSeq = Bus.userSeq
    approvedSeq = -1
    requested = summary
    return "CONFIRMATION REQUIRED: \"$summary\" has NOT been done. In the user's language, give one short summary of what you will do and ask them to confirm. Do not call any action tool until the user replies. After they confirm, retry with confirmed=true."
  }

  fun clear() {
    askedSeq = -1
    approvedSeq = -1
    requested = ""
  }

  fun pendingSummary(): String = requested
}
