package com.lineup.app.pipeline

import com.lineup.app.model.Appearance
import com.lineup.app.model.PersonCluster
import kotlin.math.abs

/**
 * Turns each [PersonCluster]'s raw detections into appearance segments: pre-filter blur and
 * extreme pose, then split the remaining sorted-by-time detections wherever the gap to the next
 * one exceeds [APPEARANCE_GAP_MS] — matching the spec's "continuous visible segment" definition
 * (a blurred whip-pan pass belongs to nobody, so it should neither start nor extend a segment).
 */
class AppearanceSegmenter(
    private val sharpnessMin: Double = SHARPNESS_MIN,
    private val maxYaw: Float = MAX_YAW,
    private val gapMs: Long = APPEARANCE_GAP_MS,
) {
    companion object {
        // Calibrated against the actual distribution of ImageUtils.laplacianVariance() over
        // ~170 real face crops per sample video (min~6, p10~200-230, median~514-588, max~1500) --
        // see Lineup's scratchpad sharpness_dist.py. 40 was an unvalidated placeholder that
        // barely filtered anything; re-tune once this runs on-device (JPEG re-compression in
        // FrameExtractor may shift the scale slightly).
        const val SHARPNESS_MIN = 150.0
        const val MAX_YAW = 45f
        const val APPEARANCE_GAP_MS = 750L
    }

    fun segment(cluster: PersonCluster): List<Appearance> {
        val usable = cluster.faces
            .filter { it.detection.sharpness >= sharpnessMin && abs(it.detection.headEulerY) <= maxYaw }
            .sortedBy { it.detection.timestampMs }
        if (usable.isEmpty()) return emptyList()

        val appearances = mutableListOf<Appearance>()
        var segStart = 0
        for (i in 1 until usable.size) {
            val gap = usable[i].detection.timestampMs - usable[i - 1].detection.timestampMs
            if (gap > gapMs) {
                appearances += buildAppearance(cluster.id, usable.subList(segStart, i))
                segStart = i
            }
        }
        appearances += buildAppearance(cluster.id, usable.subList(segStart, usable.size))
        return appearances
    }

    private fun buildAppearance(
        personId: Int,
        faces: List<com.lineup.app.model.EmbeddedFace>,
    ) = Appearance(
        personId = personId,
        startMs = faces.first().detection.timestampMs,
        endMs = faces.last().detection.timestampMs,
        faces = faces,
    )
}
