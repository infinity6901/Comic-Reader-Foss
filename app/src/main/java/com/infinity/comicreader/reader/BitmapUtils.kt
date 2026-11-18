package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs

/**
 * Bitmap decoding helpers. All decoders honour [reqW] x [reqH] by picking a power
 * of two [BitmapFactory.Options.inSampleSize] and additionally clamp the result to
 * [capPixels] so a pathological page can never allocate an outsized bitmap.
 */
object BitmapUtils {
    private const val MAX_DIM = 4096
    private const val MAX_TALL_DIM = 8192

    /** Largest single archive entry that will be buffered in memory (guards against zip bombs). */
    private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024
    private const val DEFAULT_CAP_PIXELS = 12_000_000L
    private const val TALL_CAP_PIXELS = 20_000_000L

    fun decodeFile(path: String, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val effectiveCap = effectiveCapPixels(bounds.outWidth, bounds.outHeight, reqH, capPixels)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH, effectiveCap)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    fun decodeStream(input: InputStream, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? =
        decodeBytes(readCapped(input), reqW, reqH, capPixels)

    fun decodeBytes(bytes: ByteArray, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val effectiveCap = effectiveCapPixels(bounds.outWidth, bounds.outHeight, reqH, capPixels)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH, effectiveCap)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Webtoon / manhwa strips with unconstrained height (`reqH <= 1`) and tall aspect
     * ratios (`h >= w * 2`) get a higher pixel ceiling so vertical text stays crisp.
     */
    private fun effectiveCapPixels(w: Int, h: Int, reqH: Int, baseCap: Long): Long =
        if (reqH <= 1 && h >= w * 2 && baseCap == DEFAULT_CAP_PIXELS) TALL_CAP_PIXELS else baseCap

    /** Intrinsic size of an encoded image without decoding its pixels, or null. */
    fun boundsFile(path: String): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return sizeOf(bounds)
    }

    fun boundsBytes(bytes: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return sizeOf(bounds)
    }

