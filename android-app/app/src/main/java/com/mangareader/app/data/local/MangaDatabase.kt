package com.mangareader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BookmarkEntity::class,
        ReadingProgressEntity::class,
        DownloadedChapterEntity::class,
        WebTabEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(MangaTypeConverters::class)
abstract class MangaDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun downloadedChapterDao(): DownloadedChapterDao
    abstract fun webTabDao(): WebTabDao

    companion object {
        const val DATABASE_NAME = "mangareader_local.db"

        @Volatile
        private var instance: MangaDatabase? = null

        fun getInstance(context: Context): MangaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        fun createInMemory(context: Context): MangaDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                MangaDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
