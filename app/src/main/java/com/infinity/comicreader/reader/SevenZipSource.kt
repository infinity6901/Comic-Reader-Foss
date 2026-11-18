package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.util.ComicInfo
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/** Reads image pages and optional `ComicInfo.xml` metadata from a 7z/cb7 archive (via Apache Commons Compress). */
class SevenZipSource(file: File) : PageSource {
    private val sz: SevenZFile = SevenZFile.builder().setFile(file).get()
    private var closed = false
    private var infoEntry: SevenZArchiveEntry? = null
    private var parsedInfo: ComicInfo? = null
    private var infoLoaded = false

    private val entries: List<SevenZArchiveEntry> = readEntries()

    private fun readEntries(): List<SevenZArchiveEntry> {
        try {
            val list = ArrayList<SevenZArchiveEntry>()
            var e = sz.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (infoEntry == null && name != null && ComicInfo.isComicInfoEntry(name)) {
                        infoEntry = e
                    } else if (name != null && Formats.isImageName(name)) {
                        list.add(e)
                    }
                }
                e = sz.nextEntry
            }
            return list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        } catch (t: Throwable) {
            runCatching { sz.close() }
            throw t
        }
    }

    override val pageCount: Int get() = entries.size

    @Synchronized
    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (closed) throw IOException("Archive is closed")
        if (entries.isEmpty()) throw IOException("Archive contains no images")
        if (index !in entries.indices) throw IOException("Page index out of range: $index")
        val entry = entries[index]
        try {
            sz.getInputStream(entry).use { input ->
                return BitmapUtils.decodeStream(input, reqWidth, reqHeight)
                    ?: throw IOException("Could not decode ${entry.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load 7z entry ${entry.name} (index $index)", t)
            throw t
        }
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (closed || index !in entries.indices) return null
        return runCatching {
            sz.getInputStream(entries[index]).use { BitmapUtils.boundsStream(it) }
        }.getOrNull()
    }

    @Synchronized
    override fun comicInfo(): ComicInfo? {
        if (closed) return parsedInfo
        if (!infoLoaded) {
            infoLoaded = true
            parsedInfo = infoEntry?.let { entry ->
                runCatching { sz.getInputStream(entry).use { ComicInfo.parse(it) } }.getOrNull()
            }
        }
        return parsedInfo
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { sz.close() }
    }

    companion object {
        private const val TAG = "SevenZipSource"
    }
}
