package com.readle.app.reader.reflow

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lenient XML loading for EPUB package files.
 * DOCTYPE declarations are stripped (no external entity / DTD resolution, which also closes XXE),
 * and HTML named entities such as &nbsp; that plain XML parsers reject are rewritten.
 */
internal object XmlUtil {
    private val DOCTYPE = Regex("<!DOCTYPE[^>\\[]*(\\[[\\s\\S]*?\\])?\\s*>", RegexOption.IGNORE_CASE)
    private val NAMED_ENTITY = Regex("&([A-Za-z][A-Za-z0-9]*);")
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val HTML_ENTITIES = mapOf(
        "nbsp" to 160, "copy" to 169, "reg" to 174, "mdash" to 8212, "ndash" to 8211,
        "hellip" to 8230, "laquo" to 171, "raquo" to 187, "ldquo" to 8220, "rdquo" to 8221,
        "lsquo" to 8216, "rsquo" to 8217, "bull" to 8226, "middot" to 183, "times" to 215,
        "eacute" to 233, "egrave" to 232, "agrave" to 224, "ccedil" to 231, "uuml" to 252,
        "ouml" to 246, "auml" to 228, "szlig" to 223, "trade" to 8482
    )

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /** Decodes XML honouring its own `encoding="..."` declaration (FB2 files are often windows-1251). */
    fun decodeDeclared(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val name = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']").find(head)?.groupValues?.get(1)
        val cs = try { name?.let { java.nio.charset.Charset.forName(it) } } catch (e: Exception) { null }
        return if (cs != null && !cs.name().startsWith("UTF-8") && !(bytes.size >= 2 && (bytes[0].toInt() and 0xFF) in setOf(0xFE, 0xFF)))
            String(bytes, cs) else decodeText(bytes)
    }

    fun parse(bytes: ByteArray): Document = parseText(decodeText(bytes))

    fun parseDeclared(bytes: ByteArray): Document = parseText(decodeDeclared(bytes))

    fun parseText(source: String): Document {
        var text = source.trimStart()
        text = DOCTYPE.replace(text, "")
        text = NAMED_ENTITY.replace(text) { m ->
            val name = m.groupValues[1]
            when {
                name in XML_ENTITIES -> m.value
                else -> HTML_ENTITIES[name]?.let { "&#$it;" } ?: ""
            }
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(StringReader(text)))
    }
}

internal fun Node.local(): String = nodeName.substringAfter(':')

internal fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

internal fun Element.firstChildNamed(local: String): Element? = childElements().firstOrNull { it.local() == local }

internal fun Element.descendantsNamed(local: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(e: Element) {
        for (c in e.childElements()) {
            if (c.local() == local) out.add(c)
            walk(c)
        }
    }
    walk(this)
    return out
}

internal fun Element.attrOrNull(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

internal fun Element.collapsedText(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()
