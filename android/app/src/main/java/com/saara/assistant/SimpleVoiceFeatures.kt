package com.saara.assistant

import kotlin.math.*

/**
 * Fallback lightweight voice features when TFLite wake-feature models fail to initialize.
 * Produces a 64-dim normalized vector from 16kHz PCM frames.
 */
object SimpleVoiceFeatures {
  const val SAMPLE_RATE = 16000
  const val FRAME = 512
  const val HOP = 256
  const val DIM = 64

  fun extract(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): FloatArray? {
    if (samples.isEmpty()) return null
    val pcm = if (sampleRate == SAMPLE_RATE) samples else resample(samples, sampleRate, SAMPLE_RATE)
    if (pcm.size < FRAME) return null
    val bands = 32
    val bandAcc = FloatArray(bands)
    val bandSq = FloatArray(bands)
    var frames = 0
    var i = 0
    val window = DoubleArray(FRAME) { k -> 0.5 - 0.5 * cos(2.0 * PI * k / (FRAME - 1)) }
    while (i + FRAME <= pcm.size) {
      val mag = fftMag(pcm, i, FRAME, window)
      for (b in 0 until bands) {
        val f0 = hzToBin(80.0 + b * 180.0, SAMPLE_RATE, FRAME)
        val f1 = hzToBin(80.0 + (b + 1) * 180.0, SAMPLE_RATE, FRAME)
        var sum = 0.0
        val a = f0.coerceIn(1, mag.size - 1)
        val z = max(a + 1, f1.coerceIn(a + 1, mag.size))
        for (k in a until z) sum += mag[k]
        val v = ln(1.0 + sum / max(1, z - a)).toFloat()
        bandAcc[b] += v
        bandSq[b] += v * v
      }
      frames++
      i += HOP
    }
    if (frames < 4) return null
    val out = FloatArray(DIM)
    for (b in 0 until bands) {
      val mean = bandAcc[b] / frames
      val variance = max(0f, bandSq[b] / frames - mean * mean)
      out[b] = mean
      out[bands + b] = sqrt(variance)
    }
    normalize(out)
    return out
  }

  private fun normalize(v: FloatArray) {
    var sumSq = 0.0
    for (x in v) sumSq += x * x
    val inv = if (sumSq > 1e-9) (1.0 / sqrt(sumSq)).toFloat() else 1f
    for (i in v.indices) v[i] *= inv
  }

  private fun resample(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
    if (srcRate == dstRate) return src
    val n = max(1, (src.size.toLong() * dstRate / srcRate).toInt())
    val out = ShortArray(n)
    for (i in 0 until n) {
      val sp = i.toDouble() * srcRate / dstRate
      val i0 = sp.toInt().coerceIn(0, src.size - 1)
      val i1 = min(src.size - 1, i0 + 1)
      val f = sp - i0
      val v = src[i0] * (1 - f) + src[i1] * f
      out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return out
  }

  private fun hzToBin(hz: Double, rate: Int, n: Int): Int = ((hz * n) / rate).roundToInt()

  private fun fftMag(pcm: ShortArray, off: Int, n: Int, win: DoubleArray): DoubleArray {
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    for (i in 0 until n) re[i] = (pcm[off + i] / 32768.0) * win[i]
    // bit-reverse
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
      j = j xor bit
      if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
    }
    var len = 2
    while (len <= n) {
      val ang = -2.0 * PI / len
      val wlenRe = cos(ang)
      val wlenIm = sin(ang)
      var i = 0
      while (i < n) {
        var wRe = 1.0; var wIm = 0.0
        for (k in 0 until len / 2) {
          val uRe = re[i + k]; val uIm = im[i + k]
          val vRe0 = re[i + k + len / 2]; val vIm0 = im[i + k + len / 2]
          val vRe = vRe0 * wRe - vIm0 * wIm
          val vIm = vRe0 * wIm + vIm0 * wRe
          re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
          re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
          val nwRe = wRe * wlenRe - wIm * wlenIm
          wIm = wRe * wlenIm + wIm * wlenRe
          wRe = nwRe
        }
        i += len
      }
      len = len shl 1
    }
    val half = n / 2
    val mag = DoubleArray(half)
    for (i in 0 until half) mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
    return mag
  }
}
