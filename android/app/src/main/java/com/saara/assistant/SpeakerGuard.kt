package com.saara.assistant

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Lightweight rolling speaker-match helper used by VoiceService capture loop.
 * Uses OpenWakeWordFeatures embeddings when only-user-voice is enabled.
 */
class SpeakerGuard(private val c: Context) {
  @Volatile var enabled: Boolean = false
  @Volatile var lastMatchPercent: Float = -1f
  @Volatile var lastMatchAt: Long = 0L
  @Volatile var lastDecision: String = "unknown"

  private val lock = Any()
  private var features: OpenWakeWordFeatures? = null
  private var window: ArrayDeque<FloatArray> = ArrayDeque()
  private var failed = false
  private val audioBuf = ShortArray(OpenWakeWordFeatures.CHUNK)
  private var pending = 0

  fun ensureStarted(): Boolean {
    synchronized(lock) {
      if (features != null || failed) return features != null
      if (!OpenWakeWordFeatures.available(c)) { failed = true; return false }
      return try {
        features = OpenWakeWordFeatures(c)
        failed = false
        true
      } catch (_: Exception) {
        failed = true
        false
      }
    }
  }

  fun stop() {
    synchronized(lock) {
      runCatching { features?.close() }
      features = null
      window.clear()
      pending = 0
      failed = false
    }
  }

  fun reset() {
    synchronized(lock) {
      window.clear()
      pending = 0
      runCatching { features?.reset() }
    }
  }

  /** Feed 16kHz PCM samples (any count). Returns latest match percent or null if not enough data. */
  fun feed(samples: ShortArray, count: Int = samples.size): Float? {
    if (!enabled || count <= 0) return null
    val f = synchronized(lock) { features } ?: if (ensureStarted()) synchronized(lock) { features } else null
    if (f == null) return null
    var latest: Float? = null
    synchronized(lock) {
      var i = 0
      while (i < count) {
        val take = minOf(count - i, OpenWakeWordFeatures.CHUNK - pending)
        for (j in 0 until take) audioBuf[pending + j] = samples[i + j]
        pending += take
        i += take
        if (pending >= OpenWakeWordFeatures.CHUNK) {
          pending = 0
          val emb = try { f.accept(audioBuf) } catch (_: Exception) { null } ?: continue
          window.addLast(emb.copyOf())
          while (window.size > 24) window.removeFirst()
          if (window.size >= 16) {
            val tail = ArrayList<FloatArray>(16)
            var skip = window.size - 16
            for (v in window) { if (skip-- > 0) continue; tail.add(v) }
            val vec = WakeVerifier.pool(tail)
            val pct = UserVoiceProfile.matchPercent(c, vec)
            if (pct != null) {
              lastMatchPercent = pct
              lastMatchAt = SystemClock.elapsedRealtime()
              latest = pct
            }
          }
        }
      }
    }
    return latest
  }

  fun currentPercent(): Float = lastMatchPercent

  fun hasRecentMatch(maxAgeMs: Long = 5000L): Boolean =
    lastMatchPercent >= 0f && (SystemClock.elapsedRealtime() - lastMatchAt) <= maxAgeMs

  @Volatile var startedAt: Long = 0L

  fun markStarted() { startedAt = SystemClock.elapsedRealtime() }

  fun shouldAccept(manualBypass: Boolean): Boolean {
    if (!enabled) return true
    if (manualBypass && UserVoiceProfile.bypassOnManual(c)) {
      lastDecision = "bypass_manual"
      return true
    }
    val profile = UserVoiceProfile.load(c) ?: run {
      lastDecision = "no_profile"
      return true // graceful fallback: don't lock out; UI warns
    }
    if (!hasRecentMatch()) {
      // Warmup grace: allow briefly while collecting enough embeddings, to avoid startup lockout.
      if (startedAt > 0L && SystemClock.elapsedRealtime() - startedAt < 4000L) {
        lastDecision = "warming_up"
        return true
      }
      lastDecision = "no_recent_match"
      return false
    }
    val th = UserVoiceProfile.threshold(c).toFloat().coerceIn(0f, 100f)
    val pct = lastMatchPercent.coerceIn(0f, 100f)
    val ok = pct >= th
    lastDecision = if (ok) "accept" else "reject"
    return ok
  }

  fun rejectReason(): String {
    val th = UserVoiceProfile.threshold(c)
    val pct = if (lastMatchPercent >= 0f) "%.0f%%".format(lastMatchPercent) else "n/a"
    return "Voice rejected: $pct < ${th}% threshold"
  }

  companion object {
    fun isMediaPlaying(c: Context): Boolean {
      return try {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMusicActive
      } catch (_: Exception) { false }
    }
  }
}
