package com.saara.assistant

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Lightweight rolling speaker-match helper used by VoiceService capture loop.
 * Prefers OpenWakeWord embeddings; falls back to SimpleVoiceFeatures when TFLite model init fails.
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
  private var useSimpleFallback = false
  private val audioBuf = ShortArray(OpenWakeWordFeatures.CHUNK)
  private var pending = 0
  private val simpleBuf = ArrayList<Short>(16000)

  fun ensureStarted(): Boolean {
    synchronized(lock) {
      if (features != null || useSimpleFallback) return true
      if (failed) return false
      if (!OpenWakeWordFeatures.available(c)) {
        useSimpleFallback = true
        return true
      }
      return try {
        features = OpenWakeWordFeatures(c)
        true
      } catch (_: Throwable) {
        // TFLite model allocation can fail on some devices; use simple fallback.
        features = null
        useSimpleFallback = true
        true
      }
    }
  }

  fun stop() {
    synchronized(lock) {
      runCatching { features?.close() }
      features = null
      window.clear()
      simpleBuf.clear()
      pending = 0
      failed = false
      useSimpleFallback = false
    }
  }

  fun reset() {
    synchronized(lock) {
      window.clear()
      simpleBuf.clear()
      pending = 0
      runCatching { features?.reset() }
    }
  }

  /** Feed 16kHz PCM samples (any count). Returns latest match percent or null if not enough data. */
  fun feed(samples: ShortArray, count: Int = samples.size): Float? {
    if (!enabled || count <= 0) return null
    if (!ensureStarted()) return null
    synchronized(lock) {
      if (useSimpleFallback || features == null) return feedSimpleLocked(samples, count)
      val f = features ?: return feedSimpleLocked(samples, count)
      var latest: Float? = null
      var i = 0
      while (i < count) {
        val take = minOf(count - i, OpenWakeWordFeatures.CHUNK - pending)
        for (j in 0 until take) audioBuf[pending + j] = samples[i + j]
        pending += take
        i += take
        if (pending >= OpenWakeWordFeatures.CHUNK) {
          pending = 0
          val emb = try { f.accept(audioBuf) } catch (_: Throwable) { null }
          if (emb == null) continue
          window.addLast(emb.copyOf())
          while (window.size > 24) window.removeFirst()
          if (window.size >= 16) {
            val tail = ArrayList<FloatArray>(16)
            var skip = window.size - 16
            for (v in window) { if (skip-- > 0) continue; tail.add(v) }
            val vec = WakeVerifier.pool(tail)
            val pct = UserVoiceProfile.matchPercent(c, vec) ?: continue
            lastMatchPercent = pct
            lastMatchAt = SystemClock.elapsedRealtime()
            latest = pct
          }
        }
      }
      return latest
    }
  }

  private fun feedSimpleLocked(samples: ShortArray, count: Int): Float? {
    for (i in 0 until count) {
      if (simpleBuf.size < 16000) simpleBuf.add(samples[i])
    }
    if (simpleBuf.size < 12000) return null
    val vec = SimpleVoiceFeatures.extract(simpleBuf.toShortArray(), 16000) ?: return null
    // Keep a sliding 1s window
    if (simpleBuf.size > 14000) {
      val keep = simpleBuf.takeLast(8000)
      simpleBuf.clear()
      simpleBuf.addAll(keep)
    }
    val pct = UserVoiceProfile.matchPercent(c, vec) ?: return null
    lastMatchPercent = pct
    lastMatchAt = SystemClock.elapsedRealtime()
    return pct
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
