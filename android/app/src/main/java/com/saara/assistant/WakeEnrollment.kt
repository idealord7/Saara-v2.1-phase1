package com.saara.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Guided on-device enrollment: records positives ("Hey Saara") and negatives
 * (other phrases), then trains and saves a [WakeVerifier].
 */
class WakeEnrollment(private val c: Context) {

  companion object {
    private const val POSITIVES = 5
    private const val NEGATIVES = 5
    private const val RECORD_MS = 2400L
    private const val PROMPT_MS = 900L
    private const val SETTLE_MS = 900L
    private const val MIN_EMBEDDINGS = 8
    private const val VERIFIER_WINDOW = 16
  }

  private val main = Handler(Looper.getMainLooper())

  fun train(onPrompt: (String) -> Unit, onDone: (String) -> Unit) {
    if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      onDone("Microphone permission is required to enroll.")
      return
    }
    if (!OpenWakeWordFeatures.available(c)) {
      onDone("openWakeWord feature models are missing. Add melspectrogram.tflite and embedding_model.tflite to assets/openwakeword/.")
      return
    }

    Thread {
      val features = runCatching { OpenWakeWordFeatures(c) }.getOrElse {
        main.post { onDone("Could not load the openWakeWord feature models.") }
        return@Thread
      }
      val positives = ArrayList<FloatArray>()
      val negatives = ArrayList<FloatArray>()
      try {
        Thread.sleep(SETTLE_MS)
        repeat(POSITIVES) { i ->
          main.post { onPrompt("Say \"Hey Saara\"  (${i + 1}/$POSITIVES)") }
          Thread.sleep(PROMPT_MS)
          record(features)?.let { positives.add(it) }
          Thread.sleep(250)
        }
        repeat(NEGATIVES) { i ->
          main.post { onPrompt("Say any other phrase  (${i + 1}/$NEGATIVES)") }
          Thread.sleep(PROMPT_MS)
          record(features)?.let { negatives.add(it) }
          Thread.sleep(250)
        }
        if (positives.size < 3 || negatives.size < 3) {
          main.post { onDone("Enrollment failed: not enough clear samples. Try again in a quiet room.") }
          return@Thread
        }
        val samples = positives.map { it to 1 } + negatives.map { it to 0 }
        val verifier = WakeVerifier.train(samples)
        verifier.save(c)
        main.post {
          onDone("Voice model trained on ${positives.size} positive and ${negatives.size} negative samples.\n\nSay \"Hey Saara\" to test it.")
        }
      } catch (e: Exception) {
        main.post { onDone("Enrollment failed: ${e.message}") }
      } finally {
        features.close()
      }
    }.start()
  }

  private fun record(features: OpenWakeWordFeatures): FloatArray? {
    val minBuf = AudioRecord.getMinBufferSize(
      OpenWakeWordFeatures.SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    val rec = AudioRecord.Builder()
      .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(OpenWakeWordFeatures.SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .build()
      )
      .setBufferSizeInBytes(max(minBuf, OpenWakeWordFeatures.CHUNK * 4))
      .build()
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      return null
    }

    features.reset()
    val chunk = ShortArray(OpenWakeWordFeatures.CHUNK)
    val embeddings = ArrayList<FloatArray>()
    var pending = 0
    try {
      rec.startRecording()
      val end = SystemClock.elapsedRealtime() + RECORD_MS
      while (SystemClock.elapsedRealtime() < end) {
        val n = rec.read(chunk, pending, OpenWakeWordFeatures.CHUNK - pending)
        if (n <= 0) continue
        pending += n
        if (pending < OpenWakeWordFeatures.CHUNK) continue
        pending = 0
        features.accept(chunk)?.let { embeddings.add(it) }
      }
    } catch (_: Exception) {
    } finally {
      try { rec.stop() } catch (_: Exception) {}
      try { rec.release() } catch (_: Exception) {}
    }

    if (embeddings.size < MIN_EMBEDDINGS) return null
    val window = embeddings.takeLast(VERIFIER_WINDOW)
    return WakeVerifier.pool(window)
  }
}
