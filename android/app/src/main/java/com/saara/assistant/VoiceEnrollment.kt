package com.saara.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * On-device voice enrollment: records short samples, extracts openWakeWord embeddings,
 * pools them into vectors, and saves a lightweight UserVoiceProfile.
 * No audio or embeddings leave the device.
 */
object VoiceEnrollment {
  data class Progress(val step: Int, val total: Int, val message: String)
  data class Result(val ok: Boolean, val message: String, val sampleCount: Int = 0)

  private const val DEFAULT_SAMPLES = 3
  private const val SAMPLE_MS = 2800L
  private const val WINDOW = 16
  private val SAMPLE_RATES = intArrayOf(16000, 44100, 22050, 11025)
  private val AUDIO_SOURCES = intArrayOf(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    MediaRecorder.AudioSource.MIC,
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    MediaRecorder.AudioSource.DEFAULT,
  )

  private class CaptureFailure(var reason: String)

  fun hasRecordPermission(c: Context): Boolean =
    ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  fun enroll(
    c: Context,
    samples: Int = DEFAULT_SAMPLES,
    onProgress: (Progress) -> Unit = {},
  ): Result {
    if (!hasRecordPermission(c)) {
      return Result(false, "Microphone permission denied. Allow Microphone permission and try again.")
    }
    if (!OpenWakeWordFeatures.available(c)) {
      return Result(false, "Wake feature models missing. Cannot enroll voice on this device.")
    }
    val total = samples.coerceIn(2, 5)
    val vectors = ArrayList<FloatArray>()
    for (i in 1..total) {
      if (!hasRecordPermission(c)) {
        return Result(false, "Microphone permission denied before sample $i. Allow Microphone permission and try again.", vectors.size)
      }
      onProgress(Progress(i, total, "Recording sample $i/$total… Speak naturally for ~3 seconds."))
      val failure = CaptureFailure("")
      val vec = recordOneSample(c, failure)
      if (vec == null) {
        val reason = failure.reason.ifBlank { "unknown capture failure" }
        return Result(false, "Could not record sample $i: $reason", vectors.size)
      }
      vectors.add(vec)
      onProgress(Progress(i, total, "Sample $i/$total captured."))
      if (i < total) SystemClock.sleep(700)
    }
    if (vectors.size < 2) return Result(false, "Not enough voice samples captured.", vectors.size)
    return try {
      val profile = UserVoiceProfile.enroll(vectors)
      UserVoiceProfile.save(c, profile)
      Result(true, "Voice enrolled (${vectors.size} samples). Only-your-voice matching is ready.", vectors.size)
    } catch (e: Exception) {
      Result(false, "Failed to save voice profile: ${e.message ?: "unknown error"}", vectors.size)
    }
  }

