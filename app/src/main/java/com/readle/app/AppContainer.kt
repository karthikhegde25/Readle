package com.readle.app

import android.content.Context
import com.readle.app.data.db.ReadleDatabase
import com.readle.app.data.prefs.SettingsStore
import com.readle.app.data.repo.CollectionRepository
import com.readle.app.data.repo.LibraryManager
import com.readle.app.data.repo.LibraryRepository
import com.readle.app.data.repo.ReaderRepository
import com.readle.app.files.CoverCache
import com.readle.app.files.FileCache
import com.readle.app.files.MetadataExtractor
import com.readle.app.data.prefs.CustomAssets
import com.readle.app.reader.tts.ReadAloudManager

/** Hand-wired dependency container (no DI framework needed at this size). One instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val database: ReadleDatabase = ReadleDatabase.build(appContext)
    val settings = SettingsStore(appContext)
    val fileCache = FileCache(appContext)
    val covers = CoverCache(appContext)

    private val extractor = MetadataExtractor(appContext, fileCache, covers)

    val libraryManager = LibraryManager(appContext, database, fileCache, covers, extractor)
    val libraryRepository = LibraryRepository(database, settings)
    val readerRepository = ReaderRepository(database, settings)
    val collectionRepository = CollectionRepository(database)

    /** Imported fonts and image themes. */
    val customAssets = CustomAssets(appContext)

    /** Process-wide read-aloud engine, so speech can continue after the reader is closed. */
    val readAloud = ReadAloudManager(appContext, libraryRepository, readerRepository, fileCache, settings)
}
