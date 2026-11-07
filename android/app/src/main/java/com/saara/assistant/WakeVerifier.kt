package com.saara.assistant

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Tiny on-device logistic-regression verifier trained from openWakeWord embeddings.
 *
 * The feature vector is the mean + standard deviation of the most recent 16 embeddings
 * (2 x 96 = 192 dims). Training runs in well under a second on a phone.
 *
 * It is used in two ways:
 *  - as a personalization gate on top of a trained `hey_saara.tflite` wake model, or
 *  - as the primary detector when no wake model is present (verifier-only mode).
 */
class WakeVerifier(
  private val w: FloatArray,
  private val b: Float,
  private val mu: FloatArray,
  private val sigma: FloatArray,
) {
  val dim: Int get() = w.size

  fun score(pooled: FloatArray): Float {
    if (pooled.size != w.size) return 0f
    var z = b
    for (j in w.indices) {
      val s = if (sigma[j] > 1e-6f) sigma[j] else 1f
      z += w[j] * ((pooled[j] - mu[j]) / s)
    }
    return 1f / (1f + exp(-z))
  }

  fun save(c: Context) {
    DataOutputStream(File(c.filesDir, FILE).outputStream().buffered()).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(w.size)
      for (v in w) out.writeFloat(v)
      out.writeFloat(b)
      for (v in mu) out.writeFloat(v)
      for (v in sigma) out.writeFloat(v)
    }
  }

  companion object {
    private const val FILE = "wake_verifier.bin"
    private const val MAGIC = 0x53415631

    fun exists(c: Context): Boolean = File(c.filesDir, FILE).isFile

    fun load(c: Context): WakeVerifier? {
      return try {
        val f = File(c.filesDir, FILE)
        if (!f.isFile) return null
        DataInputStream(f.inputStream().buffered()).use { input ->
          if (input.readInt() != MAGIC) return null
          val d = input.readInt()
          if (d <= 0 || d > 4096) return null
          val w = FloatArray(d) { input.readFloat() }
          val b = input.readFloat()
          val mu = FloatArray(d) { input.readFloat() }
          val sigma = FloatArray(d) { input.readFloat() }
          WakeVerifier(w, b, mu, sigma)
        }
      } catch (_: Exception) {
        null
      }
    }

    fun delete(c: Context) {
      runCatching { File(c.filesDir, FILE).delete() }
    }

    /** Mean + standard deviation of the supplied embeddings -> 2*EMB_DIM features. */
    fun pool(embeddings: List<FloatArray>): FloatArray {
      require(embeddings.isNotEmpty())
      val d = embeddings[0].size
      val out = FloatArray(d * 2)
      for (j in 0 until d) {
        var mean = 0f
        for (e in embeddings) mean += e[j]
        mean /= embeddings.size
        var variance = 0f
        for (e in embeddings) {
          val dv = e[j] - mean
          variance += dv * dv
        }
        out[j] = mean
        out[d + j] = sqrt(variance / embeddings.size)
      }
      return out
    }

    /** Binary logistic regression with L2 regularization, trained fully on-device. */
    fun train(samples: List<Pair<FloatArray, Int>>): WakeVerifier {
      require(samples.size >= 6) { "Need at least 6 samples" }
      val n = samples.size
      val d = samples[0].first.size
      require(d > 0)

      val mu = FloatArray(d)
      for ((x, _) in samples) for (j in 0 until d) mu[j] = mu[j] + x[j]
      for (j in 0 until d) mu[j] = mu[j] / n

      val sigma = FloatArray(d)
      for ((x, _) in samples) for (j in 0 until d) {
        val dv = x[j] - mu[j]
        sigma[j] = sigma[j] + dv * dv
      }
      for (j in 0 until d) sigma[j] = sqrt(sigma[j] / n).coerceAtLeast(1e-3f)

      val xs = samples.map { (x, _) -> FloatArray(d) { j -> (x[j] - mu[j]) / sigma[j] } }
      val ys = samples.map { it.second }

      val w = FloatArray(d)
      var b = 0f
      val lr = 0.1f
      val l2 = 1e-3f
      repeat(600) {
        val gw = FloatArray(d)
        var gb = 0f
        for (i in 0 until n) {
          var z = b
          for (j in 0 until d) z += w[j] * xs[i][j]
          val p = 1f / (1f + exp(-z))
          val e = p - ys[i]
          for (j in 0 until d) gw[j] = gw[j] + e * xs[i][j]
          gb += e
        }
        for (j in 0 until d) w[j] = w[j] - lr * (gw[j] / n + l2 * w[j])
        b -= lr * gb / n
      }
      return WakeVerifier(w, b, mu, sigma)
    }
  }
}
