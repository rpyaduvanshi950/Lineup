package com.lineup.app.pipeline

import com.lineup.app.model.FaceDetection
import com.lineup.app.model.PersonCluster
import com.lineup.app.model.RepresentativeShot

/**
 * Picks the single best candidate face per person for the collage:
 * prefer non-edge-clipped candidates (fall back to edge-clipped only if that's all a person has),
 * score the pool, take the max, and expand its bbox generously so the collage tile is never a
 * tight, low-resolution face crop.
 */
class RepresentativeShotSelector(
    private val cropExpandFactor: Float = CROP_EXPAND_FACTOR,
) {
    companion object {
        /** 2-3x: a full head-and-shoulders tile, not a mugshot. */
        const val CROP_EXPAND_FACTOR = 2.5f
    }

    /**
     * [detectionsByFrame] maps a frame's file path to every face detected in it, across every
     * person, not just this one. Used two ways: (1) frames with exactly one face are strongly
     * preferred, since a person with several appearances almost always has a solo one; (2) if
     * the best candidate is still in a shared frame, the crop is clipped so it never crosses
     * into a neighboring face -- confirmed on-device that an unclipped 2.5x expansion around one
     * face in a two-person frame produced a tile visibly bisecting both people.
     */
    fun select(
        cluster: PersonCluster,
        detectionsByFrame: Map<String, List<FaceDetection>> = emptyMap(),
    ): RepresentativeShot? {
        val all = cluster.faces.map { it.detection }
        if (all.isEmpty()) return null

        val nonClipped = all.filterNot { it.isEdgeClipped }
        var pool = nonClipped.ifEmpty { all }

        val soloFrame = pool.filter { (detectionsByFrame[it.framePath]?.size ?: 1) <= 1 }
        if (soloFrame.isNotEmpty()) pool = soloFrame

        val maxSharpness = ShotScorer.maxSharpness(pool)
        val best = pool.maxByOrNull { ShotScorer.score(it, maxSharpness) } ?: return null
        val bestScore = ShotScorer.score(best, maxSharpness)

        var cropRect = ImageUtils.expandRect(
            best.bbox, cropExpandFactor, best.frameWidth, best.frameHeight,
        )
        val neighbors = detectionsByFrame[best.framePath]
            ?.filter { it.bbox != best.bbox }
            ?.map { it.bbox }
            .orEmpty()
        if (neighbors.isNotEmpty()) {
            cropRect = ImageUtils.clipAwayFromNeighbors(cropRect, best.bbox, neighbors)
        }

        return RepresentativeShot(
            personId = cluster.id,
            framePath = best.framePath,
            cropRect = cropRect,
            score = bestScore,
        )
    }
}
