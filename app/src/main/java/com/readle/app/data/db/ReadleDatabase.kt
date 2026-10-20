package com.readle.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        DocumentEntity::class,
        LocationEntity::class,
        ReadingProgressEntity::class,
        CollectionEntity::class,
        CollectionDocumentEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        ReadingHistoryEntity::class,
        BookReaderSettingsEntity::class,
        VocabularyEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ReadleDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun locations(): LocationDao
    abstract fun progress(): ProgressDao
    abstract fun history(): HistoryDao
    abstract fun collections(): CollectionDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun annotations(): AnnotationDao
    abstract fun bookSettings(): BookSettingsDao
    abstract fun vocabulary(): VocabularyDao

    companion object {
        const val NAME = "readle.db"

        /**
         * Every schema change must add an explicit [Migration] here. The database never falls back to
         * destructive migration, so reading progress and annotations are never silently discarded.
         * Exported schemas live in app/schemas/ to support migration tests.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): ReadleDatabase =
            Room.databaseBuilder(context.applicationContext, ReadleDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
