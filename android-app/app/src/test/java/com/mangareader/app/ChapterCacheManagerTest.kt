package com.mangareader.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.settings.SettingsDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChapterCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `default constants and helpers enforce 4 GB default and 32 GB maximum`() {
        val gb = 1024L * 1024L * 1024L
        assertEquals(4096, ChapterCacheManager.DEFAULT_CACHE_LIMIT_MB)
        assertEquals(4L * gb, ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES)
        assertEquals(32768, ChapterCacheManager.MAX_CONFIGURABLE_CACHE_LIMIT_MB)

        assertEquals(512, ChapterCacheManager.clampCacheLimitMb(128))
        assertEquals(4096, ChapterCacheManager.clampCacheLimitMb(4096))
        assertEquals(32768, ChapterCacheManager.clampCacheLimitMb(99999))
        assertEquals(2L * gb, ChapterCacheManager.mbToBytes(2048))
        assertEquals(32L * gb, ChapterCacheManager.mbToBytes(32768))

        assertEquals("512 MB", ChapterCacheManager.formatCacheLimitLabel(512))
        assertEquals("4 GB", ChapterCacheManager.formatCacheLimitLabel(4096))
        assertEquals("32 GB", ChapterCacheManager.formatCacheLimitLabel(32768))
        assertEquals("1,5 GB", ChapterCacheManager.formatCacheLimitLabel(1536))

        assertEquals("0 B", ChapterCacheManager.formatBytesDe(0L))
        assertEquals("512 B", ChapterCacheManager.formatBytesDe(512L))
        assertEquals("2,0 KB", ChapterCacheManager.formatBytesDe(2048L))
        assertEquals("512,0 MB", ChapterCacheManager.formatBytesDe(512L * 1024L * 1024L))
        assertEquals("4,0 GB", ChapterCacheManager.formatBytesDe(4L * gb))
    }

    @Test
    fun `retains files older than 31 days when within size limit`() = runBlocking {
        val cacheRoot = tempFolder.newFolder("cacheRoot")
        val imgDir = File(cacheRoot, "image_cache").apply { mkdirs() }
        val oldFile = File(imgDir, "very_old_page.bin").apply {
            writeBytes(ByteArray(1024))
            setLastModified(System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L)
        }

        val manager = ChapterCacheManager(
            cacheRootDir = cacheRoot,
            managedDirectories = listOf(imgDir),
            initialMaxSizeBytes = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES
        )

        val report = manager.pruneIfNeeded(forceCheck = true)
        assertEquals(0, report.removedExpiredCount)
        assertEquals(0, report.removedSizeEvictedCount)
        assertTrue(oldFile.exists())
        assertEquals(1024L, report.remainingBytes)
    }

    @Test
    fun `evicts oldest files down to 90 percent hysteresis when max limit exceeded`() = runBlocking {
        val cacheRoot = tempFolder.newFolder("cacheEvict")
        val imgDir = File(cacheRoot, "image_cache").apply { mkdirs() }

        val now = System.currentTimeMillis()
        val f1 = File(imgDir, "page1.bin").apply {
            writeBytes(ByteArray(400))
            setLastModified(now - 30_000L)
        }
        val f2 = File(imgDir, "page2.bin").apply {
            writeBytes(ByteArray(400))
            setLastModified(now - 20_000L)
        }
        val f3 = File(imgDir, "page3.bin").apply {
            writeBytes(ByteArray(400))
            setLastModified(now - 10_000L)
        }
        val journal = File(imgDir, "journal").apply { writeText("keep-journal") }

        val manager = ChapterCacheManager(
            cacheRootDir = cacheRoot,
            managedDirectories = listOf(imgDir),
            initialMaxSizeBytes = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES
        )

        val stats = manager.getStats()
        assertEquals(1200L, stats.totalBytes)
        assertEquals(3, stats.fileCount) // journal excluded

        // Total 1200 > limit 1000 -> target 900 -> oldest file (f1, 400 bytes) removed -> 800 remaining
        val evicted = manager.pruneWithLimitBytes(limitBytes = 1000L, forceCheck = true)
        assertEquals(1, evicted.removedSizeEvictedCount)
        assertEquals(400L, evicted.freedBytes)
        assertEquals(800L, evicted.remainingBytes)
        assertFalse(f1.exists())
        assertTrue(f2.exists())
        assertTrue(f3.exists())

        val cleared = manager.clearAllManually()
        assertEquals(2, cleared.removedSizeEvictedCount)
        assertEquals(800L, cleared.freedBytes)
        assertEquals(0L, cleared.remainingBytes)
        assertFalse(f2.exists())
        assertFalse(f3.exists())
        assertTrue(journal.exists())
    }

    @Test
    fun `binds reactively to SettingsDataStore cache limit flow and updates statsFlow`() = runBlocking {
        val cacheRoot = tempFolder.newFolder("cacheBind")
        val imgDir = File(cacheRoot, "image_cache").apply { mkdirs() }
        File(imgDir, "page1.bin").writeBytes(ByteArray(1024))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(tempFolder.newFolder(), "cache_bind.preferences_pb") }
            )
            val settingsStore = SettingsDataStore(ds)
            val manager = ChapterCacheManager(
                cacheRootDir = cacheRoot,
                managedDirectories = listOf(imgDir)
            )

            val updated8Gb = CompletableDeferred<Long>()
            val bindJob = manager.bindToLimitFlow(
                scope = scope,
                limitBytesFlow = settingsStore.cacheLimitBytesFlow,
                pruneOnChange = true,
                onPruned = {
                    val currentLimit = manager.getMaxSizeBytes()
                    if (currentLimit == ChapterCacheManager.mbToBytes(8192) && !updated8Gb.isCompleted) {
                        updated8Gb.complete(currentLimit)
                    }
                }
            )

            settingsStore.updateCacheLimitMb(8192)
            val applied = withTimeout(5_000L) { updated8Gb.await() }
            assertEquals(8L * 1024L * 1024L * 1024L, applied)
            assertEquals(applied, manager.maxSizeBytesFlow.first())
            assertEquals(applied, manager.statsFlow.value.maxBytes)
            assertEquals(1024L, manager.statsFlow.value.totalBytes)

            bindJob.cancel()
        } finally {
            scope.cancel()
        }
    }
}
