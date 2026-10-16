package com.readle.app.reader.reflow

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * EPUB 2/3 reader built on java.util.zip. Parses container.xml -> OPF (metadata, manifest, spine)
 * and the table of contents (EPUB 3 nav document, falling back to EPUB 2 NCX, then to chapter numbers).
 * Content is never extracted to disk; chapters and assets are streamed out of the archive on demand.
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    override val meta: BookMeta,
    override val spine: List<SpineItem>,
    override val toc: List<TocEntry>
) : ReflowBook {

    override fun open(path: String): ResourceData? {
        val entry = lookup(entries, path) ?: return null
        return try {
            ResourceData(Mime.forPath(entry.name), zip.getInputStream(entry))
        } catch (e: IOException) {
            null
        }
    }

    override fun chapterText(index: Int): String {
        val item = spine.getOrNull(index) ?: return ""
        val entry = lookup(entries, item.path) ?: return ""
        return try {
            val bytes = zip.getInputStream(entry).use { it.readBounded(MAX_CHAPTER_BYTES) }
            HtmlText.toPlain(XmlUtil.decodeText(bytes))
        } catch (e: Exception) {
            ""
        }
    }

    override fun close() {
        try {
            zip.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val MAX_XML_BYTES = 8 * 1024 * 1024
        private const val MAX_CHAPTER_BYTES = 32 * 1024 * 1024
        private val FONT_OBFUSCATION = listOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC"
        )

        private fun lookup(entries: Map<String, ZipEntry>, path: String): ZipEntry? {
            val key = PathUtil.normalize(path)
            return entries[key] ?: entries[key.lowercase()]
        }

        /** @throws BookOpenException when the file is not a readable, unprotected EPUB. */
        fun open(file: File): EpubBook {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw BookOpenException(BookOpenException.Kind.CORRUPT, "Not a valid EPUB (ZIP) archive", e)
            } catch (e: IOException) {
                throw BookOpenException(BookOpenException.Kind.CORRUPT, "Could not read the EPUB archive", e)
            }
            try {
                return parse(zip)
            } catch (e: BookOpenException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw BookOpenException(BookOpenException.Kind.CORRUPT, "The EPUB is damaged or malformed", e)
            }
        }

        private fun parse(zip: ZipFile): EpubBook {
            val entries = HashMap<String, ZipEntry>()
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory) continue
                val key = PathUtil.normalize(entry.name)
                entries[key] = entry
                entries.putIfAbsent(key.lowercase(), entry)
            }

            checkDrm(zip, entries)

            val containerEntry = lookup(entries, "META-INF/container.xml")
                ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "Missing META-INF/container.xml")
            val container = XmlUtil.parse(zip.getInputStream(containerEntry).use { it.readBounded(MAX_XML_BYTES) })
            val rootfile = container.documentElement.descendantsNamed("rootfile").firstOrNull()
                ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "container.xml has no rootfile")
            val opfPath = PathUtil.normalize(
                PathUtil.decode(rootfile.attrOrNull("full-path")
                    ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "rootfile has no full-path"))
            )
            val opfEntry = lookup(entries, opfPath)
                ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "Package document $opfPath not found")
            val opfDir = PathUtil.dirOf(opfPath)
            val opf = XmlUtil.parse(zip.getInputStream(opfEntry).use { it.readBounded(MAX_XML_BYTES) }).documentElement

            // ---- metadata ----
            val metadata = opf.firstChildNamed("metadata")
            val title = metadata?.descendantsNamed("title")?.firstOrNull()?.collapsedText()
            val creators = metadata?.descendantsNamed("creator")?.map { it.collapsedText() }?.filter { it.isNotEmpty() }.orEmpty()
            val language = metadata?.descendantsNamed("language")?.firstOrNull()?.collapsedText()
            val metaTags = metadata?.descendantsNamed("meta").orEmpty()
            val series = metaTags.firstOrNull { it.attrOrNull("name") == "calibre:series" }?.attrOrNull("content")
                ?: metaTags.firstOrNull { it.attrOrNull("property") == "belongs-to-collection" }?.collapsedText()
            val coverId = metaTags.firstOrNull { it.attrOrNull("name") == "cover" }?.attrOrNull("content")

            // ---- manifest ----
            class ManifestItem(val id: String, val href: String, val mediaType: String?, val properties: String)
            val manifest = LinkedHashMap<String, ManifestItem>()
            opf.firstChildNamed("manifest")?.childElements()?.filter { it.local() == "item" }?.forEach { item ->
                val id = item.attrOrNull("id") ?: return@forEach
                val href = item.attrOrNull("href") ?: return@forEach
                manifest[id] = ManifestItem(id, PathUtil.resolve(opfDir, href), item.attrOrNull("media-type"), item.getAttribute("properties"))
            }

            val coverPath = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.href
                ?: coverId?.let { manifest[it]?.href }

            // ---- spine ----
            val spineEl = opf.firstChildNamed("spine")
                ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "Package has no spine")
            val spineItems = ArrayList<SpineItem>()
            for (ref in spineEl.childElements().filter { it.local() == "itemref" }) {
                val item = manifest[ref.attrOrNull("idref")] ?: continue
                val isHtml = item.mediaType?.let { it.contains("html") || it.contains("xml") } ?: Mime.isHtml(item.href)
                if (!isHtml) continue
                val entry = lookup(entries, item.href) ?: continue
                val size = if (entry.size > 0) entry.size else entry.compressedSize.coerceAtLeast(1L)
                spineItems.add(SpineItem(PathUtil.normalize(entry.name), size))
            }
            if (spineItems.isEmpty()) {
                throw BookOpenException(BookOpenException.Kind.CORRUPT, "The EPUB contains no readable chapters")
            }

            // ---- table of contents ----
            val toc = ArrayList<TocEntry>()
            val navItem = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
            val ncxItem = spineEl.attrOrNull("toc")?.let { manifest[it] }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }

            fun addEntry(title: String, href: String, baseDir: String, depth: Int) {
                val path = PathUtil.resolve(baseDir, href)
                val idx = spineItems.indexOfFirst { it.path == path || it.path.equals(path, ignoreCase = true) }
                if (idx >= 0 && title.isNotBlank()) {
                    toc.add(TocEntry(title.trim(), idx, PathUtil.fragmentOf(href), depth))
                }
            }

            if (navItem != null) {
                runCatching {
                    val navEntry = lookup(entries, navItem.href)!!
                    val doc = XmlUtil.parse(zip.getInputStream(navEntry).use { it.readBounded(MAX_XML_BYTES) }).documentElement
                    val navs = doc.descendantsNamed("nav")
                    val tocNav = navs.firstOrNull { (it.getAttribute("epub:type") + " " + it.getAttribute("type")).contains("toc") }
                        ?: navs.firstOrNull()
                    val navDir = PathUtil.dirOf(navItem.href)
                    fun walk(ol: org.w3c.dom.Element, depth: Int) {
                        for (li in ol.childElements().filter { it.local() == "li" }) {
                            val label = li.childElements().firstOrNull { it.local() == "a" || it.local() == "span" }
                            val href = label?.attrOrNull("href")
                            if (label != null && href != null) addEntry(label.collapsedText(), href, navDir, depth)
                            li.childElements().firstOrNull { it.local() == "ol" }?.let { walk(it, depth + 1) }
                        }
                    }
                    tocNav?.firstChildNamed("ol")?.let { walk(it, 0) }
                }
            }
            if (toc.isEmpty() && ncxItem != null) {
                runCatching {
                    val ncxEntry = lookup(entries, ncxItem.href)!!
                    val doc = XmlUtil.parse(zip.getInputStream(ncxEntry).use { it.readBounded(MAX_XML_BYTES) }).documentElement
                    val ncxDir = PathUtil.dirOf(ncxItem.href)
                    fun walk(parent: org.w3c.dom.Element, depth: Int) {
                        for (np in parent.childElements().filter { it.local() == "navPoint" }) {
                            val label = np.firstChildNamed("navLabel")?.firstChildNamed("text")?.collapsedText().orEmpty()
                            val src = np.firstChildNamed("content")?.attrOrNull("src")
                            if (src != null) addEntry(label, src, ncxDir, depth)
                            walk(np, depth + 1)
                        }
                    }
                    doc.firstChildNamed("navMap")?.let { walk(it, 0) }
                }
            }
            if (toc.isEmpty()) {
                spineItems.forEachIndexed { i, _ -> toc.add(TocEntry("Chapter ${i + 1}", i, null, 0)) }
            }

            return EpubBook(
                zip = zip,
                entries = entries,
                meta = BookMeta(
                    title = title,
                    author = creators.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    series = series,
                    language = language,
                    coverPath = coverPath
                ),
                spine = spineItems,
                toc = toc
            )
        }

        private fun checkDrm(zip: ZipFile, entries: Map<String, ZipEntry>) {
            val enc = lookup(entries, "META-INF/encryption.xml") ?: return
            val text = zip.getInputStream(enc).use { String(it.readBounded(MAX_XML_BYTES), Charsets.UTF_8) }
            val algorithms = Regex("Algorithm\\s*=\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
            val usesDrm = algorithms.any { alg -> FONT_OBFUSCATION.none { alg.startsWith(it) } }
            if (usesDrm) {
                throw BookOpenException(
                    BookOpenException.Kind.DRM,
                    "This EPUB is protected by DRM and cannot be opened"
                )
            }
        }
    }
}
