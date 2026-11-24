package com.mangareader.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.data.settings.SettingsDataStore
import com.mangareader.app.ui.screens.reader.AutoScrollLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore(file: File, scope: CoroutineScope): SettingsDataStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )
        return SettingsDataStore(dataStore)
    }

    @Test
    fun `emits expected default values before any writes`() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = createStore(File(tempFolder.newFolder(), "defaults.preferences_pb"), scope)
            val settings = store.getSettings()

            assertEquals(ReaderMode.VERTICAL_WEBTOON, settings.readerMode)
            assertEquals(ReadingDirection.RTL, settings.readerPreferences.readingDirection)
            assertTrue(settings.readerPreferences.isHistoryTrackingEnabled)
            assertTrue(settings.readerPreferences.isAutoScrollEnabled)
            assertEquals(1.0f, settings.readerPreferences.autoScrollSpeed, 0.001f)
            assertTrue(settings.readerPreferences.isPrefetchEnabled)
            assertFalse(settings.readerPreferences.isCustomBrightnessEnabled)
            assertEquals(1.0f, settings.readerPreferences.brightnessLevel, 0.001f)
            assertFalse(settings.readerPreferences.isNightFilterEnabled)
            assertEquals(0.35f, settings.readerPreferences.nightFilterStrength, 0.001f)
            assertTrue(settings.readerPreferences.keepScreenOn)
            assertEquals(ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES, settings.cacheLimitBytes)
            assertEquals(4096, settings.cacheLimitMb)
            assertNull(settings.activeWebTabId)
            assertFalse(settings.legacyMigrationCompleted)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `persists reader preferences, mode, active tab, and cache limit across instances`() = runBlocking {
        val backingFile = File(tempFolder.newFolder(), "persist.preferences_pb")
        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(job1 + Dispatchers.IO)
        try {
            val store1 = createStore(backingFile, scope1)
            store1.updateReaderMode(ReaderMode.HORIZONTAL_PAGED)
            store1.updateReaderPreferences(
                ReaderPreferences(
                    isHistoryTrackingEnabled = false,
                    isAutoScrollEnabled = false,
                    autoScrollSpeed = 2.4f,
                    isPrefetchEnabled = false,
                    isCustomBrightnessEnabled = true,
                    brightnessLevel = 0.45f,
                    isNightFilterEnabled = true,
                    nightFilterStrength = 0.65f,
                    readingDirection = ReadingDirection.LTR,
                    keepScreenOn = false
                )
            )
            store1.updateCacheLimitMb(16384)
            store1.updateActiveWebTabId("tab-42")
            store1.markLegacyMigrationCompleted()
        } finally {
            job1.cancelAndJoin()
        }

        val job2 = SupervisorJob()
        val scope2 = CoroutineScope(job2 + Dispatchers.IO)
        try {
            val store2 = createStore(backingFile, scope2)
            val restored = store2.settingsFlow.first()

            assertEquals(ReaderMode.HORIZONTAL_PAGED, restored.readerMode)
            assertFalse(restored.readerPreferences.isHistoryTrackingEnabled)
            assertFalse(restored.readerPreferences.isAutoScrollEnabled)
            assertEquals(2.4f, restored.readerPreferences.autoScrollSpeed, 0.001f)
            assertFalse(restored.readerPreferences.isPrefetchEnabled)
            assertTrue(restored.readerPreferences.isCustomBrightnessEnabled)
            assertEquals(0.45f, restored.readerPreferences.brightnessLevel, 0.001f)
            assertTrue(restored.readerPreferences.isNightFilterEnabled)
            assertEquals(0.65f, restored.readerPreferences.nightFilterStrength, 0.001f)
            assertEquals(ReadingDirection.LTR, restored.readerPreferences.readingDirection)
            assertFalse(restored.readerPreferences.keepScreenOn)
            assertEquals(16384, restored.cacheLimitMb)
            assertEquals(16L * 1024L * 1024L * 1024L, restored.cacheLimitBytes)
            assertEquals("tab-42", restored.activeWebTabId)
            assertTrue(restored.legacyMigrationCompleted)
        } finally {
            job2.cancelAndJoin()
        }
    }

    @Test
    fun `clamps out-of-range cache limit and display filter values`() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = createStore(File(tempFolder.newFolder(), "clamp.preferences_pb"), scope)

            val lowClamped = store.updateCacheLimitBytes(10L * 1024L * 1024L) // 10 MB -> 512 MB
            assertEquals(ChapterCacheManager.MIN_CACHE_SIZE_BYTES, lowClamped)
            assertEquals(512, store.cacheLimitMbFlow.first())

            val highClamped = store.updateCacheLimitBytes(100L * 1024L * 1024L * 1024L) // 100 GB -> 32 GB
            assertEquals(ChapterCacheManager.MAX_CONFIGURABLE_CACHE_SIZE_BYTES, highClamped)
            assertEquals(32768, store.cacheLimitMbFlow.first())

            store.updateReaderPreferences(
                ReaderPreferences(
                    autoScrollSpeed = 99f,
                    brightnessLevel = 0.01f,
                    nightFilterStrength = 1.5f
                )
            )
            val prefs = store.readerPreferencesFlow.first()
            assertEquals(AutoScrollLogic.MAX_SPEED, prefs.autoScrollSpeed, 0.001f)
            assertEquals(0.20f, prefs.brightnessLevel, 0.001f)
            assertEquals(0.80f, prefs.nightFilterStrength, 0.001f)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `applyLegacyMigration preserves already explicitly set DataStore keys`() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = createStore(File(tempFolder.newFolder(), "migration_preserve.preferences_pb"), scope)
            store.updateCacheLimitMb(8192)
            store.updateReaderMode(ReaderMode.HORIZONTAL_PAGED)

            store.applyLegacyMigration(
                readerMode = ReaderMode.VERTICAL_WEBTOON,
                readerPreferences = ReaderPreferences(readingDirection = ReadingDirection.LTR, keepScreenOn = false),
                cacheLimitBytes = ChapterCacheManager.mbToBytes(2048),
                activeWebTabId = "legacy-tab"
            )

            val settings = store.getSettings()
            // Existing DataStore keys were NOT overwritten
            assertEquals(8192, settings.cacheLimitMb)
            assertEquals(ReaderMode.HORIZONTAL_PAGED, settings.readerMode)
            // Missing keys WERE populated from legacy migration
            assertEquals(ReadingDirection.LTR, settings.readerPreferences.readingDirection)
            assertFalse(settings.readerPreferences.keepScreenOn)
            assertEquals("legacy-tab", settings.activeWebTabId)
            assertTrue(settings.legacyMigrationCompleted)
        } finally {
            job.cancelAndJoin()
        }
    }
}
