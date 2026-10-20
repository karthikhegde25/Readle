package com.readle.app.dict

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Loads the bundled WordNet on first use. The asset is copied once into app-private storage so it can be
 * memory-mapped; nothing leaves the device and no permission is involved.
 */
object DictionaryProvider {
    private const val ASSET = "wordnet/wordnet.bin"
    private const val EXC = "wordnet/wordnet-exc.txt"
    private const val FILE = "wordnet-v1.bin"

    private val lock = Mutex()
    @Volatile private var cached: WordNet? = null

    /** Returns the dictionary, or null if the bundled file could not be opened. */
    suspend fun get(context: Context): WordNet? {
        cached?.let { return it }
        return lock.withLock {
            cached ?: withContext(Dispatchers.IO) { load(context.applicationContext) }.also { cached = it }
        }
    }

    private fun load(app: Context): WordNet? = try {
        val target = File(app.filesDir, FILE)
        // The asset is compressed inside the APK, so its size is unknown up front; the file name carries the
        // format version and the copy is atomic (temp file then rename), so an existing file is complete.
        if (!target.exists() || target.length() == 0L) {
            val tmp = File(app.filesDir, "$FILE.tmp")
            app.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(target)) { tmp.delete(); error("could not install dictionary") }
        }
        val map = RandomAccessFile(target, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        WordNet(map, app.assets.open(EXC).bufferedReader().use { it.readText() })
    } catch (_: Exception) {
        null
    }
}
