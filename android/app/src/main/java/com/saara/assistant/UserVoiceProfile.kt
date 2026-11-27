package com.saara.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Local user voice profile for "Only catch my voice" / speaker focus.
 * Stores lightweight acoustic signature derived from openWakeWord embeddings.
 * All data stays on-device.
 */
object UserVoiceProfile {
  private const val FILE = "user_voice_profile.json"
  const val MIN_THRESHOLD = 0
  const val MAX_THRESHOLD = 100
  const val DEFAULT_THRESHOLD = 50

  data class Profile(
    val centroid: FloatArray,
    val spread: FloatArray, // per-dim stddev for normalization
    val sampleCount: Int,
    val createdAt: Long,
    val version: Int = 1,
  )

  @Volatile private var cached: Profile? = null
  @Volatile private var cachedMtime: Long = -1L

  fun file(c: Context): File = File(c.filesDir, FILE)

  fun exists(c: Context): Boolean = file(c).isFile

  fun clear(c: Context) {
    cached = null; cachedMtime = -1L
    runCatching { file(c).delete() }
  }

  fun load(c: Context): Profile? {
    val f = file(c)
    if (!f.isFile) { cached = null; cachedMtime = -1L; return null }
    val mt = f.lastModified()
    cached?.let { if (cachedMtime == mt) return it }
    return try {
      val o = JSONObject(f.readText())
      val cArr = o.optJSONArray("centroid") ?: return null
      val sArr = o.optJSONArray("spread") ?: return null
      val dim = cArr.length()
      if (dim <= 0) return null
      val centroid = FloatArray(dim) { i -> cArr.optDouble(i, 0.0).toFloat() }
      val spread = FloatArray(dim) { i -> sArr.optDouble(i, 1.0).toFloat().coerceAtLeast(0.01f) }
      val p = Profile(
        centroid = centroid,
        spread = spread,
        sampleCount = o.optInt("sampleCount", 0),
        createdAt = o.optLong("createdAt", 0L),
        version = o.optInt("version", 1),
      )
      cached = p; cachedMtime = mt
      p
    } catch (_: Exception) { null }
  }

  fun save(c: Context, p: Profile) {
    val o = JSONObject()
    o.put("version", p.version)
    o.put("sampleCount", p.sampleCount)
    o.put("createdAt", p.createdAt)
    o.put("dim", p.centroid.size)
    val ca = JSONArray(); for (v in p.centroid) ca.put(v.toDouble()); o.put("centroid", ca)
    val sa = JSONArray(); for (v in p.spread) sa.put(v.toDouble()); o.put("spread", sa)
    file(c).writeText(o.toString())
    cached = p; cachedMtime = file(c).lastModified()
  }

  /**
   * Build profile from enrollment embedding vectors. Each vector should be the same dim
   * (e.g. 192 from WakeVerifier.pool mean+std of 16x96 embeddings).
   */
  fun enroll(vectors: List<FloatArray>): Profile {
    require(vectors.isNotEmpty()) { "No enrollment samples" }
    val dim = vectors[0].size
    val centroid = FloatArray(dim)
    for (v in vectors) for (i in 0 until min(dim, v.size)) centroid[i] += v[i]
    for (i in 0 until dim) centroid[i] /= vectors.size.toFloat()
    val spread = FloatArray(dim)
    for (v in vectors) for (i in 0 until min(dim, v.size)) {
      val d = v[i] - centroid[i]; spread[i] += d * d
    }
    for (i in 0 until dim) spread[i] = sqrt(spread[i] / vectors.size.toFloat()).coerceAtLeast(0.01f)
    return Profile(centroid, spread, vectors.size, System.currentTimeMillis(), 1)
  }

  /**
   * Returns similarity as 0..100 percent. Higher means closer to enrolled user.
   * Uses cosine similarity mapped to 0..100, with spread-aware normalization.
   */
  fun matchPercent(profile: Profile, vector: FloatArray): Float {
    val dim = min(profile.centroid.size, vector.size)
    if (dim <= 0) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 0 until dim) {
      val s = profile.spread[i].coerceAtLeast(0.01f)
      val an = profile.centroid[i] / s
      val bn = vector[i] / s
      dot += an.toDouble() * bn.toDouble()
      na += an.toDouble() * an.toDouble()
      nb += bn.toDouble() * bn.toDouble()
    }
    val denom = sqrt(na) * sqrt(nb)
    if (denom <= 1e-9) return 0f
    val cos = (dot / denom).coerceIn(-1.0, 1.0)
    val pct = (((cos + 1.0) / 2.0) * 100.0).toFloat()
    return pct.coerceIn(0f, 100f)
  }

  fun matchPercent(c: Context, vector: FloatArray): Float? {
    val p = load(c) ?: return null
    return matchPercent(p, vector)
  }

  fun threshold(c: Context): Int =
    Prefs.plain(c).getInt("voice_match_threshold", DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

  fun setThreshold(c: Context, value: Int) {
    Prefs.plain(c).edit().putInt("voice_match_threshold", value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)).apply()
  }

  fun onlyUserVoiceEnabled(c: Context): Boolean = Prefs.on(c, "f_only_user_voice", false)

  fun bypassOnManual(c: Context): Boolean = Prefs.on(c, "f_voice_match_bypass_manual", true)

  fun rejectMediaNoise(c: Context): Boolean = Prefs.on(c, "f_voice_reject_media", true)

  fun strictnessPreset(c: Context): String = Prefs.plain(c).getString("voice_match_strictness", "balanced") ?: "balanced"

  fun applyStrictnessPreset(c: Context, preset: String): Int {
    val v = when (preset.lowercase()) {
      "relaxed" -> 35
      "strict" -> 75
      else -> 50
    }
    Prefs.plain(c).edit().putString("voice_match_strictness", preset.lowercase()).putInt("voice_match_threshold", v).apply()
    return v
  }

  /** Human-readable status for settings/diagnostics. */
  fun statusSummary(c: Context): String {
    val p = load(c)
    val th = threshold(c)
    return if (p == null) "No voice sample enrolled. Enroll your voice to enable matching."
    else "Enrolled ${p.sampleCount} sample(s), threshold ${th}%."
  }
}
