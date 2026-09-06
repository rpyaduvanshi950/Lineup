package com.lineup.app.pipeline

import com.lineup.app.model.EmbeddedFace
import com.lineup.app.model.PersonCluster

/**
 * Hand-rolled agglomerative clustering over cosine similarity of FaceNet embeddings — no
 * external library, appropriate at this scale (a few hundred points per video).
 *
 * Centroid-linkage: repeatedly merges the two clusters whose centroids are most similar,
 * until the best remaining pair falls below [CLUSTER_SIMILARITY_THRESHOLD]. That threshold is
 * the one knob to sweep against ground truth (see the "Similarity threshold" section of the README).
 */
class FaceClusterer(
    private val similarityThreshold: Float = CLUSTER_SIMILARITY_THRESHOLD,
) {
    companion object {
        /**
         * Tuned by sweeping against known cluster counts (see the sweep log in VideoRepository).
         * MobileFaceNet + 5-point alignment: same-person cosine sits lower than plain FaceNet's,
         * so this is well below the old FaceNet value of 0.6.
         */
        const val CLUSTER_SIMILARITY_THRESHOLD = 0.45f

        /**
         * A single sharp, frontal, well-lit crop can still land below [CLUSTER_SIMILARITY_THRESHOLD]
         * against its own person's centroid purely from scale mismatch — e.g. a much closer/wider
         * framing than that person's other shots (verified on-device: a 21.7s frame of an
         * otherwise 4-appearance person came out as its own size-1 cluster). A tiny cluster
         * that never grew past the main threshold is far more likely to be exactly that kind of
         * fragment than a genuine standalone extra person, so it gets one more chance to merge
         * into whichever real cluster it's closest to, at a lower bar.
         */
        const val RESCUE_MERGE_MAX_SIZE = 2
        const val RESCUE_MERGE_THRESHOLD = 0.30f
    }

    fun cluster(faces: List<EmbeddedFace>): List<PersonCluster> {
        if (faces.isEmpty()) return emptyList()

        var clusters = agglomerate(faces.mapIndexed { i, f -> PersonCluster(i, listOf(f)) }, similarityThreshold)
        clusters = rescueMergeFragments(clusters)

        return clusters.mapIndexed { newId, c -> c.copy(id = newId) }
    }

    private fun agglomerate(start: List<PersonCluster>, threshold: Float): List<PersonCluster> {
        var clusters = start
        while (clusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var bestSim = -2f
            val centroids = clusters.map { it.centroid() }
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val sim = cosineSimilarity(centroids[i], centroids[j])
                    if (sim > bestSim) {
                        bestSim = sim
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestSim < threshold) break

            val merged = PersonCluster(
                id = clusters[bestI].id,
                faces = clusters[bestI].faces + clusters[bestJ].faces,
            )
            clusters = clusters.filterIndexed { idx, _ -> idx != bestI && idx != bestJ } + merged
        }
        return clusters
    }

    /** Second pass: give every small leftover cluster one chance to merge into its nearest
     * real (larger) cluster at [RESCUE_MERGE_THRESHOLD], instead of surviving as a phantom person. */
    private fun rescueMergeFragments(clusters: List<PersonCluster>): List<PersonCluster> {
        val (small, real) = clusters.partition { it.faces.size <= RESCUE_MERGE_MAX_SIZE }
        if (small.isEmpty() || real.isEmpty()) return clusters

        val result = real.toMutableList()
        for (fragment in small) {
            val fragmentCentroid = fragment.centroid()
            val bestIdx = result.indices.maxByOrNull { cosineSimilarity(fragmentCentroid, result[it].centroid()) }
            val bestSim = bestIdx?.let { cosineSimilarity(fragmentCentroid, result[it].centroid()) } ?: -2f
            if (bestIdx != null && bestSim >= RESCUE_MERGE_THRESHOLD) {
                result[bestIdx] = PersonCluster(id = result[bestIdx].id, faces = result[bestIdx].faces + fragment.faces)
            } else {
                result += fragment // genuinely stays its own person -- no good match found
            }
        }
        return result
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // both vectors are already L2-normalized
    }
}
