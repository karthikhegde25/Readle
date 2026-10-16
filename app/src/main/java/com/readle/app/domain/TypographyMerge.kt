package com.readle.app.domain

import com.readle.app.data.db.BookReaderSettingsEntity

/** Per-book overrides sit on top of the global typography; null columns fall through to the global value. */
object TypographyMerge {
    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    fun effective(global: TypographySettings, override: BookReaderSettingsEntity?): TypographySettings {
        if (override == null) return global
        return TypographySettings(
            theme = enumOrNull<ReaderTheme>(override.theme) ?: global.theme,
            font = enumOrNull<ReaderFont>(override.fontFamily) ?: global.font,
            fontSizePercent = (override.fontSizePercent ?: global.fontSizePercent)
                .coerceIn(TypographySettings.MIN_FONT_PERCENT, TypographySettings.MAX_FONT_PERCENT),
            lineSpacing = (override.lineSpacing ?: global.lineSpacing)
                .coerceIn(TypographySettings.MIN_LINE_SPACING, TypographySettings.MAX_LINE_SPACING),
            paragraphSpacing = (override.paragraphSpacing ?: global.paragraphSpacing)
                .coerceIn(TypographySettings.MIN_PARAGRAPH_SPACING, TypographySettings.MAX_PARAGRAPH_SPACING),
            marginDp = (override.marginDp ?: global.marginDp)
                .coerceIn(TypographySettings.MIN_MARGIN, TypographySettings.MAX_MARGIN),
            align = enumOrNull<ReaderAlign>(override.textAlign) ?: global.align
        )
    }

    fun toEntity(documentId: Long, t: TypographySettings) = BookReaderSettingsEntity(
        documentId = documentId,
        theme = t.theme.name,
        fontFamily = t.font.name,
        fontSizePercent = t.fontSizePercent,
        lineSpacing = t.lineSpacing,
        paragraphSpacing = t.paragraphSpacing,
        marginDp = t.marginDp,
        textAlign = t.align.name
    )
}
