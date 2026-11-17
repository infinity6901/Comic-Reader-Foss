package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.infinity.comicreader.util.ComicInfo
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException

/** Reads image pages and optional `ComicInfo.xml` metadata from a rar/cbr archive (via junrar). */
class RarSource(file: File) : PageSource {
    private val archive = Archive(file)
    private var closed = false
    private var infoHeader: FileHeader? = null
    private var parsedInfo: ComicInfo? = null
    private var infoLoaded = false

    private val headers: List<FileHeader> = try {
        val images = ArrayList<FileHeader>()
        for (h in archive.fileHeaders) {
            if (h.isDirectory) continue
            if (infoHeader == null && ComicInfo.isComicInfoEntry(h.fileName)) {
                infoHeader = h
            } else if (Formats.isImageName(h.fileName)) {
                images.add(h)
            }
        }
        images.sortedWith { a, b -> NaturalOrder.compare(a.fileName, b.fileName) }
    } catch (t: Throwable) {
        runCatching { archive.close() }
        throw t
    }

    override val pageCount: Int get() = headers.size

    /**
     * junrar's [Archive] is not thread-safe; only the sequential extraction needs the lock
     * while pixel decoding runs outside it.
     */
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (headers.isEmpty()) throw IOException("Archive contains no images")
        if (index !in headers.indices) throw IOException("Page index out of range: $index")
        val header = headers[index]
        try {
            val bytes = synchronized(this) {
                if (closed) throw IOException("Archive is closed")
                archive.getInputStream(header).use { BitmapUtils.readBytes(it) }
            }
            return BitmapUtils.decodeBytes(bytes, reqWidth, reqHeight)
                ?: throw IOException("Could not decode ${header.fileName}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load rar entry ${header.fileName} (index $index)", t)
            throw t
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (closed || index !in headers.indices) return null
        return runCatching {
            archive.getInputStream(headers[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    @Synchronized
    override fun comicInfo(): ComicInfo? {
        if (closed) return parsedInfo
        if (!infoLoaded) {
            infoLoaded = true
            parsedInfo = infoHeader?.let { header ->
                runCatching { archive.getInputStream(header).use { ComicInfo.parse(it) } }.getOrNull()
            }
        }
        return parsedInfo
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "RarSource"
    }
}
