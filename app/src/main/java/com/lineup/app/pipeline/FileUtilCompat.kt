package com.lineup.app.pipeline

import android.content.Context
import android.content.res.AssetFileDescriptor
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Minimal `FileUtil.loadMappedFile` replacement so we don't need the tflite-support library. */
object FileUtilCompat {
    fun loadMappedAsset(context: Context, assetName: String): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength,
            )
        }
    }
}
