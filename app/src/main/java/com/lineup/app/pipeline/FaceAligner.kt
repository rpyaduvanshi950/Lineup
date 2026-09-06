package com.lineup.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Warps a face to the canonical 5-point template FaceNet / ArcFace models are trained on, via a
 * 2-D similarity transform (scale + rotation + translation, no shear) fitted least-squares to the
 * five ML Kit landmarks. Feeding an *aligned* face is the difference between the embedding model
 * working as designed and it working on out-of-distribution input.
 *
 * Closed-form similarity (2-D Procrustes): with `a = s·cosθ`, `b = s·sinθ`,
 *   X = a·x − b·y + tx      Y = b·x + a·y + ty
 * least-squares over landmark-centred points gives a, b directly; tx, ty follow from the means.
 */
object FaceAligner {

    /**
     * InsightFace's canonical 5-point template for a 112×112 crop
     * (left eye, right eye, nose, left mouth, right mouth), scaled to [size].
     */
    private val TEMPLATE_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /**
     * @param source the full-resolution frame bitmap.
     * @param landmarks 10 floats: 5 (x,y) landmark pixel coords, spatially ordered
     *   [eyeL, eyeR, nose, mouthL, mouthR] — exactly what FaceDetection.landmarks holds.
     * @param size output edge length (112 for MobileFaceNet, 160 for FaceNet).
     * @return a size×size aligned crop, or null if the transform is degenerate.
     */
    fun align(source: Bitmap, landmarks: FloatArray, size: Int): Bitmap? {
        require(landmarks.size == 10)
        val scale = size / 112f
        val dst = FloatArray(10) { TEMPLATE_112[it] * scale }

        val m = similarityTransform(src = landmarks, dst = dst) ?: return null

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Least-squares 2-D similarity mapping src points -> dst points, as an android Matrix. */
    private fun similarityTransform(src: FloatArray, dst: FloatArray): Matrix? {
        val n = src.size / 2
        var sxM = 0f; var syM = 0f; var dxM = 0f; var dyM = 0f
        for (i in 0 until n) {
            sxM += src[2 * i]; syM += src[2 * i + 1]
            dxM += dst[2 * i]; dyM += dst[2 * i + 1]
        }
        sxM /= n; syM /= n; dxM /= n; dyM /= n

        var sPP = 0f  // Σ (a_i² + b_i²)   for centred src
        var num1 = 0f // Σ (a_i·c_i + b_i·d_i)
        var num2 = 0f // Σ (a_i·d_i − b_i·c_i)
        for (i in 0 until n) {
            val a = src[2 * i] - sxM
            val b = src[2 * i + 1] - syM
            val c = dst[2 * i] - dxM
            val d = dst[2 * i + 1] - dyM
            sPP += a * a + b * b
            num1 += a * c + b * d
            num2 += a * d - b * c
        }
        if (sPP < 1e-6f) return null

        val a = num1 / sPP        // s·cosθ
        val b = num2 / sPP        // s·sinθ
        val tx = dxM - (a * sxM - b * syM)
        val ty = dyM - (b * sxM + a * syM)

        return Matrix().apply {
            setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))
        }
    }
}
