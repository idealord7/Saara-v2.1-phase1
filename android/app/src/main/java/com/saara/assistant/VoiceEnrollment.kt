package com.saara.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * On-device voice enrollment: records short samples, extracts openWakeWord embeddings,
 * pools them into vectors, and saves a lightweight UserVoiceProfile.
 * No audio or embeddings leave the device.
 */
object VoiceEnrollment {
  data class Progress(val step: Int, val total: Int, val message: String)
  data class Result(val ok: Boolean, val message: String, val sampleCount: Int = 0)

  private const val SAMPLE_RATE = OpenWakeWordFeatures.SAMPLE_RATE
  private const val CHUNK = OpenWakeWordFeatures.CHUNK
  private const val DEFAULT_SAMPLES = 3
  private const val SAMPLE_MS = 2600L
  private const val WINDOW = 16

  fun enroll(
    c: Context,
    samples: Int = DEFAULT_SAMPLES,
    onProgress: (Progress) -> Unit = {},
  ): Result {
    if (!OpenWakeWordFeatures.available(c)) {
      return Result(false, "Wake feature models missing. Cannot enroll voice on this device.")
    }
    val total = samples.coerceIn(2, 5)
    val vectors = ArrayList<FloatArray>()
    for (i in 1..total) {
      onProgress(Progress(i, total, "Recording sample $i/$total… Speak naturally for ~3 seconds."))
      val vec = recordOneSample(c) ?: return Result(false, "Could not record sample $i. Check microphone permission and try again.", vectors.size)
      vectors.add(vec)
      onProgress(Progress(i, total, "Sample $i/$total captured."))
      if (i < total) SystemClock.sleep(700)
    }
    if (vectors.size < 2) return Result(false, "Not enough voice samples captured.", vectors.size)
    val profile = UserVoiceProfile.enroll(vectors)
    UserVoiceProfile.save(c, profile)
    return Result(true, "Voice enrolled (${vectors.size} samples). Only-your-voice matching is ready.", vectors.size)
  }

  private fun recordOneSample(c: Context): FloatArray? {
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try {
      AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(max(minBuf, CHUNK * 4))
        .build()
    } catch (_: Exception) { return null }
    if (rec.state != AudioRecord.STATE_INITIALIZED) { runCatching { rec.release() }; return null }
    val features = try { OpenWakeWordFeatures(c) } catch (_: Exception) { runCatching { rec.release() }; return null }
    val embeddings = ArrayList<FloatArray>()
    val buf = ShortArray(CHUNK)
    var pending = 0
    val deadline = SystemClock.elapsedRealtime() + SAMPLE_MS
    try {
      rec.startRecording()
      // Discard very first noisy buffer
      val warm = ShortArray(CHUNK)
      rec.read(warm, 0, warm.size)
      while (SystemClock.elapsedRealtime() < deadline) {
        val n = rec.read(buf, pending, CHUNK - pending)
        if (n <= 0) continue
        pending += n
        if (pending < CHUNK) continue
        pending = 0
        val emb = features.accept(buf) ?: continue
        embeddings.add(emb.copyOf())
        while (embeddings.size > 120) embeddings.removeAt(0)
      }
    } catch (_: Exception) {
      return null
    } finally {
      runCatching { rec.stop() }; runCatching { rec.release() }; runCatching { features.close() }
    }
    if (embeddings.size < WINDOW) return null
    val tail = embeddings.takeLast(WINDOW)
    return WakeVerifier.pool(tail)
  }

  /**
   * Quick capture for live diagnostics: returns a match percent against enrolled profile or null.
   */
  fun quickCheck(c: Context): Float? {
    if (!UserVoiceProfile.exists(c) || !OpenWakeWordFeatures.available(c)) return null
    val vec = recordOneSample(c) ?: return null
    return UserVoiceProfile.matchPercent(c, vec)
  }
}
