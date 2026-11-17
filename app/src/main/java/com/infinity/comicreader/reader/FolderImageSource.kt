package com.infinity.comicreader.reader

import android.graphics.Bitmap
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.io.IOException

/**
 * Treats a directory containing loose image files (`001.jpg`, `002.png`, `003.webp`)
 * as a single multi-page comic book.
 */
class FolderImageSource(dir: File) : PageSource {
    val files: List<File> = dir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") && Formats.isImageName(it.name) }
        ?.sortedWith(compareBy(NaturalOrder) { it.name })
        .orEmpty()

    @Volatile
    private var closed = false

    override val pageCount: Int get() = files.size

    fun indexOf(file: File): Int =
        files.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)

    override fun loadPage(index: Int, reqWidth: Int, reqHeight: Int): Bitmap {
        if (closed) throw IOException("Source is closed")
        if (files.isEmpty()) throw IOException("Folder contains no images")
        if (index !in files.indices) throw IOException("Page index out of range: $index")
        val target = files[index]
        return BitmapUtils.decodeFile(target.absolutePath, reqWidth, reqHeight)
            ?: throw IOException("Could not decode image: ${target.name}")
    }

    override fun pageSize(index: Int): IntArray? {
        if (closed || index !in files.indices) return null
        return BitmapUtils.boundsFile(files[index].absolutePath)
    }

    override fun close() {
        closed = true
    }

    companion object {
        /** True when [dir] contains at least one loose image file and no nested archive/PDF/sub-folder chapters. */
        fun isImageChapterFolder(dir: File): Boolean {
            if (!dir.isDirectory) return false
            val children = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return false
            val hasImages = children.any { it.isFile && Formats.isImageName(it.name) }
            val hasSubDirsOrArchives = children.any {
                it.isDirectory || (it.isFile && Formats.isOpenable(it.name) && !Formats.isImageName(it.name))
            }
            return hasImages && !hasSubDirsOrArchives
        }
    }
}
