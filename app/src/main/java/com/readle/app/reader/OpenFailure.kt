package com.readle.app.reader

import com.readle.app.reader.reflow.BookOpenException

/** Why a document could not be opened, in terms the UI can explain and offer a next step for. */
enum class OpenFailure {
    UNSUPPORTED, CORRUPT, PASSWORD, PERMISSION, MISSING, TOO_LARGE, DRM, UNKNOWN;

    val title: String
        get() = when (this) {
            UNSUPPORTED -> "Unsupported format"
            CORRUPT -> "This file can't be read"
            PASSWORD -> "Password-protected"
            PERMISSION -> "Access was revoked"
            MISSING -> "File not found"
            TOO_LARGE -> "File too large"
            DRM -> "Protected by DRM"
            UNKNOWN -> "Something went wrong"
        }

    val explanation: String
        get() = when (this) {
            UNSUPPORTED -> "Readle can open PDF, EPUB, MOBI, FB2, DOCX, RTF, HTML, Markdown, TXT and CBZ files."
            CORRUPT -> "The file looks damaged or isn't a valid document. Try re-downloading or re-copying it."
            PASSWORD -> "Readle can't open password-protected PDFs yet. Remove the password in another app and try again."
            PERMISSION -> "Android no longer lets Readle read this file. Relink it by choosing the file again."
            MISSING -> "The file was moved, renamed or deleted. If you moved it, relink it by choosing it again."
            TOO_LARGE -> "This file is larger than Readle can safely load."
            DRM -> "This book is copy-protected. Readle only opens DRM-free books."
            UNKNOWN -> "An unexpected error occurred while opening this file."
        }

    /** True when choosing the file again could fix the problem. */
    val canRelink: Boolean get() = this == PERMISSION || this == MISSING

    companion object {
        fun from(kind: BookOpenException.Kind): OpenFailure = when (kind) {
            BookOpenException.Kind.CORRUPT -> CORRUPT
            BookOpenException.Kind.UNSUPPORTED -> UNSUPPORTED
            BookOpenException.Kind.TOO_LARGE -> TOO_LARGE
            BookOpenException.Kind.DRM -> DRM
        }
    }
}

class DocumentOpenException(
    val failure: OpenFailure,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: failure.title, cause)
