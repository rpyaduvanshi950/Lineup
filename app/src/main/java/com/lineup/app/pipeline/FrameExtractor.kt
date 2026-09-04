package com.lineup.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.lineup.app.model.FrameRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Samples a video at [SAMPLE_INTERVAL_MS] (~6 fps), JPEG-compresses each frame to
 * [cacheDir]/frames/ immediately and recycles the bitmap. Never holds more than one
 * decoded frame in memory.
 */
class FrameExtractor(private val context: Context) {

    companion object {
        const val SAMPLE_INTERVAL_MS = 166L
        private const val JPEG_QUALITY = 90
    }

    suspend fun extract(
        uri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<FrameRef> = withContext(Dispatchers.IO) {
        val framesDir = File(context.cacheDir, "frames").apply {
            deleteRecursively()
            mkdirs()
        }

        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<FrameRef>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val total = if (durationMs <= 0) 0 else (durationMs / SAMPLE_INTERVAL_MS).toInt() + 1

            var index = 0
            var tUs = 0L
            val durationUs = durationMs * 1000
            while (tUs <= durationUs || index == 0) {
                coroutineContext.ensureActive()
                // OPTION_CLOSEST (not _SYNC): decode the actual frame nearest tUs. _SYNC
                // snaps every request to a keyframe, and encoders place keyframes on scene
                // cuts — which in this footage are the motion-blurred whip-pan frames — so
                // _SYNC feeds the detector ~35 mostly-unusable frames instead of ~180 real
                // ones. CLOSEST costs a short forward decode from the prior keyframe.
                val bmp: Bitmap? = retriever.getFrameAtTime(
                    tUs, MediaMetadataRetriever.OPTION_CLOSEST,
                )
                if (bmp != null) {
                    val file = File(framesDir, "frame_%05d.jpg".format(index))
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                    frames.add(
                        FrameRef(
                            index = index,
                            timestampMs = tUs / 1000,
                            path = file.absolutePath,
                            width = bmp.width,
                            height = bmp.height,
                        ),
                    )
                    bmp.recycle()
                }
                index++
                onProgress(index, total)
                if (durationUs <= 0) break
                tUs += SAMPLE_INTERVAL_MS * 1000
            }
        } finally {
            retriever.release()
        }
        frames
    }
}
