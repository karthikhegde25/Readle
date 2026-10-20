package com.readle.app.domain

enum class AppTheme(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class LibraryLayout(val label: String) {
    GRID("Grid"), COMPACT("Compact list"), DETAILED("Detailed list")
}

enum class SortKey(val label: String) {
    TITLE("Title"),
    FILENAME("Filename"),
    AUTHOR("Author"),
    RECENTLY_OPENED("Recently opened"),
    LAST_MODIFIED("Last modified"),
    FILE_SIZE("File size"),
    FORMAT("Format"),
    PROGRESS("Reading progress")
}

enum class ReaderTheme(val label: String) {
    DAY("Day"), NIGHT("Night"), SEPIA("Sepia"),
    /** Pure black page with white text. */
    NIGHT_CONTRAST("Night contrast"),
    /** Sepia paper with near-black text. */
    SEPIA_CONTRAST("Sepia contrast")
}

enum class ReaderFont(val label: String, val css: String) {
    SERIF("Serif", "Georgia, 'Times New Roman', serif"),
    SANS("Sans", "'Helvetica Neue', Roboto, Arial, sans-serif"),
    MONO("Mono", "'Courier New', Courier, monospace")
}

enum class ReaderAlign(val label: String, val css: String) {
    LEFT("Left", "left"), JUSTIFY("Justify", "justify")
}

enum class PageTurnMode(val label: String) {
    SCROLL("Continuous scroll"),
    TAP("Pages (tap or swipe)")
}

enum class OrientationPref(val label: String) {
    AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape")
}

enum class ReadStatus(val label: String) { UNREAD("Unread"), READING("Reading"), FINISHED("Finished") }

/** Typography preferences that only make sense for reflowable formats. */
data class TypographySettings(
    val theme: ReaderTheme = ReaderTheme.DAY,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizePercent: Int = 100,
    val lineSpacing: Float = 1.5f,
    /** Space after each paragraph, in em. */
    val paragraphSpacing: Float = 0.6f,
    val marginDp: Int = 16,
    val align: ReaderAlign = ReaderAlign.LEFT,
    /** When set (and still present), an imported image theme is used instead of [theme]. */
    val customThemeId: String? = null,
    /** When set (and still present), an imported font is used instead of [font]. */
    val customFontId: String? = null
) {
    companion object {
        const val MIN_FONT_PERCENT = 60
        const val MAX_FONT_PERCENT = 260
        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 2.4f
        const val MIN_PARAGRAPH_SPACING = 0f
        const val MAX_PARAGRAPH_SPACING = 2f
        const val MIN_MARGIN = 0
        const val MAX_MARGIN = 48
    }
}

/** Every persisted app-wide preference. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    // Appearance
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val layout: LibraryLayout = LibraryLayout.GRID,
    val showCovers: Boolean = true,
    // Library
    val sortKey: SortKey = SortKey.TITLE,
    val sortAscending: Boolean = true,
    val keepHistory: Boolean = true,
    val detectDuplicates: Boolean = true,
    // Reading
    val typography: TypographySettings = TypographySettings(),
    val pageTurn: PageTurnMode = PageTurnMode.SCROLL,
    val orientation: OrientationPref = OrientationPref.AUTO,
    /** 0.05..1.0, or null to follow the system brightness. */
    val brightnessOverride: Float? = null,
    val keepScreenOn: Boolean = true,
    val ttsRate: Float = 1.0f,
    /** Keep reading aloud (with a notification) after the reader or the app is closed. */
    val backgroundReading: Boolean = true,
    /** Name of the chosen text-to-speech voice, or null to pick one automatically for each book's language. */
    val ttsVoice: String? = null,
    /** Package name of the chosen text-to-speech engine, or null for the phone's default engine. */
    val ttsEngine: String? = null,
    /** Sleep timer for read aloud in minutes; 0 means off. It starts when reading starts. */
    val sleepTimerMinutes: Int = 0,
    // Accessibility
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val largeTouchTargets: Boolean = false
)
