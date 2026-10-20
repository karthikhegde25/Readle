package com.readle.app.reader.reflow

import com.readle.app.domain.DocFormat
import java.io.File

/** Single entry point that turns a local copy of a file into a [ReflowBook], whatever its format. */
object BookOpener {
    /** @throws BookOpenException when the file cannot be shown; the message is safe to display. */
    fun open(format: DocFormat, file: File, title: String?): ReflowBook = when (format) {
        DocFormat.EPUB -> EpubBook.open(file)
        DocFormat.TXT -> TxtBook.open(file, title)
        DocFormat.MOBI -> MobiBook.open(file, title)
        DocFormat.FB2 -> Fb2Book.open(file, title)
        DocFormat.DOCX -> DocxBook.open(file, title)
        DocFormat.RTF -> RtfBook.open(file, title)
        DocFormat.HTML -> HtmlDocBook.open(file, title)
        DocFormat.MD -> MarkdownBook.open(file, title)
        DocFormat.CBZ -> CbzBook.open(file, title)
        DocFormat.PDF -> throw BookOpenException(BookOpenException.Kind.UNSUPPORTED, "PDF files use the PDF viewer")
    }
}
