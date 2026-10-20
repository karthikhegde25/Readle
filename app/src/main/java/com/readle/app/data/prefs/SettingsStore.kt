package com.readle.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.readle.app.domain.AppSettings
import com.readle.app.domain.AppTheme
import com.readle.app.domain.CustomRules
import com.readle.app.domain.LibraryLayout
import com.readle.app.domain.OrientationPref
import com.readle.app.domain.PageTurnMode
import com.readle.app.domain.ReaderAlign
import com.readle.app.domain.ReaderFont
import com.readle.app.domain.ReaderTheme
import com.readle.app.domain.SortKey
import com.readle.app.domain.TypographySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "readle_settings")

/** DataStore-backed persistence for [AppSettings]. Unknown or corrupt values fall back to defaults. */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs.write(transform(prefs.toSettings()))
        }
    }

    suspend fun clearAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val appTheme = stringPreferencesKey("app_theme")
        val layout = stringPreferencesKey("library_layout")
        val showCovers = booleanPreferencesKey("show_covers")
        val sortKey = stringPreferencesKey("sort_key")
        val sortAscending = booleanPreferencesKey("sort_ascending")
        val keepHistory = booleanPreferencesKey("keep_history")
        val detectDuplicates = booleanPreferencesKey("detect_duplicates")
        val readerTheme = stringPreferencesKey("reader_theme")
        val readerFont = stringPreferencesKey("reader_font")
        val fontSize = intPreferencesKey("reader_font_size")
        val lineSpacing = floatPreferencesKey("reader_line_spacing")
        val paragraphSpacing = floatPreferencesKey("reader_paragraph_spacing")
        val margin = intPreferencesKey("reader_margin")
        val align = stringPreferencesKey("reader_align")
        val pageTurn = stringPreferencesKey("page_turn")
        val orientation = stringPreferencesKey("orientation")
        val brightness = floatPreferencesKey("brightness_override")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val ttsRate = floatPreferencesKey("tts_rate")
        val backgroundReading = booleanPreferencesKey("background_reading")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val sleepTimer = intPreferencesKey("sleep_timer_minutes")
        val customTheme = stringPreferencesKey("custom_theme_id")
        val customFont = stringPreferencesKey("custom_font_id")
        val highContrast = booleanPreferencesKey("high_contrast")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val largeTouch = booleanPreferencesKey("large_touch_targets")
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val dt = d.typography
        return AppSettings(
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            appTheme = enumOf(this[K.appTheme], d.appTheme),
            layout = enumOf(this[K.layout], d.layout),
            showCovers = this[K.showCovers] ?: d.showCovers,
            sortKey = enumOf(this[K.sortKey], d.sortKey),
            sortAscending = this[K.sortAscending] ?: d.sortAscending,
            keepHistory = this[K.keepHistory] ?: d.keepHistory,
            detectDuplicates = this[K.detectDuplicates] ?: d.detectDuplicates,
            typography = TypographySettings(
                theme = enumOf(this[K.readerTheme], dt.theme),
                font = enumOf(this[K.readerFont], dt.font),
                fontSizePercent = (this[K.fontSize] ?: dt.fontSizePercent)
                    .coerceIn(TypographySettings.MIN_FONT_PERCENT, TypographySettings.MAX_FONT_PERCENT),
                lineSpacing = (this[K.lineSpacing] ?: dt.lineSpacing)
                    .coerceIn(TypographySettings.MIN_LINE_SPACING, TypographySettings.MAX_LINE_SPACING),
                paragraphSpacing = (this[K.paragraphSpacing] ?: dt.paragraphSpacing)
                    .coerceIn(TypographySettings.MIN_PARAGRAPH_SPACING, TypographySettings.MAX_PARAGRAPH_SPACING),
                marginDp = (this[K.margin] ?: dt.marginDp)
                    .coerceIn(TypographySettings.MIN_MARGIN, TypographySettings.MAX_MARGIN),
                align = enumOf(this[K.align], dt.align),
                customThemeId = this[K.customTheme]?.takeIf { CustomRules.isSafeId(it) },
                customFontId = this[K.customFont]?.takeIf { CustomRules.isSafeId(it) }
            ),
            pageTurn = enumOf(this[K.pageTurn], d.pageTurn),
            orientation = enumOf(this[K.orientation], d.orientation),
            brightnessOverride = this[K.brightness]?.takeIf { it in 0.02f..1f },
            keepScreenOn = this[K.keepScreenOn] ?: d.keepScreenOn,
            ttsRate = (this[K.ttsRate] ?: d.ttsRate).coerceIn(0.5f, 2.5f),
            backgroundReading = this[K.backgroundReading] ?: d.backgroundReading,
            ttsVoice = this[K.ttsVoice]?.takeIf { it.isNotBlank() },
            ttsEngine = this[K.ttsEngine]?.takeIf { it.isNotBlank() },
            sleepTimerMinutes = (this[K.sleepTimer] ?: d.sleepTimerMinutes).coerceIn(0, 600),
            highContrast = this[K.highContrast] ?: d.highContrast,
            reducedMotion = this[K.reducedMotion] ?: d.reducedMotion,
            largeTouchTargets = this[K.largeTouch] ?: d.largeTouchTargets
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.onboardingDone] = s.onboardingDone
        this[K.appTheme] = s.appTheme.name
        this[K.layout] = s.layout.name
        this[K.showCovers] = s.showCovers
        this[K.sortKey] = s.sortKey.name
        this[K.sortAscending] = s.sortAscending
        this[K.keepHistory] = s.keepHistory
        this[K.detectDuplicates] = s.detectDuplicates
        this[K.readerTheme] = s.typography.theme.name
        this[K.readerFont] = s.typography.font.name
        this[K.fontSize] = s.typography.fontSizePercent
        this[K.lineSpacing] = s.typography.lineSpacing
        this[K.paragraphSpacing] = s.typography.paragraphSpacing
        this[K.margin] = s.typography.marginDp
        this[K.align] = s.typography.align.name
        this[K.pageTurn] = s.pageTurn.name
        this[K.orientation] = s.orientation.name
        val b = s.brightnessOverride
        if (b == null) remove(K.brightness) else this[K.brightness] = b
        this[K.keepScreenOn] = s.keepScreenOn
        this[K.ttsRate] = s.ttsRate
        this[K.backgroundReading] = s.backgroundReading
        val v = s.ttsVoice
        if (v == null) remove(K.ttsVoice) else this[K.ttsVoice] = v
        val eng = s.ttsEngine
        if (eng == null) remove(K.ttsEngine) else this[K.ttsEngine] = eng
        this[K.sleepTimer] = s.sleepTimerMinutes.coerceIn(0, 600)
        val ct = s.typography.customThemeId
        if (ct == null) remove(K.customTheme) else this[K.customTheme] = ct
        val cf = s.typography.customFontId
        if (cf == null) remove(K.customFont) else this[K.customFont] = cf
        this[K.highContrast] = s.highContrast
        this[K.reducedMotion] = s.reducedMotion
        this[K.largeTouch] = s.largeTouchTargets
    }
}
