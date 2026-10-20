import com.readle.app.domain.TypographySettings
import com.readle.app.reader.reflow.ChapterTarget
import com.readle.app.reader.reflow.HtmlInjector
import com.readle.app.reader.reflow.ReaderCss
import com.readle.app.reader.reflow.ReaderScript
import java.io.File

// Usage: GenPage <outDir>
// Writes the same HTML the WebView would be served for a sample EPUB-style (XHTML) chapter.
fun main(args: Array<String>) {
    val out = File(args[0]).also { it.mkdirs() }
    val paragraphs = (1..60).joinToString("\n") { i ->
        "<p id=\"p$i\">Paragraph $i begins here. The quick brown fox jumps over the lazy dog, again and again, until the sentence is long enough to wrap onto several lines on a narrow phone screen number $i.</p>"
    }
    val chapter = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title>
<style>p{color:red !important;font-size:9px}</style>
<script>window.BOOK_SCRIPT_RAN = true;</script></head>
<body><h1 id="top">Chapter One</h1>
<p id="multi">Alpha <em>beta <b>gamma</b></em> delta epsilon.</p>
<p>Second line with <a href="#p30" id="jump">a footnote link</a> and <a href="other.xhtml" id="ext">another chapter</a>.</p>
$paragraphs
<p id="needle">The needle appears once here, and the Needle appears twice, and NEEDLE a third time.</p>
</body></html>"""

    val css = ReaderCss.build(TypographySettings())
    fun page(name: String, target: ChapterTarget, first: Boolean, last: Boolean, highlights: String = "[]") {
        val init = HtmlInjector.initJson(target, first, last, highlights)
        val html = HtmlInjector.inject(chapter, css, ReaderScript.JS, init, "TESTNONCE")
        File(out, name).writeText(html)
    }
    page("frac.html", ChapterTarget(0, fraction = 0.5f), first = false, last = false)
    page("end.html", ChapterTarget(0, fraction = 1f), first = false, last = true)
    page("anchor.html", ChapterTarget(0, anchor = "p40"), first = true, last = false)
    page("hl.html", ChapterTarget(0), first = true, last = false,
        highlights = "[{\"id\":7,\"start\":14,\"end\":38,\"color\":\"#FFE066\",\"note\":true}]")
    val pagedCss = ReaderCss.build(TypographySettings(), paged = true)
    fun pagedPage(name: String, target: ChapterTarget, first: Boolean, last: Boolean) {
        val init = HtmlInjector.initJson(target, first, last, "[]")
        File(out, name).writeText(HtmlInjector.inject(chapter, pagedCss, ReaderScript.JS, init, "TESTNONCE"))
    }
    pagedPage("paged.html", ChapterTarget(0, fraction = 0f), first = false, last = false)
    pagedPage("paged_end.html", ChapterTarget(0, fraction = 1f), first = false, last = false)
    pagedPage("paged_anchor.html", ChapterTarget(0, anchor = "p40"), first = true, last = false)
    File(out, "pagedcss.txt").writeText(pagedCss)
    File(out, "csp.txt").writeText(HtmlInjector.contentSecurityPolicy("TESTNONCE", "readle.local"))
    println("wrote pages to $out")
}
