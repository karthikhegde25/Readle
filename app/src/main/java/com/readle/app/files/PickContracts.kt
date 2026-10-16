package com.readle.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

/**
 * File pickers that also ask Android for write access. Without it Readle could read a picked book but
 * Android would refuse to let it delete the file. Providers that can't grant write simply return read access.
 */
class OpenDocumentsReadWrite : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(RW)
}

/** Single-file picker, opened at [initialUri] when given, used to re-grant access to one specific file. */
class OpenDocumentReadWrite : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(RW)

    fun intentFor(context: Context, mimeTypes: Array<String>, initial: Uri?): Intent =
        createIntent(context, mimeTypes).also { i ->
            if (initial != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
}