  private fun recordOneSample(c: Context, failure: CaptureFailure): FloatArray? {
    var lastReason = "no audio source/sample rate combination initialized"
    if (!hasRecordPermission(c)) {
      failure.reason = "microphone permission denied"
      return null
    }
    if (!OpenWakeWordFeatures.available(c)) {
      failure.reason = "wake feature models missing"
      return null
    }

    for (rate in SAMPLE_RATES) {
      val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
        lastReason = "unsupported audio config at ${rate}Hz"
        continue
      }
      val bufSize = max(minBuf * 2, rate / 5 * 2) // >=200ms
      for (source in AUDIO_SOURCES) {
        if (!hasRecordPermission(c)) {
          failure.reason = "microphone permission denied"
          return null
        }
        var recorder: AudioRecord? = null
        var feat: OpenWakeWordFeatures? = null
        try {
          val built = try {
            AudioRecord.Builder()
              .setAudioSource(source)
              .setAudioFormat(
                AudioFormat.Builder()
                  .setSampleRate(rate)
                  .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                  .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                  .build()
              )
              .setBufferSizeInBytes(bufSize)
              .build()
          } catch (e: Exception) {
            lastReason = "AudioRecord init error (${sourceName(source)}, ${rate}Hz): ${e.message ?: e.javaClass.simpleName}"
            null
          } ?: continue
          recorder = built

          if (built.state != AudioRecord.STATE_INITIALIZED) {
            lastReason = "AudioRecord not initialized (${sourceName(source)}, ${rate}Hz)"
            runCatching { built.release() }
            recorder = null
            continue
          }

          val builtFeat = try { OpenWakeWordFeatures(c) } catch (e: Exception) {
            lastReason = "feature extractor init error: ${e.message ?: e.javaClass.simpleName}"
            failure.reason = lastReason
            runCatching { built.release() }
            recorder = null
            return null
          }
          feat = builtFeat

          try {
            built.startRecording()
          } catch (e: Exception) {
            lastReason = "startRecording failed (${sourceName(source)}, ${rate}Hz): ${e.message ?: e.javaClass.simpleName}"
            runCatching { builtFeat.close() }
            runCatching { built.release() }
            recorder = null
            feat = null
            continue
          }
          if (built.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            lastReason = "recorder did not enter recording state (${sourceName(source)}, ${rate}Hz)"
            runCatching { built.stop() }
            runCatching { built.release() }
            runCatching { builtFeat.close() }
            recorder = null
            feat = null
            continue
          }

          val (vec, captureErr) = captureFromRecorder(built, builtFeat, rate, source)
          runCatching { built.stop() }
          runCatching { built.release() }
          runCatching { builtFeat.close() }
          recorder = null
          feat = null
          if (vec != null) return vec
          lastReason = captureErr?.takeIf { it.isNotBlank() } ?: lastReason
          // Try next source/rate on empty/timeout/init-like failures.
        } catch (e: Exception) {
          lastReason = "capture error (${sourceName(source)}, ${rate}Hz): ${e.message ?: e.javaClass.simpleName}"
          runCatching { recorder?.stop() }
          runCatching { recorder?.release() }
          runCatching { feat?.close() }
          recorder = null
          feat = null
        } finally {
          runCatching { recorder?.release() }
          runCatching { feat?.close() }
        }
      }
    }
    failure.reason = lastReason.ifBlank { "unknown capture failure" }
    return null
  }

  private fun captureFromRecorder(rec: AudioRecord, features: OpenWakeWordFeatures, sampleRate: Int, source: Int): Pair<FloatArray?, String?> {
    val chunkSamples = max(1, sampleRate * 80 / 1000) // ~80ms
    val srcBuf = ShortArray(chunkSamples)
    val target = ShortArray(OpenWakeWordFeatures.CHUNK)
    var pending = 0
    val embeddings = ArrayList<FloatArray>()
    val deadline = SystemClock.elapsedRealtime() + SAMPLE_MS
    var totalRead = 0
    var readErrors = 0
    var lastReadCode = 0
    // Warmup read
    val warm = ShortArray(chunkSamples)
    try { rec.read(warm, 0, warm.size) } catch (_: Exception) {}
    while (SystemClock.elapsedRealtime() < deadline) {
      val n = try { rec.read(srcBuf, 0, srcBuf.size) } catch (_: Exception) { Int.MIN_VALUE }
      if (n <= 0) {
        lastReadCode = n
        if (n < 0) readErrors++
        if (readErrors > 12) return null to "audio read error (${sourceName(source)}, ${sampleRate}Hz, code=$n)"
        SystemClock.sleep(20)
        continue
      }
      totalRead += n
      if (sampleRate == OpenWakeWordFeatures.SAMPLE_RATE) {
        var i = 0
        while (i < n) {
          val take = minOf(n - i, OpenWakeWordFeatures.CHUNK - pending)
          System.arraycopy(srcBuf, i, target, pending, take)
          pending += take
          i += take
          if (pending >= OpenWakeWordFeatures.CHUNK) {
            pending = 0
            val emb = try { features.accept(target) } catch (_: Exception) { null } ?: continue
            embeddings.add(emb.copyOf())
            while (embeddings.size > 120) embeddings.removeAt(0)
          }
        }
      } else {
        var i = 0
        while (i < n) {
          // Simple downsample/resample by nearest sample to 16kHz.
          val srcPos = i.toLong()
          val dstIndex = ((srcPos * OpenWakeWordFeatures.SAMPLE_RATE) / sampleRate).toInt()
          // Fill sequentially; approximate by stepping source.
          target[pending++] = srcBuf[i]
          val step = max(1, sampleRate / OpenWakeWordFeatures.SAMPLE_RATE)
          i += step
          if (pending >= OpenWakeWordFeatures.CHUNK) {
            pending = 0
            val emb = try { features.accept(target) } catch (_: Exception) { null } ?: continue
            embeddings.add(emb.copyOf())
            while (embeddings.size > 120) embeddings.removeAt(0)
          }
        }
      }
    }
    if (totalRead <= 0) return null to "no audio samples captured (${sourceName(source)}, ${sampleRate}Hz, lastRead=$lastReadCode)"
    if (embeddings.size < WINDOW) return null to "captured audio but not enough speech embeddings (${embeddings.size}/$WINDOW, read=$totalRead, ${sourceName(source)}, ${sampleRate}Hz)"
    val tail = embeddings.takeLast(WINDOW)
    return WakeVerifier.pool(tail) to null
  }

  private fun sourceName(source: Int): String = when (source) {
    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
    MediaRecorder.AudioSource.MIC -> "MIC"
    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
    MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
    else -> "source_$source"
  }

  /**
   * Quick capture for live diagnostics: returns a match percent against enrolled profile or null.
   */
  fun quickCheck(c: Context): Pair<Float?, String?> {
    if (!hasRecordPermission(c)) return null to "microphone permission denied"
    if (!UserVoiceProfile.exists(c)) return null to "no enrolled voice profile"
    if (!OpenWakeWordFeatures.available(c)) return null to "wake feature models missing"
    val failure = CaptureFailure("")
    val vec = recordOneSample(c, failure)
    if (vec == null) return null to failure.reason.ifBlank { "capture failed" }
    val pct = UserVoiceProfile.matchPercent(c, vec)
    return pct to null
  }
}
