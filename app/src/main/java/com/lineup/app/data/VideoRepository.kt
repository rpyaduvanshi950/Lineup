package com.lineup.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.lineup.app.model.EmbeddedFace
import com.lineup.app.model.FaceDetection
import com.lineup.app.model.RepresentativeShot
import com.lineup.app.pipeline.AppearanceSegmenter
import com.lineup.app.pipeline.CollageComposer
import com.lineup.app.pipeline.FaceClusterer
import com.lineup.app.pipeline.FaceDetectorWrapper
import com.lineup.app.pipeline.FaceEmbedder
import com.lineup.app.pipeline.FrameExtractor
import com.lineup.app.pipeline.ImageUtils
import com.lineup.app.pipeline.RepresentativeShotSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates the on-device pipeline as one suspend function, exposing progress and
 * results through a [ProcessingState] StateFlow. One class per pipeline stage.
 */
class VideoRepository(private val context: Context) {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val frameExtractor = FrameExtractor(context)

    /** Full pipeline: Step 1 (ingest & detect) followed by Step 2 (identify). */
    suspend fun run(uri: Uri, similarityThreshold: Float = FaceClusterer.CLUSTER_SIMILARITY_THRESHOLD) {
        val detector = FaceDetectorWrapper()
        try {
            _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING_FRAMES, 0, 0)
            val frames = frameExtractor.extract(uri) { done, total ->
                _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING_FRAMES, done, total)
            }

