package com.lineup.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.lineup.app.model.EmbeddedFace
import com.lineup.app.pipeline.FaceClusterer
import com.lineup.app.pipeline.FaceDetectorWrapper
import com.lineup.app.pipeline.FaceEmbedder
import com.lineup.app.pipeline.FrameExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Step 2 checkpoints (BUILD_GUIDE.md):
 *  1. embeddingSanityCheck — same-person vs different-person cosine similarity, BEFORE trusting
 *     the clusterer with anything.
 *  2. sweepSampleN — run frames -> detect -> embed once per sample, then sweep
 *     CLUSTER_SIMILARITY_THRESHOLD (cheap: clustering/segmentation only) and log cluster count +
 *     per-person appearance counts at each step, to find the threshold where clusters.size == 5.
 */
class Step2CheckpointTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): Uri {
        val out = File(context.cacheDir, name)
        testContext.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    /** Cosine similarity between two already-L2-normalized embeddings. */
    private fun cos(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    @Test
    fun embeddingSanityCheck(): Unit = runBlocking {
        val uri = copyAsset("iykyk_handheld_chaos_sample_1.mp4")
        val detector = FaceDetectorWrapper()
        val embedder = FaceEmbedder(context)
        try {
            // Hand-picked timestamps from the sample-1 montage: two frames of the same person
            // (suit-and-clipboard host, ~0.0s/~1.0s), two of a second person (dark-hijab woman,
            // ~2.0s/~3.0s), and one of a third (long-hair woman, ~5.0s) as a cross check.
            val labeled = listOf(
                "hostA_0.0s" to 0.0,
                "hostA_1.0s" to 1.0,
                "hijabB_2.0s" to 2.0,
                "hijabB_3.0s" to 3.0,
                "womanC_5.0s" to 5.0,
            )
            val frames = FrameExtractor(context).extract(uri)
            val embeddings = mutableListOf<Pair<String, FloatArray>>()
            for ((label, sec) in labeled) {
                val nearest = frames.minBy { kotlin.math.abs(it.timestampMs - (sec * 1000).toLong()) }
                val bmp = BitmapFactory.decodeFile(nearest.path) ?: continue
                val faces = detector.detectAll(listOf(nearest))
                val face = faces.maxByOrNull { it.bbox.width() * it.bbox.height() }
                if (face != null) embeddings += label to embedder.embed(bmp, face)
                bmp.recycle()
            }

            Log.i(TAG, "======== embedding sanity check ========")
            for (i in embeddings.indices) {
                for (j in i + 1 until embeddings.size) {
                    val (labelA, a) = embeddings[i]
                    val (labelB, b) = embeddings[j]
                    val sameExpected = labelA.take(6) == labelB.take(6)
                    Log.i(
                        TAG,
                        "%-14s vs %-14s  cos=%.3f  (expect %s)".format(
                            labelA, labelB, cos(a, b), if (sameExpected) "HIGH" else "LOW",
                        ),
                    )
                }
            }
        } finally {
            detector.close()
            embedder.close()
        }
    }

    private fun runSweep(assetName: String): Unit = runBlocking<Unit> {
        val uri = copyAsset(assetName)
        val detector = FaceDetectorWrapper()
        val embedder = FaceEmbedder(context)
        try {
            val frames = FrameExtractor(context).extract(uri)
            val allDetections = detector.detectAll(frames)
            val detections = allDetections.filter {
                it.sharpness >= com.lineup.app.pipeline.AppearanceSegmenter.SHARPNESS_MIN &&
                    kotlin.math.abs(it.headEulerY) <= com.lineup.app.pipeline.AppearanceSegmenter.MAX_YAW
            }
            Log.i(TAG, "usable detections: ${detections.size}/${allDetections.size}")

            val embedded = ArrayList<EmbeddedFace>(detections.size)
            for ((path, faces) in detections.groupBy { it.framePath }) {
                val bmp = BitmapFactory.decodeFile(path) ?: continue
                for (f in faces) embedded += EmbeddedFace(f, embedder.embed(bmp, f))
                bmp.recycle()
            }

            Log.i(TAG, "======== $assetName (${embedded.size} embedded faces) ========")
            var tau = 0.50f
            while (tau <= 0.80f + 1e-6f) {
                val clusters = FaceClusterer(tau).cluster(embedded)
                val segmenter = com.lineup.app.pipeline.AppearanceSegmenter()
                val appearances = clusters.flatMap { segmenter.segment(it) }
                val counts = clusters.map { c -> appearances.count { it.personId == c.id } }
                Log.i(
                    TAG,
                    "tau=%.2f  clusters=%d  totalAppearances=%d  perPerson=%s".format(
                        tau, clusters.size, appearances.size, counts,
                    ),
                )
                tau += 0.05f
            }
        } finally {
            detector.close()
            embedder.close()
        }
        Unit
    }

    @Test fun sweepSample1() = runSweep("iykyk_handheld_chaos_sample_1.mp4")
    @Test fun sweepSample2() = runSweep("iykyk_handheld_chaos_sample_2.mp4")
    @Test fun sweepSample3() = runSweep("iykyk_handheld_chaos_sample_3.mp4")

    companion object {
        private const val TAG = "Lineup.Step2"
    }
}
