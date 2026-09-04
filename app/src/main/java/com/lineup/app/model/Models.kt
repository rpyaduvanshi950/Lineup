package com.lineup.app.model

import android.graphics.Rect

/** One extracted, JPEG-compressed video frame kept on disk (path only, never a live bitmap). */
data class FrameRef(
    val index: Int,
    val timestampMs: Long,
    val path: String,
    val width: Int,
    val height: Int,
)

/** A single face detected in a single frame, with all the per-detection signals Step 3 needs. */
data class FaceDetection(
    val frameIndex: Int,
    val timestampMs: Long,
    val framePath: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val bbox: Rect,
    val headEulerX: Float,
    val headEulerY: Float,
    val headEulerZ: Float,
    val leftEyeOpenProb: Float,
    val rightEyeOpenProb: Float,
    val smilingProb: Float,
    /** Laplacian variance of the face crop — higher is sharper. */
    val sharpness: Double,
    val trackingId: Int?,
) {
    val isEdgeClipped: Boolean
        get() = bbox.left <= 1 || bbox.top <= 1 ||
            bbox.right >= frameWidth - 1 || bbox.bottom >= frameHeight - 1
}

/** A [FaceDetection] plus its L2-normalized FaceNet embedding. */
data class EmbeddedFace(
    val detection: FaceDetection,
    val embedding: FloatArray,
)

/** One identified person: every embedded face the clusterer grouped together. */
data class PersonCluster(
    val id: Int,
    val faces: List<EmbeddedFace>,
) {
    /** Centroid of member embeddings, L2-normalized — used for merge decisions. */
    fun centroid(): FloatArray {
        val dim = faces.first().embedding.size
        val sum = FloatArray(dim)
        for (f in faces) for (i in 0 until dim) sum[i] += f.embedding[i]
        var norm = 0.0
        for (v in sum) norm += v.toDouble() * v
        norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-8)
        return FloatArray(dim) { (sum[it] / norm).toFloat() }
    }
}

/** One continuous visible segment of a single person — the unit "appearance count" is built from. */
data class Appearance(
    val personId: Int,
    val startMs: Long,
    val endMs: Long,
    val faces: List<EmbeddedFace>,
)

/** The chosen best shot for one person: which frame, and the generous (not tight-face) crop rect. */
data class RepresentativeShot(
    val personId: Int,
    val framePath: String,
    val cropRect: Rect,
    val score: Float,
)