    /**
     * Reads only the image header from [input] via [BitmapFactory.decodeStream]
     * with `inJustDecodeBounds = true`, avoiding buffering up to 96 MB just to
     * inspect width and height.
     */
    fun boundsStream(input: InputStream): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(CappedInputStream(input, MAX_ENTRY_BYTES), null, bounds)
        return sizeOf(bounds)
    }

    private class CappedInputStream(
        delegate: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(delegate) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++total > maxBytes) throw IOException("Image entry is too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > maxBytes) throw IOException("Image entry is too large")
            }
            return n
        }
    }

    /** Reads [input] fully, but refuses to buffer more than [MAX_ENTRY_BYTES]. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) {
                val single = input.read()
                if (single < 0) break
                total++
                if (total > MAX_ENTRY_BYTES) throw IOException("Image entry is too large")
                out.write(single)
                continue
            }
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("Image entry is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sizeOf(bounds: BitmapFactory.Options): IntArray? =
        if (bounds.outWidth > 0 && bounds.outHeight > 0)
            intArrayOf(bounds.outWidth, bounds.outHeight)
        else null

    fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int, capPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        val tallStrip = reqH <= 1 && h >= w * 2
        val tw = if (reqW > 1) reqW else MAX_DIM
        val th = if (reqH > 1) reqH else if (tallStrip) MAX_TALL_DIM else MAX_DIM
        var sample = 1
        while (sample <= (1 shl 29) &&
            (w.toLong() / (sample.toLong() * 2L) >= tw ||
             h.toLong() / (sample.toLong() * 2L) >= th)) {
            sample *= 2
        }
        while (sample <= (1 shl 29) &&
            w.toLong() * h.toLong() / (sample.toLong() * sample.toLong()) > capPixels) {
            sample *= 2
        }
        return sample
    }

    // ------------------------------------------------------------------ auto-crop

    private fun differs(a: Int, b: Int, tol: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) > tol ||
            abs(Color.green(a) - Color.green(b)) > tol ||
            abs(Color.blue(a) - Color.blue(b)) > tol

    /**
     * Trims uniform borders (the colour found in the corners) from [src]. Returns
     * [src] itself when there is nothing worth trimming, when the page has no
     * consistent border colour, or when the result would be implausibly small.
     */
    fun autoCrop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return src
        val tol = 60
        val bg = src.getPixel(1, 1)
        val similar = listOf(
            src.getPixel(w - 2, 1), src.getPixel(1, h - 2), src.getPixel(w - 2, h - 2)
        ).count { !differs(it, bg, tol) }
        if (similar < 1) return src

        val minHits = maxOf(2, w / 200)
        val row = IntArray(w)
        fun rowHasContent(y: Int): Boolean {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var hits = 0
            var x = 0
            while (x < w) {
                if (differs(row[x], bg, tol) && ++hits >= minHits) return true
                x += 2
            }
            return false
        }

        var top = 0
        while (top < h && !rowHasContent(top)) top++
        if (top >= h) return src
        var bottom = h - 1
        while (bottom > top && !rowHasContent(bottom)) bottom--

        val ch = bottom - top + 1
        val col = IntArray(ch)
        val minColHits = maxOf(2, ch / 200)
        fun colHasContent(x: Int): Boolean {
            src.getPixels(col, 0, 1, x, top, 1, ch)
            var hits = 0
            var y = 0
            while (y < ch) {
                if (differs(col[y], bg, tol) && ++hits >= minColHits) return true
                y += 2
            }
            return false
        }
        var left = 0
        while (left < w && !colHasContent(left)) left++
        var right = w - 1
        while (right > left && !colHasContent(right)) right--

        val pad = maxOf(4, minOf(w, h) / 200)
        val l = (left - pad).coerceAtLeast(0)
        val t = (top - pad).coerceAtLeast(0)
        val r = (right + pad).coerceAtMost(w - 1)
        val b = (bottom + pad).coerceAtMost(h - 1)
        val newW = r - l + 1
        val newH = b - t + 1
        val ratio = (newW.toLong() * newH).toDouble() / (w.toLong() * h)
        if (ratio > 0.97 || ratio < 0.25) return src
        return Bitmap.createBitmap(src, l, t, newW, newH)
    }

    /**
     * Webtoon / Long-Strip vertical whitespace compressor: preserves horizontal
     * width (`[src.width]`) so the strip stays edge-to-edge, while compressing
     * tall uniform blank horizontal bands (exceeding `maxGapPx`) down to `keepGapPx`.
     */
    fun trimVerticalGaps(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 320) return src
        val tol = 28
        val maxGapPx = (h * 0.18f).toInt().coerceIn(180, 420)
        val keepGapPx = 64.coerceAtMost(maxGapPx / 2)
        val row = IntArray(w)
        val minHits = maxOf(2, w / 250)

        fun isUniformRow(y: Int): Boolean {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val ref = row[w / 2]
            var diffs = 0
            var x = 0
            while (x < w) {
                if (differs(row[x], ref, tol) && ++diffs >= minHits) return false
                x += 4
            }
            return true
        }

        // Identify slices [startY, endYExclusive) to keep
        val slices = ArrayList<IntArray>()
        var y = 0
        while (y < h) {
            if (!isUniformRow(y)) {
                val start = y
                y++
                while (y < h && !isUniformRow(y)) y++
                slices.add(intArrayOf(start, y))
            } else {
                val gapStart = y
                y++
                while (y < h && isUniformRow(y)) y++
                val gapLen = y - gapStart
                val kept = if (gapLen > maxGapPx) keepGapPx else gapLen
                if (kept > 0) slices.add(intArrayOf(gapStart, gapStart + kept))
            }
        }
        val newH = slices.sumOf { it[1] - it[0] }
        if (newH >= h - 32 || newH < 64) return src

        val out = Bitmap.createBitmap(w, newH, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var dstY = 0
        for (slice in slices) {
            val sh = slice[1] - slice[0]
            canvas.drawBitmap(
                src,
                Rect(0, slice[0], w, slice[1]),
                Rect(0, dstY, w, dstY + sh),
                null
            )
            dstY += sh
        }
        return out
    }
}
