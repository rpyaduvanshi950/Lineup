package com.lineup.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.lineup.app.model.EmbeddedFace
import com.lineup.app.model.FaceDetection
import com.lineup.app.model.PersonCluster
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
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates the on-device pipeline as one suspend function, exposing progress and
 * results through a [ProcessingState] StateFlow. One class per pipeline stage.
 */
class VideoRepository(private val context: Context) {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val frameExtractor = FrameExtractor(context)

    /** Full pipeline: Step 1 (ingest & detect) followed by Step 2 (identify) and Step 3 (compose). */
    suspend fun run(uri: Uri, similarityThreshold: Float = FaceClusterer.CLUSTER_SIMILARITY_THRESHOLD) {
        val detector = FaceDetectorWrapper()
        try {
            _state.value = ProcessingState.Running(ProcessingState.Stage.EXTRACTING_FRAMES, 0, 0)
            val frames = frameExtractor.extract(uri) { done, total, framePath ->
                _state.value = ProcessingState.Running(
                    ProcessingState.Stage.EXTRACTING_FRAMES, done, total, previewFramePath = framePath,
                )
            }

            _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, 0, frames.size)
            val detections = detector.detectAll(frames) { done, total, framePath ->
                _state.value = ProcessingState.Running(
                    ProcessingState.Stage.DETECTING_FACES, done, total, previewFramePath = framePath,
                )
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
            // NB: tried track-averaged clustering (group by ML Kit trackingId, cluster the track
            // means). Measured on-device it collapsed everyone together -- ML Kit's tracking
            // isn't shot-boundary aware and this footage is all hard cuts, so a "track" spans
            // multiple people framed similarly across a cut. Reverted; per-frame clustering it is.
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

            // Proof this is real per-identity tracking, not a shared scene-cut counter:
            // each person's own appearance timestamp windows, independent of everyone else's.
            for (c in clusters) {
                val windows = appearances.filter { it.personId == c.id }
                    .sortedBy { it.startMs }
                    .joinToString { "%.1f-%.1fs".format(it.startMs / 1000.0, it.endMs / 1000.0) }
                Log.i(TAG, "person${c.id} windows: $windows")
            }

            Log.i(
                TAG,
                "Step 2 done: ${clusters.size} people, ${appearances.size} appearances " +
                    "(threshold=$similarityThreshold) -> " +
                    clusters.joinToString { c -> "person${c.id}=${appearances.count { it.personId == c.id }}" },
            )
            // The big "X people found" counter on the Processing screen becomes meaningful
            // exactly here -- clustering resolves all at once (not incrementally), so this is
            // the moment the UI animates its count-up from 0 to the real number.
            _state.value = ProcessingState.Running(
                ProcessingState.Stage.CLUSTERING, 1, 1, peopleFound = clusters.size,
            )
            _state.value = ProcessingState.Step2Complete(frames, clusters, appearances, similarityThreshold)

            _state.value = ProcessingState.Running(
                ProcessingState.Stage.COMPOSING, 0, 1, peopleFound = clusters.size,
            )
            val detectionsByFrame = usableDetections.groupBy { it.framePath }
            val (collageFile, avatarPaths) = composeCollage(clusters, detectionsByFrame)
            Log.i(TAG, "Step 3 done: collage written to ${collageFile.absolutePath}")
            _state.value = ProcessingState.Step3Complete(
                clusters, appearances, similarityThreshold, collageFile.absolutePath, avatarPaths,
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
            val frames = frameExtractor.extract(uri) { done, total, framePath ->
                _state.value = ProcessingState.Running(
                    ProcessingState.Stage.EXTRACTING_FRAMES, done, total, previewFramePath = framePath,
                )
            }

            _state.value = ProcessingState.Running(ProcessingState.Stage.DETECTING_FACES, 0, frames.size)
            val detections = detector.detectAll(frames) { done, total, framePath ->
                _state.value = ProcessingState.Running(
                    ProcessingState.Stage.DETECTING_FACES, done, total, previewFramePath = framePath,
                )
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
     * bbox) from the full-resolution source frame, composes the grid collage, and caches
     * both the collage and each person's individual crop (for the results screen's avatar
     * chips) to disk so they can hand off to ResultsActivity by file path.
     *
     * Builds (personId, croppedBitmap) pairs in a single pass rather than two separate
     * mapNotNull chains -- with two chains, a dropped shot/crop silently shifts every later
     * index out of alignment with `clusters`, which would attribute the wrong crop to the
     * wrong personId.
     */
    private suspend fun composeCollage(
        clusters: List<PersonCluster>,
        detectionsByFrame: Map<String, List<FaceDetection>>,
    ): Pair<File, Map<Int, String>> = withContext(Dispatchers.Default) {
        val selector = RepresentativeShotSelector()

        // Not recycling these decoded frames: there are only as many as there are people (a
        // handful), and cropBitmap can alias the source bitmap itself (see FaceEmbedder's fix)
        // when a rect fills the whole frame, so recycling here isn't safe to do blindly anyway.
        val personTiles: List<Pair<Int, android.graphics.Bitmap>> = clusters.mapNotNull { cluster ->
            coroutineContext.ensureActive()
            val shot = selector.select(cluster, detectionsByFrame) ?: return@mapNotNull null
            val frame = BitmapFactory.decodeFile(shot.framePath) ?: return@mapNotNull null
            val crop = ImageUtils.cropBitmap(frame, shot.cropRect) ?: return@mapNotNull null
            cluster.id to crop
        }

        val collage = CollageComposer.compose(personTiles.map { it.second })
        val collageFile = CollageComposer.writeToShareCache(
            context, collage, "collage_${System.currentTimeMillis()}.png",
        )
        val avatarPaths = personTiles.associate { (personId, bitmap) ->
            personId to CollageComposer.writeToShareCache(context, bitmap, "avatar_$personId.png").absolutePath
        }
        collageFile to avatarPaths
    }

    fun reset() {
        _state.value = ProcessingState.Idle
    }

    companion object {
        private const val TAG = "Lineup.Pipeline"
    }
}
