package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.util.ComicInfo
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads image pages and optional `ComicInfo.xml` metadata from a zip/cbz archive. */
class ZipSource(file: File, charsetName: String) : PageSource {
    private val zip: ZipFile = runCatching { ZipFile(file, Charset.forName(charsetName)) }
        .getOrElse { ZipFile(file) }

    private var closed = false
    private var infoEntry: ZipEntry? = null
    private var parsedInfo: ComicInfo? = null
    private var infoLoaded = false

    private val entries: List<ZipEntry> = try {
        val images = ArrayList<ZipEntry>()
        val all = zip.entries()
        while (all.hasMoreElements()) {
            val e = all.nextElement()
            if (e.isDirectory) continue
            if (infoEntry == null && ComicInfo.isComicInfoEntry(e.name)) {
                infoEntry = e
            } else if (Formats.isImageName(e.name)) {
                images.add(e)
            }
        }
        images.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    } catch (t: Throwable) {
        runCatching { zip.close() }
        throw t
    }

    override val pageCount: Int get() = entries.size

    @Synchronized
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (closed) throw IOException("Archive is closed")
        if (entries.isEmpty()) throw IOException("Archive contains no images")
        if (index !in entries.indices) throw IOException("Page index out of range: $index")
        val entry = entries[index]
        zip.getInputStream(entry).use { input ->
            return BitmapUtils.decodeStream(input, reqWidth, reqHeight)
                ?: throw IOException("Could not decode ${entry.name}")
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (closed || index !in entries.indices) return null
        return runCatching {
            zip.getInputStream(entries[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    @Synchronized
    override fun comicInfo(): ComicInfo? {
        if (closed) return parsedInfo
        if (!infoLoaded) {
            infoLoaded = true
            parsedInfo = infoEntry?.let { entry ->
                runCatching { zip.getInputStream(entry).use { ComicInfo.parse(it) } }.getOrNull()
            }
        }
        return parsedInfo
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { zip.close() }
    }
}