            _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, 0, frames.size)
            val detections = detector.detectAll(frames) { done, total ->
                _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, done, total)
            }
            Log.i(TAG, "Step 1 done: ${frames.size} frames, ${detections.size} detections")

            // Filter out blur/extreme-pose BEFORE embedding+clustering, not just before
            // counting appearances: a blurry crop embeds to a near-random vector (verified via
            // a standalone same/different-person check, see BUILD_GUIDE.md Step 2) that can
            // fail to merge into its real cluster and show up as a phantom extra "person" once
            // clusters.size is read as the people count. AppearanceSegmenter's own pre-filter
            // stays too, as a defensive no-op, in case it's ever called on unfiltered input.
            val usableDetections = detections.filter {
                it.sharpness >= AppearanceSegmenter.SHARPNESS_MIN &&
                    kotlin.math.abs(it.headEulerY) <= AppearanceSegmenter.MAX_YAW
            }
            Log.i(
                TAG,
                "Filtered to ${usableDetections.size}/${detections.size} usable " +
                    "(sharp + frontal-enough) detections before embedding",
            )

            _state.value = ProcessingState.Running(ProcessingState.Stage.EMBEDDING_FACES, 0, usableDetections.size)
            val embedded = embedAll(usableDetections) { done, total ->
                _state.value = ProcessingState.Running(ProcessingState.Stage.EMBEDDING_FACES, done, total)
            }

            _state.value = ProcessingState.Running(ProcessingState.Stage.CLUSTERING, 0, 1)
            val clusters = FaceClusterer(similarityThreshold).cluster(embedded)
            val segmenter = AppearanceSegmenter()
            val appearances = clusters.flatMap { segmenter.segment(it) }

            // Debug aid: small (<=2-face) clusters that survived rescue-merge are almost always
            // a fragmentation artifact worth investigating (see FaceClusterer's rescue-merge
            // pass) rather than a genuine one-off extra person -- log enough to pull the actual
            // frame and inspect it.
            for (c in clusters) {
                if (c.faces.size <= 2) {
                    Log.i(
                        TAG,
                        "SMALL CLUSTER person${c.id} size=${c.faces.size}: " +
                            c.faces.joinToString {
                                "t=${it.detection.timestampMs}ms sharp=${it.detection.sharpness.toInt()} " +
                                    "yaw=${it.detection.headEulerY.toInt()} path=${it.detection.framePath}"
                            },
                    )
                }
            }

            Log.i(
                TAG,
                "Step 2 done: ${clusters.size} people, ${appearances.size} appearances " +
                    "(threshold=$similarityThreshold) -> " +
                    clusters.joinToString { c -> "person${c.id}=${appearances.count { it.personId == c.id }}" },
            )
            _state.value = ProcessingState.Step2Complete(frames, clusters, appearances, similarityThreshold)

            _state.value = ProcessingState.Running(ProcessingState.Stage.COMPOSING, 0, 1)
            val detectionsByFrame = usableDetections.groupBy { it.framePath }
            val collageFile = composeCollage(clusters, detectionsByFrame)
            Log.i(TAG, "Step 3 done: collage written to ${collageFile.absolutePath}")
            _state.value = ProcessingState.Step3Complete(
                clusters, appearances, similarityThreshold, collageFile.absolutePath,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed", t)
            _state.value = ProcessingState.Failed(t.message ?: "Processing failed", t)
        } finally {
            detector.close()
        }
    }

    /** Step 1 only — kept for the Step 1 checkpoint / callers that don't need identity yet. */
    suspend fun runStep1(uri: Uri) {
        val detector = FaceDetectorWrapper()
        try {
            _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING_FRAMES, 0, 0)
            val frames = frameExtractor.extract(uri) { done, total ->
                _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING_FRAMES, done, total)
            }

            _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, 0, frames.size)
            val detections = detector.detectAll(frames) { done, total ->
                _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, done, total)
            }

            Log.i(TAG, "Step 1 done: ${frames.size} frames extracted, ${detections.size} face detections")
            _state.value = ProcessingState.Step1Complete(frames, detections)
        } catch (t: Throwable) {
            Log.e(TAG, "Step 1 failed", t)
            _state.value = ProcessingState.Failed(t.message ?: "Processing failed", t)
        } finally {
            detector.close()
        }
    }

    /** Embeds every detection, decoding each distinct frame JPEG only once. */
    private suspend fun embedAll(
        detections: List<FaceDetection>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<EmbeddedFace> = withContext(Dispatchers.Default) {
        val embedder = FaceEmbedder(context)
        try {
            val out = ArrayList<EmbeddedFace>(detections.size)
            var done = 0
            detections.groupBy { it.framePath }.forEach { (path, faces) ->
                coroutineContext.ensureActive()
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    try {
                        for (face in faces) {
                            out += EmbeddedFace(face, embedder.embed(bitmap, face))
                            done++
                            onProgress(done, detections.size)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    done += faces.size
                    onProgress(done, detections.size)
                }
            }
            out
        } finally {
            embedder.close()
        }
    }

    /**
     * Picks one representative shot per person, crops it generously (never a tight face
     * bbox) from the full-resolution source frame, composes the grid collage, and caches it
     * to disk so it can hand off to ResultsActivity by file path.
     */
    private suspend fun composeCollage(
        clusters: List<com.lineup.app.model.PersonCluster>,
        detectionsByFrame: Map<String, List<FaceDetection>>,
    ): java.io.File = withContext(Dispatchers.Default) {
        val selector = RepresentativeShotSelector()
        val shots: List<RepresentativeShot> = clusters.mapNotNull { selector.select(it, detectionsByFrame) }

        // Not recycling these decoded frames: there are only as many as there are people (a
        // handful), and cropBitmap can alias the source bitmap itself (see FaceEmbedder's fix)
        // when a rect fills the whole frame, so recycling here isn't safe to do blindly anyway.
        val tiles = shots.mapNotNull { shot ->
            coroutineContext.ensureActive()
            val frame = BitmapFactory.decodeFile(shot.framePath) ?: return@mapNotNull null
            ImageUtils.cropBitmap(frame, shot.cropRect)
        }

        val collage = CollageComposer.compose(tiles)
        CollageComposer.writeToShareCache(context, collage, "collage_${System.currentTimeMillis()}.png")
    }

    fun reset() {
        _state.value = ProcessingState.Idle
    }

    companion object {
        private const val TAG = "Lineup.Pipeline"
    }
}
