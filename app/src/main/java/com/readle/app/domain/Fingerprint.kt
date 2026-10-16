package com.readle.app.domain

import java.security.MessageDigest

/**
 * Cheap document identity for duplicate detection: file size plus a SHA-256 of the first 64 KB.
 * Hashing the whole file would be slow for large libraries; two different files that share both size
 * and the first 64 KB are, in practice, copies.
 */
object Fingerprint {
    const val HEAD_BYTES = 64 * 1024

    fun of(sizeBytes: Long, head: ByteArray, headLength: Int = head.size): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(head, 0, headLength.coerceIn(0, head.size))
        val hex = md.digest().take(16).joinToString("") { "%02x".format(it) }
        return "$sizeBytes:$hex"
    }
}
