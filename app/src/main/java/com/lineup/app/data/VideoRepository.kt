package com.lineup.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.lineup.app.model.EmbeddedFace
import com.lineup.app.model.FaceDetection
import com.lineup.app.pipeline.AppearanceSegmenter
import com.lineup.app.pipeline.FaceClusterer
import com.lineup.app.pipeline.FaceDetectorWrapper
import com.lineup.app.pipeline.FaceEmbedder
import com.lineup.app.pipeline.FrameExtractor
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

            Log.i(
                TAG,
                "Step 2 done: ${clusters.size} people, ${appearances.size} appearances " +
                    "(threshold=$similarityThreshold) -> " +
                    clusters.joinToString { c -> "person${c.id}=${appearances.count { it.personId == c.id }}" },
            )
            _state.value = ProcessingState.Step2Complete(frames, clusters, appearances, similarityThreshold)
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

    fun reset() {
        _state.value = ProcessingState.Idle
    }

    companion object {
        private const val TAG = "Lineup.Pipeline"
    }
}
