package com.lineup.app.pipeline

import android.content.Context
import android.graphics.Bitmap
// LiteRT (com.google.ai.edge.litert:litert) ships its Interpreter under the same package as
// classic TFLite for drop-in compatibility -- see BUILD_GUIDE.md's LiteRT note.
import org.tensorflow.lite.Interpreter
import com.lineup.app.model.FaceDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device face embedding via FaceNet (Keras-FaceNet, ported to TFLite/LiteRT), sourced from
 * shubham0204/FaceRecognition_With_FaceNet_Android (Apache-2.0):
 * https://github.com/shubham0204/FaceRecognition_With_FaceNet_Android
 *   assets/facenet.tflite — input 160x160x3, output 128-dim embedding.
 *
 * Preprocessing matches that repo's FaceNetModel.kt exactly: bilinear resize to 160x160, then
 * per-image standardization `(x - mean) / max(std, 1/sqrt(N))` over all pixels (not a fixed
 * ImageNet mean/std) — this is the classic FaceNet "prewhiten" step. Getting this normalization
 * wrong is the most common way to silently wreck embedding quality (see BUILD_GUIDE.md §3).
 *
 * Output embeddings are L2-normalized here so cosine similarity in FaceClusterer is well-defined.
 */
class FaceEmbedder(context: Context) : AutoCloseable {

    companion object {
        const val INPUT_SIZE = 160
        const val EMBEDDING_DIM = 128
        private const val MODEL_ASSET = "facenet.tflite"

        /** MVP alignment: no landmark-based warp, just a generous padded crop (see §3). */
        const val CROP_EXPAND_FACTOR = 1.6f
    }

    private val interpreter: Interpreter by lazy {
        Interpreter(FileUtilCompat.loadMappedAsset(context, MODEL_ASSET))
    }

    /** Crops [frame] generously around [detection]'s bbox, resizes, and embeds it. */
    suspend fun embed(frame: Bitmap, detection: FaceDetection): FloatArray = withContext(Dispatchers.Default) {
        val cropRect = ImageUtils.expandRect(
            detection.bbox, CROP_EXPAND_FACTOR, frame.width, frame.height,
        )
        val crop = ImageUtils.cropBitmap(frame, cropRect)
            ?: return@withContext FloatArray(EMBEDDING_DIM)
        try {
            embedCrop(crop)
        } finally {
            // Bitmap.createBitmap(src, 0, 0, src.width, src.height) returns `src` itself, no
            // copy -- which happens whenever the expanded+clamped crop rect exactly fills the
            // frame (edge-clipped faces near a border). Recycling `crop` there would recycle
            // the caller-owned, possibly-shared `frame` bitmap out from under it, crashing the
            // next face embedded from the same frame ("cannot use a recycled source").
            if (crop !== frame) crop.recycle()
        }
    }

    fun embedCrop(crop: Bitmap): FloatArray {
        val resized = if (crop.width == INPUT_SIZE && crop.height == INPUT_SIZE) crop
        else Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)

        val input = preprocess(resized)
        if (resized !== crop) resized.recycle()

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    /** Resize -> raw RGB floats -> whole-image standardization, matching FaceNetModel.kt. */
    private fun preprocess(bmp: Bitmap): ByteBuffer {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val raw = FloatArray(pixels.size * 3)
        var i = 0
        for (p in pixels) {
            raw[i++] = ((p shr 16) and 0xFF).toFloat()
            raw[i++] = ((p shr 8) and 0xFF).toFloat()
            raw[i++] = (p and 0xFF).toFloat()
        }

        var mean = 0.0
        for (v in raw) mean += v
        mean /= raw.size
        var variance = 0.0
        for (v in raw) variance += (v - mean) * (v - mean)
        variance /= raw.size
        val std = max(sqrt(variance), 1.0 / sqrt(raw.size.toDouble()))

        val buffer = ByteBuffer
            .allocateDirect(raw.size * 4)
            .order(ByteOrder.nativeOrder())
        for (v in raw) buffer.putFloat(((v - mean) / std).toFloat())
        buffer.rewind()
        return buffer
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x.toDouble() * x
        val norm = max(sqrt(sumSq), 1e-8)
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    override fun close() {
        interpreter.close()
    }
}
