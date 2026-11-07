package com.saara.assistant

import org.json.JSONObject
import java.util.Locale

/** Smart confirmation, interruption and destructive-action guard: simple actions run; only risky ones ask. */
object TaskControl {
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
  private val DESTRUCTIVE = Regex(
    "\\b(delete|erase|remove|uninstall|cancel|clear data|clear storage|factory reset|format|reset|pay|payment|purchase|buy|order|checkout|book|booking|confirm|submit|log out|logout|sign out|block|report|" +
      "हटाओ|हटा|मिटाओ|मिटा|रद्द|भुगतान|ऑर्डर|बुक|खरीद|কাট|মুছ|বাতিল|পেমেন্ট|অর্ডার|বুক|কিন)\\b",
    RegexOption.IGNORE_CASE
  )
  private val GATED_DEVICE = setOf("end_call", "reject_call")

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

  /** Only destructive/irreversible actions need a code-level confirmation. Clear, short actions run immediately. */
  fun confirmationFor(name: String, a: JSONObject): String? = when (name) {
    "device_control" -> {
      val act = a.optString("action")
      if (act !in GATED_DEVICE) null else when (act) {
        "end_call", "reject_call" -> "end or reject the call"
        else -> null
      }
    }
    "ui_action" -> {
      val op = a.optString("op")
      val detail = when {
        a.optString("text").isNotBlank() -> "tap \"${a.optString("text").take(80)}\""
        a.optString("id").isNotBlank() -> "tap id ${a.optString("id").take(80)}"
        a.has("x") || a.has("y") -> "tap at (${a.optDouble("x").toInt()}, ${a.optDouble("y").toInt()})"
        else -> "perform screen action '$op'"
      }
      val intent = listOf(Bus.lastUser, a.optString("text"), a.optString("id"), a.optString("target"), a.optString("direction")).joinToString(" ")
      if (DESTRUCTIVE.containsMatchIn(intent)) detail else null
    }
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
