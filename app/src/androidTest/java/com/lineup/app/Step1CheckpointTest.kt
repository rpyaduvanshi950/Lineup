package com.lineup.app

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.lineup.app.pipeline.FaceDetectorWrapper
import com.lineup.app.pipeline.FrameExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Step 1 checkpoint: FrameExtractor + FaceDetectorWrapper on each bundled sample video,
 * one @Test per video so results land incrementally.
 */
class Step1CheckpointTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun copyAsset(name: String): Uri {
        val out = File(context.cacheDir, name)
        testContext.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(out)
    }

    private fun run(name: String): Unit = runBlocking<Unit> {
        val detector = FaceDetectorWrapper()
        try {
            val uri = copyAsset(name)
            val t0 = System.currentTimeMillis()
            val frames = FrameExtractor(context).extract(uri)
            val tExtract = System.currentTimeMillis()
            val detections = detector.detectAll(frames)
            val tDetect = System.currentTimeMillis()

            val perFrame = detections.groupingBy { it.frameIndex }.eachCount()
            val twoPlus = perFrame.filter { it.value >= 2 }
            val avgSharp = detections.map { it.sharpness }.ifEmpty { listOf(0.0) }.average()

            Log.i(TAG, "======== $name ========")
            Log.i(TAG, "frames extracted:        ${frames.size}")
            Log.i(TAG, "total face detections:   ${detections.size}")
            Log.i(TAG, "frames with >=1 face:    ${perFrame.size}")
            Log.i(TAG, "frames with >=2 faces:   ${twoPlus.size}")
            Log.i(TAG, "max faces in one frame:  ${perFrame.values.maxOrNull() ?: 0}")
            Log.i(TAG, "mean crop sharpness:     ${"%.1f".format(avgSharp)}")
            Log.i(
                TAG,
                "2-face frame timestamps: " + twoPlus.keys.sorted().joinToString {
                    "%.1fs".format(detections.first { d -> d.frameIndex == it }.timestampMs / 1000.0)
                },
            )
            Log.i(
                TAG,
                "timing: extract=${tExtract - t0}ms detect=${tDetect - tExtract}ms",
            )
        } finally {
            detector.close()
        }
        Unit
    }

    @Test fun sample1() = run("iykyk_handheld_chaos_sample_1.mp4")
    @Test fun sample2() = run("iykyk_handheld_chaos_sample_2.mp4")
    @Test fun sample3() = run("iykyk_handheld_chaos_sample_3.mp4")

    companion object {
        private const val TAG = "Lineup.Step1"
    }
}
