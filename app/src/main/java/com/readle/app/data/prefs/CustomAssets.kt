package com.readle.app.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.readle.app.domain.CustomFont
import com.readle.app.domain.CustomRules
import com.readle.app.domain.CustomTheme
import com.readle.app.domain.ThemeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's imported fonts and image themes. Everything is copied into Readle's private storage, so the
 * originals can be moved or deleted afterwards and no storage permission is ever needed. The list is kept
 * in a small JSON index next to the files.
 */
class CustomAssets(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "custom")
    private val themeDir = File(root, "themes")
    private val fontDir = File(root, "fonts")
    private val index = File(root, "index.json")
    private val lock = Any()

    private val _themes = MutableStateFlow<List<CustomTheme>>(emptyList())
    val themes: StateFlow<List<CustomTheme>> = _themes.asStateFlow()

    private val _fonts = MutableStateFlow<List<CustomFont>>(emptyList())
    val fonts: StateFlow<List<CustomFont>> = _fonts.asStateFlow()

    init {
        load()
    }

    /** Maps a request path from the reader page to a stored file; only generated, listed names resolve. */
    fun resolve(kind: String, name: String): File? {
        if (!CustomRules.isSafeFileName(name)) return null
        val known = when (kind) {
            "theme" -> _themes.value.any { it.file == name }
            "font" -> _fonts.value.any { it.file == name }
            else -> false
        }
        if (!known) return null
        val f = File(if (kind == "theme") themeDir else fontDir, name)
        return f.takeIf { it.isFile }
    }

    fun themeFile(theme: CustomTheme): File = File(themeDir, theme.file)

    fun fontFile(font: CustomFont): File = File(fontDir, font.file)

    // ------------------------------------------------------------------ fonts

    /** Copies a font in after checking its extension, size and file header. Failures carry a message safe to show. */
    suspend fun addFont(uri: Uri): Result<CustomFont> = withContext(Dispatchers.IO) {
        runCatching {
            if (_fonts.value.size >= CustomRules.MAX_ITEMS) throw IllegalArgumentException("You can keep up to ${CustomRules.MAX_ITEMS} fonts. Remove one first.")
            val displayName = queryName(uri)
            val ext = CustomRules.fontExtension(displayName)
                ?: throw IllegalArgumentException("Only .ttf, .otf, .woff and .woff2 font files can be added.")
            fontDir.mkdirs()
            val id = CustomRules.newId()
            val fileName = "$id.$ext"
            val target = File(fontDir, fileName)
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("That file couldn't be opened.")
                input.use { ins ->
                    val header = ByteArray(4)
                    var got = 0
                    while (got < 4) {
                        val n = ins.read(header, got, 4 - got)
                        if (n < 0) break
                        got += n
                    }
                    if (!CustomRules.fontHeaderOk(ext, header)) {
                        throw IllegalArgumentException("That file doesn't look like a valid .$ext font.")
                    }
                    target.outputStream().use { out ->
                        out.write(header, 0, 4)
                        var total = 4L
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > CustomRules.MAX_FONT_BYTES) throw IllegalArgumentException("That font file is larger than 25 MB.")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            val base = CustomRules.fontDisplayName(displayName ?: "Custom font")
            val name = uniqueName(base)
            val font = CustomFont(id, name, fileName, ext)
            synchronized(lock) {
                _fonts.value = _fonts.value + font
                save()
            }
            font
        }.recoverCatching { e -> throw friendly(e) }
    }

    fun removeFont(id: String) {
        synchronized(lock) {
            val f = _fonts.value.firstOrNull { it.id == id } ?: return
            File(fontDir, f.file).delete()
            _fonts.value = _fonts.value.filterNot { it.id == id }
            save()
        }
    }

    // ------------------------------------------------------------------ themes

    /** A theme made of one plain background colour, with a chosen or automatic text colour. */
    fun addColorTheme(name: String, background: Int, textColor: Int?, reservedNames: Collection<String>): Result<CustomTheme> = runCatching {
        if (_themes.value.size >= CustomRules.MAX_ITEMS) throw IllegalArgumentException("You can keep up to ${CustomRules.MAX_ITEMS} themes. Remove one first.")
        val problem = CustomRules.nameError(name, _themes.value.map { it.name } + _fonts.value.map { it.name }, reservedNames)
        if (problem != null) throw IllegalArgumentException(problem)
        val theme = CustomRules.themeFor(CustomRules.newId(), name, "", background, ThemeText.AUTO, textColor)
        synchronized(lock) {
            _themes.value = _themes.value + theme
            save()
        }
        theme
    }

    /**
     * Renames a theme and/or changes its colours. [background] only applies to plain colour themes (a picture
     * theme keeps its picture); a null [textColor] means automatic.
     */
    fun updateTheme(id: String, name: String, background: Int?, textColor: Int?, reservedNames: Collection<String>): Result<CustomTheme> = runCatching {
        synchronized(lock) {
            val old = _themes.value.firstOrNull { it.id == id } ?: throw IllegalArgumentException("That theme no longer exists.")
            val others = _themes.value.filter { it.id != id }.map { it.name } + _fonts.value.map { it.name }
            val problem = CustomRules.nameError(name, others, reservedNames)
            if (problem != null) throw IllegalArgumentException(problem)
            val bg = if (old.file.isEmpty() && background != null) background else old.background
            val updated = CustomRules.themeFor(old.id, name, old.file, bg, ThemeText.AUTO, textColor)
            _themes.value = _themes.value.map { if (it.id == id) updated else it }
            save()
            updated
        }
    }

    fun removeTheme(id: String) {
        synchronized(lock) {
            val t = _themes.value.firstOrNull { it.id == id } ?: return
            if (t.file.isNotEmpty()) File(themeDir, t.file).delete()
            _themes.value = _themes.value.filterNot { it.id == id }
            save()
        }
    }

    /** Deletes every imported font and theme (used by "Delete all Readle data"). */
    fun clearAll() {
        synchronized(lock) {
            root.deleteRecursively()
            _themes.value = emptyList()
            _fonts.value = emptyList()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun friendly(e: Throwable): Throwable = when (e) {
        is IllegalArgumentException -> e
        is OutOfMemoryError -> IllegalArgumentException("That picture is too large to process on this device.")
        is SecurityException -> IllegalArgumentException("Android didn't allow Readle to read that file.")
        else -> IllegalArgumentException("That file couldn't be added.")
    }

    private fun uniqueName(base: String): String {
        val taken = (_fonts.value.map { it.name } + _themes.value.map { it.name }).map { it.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var i = 2
        while (true) {
            val suffix = " $i"
            val candidate = base.take(CustomRules.MAX_NAME - suffix.length) + suffix
            if (candidate.lowercase() !in taken) return candidate
            i++
        }
    }

    private fun queryName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) {
        uri.lastPathSegment
    }

    private fun averageColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = (small.width * small.height).toLong()
        if (small !== src) small.recycle()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun save() {
        try {
            root.mkdirs()
            val o = JSONObject()
            o.put("themes", JSONArray().also { arr ->
                _themes.value.forEach { t ->
                    arr.put(JSONObject().put("id", t.id).put("name", t.name).put("file", t.file)
                        .put("bg", t.background).put("text", t.text).put("link", t.link).put("muted", t.muted).put("dark", t.dark))
                }
            })
            o.put("fonts", JSONArray().also { arr ->
                _fonts.value.forEach { f ->
                    arr.put(JSONObject().put("id", f.id).put("name", f.name).put("file", f.file).put("format", f.format))
                }
            })
            val tmp = File(root, "index.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        } catch (_: Exception) {
            // Best effort: the in-memory lists stay correct for this session.
        }
    }

    private fun load() {
        try {
            if (!index.isFile) return
            val o = JSONObject(index.readText())
            val themes = ArrayList<CustomTheme>()
            val ta = o.optJSONArray("themes") ?: JSONArray()
            for (i in 0 until ta.length()) {
                val j = ta.optJSONObject(i) ?: continue
                val id = j.optString("id")
                val file = j.optString("file")
                val name = CustomRules.cleanName(j.optString("name"))
                if (!CustomRules.isSafeId(id) || name.isEmpty()) continue
                // An empty file name is a plain colour theme; otherwise the picture must be a safe name and still exist.
                if (file.isNotEmpty() && (!CustomRules.isSafeFileName(file) || !File(themeDir, file).isFile)) continue
                themes.add(CustomTheme(id, name, file, j.optInt("bg"), j.optInt("text"), j.optInt("link"), j.optInt("muted"), j.optBoolean("dark")))
            }
            val fonts = ArrayList<CustomFont>()
            val fa = o.optJSONArray("fonts") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val j = fa.optJSONObject(i) ?: continue
                val id = j.optString("id")
                val file = j.optString("file")
                val format = j.optString("format")
                val name = CustomRules.cleanName(j.optString("name"))
                if (!CustomRules.isSafeId(id) || !CustomRules.isSafeFileName(file) || format !in CustomRules.FONT_EXTENSIONS || name.isEmpty()) continue
                if (!File(fontDir, file).isFile) continue
                fonts.add(CustomFont(id, name, file, format))
            }
            _themes.value = themes
            _fonts.value = fonts
        } catch (_: Exception) {
            // A damaged index just means no custom items; the files are left alone.
        }
    }
}
