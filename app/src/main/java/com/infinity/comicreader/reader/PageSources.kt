package com.infinity.comicreader.reader

import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.IOException

/** Chooses the right [PageSource] for a file or image-chapter directory. */
object PageSources {
    fun open(file: File, charsetName: String): PageSource {
        if (file.isDirectory) {
            val src = FolderImageSource(file)
            if (src.pageCount <= 0) throw IOException("Folder contains no images: ${file.name}")
            return src
        }
        return when (Formats.typeOf(file.name)) {
            EntryType.IMAGE -> {
                val parent = file.parentFile
                if (parent != null && FolderImageSource.isImageChapterFolder(parent)) {
                    val folderSrc = FolderImageSource(parent)
                    if (folderSrc.pageCount > 1) return folderSrc
                }
                ImageSource(file)
            }
            EntryType.PDF -> PdfSource(file)
            EntryType.ARCHIVE -> {
                val ext = Formats.ext(file.name)
                when {
                    ext in Formats.ZIP -> ZipSource(file, charsetName)
                    ext in Formats.RAR -> RarSource(file)
                    ext in Formats.SEVEN_ZIP -> SevenZipSource(file)
                    else -> throw IOException("Unsupported archive: $ext")
                }
            }
            else -> throw IOException("Unsupported file type: ${file.name}")
        }
    }
}
