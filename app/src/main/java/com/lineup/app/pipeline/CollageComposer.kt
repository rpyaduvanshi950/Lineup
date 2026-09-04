package com.lineup.app.pipeline

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

/**
 * Composes representative-shot bitmaps into one shareable collage: a responsive grid (2/3/4
 * columns by person count), rounded-corner tiles at a common aspect ratio, subtle shadow, and a
 * header with the person count. Never a tight face crop -- tiles are whatever
 * RepresentativeShotSelector handed in.
 */
object CollageComposer {

    private const val CANVAS_WIDTH = 1080
    private const val TILE_ASPECT = 4f / 5f // width:height, portrait-ish, Instagram-grid-like
    private const val TILE_GAP = 20
    private const val OUTER_MARGIN = 32
    private const val TILE_RADIUS = 28f
    private const val HEADER_HEIGHT = 160

    private fun columnsFor(personCount: Int) = when {
        personCount <= 4 -> 2
        personCount <= 9 -> 3
        else -> 4
    }

    fun compose(tiles: List<Bitmap>): Bitmap {
        val n = tiles.size.coerceAtLeast(1)
        val cols = columnsFor(n)
        val rows = ceil(n / cols.toFloat()).toInt()

        val tileWidth = (CANVAS_WIDTH - OUTER_MARGIN * 2 - TILE_GAP * (cols - 1)) / cols
        val tileHeight = (tileWidth / TILE_ASPECT).toInt()
        val canvasHeight = HEADER_HEIGHT + OUTER_MARGIN + rows * tileHeight +
            (rows - 1).coerceAtLeast(0) * TILE_GAP + OUTER_MARGIN

        val output = Bitmap.createBitmap(CANVAS_WIDTH, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.parseColor("#F7F6FB"))

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1B1B2A")
            textSize = 56f
            isFakeBoldText = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            headerText(n),
            OUTER_MARGIN.toFloat(),
            HEADER_HEIGHT * 0.62f,
            headerPaint,
        )

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        tiles.forEachIndexed { index, bitmap ->
            val col = index % cols
            val row = index / cols
            val left = OUTER_MARGIN + col * (tileWidth + TILE_GAP)
            val top = HEADER_HEIGHT + OUTER_MARGIN + row * (tileHeight + TILE_GAP)
            val rect = RectF(left.toFloat(), top.toFloat(), (left + tileWidth).toFloat(), (top + tileHeight).toFloat())

            canvas.drawRoundRect(
                RectF(rect.left, rect.top + 6, rect.right, rect.bottom + 10),
                TILE_RADIUS, TILE_RADIUS, shadowPaint,
            )
            drawRoundedCenterCrop(canvas, bitmap, rect, TILE_RADIUS)
        }

        return output
    }

    private fun headerText(n: Int): String = if (n == 1) "1 person found" else "$n people found"

    /** Center-crop [src] to fill [dest] exactly (scale-to-cover), clipped to a rounded-rect path. */
    private fun drawRoundedCenterCrop(canvas: Canvas, src: Bitmap, dest: RectF, radius: Float) {
        val scale = maxOf(dest.width() / src.width, dest.height() / src.height)
        val scaledW = src.width * scale
        val scaledH = src.height * scale
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(dest.left + (dest.width() - scaledW) / 2f, dest.top + (dest.height() - scaledH) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        val path = android.graphics.Path().apply {
            addRoundRect(dest, radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(dest, tilePaint)
        canvas.restore()
    }

    // ---------------------------------------------------------------- save / share

    /** Writes [bitmap] to cacheDir/shared/ (matches file_paths.xml) for FileProvider sharing. */
    fun writeToShareCache(context: Context, bitmap: Bitmap, fileName: String): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Saves [bitmap] into the device gallery (Pictures/Lineup) via MediaStore. Returns the new Uri. */
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lineup")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
