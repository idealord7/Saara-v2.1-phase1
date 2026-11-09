package com.saara.assistant

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.ArrayDeque

/**
 * Shared openWakeWord feature front-end: 16 kHz mono PCM -> 96-dim speech embeddings.
 * Both live detection and on-device enrollment use this so their features match exactly.
 */
class OpenWakeWordFeatures(private val c: Context) {

  companion object {
    const val SAMPLE_RATE = 16000
    const val CHUNK = 1280               // 80 ms
    const val PRE_ROLL = 480             // 30 ms
    const val MEL_BINS = 32
    const val EMB_DIM = 96
    const val MEL_WINDOW = 76
    const val MEL_MAX = 970
    const val DIR = "openwakeword"
    const val MEL_ASSET = "$DIR/melspectrogram.tflite"
    const val EMB_ASSET = "$DIR/embedding_model.tflite"

    fun available(c: Context): Boolean {
      val names = runCatching { c.assets.list(DIR)?.toSet() }.getOrNull() ?: return false
      return names.contains("melspectrogram.tflite") && names.contains("embedding_model.tflite")
    }

    fun loadModel(c: Context, path: String): MappedByteBuffer {
      val afd = c.assets.openFd(path)
      try {
        val length = if (afd.declaredLength >= 0) afd.declaredLength else afd.parcelFileDescriptor.statSize
        FileInputStream(afd.fileDescriptor).use { fis ->
          return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, length)
        }
      } finally {
        try { afd.close() } catch (_: Exception) {}
      }
    }

    fun runModel(interpreter: Interpreter, input: FloatArray): FloatArray {
      val outShape = interpreter.getOutputTensor(0).shape()
      var outSize = 1
      for (d in outShape) outSize *= if (d > 0) d else 1

      val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
      inBuf.asFloatBuffer().put(input)
      val outBuf = ByteBuffer.allocateDirect(outSize.coerceAtLeast(1) * 4).order(ByteOrder.nativeOrder())
      interpreter.run(inBuf, outBuf)

      val out = FloatArray(outSize.coerceAtLeast(1))
      outBuf.rewind()
      outBuf.asFloatBuffer().get(out)
      return out
    }
  }

  private val mel = Interpreter(loadModel(c, MEL_ASSET), Interpreter.Options().setNumThreads(1))
  private val emb = Interpreter(loadModel(c, EMB_ASSET), Interpreter.Options().setNumThreads(1))
  private val melInput = FloatArray(CHUNK + PRE_ROLL)
  private val melBuf = ArrayDeque<FloatArray>()
  private val melWindow = FloatArray(MEL_WINDOW * MEL_BINS)

  init {
    mel.resizeInput(0, intArrayOf(1, CHUNK + PRE_ROLL))
    mel.allocateTensors()
    emb.resizeInput(0, intArrayOf(1, MEL_WINDOW, MEL_BINS, 1))
    emb.allocateTensors()
  }

  /** Feed exactly [CHUNK] samples. Returns a fresh 96-dim embedding once 76 mel frames exist. */
  fun accept(chunk: ShortArray): FloatArray? {
    require(chunk.size == CHUNK)

    System.arraycopy(melInput, CHUNK, melInput, 0, PRE_ROLL)
    for (i in 0 until CHUNK) melInput[PRE_ROLL + i] = chunk[i].toFloat()

    val spec = runModel(mel, melInput)
    val frames = spec.size / MEL_BINS
    for (f in 0 until frames) {
      val row = FloatArray(MEL_BINS)
      for (b in 0 until MEL_BINS) row[b] = spec[f * MEL_BINS + b] / 10f + 2f
      melBuf.addLast(row)
    }
    while (melBuf.size > MEL_MAX) melBuf.removeFirst()

    if (melBuf.size < MEL_WINDOW) return null

    val start = melBuf.size - MEL_WINDOW
    var idx = 0
    var k = 0
    for (row in melBuf) {
      if (k++ < start) continue
      System.arraycopy(row, 0, melWindow, idx, MEL_BINS)
      idx += MEL_BINS
    }
    val e = runModel(emb, melWindow)
    return if (e.size >= EMB_DIM) e.copyOf(EMB_DIM) else null
  }

  fun reset() {
    melBuf.clear()
    melInput.fill(0f)
  }

  fun close() {
    runCatching { mel.close() }
    runCatching { emb.close() }
  }
}
