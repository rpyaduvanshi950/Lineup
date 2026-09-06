package com.lineup.app.pipeline

import android.content.Context
import android.graphics.Bitmap
// LiteRT (com.google.ai.edge.litert:litert) ships its Interpreter under the same package as
// classic TFLite for drop-in compatibility.
import org.tensorflow.lite.Interpreter
import com.lineup.app.model.FaceDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device face embedding. Two models are bundled:
 *
 *  - [Model.MOBILEFACENET] (default): ArcFace/InsightFace-trained MobileFaceNet, 112×112 in →
 *    192-d out, fixed `(x-127.5)/128` normalization. From
 *    syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing. ArcFace models have far better
 *    verification margins than classic triplet-loss FaceNet — but only when fed *aligned* faces.
 *  - [Model.FACENET]: Keras-FaceNet, 160×160 in → 128-d out, whole-image standardization. From
 *    shubham0204/FaceRecognition_With_FaceNet_Android. Kept as a fallback/comparison.
 *
 * Either way: when ML Kit gave us 5 landmarks the crop is a proper similarity-transform
 * alignment (see [FaceAligner]); otherwise it falls back to a generous padded bbox crop.
 * Output is L2-normalized so cosine similarity in FaceClusterer is well-defined.
 */
class FaceEmbedder(
    context: Context,
    private val model: Model = Model.MOBILEFACENET,
) : AutoCloseable {

    enum class Model(val asset: String, val inputSize: Int, val dim: Int, val standardize: Boolean) {
        FACENET("facenet.tflite", 160, 128, standardize = true),
        MOBILEFACENET("mobilefacenet.tflite", 112, 192, standardize = false),
    }

    companion object {
        /** Fallback only, when landmarks are missing: a generous padded bbox crop. */
        const val CROP_EXPAND_FACTOR = 1.6f
    }

    val embeddingDim: Int get() = model.dim

    private val interpreter: Interpreter by lazy {
        Interpreter(FileUtilCompat.loadMappedAsset(context, model.asset))
    }

    /**
     * Some of these ported models fix the input batch at 2 (their original use case was
     * comparing two faces in one call). Read it from the tensor shape and fill every slot with
     * the same face — the extra slot costs a little compute but avoids resize/allocate gymnastics.
     */
    private val batchSize: Int by lazy {
        interpreter.getInputTensor(0).shape().firstOrNull()?.coerceAtLeast(1) ?: 1
    }

    /** Aligns (or falls back to cropping) [detection] out of [frame], resizes, and embeds it. */
    suspend fun embed(frame: Bitmap, detection: FaceDetection): FloatArray = withContext(Dispatchers.Default) {
        val face: Bitmap = detection.landmarks
            ?.let { FaceAligner.align(frame, it, model.inputSize) }
            ?: run {
                val cropRect = ImageUtils.expandRect(
                    detection.bbox, CROP_EXPAND_FACTOR, frame.width, frame.height,
                )
                ImageUtils.cropBitmap(frame, cropRect)
            }
            ?: return@withContext FloatArray(model.dim)

        try {
            embedCrop(face)
        } finally {
            // Bitmap.createBitmap(src, 0, 0, src.width, src.height) can return `src` itself with
            // no copy (crop rect fills the whole frame). Recycling that would take out the
            // caller-owned shared frame bitmap. Aligned bitmaps are always fresh, so safe.
            if (face !== frame) face.recycle()
        }
    }

    fun embedCrop(crop: Bitmap): FloatArray {
        val size = model.inputSize
        val resized = if (crop.width == size && crop.height == size) crop
        else Bitmap.createScaledBitmap(crop, size, size, true)

        val input = preprocess(resized)
        if (resized !== crop) resized.recycle()

        val output = Array(batchSize) { FloatArray(model.dim) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    private fun preprocess(bmp: Bitmap): ByteBuffer {
        val size = model.inputSize
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)

        val raw = FloatArray(pixels.size * 3)
        var i = 0
        for (p in pixels) {
            raw[i++] = ((p shr 16) and 0xFF).toFloat()
            raw[i++] = ((p shr 8) and 0xFF).toFloat()
            raw[i++] = (p and 0xFF).toFloat()
        }

        val perImage = FloatArray(raw.size)
        if (model.standardize) {
            // FaceNet "prewhiten": (x - mean) / max(std, 1/sqrt(N)) over all pixels.
            var mean = 0.0
            for (v in raw) mean += v
            mean /= raw.size
            var variance = 0.0
            for (v in raw) variance += (v - mean) * (v - mean)
            variance /= raw.size
            val std = max(sqrt(variance), 1.0 / sqrt(raw.size.toDouble()))
            for (j in raw.indices) perImage[j] = ((raw[j] - mean) / std).toFloat()
        } else {
            // MobileFaceNet: fixed (x - 127.5) / 128.
            for (j in raw.indices) perImage[j] = (raw[j] - 127.5f) / 128f
        }

        // Fill every batch slot with the same face (see [batchSize]).
        val buffer = ByteBuffer.allocateDirect(perImage.size * 4 * batchSize).order(ByteOrder.nativeOrder())
        repeat(batchSize) { for (v in perImage) buffer.putFloat(v) }
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
