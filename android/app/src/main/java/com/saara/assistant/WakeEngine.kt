package com.saara.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.util.ArrayDeque
import kotlin.math.max

/**
 * On-device openWakeWord processor that consumes 16kHz mono PCM chunks from an external
 * AudioRecord stream (so VoiceService can keep one continuous capture). In idle mode,
 * feed chunks here and do NOT stream audio to Gemini; on wake, switch to Gemini streaming.
 */
class WakeEngine(
  private val c: Context,
  private val onWake: () -> Unit,
  private val onUnavailable: (String) -> Unit = {},
  private val onNotice: (String) -> Unit = {},
) {
  companion object {
    private const val DIR = OpenWakeWordFeatures.DIR
    private const val CUSTOM = "$DIR/hey_saara.tflite"
    private const val EMB_DIM = OpenWakeWordFeatures.EMB_DIM
    private const val CHUNK = OpenWakeWordFeatures.CHUNK

    private const val FEATURE_MAX = 120
    private const val PRED_MAX = 30
    private const val DEFAULT_WINDOW = 16
    private const val VERIFIER_WINDOW = 16
    private const val THRESHOLD = 0.5f
    private const val VERIFIER_GATE_THRESHOLD = 0.5f
    private const val VERIFIER_PRIMARY_THRESHOLD = 0.8f
    private const val DEBOUNCE_MS = 1200L
  }

  private val main = Handler(Looper.getMainLooper())
  private val lock = Any()

  @Volatile var running = false
    private set

  private var features: OpenWakeWordFeatures? = null
  private var wake: Interpreter? = null
  private var verifier: WakeVerifier? = null
  private var window = DEFAULT_WINDOW

  private val audio = ShortArray(CHUNK)
  private var pending = 0
  private val feats = ArrayDeque<FloatArray>()
  private val preds = ArrayDeque<Float>()
  private var lastHit = 0L
  private var settled = false
  @Volatile private var failed = false

  fun available(): Boolean = OpenWakeWordFeatures.available(c) &&
    (OpenWakeWordDetector.wakeModel(c) != null || WakeVerifier.exists(c))

  fun start(): Boolean {
    synchronized(lock) {
      if (running) return true
      if (!OpenWakeWordFeatures.available(c)) {
        onUnavailable("openWakeWord feature models are missing (melspectrogram/embedding).")
        return false
      }
      val wakePath = OpenWakeWordDetector.wakeModel(c)
      val v = WakeVerifier.load(c)
      if (wakePath == null && v == null) {
        onUnavailable("No wake model or trained voice model found.")
        return false
      }
      try {
        features = OpenWakeWordFeatures(c)
        verifier = v
        if (wakePath != null) {
          val interp = Interpreter(
            OpenWakeWordFeatures.loadModel(c, wakePath),
            Interpreter.Options().setNumThreads(1)
          )
          try {
            val shape = interp.getInputTensor(0).shape()
            window = if (shape.size >= 2 && shape[1] > 0) shape[1] else DEFAULT_WINDOW
            interp.allocateTensors()
          } catch (e: Exception) {
            try {
              val shape = interp.getInputTensor(0).shape()
              window = if (shape.size >= 2 && shape[1] > 0) shape[1] else DEFAULT_WINDOW
              interp.resizeInput(0, intArrayOf(1, window, EMB_DIM))
              interp.allocateTensors()
            } catch (e2: Exception) { throw e2 }
          }
          wake = interp
        } else {
          window = DEFAULT_WINDOW
        }
        resetState()
        failed = false
        running = true
        when {
          wakePath == null -> onNotice("Using on-device trained voice model for wake.")
          wakePath != "$DIR/hey_saara.tflite" && v == null ->
            onNotice("Wake model active: ${wakePath.substringAfterLast('/')}.")
        }
        return true
      } catch (e: Exception) {
        stop()
        onUnavailable("Wake engine failed: ${e.message}")
        return false
      }
    }
  }

  private fun resetState() {
    pending = 0
    feats.clear()
    preds.clear()
    settled = false
    try { features?.reset() } catch (_: Exception) {}
  }

  /** Feed PCM samples (any count). Thread-safe; call from capture thread. */
  fun feed(samples: ShortArray, count: Int) {
    if (!running || count <= 0) return
    synchronized(lock) {
      if (!running) return
      var i = 0
      while (i < count) {
        val take = minOf(count - i, CHUNK - pending)
        for (j in 0 until take) audio[pending + j] = samples[i + j]
        pending += take
        i += take
        if (pending >= CHUNK) {
          pending = 0
          processChunk()
          if (!running) return
        }
      }
    }
  }

  private fun processChunk() {
    if (failed) return
    val f = features ?: return
    val embedding = try { f.accept(audio) } catch (e: Exception) { fail(e); return } ?: return
    feats.addLast(embedding)
    while (feats.size > FEATURE_MAX) feats.removeFirst()
    if (feats.size < VERIFIER_WINDOW) return

    try {
      val baseScore = if (wake != null && feats.size >= window) {
        val input = FloatArray(window * EMB_DIM)
        val win = lastN(feats, window)
        var idx = 0
        for (row in win) {
          val copy = minOf(row.size, EMB_DIM)
          if (copy > 0) System.arraycopy(row, 0, input, idx, copy)
          idx += EMB_DIM
        }
        val out = OpenWakeWordFeatures.runModel(wake!!, input)
        if (out.size > 1) (out.maxOrNull() ?: 0f) else out[0]
      } else 1f

      val verifierScore = verifier?.let { it.score(WakeVerifier.pool(lastN(feats, VERIFIER_WINDOW))) } ?: 1f

      val hit = when {
        wake != null && verifier != null ->
          baseScore >= THRESHOLD && verifierScore >= VERIFIER_GATE_THRESHOLD
        wake != null -> baseScore >= THRESHOLD
        verifier != null -> verifierScore >= VERIFIER_PRIMARY_THRESHOLD
        else -> false
      }

      if (preds.size >= 5) settled = true
      preds.addLast(if (hit) 1f else 0f)
      while (preds.size > PRED_MAX) preds.removeFirst()

      val now = SystemClock.elapsedRealtime()
      if (settled && hit && now - lastHit > DEBOUNCE_MS) {
        lastHit = now
        running = false
        main.post { onWake() }
      }
    } catch (e: Exception) {
      fail(e)
    }
  }

  private fun fail(e: Exception) {
    if (failed) return
    failed = true
    running = false
    val msg = "Wake engine failed: ${e.message}"
    main.post { onUnavailable(msg) }
  }

  private fun lastN(q: ArrayDeque<FloatArray>, n: Int): List<FloatArray> {
    val out = ArrayList<FloatArray>(n)
    var skip = max(0, q.size - n)
    for (v in q) {
      if (skip-- > 0) continue
      out.add(v)
    }
    return out
  }

  fun stop() {
    synchronized(lock) {
      running = false
      try { features?.close() } catch (_: Exception) {}
      try { wake?.close() } catch (_: Exception) {}
      features = null; wake = null; verifier = null
      feats.clear(); preds.clear(); pending = 0
    }
  }
}
