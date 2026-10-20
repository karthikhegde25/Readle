package com.readle.app.reader.reflow

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Comic book archives (.cbz = a ZIP of page images). Every image is one "page"; pages are read straight
 * out of the archive when shown. CBR / CB7 (RAR / 7-Zip archives) are not supported.
 */
object CbzBook {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private const val MAX_PAGE = 24 * 1024 * 1024

    fun open(file: File, fallbackTitle: String?): MemoryBook {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw BookOpenException(
                BookOpenException.Kind.UNSUPPORTED,
                "This isn't a ZIP-based comic archive. RAR-based .cbr files aren't supported; re-save the comic as .cbz.", e
            )
        } catch (e: IOException) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "Could not read the comic archive", e)
        }
        try {
            return parse(zip, fallbackTitle)
        } catch (e: BookOpenException) {
            zip.close(); throw e
        } catch (e: Exception) {
            zip.close()
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The comic archive is damaged", e)
        }
    }

    /** The page images of an archive in reading order (natural sort: "page2" before "page10"). */
    fun pageEntries(zip: ZipFile): List<java.util.zip.ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { e ->
                val base = e.name.substringAfterLast('/')
                !e.name.startsWith("__MACOSX") && !base.startsWith(".") && base.substringAfterLast('.', "").lowercase() in IMAGE_EXT
            }
            .toList()
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

    private fun parse(zip: ZipFile, fallbackTitle: String?): MemoryBook {
        val pages = pageEntries(zip)
        if (pages.isEmpty()) throw BookOpenException(BookOpenException.Kind.CORRUPT, "The archive contains no page images")

        val resources = LinkedHashMap<String, LazyResource>()
        val chapters = ArrayList<MemoryChapter>()
        pages.forEachIndexed { i, entry ->
            val n = i + 1
            val key = MemoryBook.resourcePath("p$n")
            resources[key] = LazyResource(Mime.forPath(entry.name)) { zip.getInputStream(entry).use { it.readBounded(MAX_PAGE) } }
            chapters.add(MemoryChapter("Page $n", "<p style=\"text-align:center\"><img src=\"../$key\" alt=\"Page $n\"></p>"))
        }

        var title: String? = null
        var author: String? = null
        var series: String? = null
        zip.getEntry("ComicInfo.xml")?.let { info ->
            runCatching {
                val root = XmlUtil.parse(zip.getInputStream(info).use { it.readBounded(1024 * 1024) }).documentElement
                fun f(name: String) = root.firstChildNamed(name)?.collapsedText()?.ifEmpty { null }
                title = f("Title")
                author = f("Writer")
                series = f("Series")?.let { s -> f("Number")?.let { "$s #$it" } ?: s }
            }
        }
        return MemoryBook(
            BookMeta(title ?: fallbackTitle, author, series, null, MemoryBook.resourcePath("p1")),
            chapters, resources
        ) { runCatching { zip.close() } }
    }

    /** "page2" sorts before "page10". */
    fun naturalCompare(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var ei = i
                while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j
                while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0')
                val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++; j++
            }
        }
        return (x.length - i) - (y.length - j)
    }
}
