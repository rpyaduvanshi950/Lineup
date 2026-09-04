package com.lineup.app.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.get
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /** Clamp a rect to [0,w) x [0,h). Returns null if the result is empty. */
    fun clampRect(r: Rect, w: Int, h: Int): Rect? {
        val left = max(0, min(r.left, w - 1))
        val top = max(0, min(r.top, h - 1))
        val right = max(left + 1, min(r.right, w))
        val bottom = max(top + 1, min(r.bottom, h))
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    /** Expand a rect about its center by [factor], then clamp to frame bounds. */
    fun expandRect(r: Rect, factor: Float, w: Int, h: Int): Rect {
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val halfW = r.width() * factor / 2f
        val halfH = r.height() * factor / 2f
        val expanded = Rect(
            (cx - halfW).toInt(), (cy - halfH).toInt(),
            (cx + halfW).toInt(), (cy + halfH).toInt(),
        )
        return clampRect(expanded, w, h) ?: Rect(0, 0, w, h)
    }

    /**
     * Shrinks [rect] so it never crosses the midpoint towards any bbox in [neighbors] -- used
     * when a representative-shot crop would otherwise bleed into a different person's face in
     * a shared frame (confirmed on-device: an unclipped 2.5x expansion around one face in a
     * two-person frame produced a tile visibly bisecting both people).
     */
    fun clipAwayFromNeighbors(rect: Rect, ownBbox: Rect, neighbors: List<Rect>): Rect {
        val r = Rect(rect)
        val ownCx = ownBbox.exactCenterX()
        val ownCy = ownBbox.exactCenterY()
        for (n in neighbors) {
            val midX = (ownCx + n.exactCenterX()) / 2f
            if (n.exactCenterX() > ownCx) r.right = min(r.right, midX.toInt())
            else if (n.exactCenterX() < ownCx) r.left = max(r.left, midX.toInt())

            val midY = (ownCy + n.exactCenterY()) / 2f
            if (n.exactCenterY() > ownCy) r.bottom = min(r.bottom, midY.toInt())
            else if (n.exactCenterY() < ownCy) r.top = max(r.top, midY.toInt())
        }
        if (r.right <= r.left) r.right = r.left + 1
        if (r.bottom <= r.top) r.bottom = r.top + 1
        return r
    }

    fun cropBitmap(src: Bitmap, rect: Rect): Bitmap? {
        val r = clampRect(rect, src.width, src.height) ?: return null
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    /**
     * Variance of the Laplacian on a grayscale downscale of the bitmap — a standard
     * blur metric. Higher variance = more high-frequency detail = sharper.
     */
    fun laplacianVariance(bitmap: Bitmap): Double {
        val maxDim = 160
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(3, (bitmap.width * scale).toInt())
        val h = max(3, (bitmap.height * scale).toInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)

        val gray = DoubleArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = small[x, y]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[y * w + x] = 0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        if (small != bitmap) small.recycle()

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
