package com.lineup.app.pipeline

import com.lineup.app.model.FaceDetection
import kotlin.math.abs

/**
 * Scores a face detection as a collage candidate on frontality, sharpness (normalized against
 * the sharpest candidate in the same pool, since "sharp" is relative to what a clip actually
 * offers), eyes-open, and smiling — per BUILD_GUIDE.md Step 3. Higher is better, roughly 0..1.
 */
object ShotScorer {

    private const val FRONTALITY_MAX_ANGLE = 60f
    private const val NEUTRAL_PROB = 0.6f // used when ML Kit didn't return a classification

    private const val WEIGHT_FRONTALITY = 0.35f
    private const val WEIGHT_SHARPNESS = 0.30f
    private const val WEIGHT_EYES_OPEN = 0.25f
    private const val WEIGHT_SMILING = 0.10f

    fun score(detection: FaceDetection, maxSharpnessInPool: Double): Float {
        val yawScore = 1f - (abs(detection.headEulerY) / FRONTALITY_MAX_ANGLE).coerceIn(0f, 1f)
        val pitchScore = 1f - (abs(detection.headEulerX) / FRONTALITY_MAX_ANGLE).coerceIn(0f, 1f)
        val frontality = (yawScore + pitchScore) / 2f

        val sharpness = if (maxSharpnessInPool <= 0.0) 0f
        else (detection.sharpness / maxSharpnessInPool).toFloat().coerceIn(0f, 1f)

        val eyesOpen = averageOrNeutral(detection.leftEyeOpenProb, detection.rightEyeOpenProb)
        val smiling = if (detection.smilingProb >= 0f) detection.smilingProb else NEUTRAL_PROB

        return frontality * WEIGHT_FRONTALITY +
            sharpness * WEIGHT_SHARPNESS +
            eyesOpen * WEIGHT_EYES_OPEN +
            smiling * WEIGHT_SMILING
    }

    private fun averageOrNeutral(a: Float, b: Float): Float {
        val valid = listOf(a, b).filter { it >= 0f }
        return if (valid.isEmpty()) NEUTRAL_PROB else valid.average().toFloat()
    }

    /** Max sharpness across a pool, used to normalize [score] relative to what's actually available. */
    fun maxSharpness(pool: List<FaceDetection>): Double = pool.maxOfOrNull { it.sharpness } ?: 0.0
}
