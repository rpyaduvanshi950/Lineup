package com.lineup.app.pipeline

import com.lineup.app.model.EmbeddedFace
import com.lineup.app.model.PersonCluster

/**
 * Hand-rolled agglomerative clustering over cosine similarity of FaceNet embeddings — no
 * external library, appropriate at this scale (a few hundred points per video).
 *
 * Centroid-linkage: repeatedly merges the two clusters whose centroids are most similar,
 * until the best remaining pair falls below [CLUSTER_SIMILARITY_THRESHOLD]. That threshold is
 * the one knob to sweep against ground truth (see BUILD_GUIDE.md §2, Step 2 checkpoint).
 */
class FaceClusterer(
    private val similarityThreshold: Float = CLUSTER_SIMILARITY_THRESHOLD,
) {
    companion object {
        /** Named per BUILD_GUIDE.md — tune by sweeping 0.50..0.80 against known cluster counts. */
        const val CLUSTER_SIMILARITY_THRESHOLD = 0.6f
    }

    fun cluster(faces: List<EmbeddedFace>): List<PersonCluster> {
        if (faces.isEmpty()) return emptyList()

        var clusters = faces.mapIndexed { i, f -> PersonCluster(i, listOf(f)) }

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
            if (bestSim < similarityThreshold) break

            val merged = PersonCluster(
                id = clusters[bestI].id,
                faces = clusters[bestI].faces + clusters[bestJ].faces,
            )
            clusters = clusters.filterIndexed { idx, _ -> idx != bestI && idx != bestJ } + merged
        }

        return clusters.mapIndexed { newId, c -> c.copy(id = newId) }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // both vectors are already L2-normalized
    }
}
