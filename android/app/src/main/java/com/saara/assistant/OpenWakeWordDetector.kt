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
import org.tensorflow.lite.Interpreter
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Fully offline "Hey Saara" detector built on the openWakeWord TFLite pipeline.
 *
 * Ways it can run:
 *  1. wake model only      – melspectrogram + embedding + hey_saara.tflite
 *  2. wake model + verifier – the user's on-device enrollment gates the model
 *  3. verifier only        – no .tflite wake model needed; the on-device verifier
 *                            trained in the app acts as the detector
 *
 * Required assets (kept uncompressed by the `noCompress 'tflite'` build setting):
 *   openwakeword/melspectrogram.tflite
 *   openwakeword/embedding_model.tflite
 *   openwakeword/hey_saara.tflite   (optional if a trained verifier exists)
 */
class OpenWakeWordDetector(
  private val c: Context,
  private val onWake: () -> Unit,
  private val onUnavailable: (String) -> Unit,
  private val onNotice: (String) -> Unit = {},
) : HotwordDetector {

  companion object {
    private const val DIR = OpenWakeWordFeatures.DIR
    private const val CUSTOM = "$DIR/hey_saara.tflite"
    private const val EMB_DIM = OpenWakeWordFeatures.EMB_DIM

    private const val FEATURE_MAX = 120
    private const val PRED_MAX = 30
    private const val DEFAULT_WINDOW = 16
    private const val VERIFIER_WINDOW = 16
    private const val THRESHOLD = 0.5f
    private const val VERIFIER_GATE_THRESHOLD = 0.5f
    private const val VERIFIER_PRIMARY_THRESHOLD = 0.8f
    private const val DEBOUNCE_MS = 1200L

    /** The custom model when present, otherwise any bundled openWakeWord model. */
    fun wakeModel(c: Context): String? {
      val names = runCatching { c.assets.list(DIR)?.toList() }.getOrNull() ?: return null
      if (names.contains("hey_saara.tflite")) return CUSTOM
      val other = names.firstOrNull {
        it.endsWith(".tflite") &&
          it != "melspectrogram.tflite" &&
          it != "embedding_model.tflite"
      }
      return other?.let { "$DIR/$it" }
    }

    /** True when a wake model OR an on-device verifier can drive detection. */
    fun available(c: Context): Boolean =
      OpenWakeWordFeatures.available(c) && (wakeModel(c) != null || WakeVerifier.exists(c))

    fun modelsAvailable(c: Context): Boolean = available(c)
  }

  private val main = Handler(Looper.getMainLooper())

  @Volatile private var running = false
  @Volatile private var triggered = false
  @Volatile private var record: AudioRecord? = null
  private var thread: Thread? = null

  override fun isRunning() = running

  override fun start() {
    if (running) return
    if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      onUnavailable("Microphone permission is missing.")
      return
    }
    if (!OpenWakeWordFeatures.available(c)) {
      onUnavailable("openWakeWord feature models are missing. Add melspectrogram.tflite and embedding_model.tflite to assets/$DIR/.")
      return
    }

    val wakePath = wakeModel(c)
    val verifier = WakeVerifier.load(c)
    if (wakePath == null && verifier == null) {
      onUnavailable("No wake model or trained voice model found. Add a .tflite model to assets/$DIR/ or train your voice in Settings.")
      return
    }

    when {
      wakePath == null -> onNotice("No .tflite wake model found, so your on-device trained voice model is active.")
      wakePath != CUSTOM && verifier == null ->
        onNotice("Custom \"Hey Saara\" model not found, so \"${wakePath.substringAfterLast('/')}\" is active. Add assets/$CUSTOM or train your voice in Settings.")
      wakePath != CUSTOM ->
        onNotice("\"${wakePath.substringAfterLast('/')}\" is active and gated by your on-device trained voice model.")
    }

    running = true
    triggered = false
    thread = Thread({ infer(wakePath, verifier) }, "saara-openwakeword").also { it.start() }
  }

  private fun infer(wakePath: String?, verifier: WakeVerifier?) {
    var features: OpenWakeWordFeatures? = null
    var wake: Interpreter? = null
    var rec: AudioRecord? = null
    try {
      features = OpenWakeWordFeatures(c)

      var window = DEFAULT_WINDOW
      if (wakePath != null) {
        wake = Interpreter(
          OpenWakeWordFeatures.loadModel(c, wakePath),
          Interpreter.Options().setNumThreads(1)
        )
        val shape = wake.getInputTensor(0).shape()
        window = if (shape.size >= 2 && shape[1] > 0) shape[1] else DEFAULT_WINDOW
        wake.resizeInput(0, intArrayOf(1, window, EMB_DIM))
        wake.allocateTensors()
      }
      val readyForWake = wake == null || window > 0
      if (!readyForWake) throw IllegalStateException("Unsupported wake model shape")

      val minBuf = AudioRecord.getMinBufferSize(
        OpenWakeWordFeatures.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      rec = AudioRecord.Builder()
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
        throw IllegalStateException("Microphone is busy")
      }
      record = rec
      rec.startRecording()

      val audio = ShortArray(OpenWakeWordFeatures.CHUNK)
      val feats = ArrayDeque<FloatArray>()
      val preds = ArrayDeque<Float>()
      var lastHit = 0L
      var pending = 0

      while (running) {
        val n = rec.read(audio, pending, OpenWakeWordFeatures.CHUNK - pending)
        if (n <= 0) {
          if (n < 0) break else continue
        }
        pending += n
        if (pending < OpenWakeWordFeatures.CHUNK) continue
        pending = 0

        val embedding = features.accept(audio) ?: continue
        feats.addLast(embedding)
        while (feats.size > FEATURE_MAX) feats.removeFirst()

        if (feats.size < VERIFIER_WINDOW) continue

        val baseScore = if (wake != null && feats.size >= window) {
          val input = FloatArray(window * EMB_DIM)
          val win = lastN(feats, window)
          var idx = 0
          for (f in win) {
            System.arraycopy(f, 0, input, idx, EMB_DIM)
            idx += EMB_DIM
          }
          val out = OpenWakeWordFeatures.runModel(wake, input)
          if (out.size > 1) (out.maxOrNull() ?: 0f) else out[0]
        } else 1f

        val verifierScore = if (verifier != null) {
          verifier.score(WakeVerifier.pool(lastN(feats, VERIFIER_WINDOW)))
        } else 1f

        val hit = when {
          wake != null && verifier != null ->
            baseScore >= THRESHOLD && verifierScore >= VERIFIER_GATE_THRESHOLD
          wake != null -> baseScore >= THRESHOLD
          verifier != null -> verifierScore >= VERIFIER_PRIMARY_THRESHOLD
          else -> false
        }

        val settled = preds.size >= 5
        preds.addLast(if (hit) 1f else 0f)
        while (preds.size > PRED_MAX) preds.removeFirst()

        val now = SystemClock.elapsedRealtime()
        if (settled && hit && now - lastHit > DEBOUNCE_MS && !triggered) {
          triggered = true
          lastHit = now
          running = false
          main.post { if (triggered) onWake() }
          break
        }
      }
    } catch (e: Exception) {
      if (running) main.post { onUnavailable("openWakeWord could not start: ${e.message}") }
    } finally {
      running = false
      try { rec?.stop() } catch (_: Exception) {}
      try { rec?.release() } catch (_: Exception) {}
      record = null
      features?.close()
      runCatching { wake?.close() }
    }
  }

  private fun lastN(q: ArrayDeque<FloatArray>, n: Int): List<FloatArray> {
    val out = ArrayList<FloatArray>(n)
    var skip = q.size - n
    for (v in q) {
      if (skip-- > 0) continue
      out.add(v)
    }
    return out
  }

  override fun stop() {
    running = false
    triggered = false
    try { record?.stop() } catch (_: Exception) {}
    try { record?.release() } catch (_: Exception) {}
    record = null
    thread?.interrupt()
    thread = null
  }
}
