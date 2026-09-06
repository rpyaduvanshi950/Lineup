package com.lineup.app.pipeline

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection as MlKitFaceDetection
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.lineup.app.model.FaceDetection
import com.lineup.app.model.FrameRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * ML Kit face detection, accurate mode, landmarks + classification on. Runs sequentially
 * on Dispatchers.Default, decoding one frame JPEG at a time.
 */
class FaceDetectorWrapper {

    private val detector: FaceDetector = MlKitFaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build(),
    )

    suspend fun detectAll(
        frames: List<FrameRef>,
        onProgress: (done: Int, total: Int, framePath: String) -> Unit = { _, _, _ -> },
    ): List<FaceDetection> = withContext(Dispatchers.Default) {
        val out = ArrayList<FaceDetection>()
        frames.forEachIndexed { i, frame ->
            coroutineContext.ensureActive()
            val bitmap = BitmapFactory.decodeFile(frame.path) ?: run {
                onProgress(i + 1, frames.size, frame.path)
                return@forEachIndexed
            }
            try {
                val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                for (face in faces) {
                    val crop = ImageUtils.cropBitmap(bitmap, face.boundingBox)
                    val sharpness = crop?.let {
                        val v = ImageUtils.laplacianVariance(it)
                        it.recycle()
                        v
                    } ?: 0.0
                    out.add(
                        FaceDetection(
                            frameIndex = frame.index,
                            timestampMs = frame.timestampMs,
                            framePath = frame.path,
                            frameWidth = bitmap.width,
                            frameHeight = bitmap.height,
                            bbox = face.boundingBox,
                            headEulerX = face.headEulerAngleX,
                            headEulerY = face.headEulerAngleY,
                            headEulerZ = face.headEulerAngleZ,
                            leftEyeOpenProb = face.leftEyeOpenProbability ?: -1f,
                            rightEyeOpenProb = face.rightEyeOpenProbability ?: -1f,
                            smilingProb = face.smilingProbability ?: -1f,
                            sharpness = sharpness,
                            trackingId = face.trackingId,
                            landmarks = extractLandmarks(face),
                        ),
                    )
                }
            } finally {
                bitmap.recycle()
            }
            onProgress(i + 1, frames.size, frame.path)
        }
        out
    }

    /**
     * The 5 landmarks alignment needs, spatially ordered by x so we don't depend on ML Kit's
     * subject-relative LEFT/RIGHT naming. Null if any of the five is missing.
     */
    private fun extractLandmarks(face: Face): FloatArray? {
        val eyeA = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val eyeB = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthA = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        val (eyeL, eyeR) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        val (mouthL, mouthR) = if (mouthA.x <= mouthB.x) mouthA to mouthB else mouthB to mouthA
        return floatArrayOf(
            eyeL.x, eyeL.y, eyeR.x, eyeR.y, nose.x, nose.y, mouthL.x, mouthL.y, mouthR.x, mouthR.y,
        )
    }

    fun close() = detector.close()
}
