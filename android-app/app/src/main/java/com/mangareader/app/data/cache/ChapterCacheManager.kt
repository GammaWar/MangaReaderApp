package com.mangareader.app.data.cache

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Ergebnisbericht einer Cache-Prüfung oder manuellen Bereinigung.
 */
data class CachePruneReport(
    val removedExpiredCount: Int,
    val removedSizeEvictedCount: Int,
    val freedBytes: Long,
    val remainingBytes: Long,
    val remainingFileCount: Int,
    val timestampMs: Long
)

/**
 * Aktueller Auslastungsstatus des verwalteten Bild-Caches.
 */
data class CacheStats(
    val totalBytes: Long = 0L,
    val fileCount: Int = 0,
    val maxBytes: Long = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES,
    val usagePercent: Float = 0f,
    val lastPruneTimestampMs: Long? = null
)

/**
 * Konfigurierbarer Cache-Manager für Kapitel- & Bild-Caches.
 *
 * Regeln:
 * 1. Keine automatische 31-Tage-Löschung — zwischengespeicherte Seiten bleiben erhalten,
 *    bis sie manuell über `clearAllManually()` gelöscht werden oder das Limit überschritten wird.
 * 2. Prüft die Gesamtgröße über alle verwalteten Cache-Ordner; überschreitet sie das
 *    konfigurierbare Limit (Standard: 4 GB, Bereich: 512 MB – 32 GB), werden die ältesten
 *    Einträge bis zur 90-%-Hysterese entfernt.
 * 3. Thread-sicher über Kotlin Coroutines `Mutex` und vollständig auf `Dispatchers.IO`.
 */
class ChapterCacheManager(
    private val cacheRootDir: File,
    private val managedDirectories: List<File>,
    initialMaxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
) {

    constructor(
        context: Context,
        managedDirectories: List<File>,
        initialMaxSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
    ) : this(context.cacheDir, managedDirectories, initialMaxSizeBytes)

    private val pruneMutex = Mutex()

    @Volatile
    private var maxSizeBytes: Long = clampCacheLimitBytes(initialMaxSizeBytes)

    @Volatile
    private var lastPruneTimestampMs: Long = 0L

    fun getMaxSizeBytes(): Long = maxSizeBytes

    fun updateMaxSizeBytes(newMaxBytes: Long) {
        maxSizeBytes = clampCacheLimitBytes(newMaxBytes)
    }

    /**
     * Startet eine leichtgewichtige periodische Hintergrund-Wartung (alle 6 Stunden),
     * die ausschließlich das konfigurierbare Speicherlimit (Standard: 4 GB) überwacht.
     */
    fun startPeriodicMaintenance(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TimeUnit.HOURS.toMillis(6))
                pruneIfNeeded(forceCheck = true)
            }
        }
    }

    /**
     * Leichter Trigger nach neuen Bild-Downloads (gedrosselt auf max. alle 5 Minuten),
     * sodass ein Überschreiten des konfigurierten Speicherlimits unmittelbar abgefangen wird.
     */
    fun scheduleThresholdCheck(scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        if (now - lastPruneTimestampMs < TimeUnit.MINUTES.toMillis(5)) return
        scope.launch(Dispatchers.IO) {
            pruneIfNeeded(forceCheck = false)
        }
    }

    suspend fun getCurrentCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        collectManagedFiles().sumOf { it.length() }
    }

    suspend fun getStats(): CacheStats = withContext(Dispatchers.IO) {
        val files = collectManagedFiles()
        val total = files.sumOf { it.length() }
        val limit = maxSizeBytes
        val pct = if (limit > 0L) {
            ((total.toDouble() / limit.toDouble()) * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else {
            0f
        }
        CacheStats(
            totalBytes = total,
            fileCount = files.size,
            maxBytes = limit,
            usagePercent = pct,
            lastPruneTimestampMs = lastPruneTimestampMs.takeIf { it > 0L }
        )
    }

    /**
     * Löscht alle zwischengespeicherten Kapitel- und Bilddateien explizit auf Nutzerwunsch.
     */
    suspend fun clearAllManually(): CachePruneReport = withContext(Dispatchers.IO) {
        pruneMutex.withLock {
            val now = System.currentTimeMillis()
            val allFiles = collectManagedFiles()
            var removedCount = 0
            var freedBytes = 0L
            var remainingBytes = 0L
            var remainingCount = 0

            for (file in allFiles) {
                val size = file.length()
                if (safeDeleteFile(file)) {
                    removedCount++
                    freedBytes += size
                } else {
                    remainingBytes += size
                    remainingCount++
                }
            }

            managedDirectories.forEach { pruneEmptySubdirectories(it) }
            lastPruneTimestampMs = now

            CachePruneReport(
                removedExpiredCount = 0,
                removedSizeEvictedCount = removedCount,
                freedBytes = freedBytes,
                remainingBytes = remainingBytes,
                remainingFileCount = remainingCount,
                timestampMs = now
            )
        }
    }

    suspend fun pruneIfNeeded(forceCheck: Boolean = false): CachePruneReport =
        pruneWithLimitBytes(limitBytes = maxSizeBytes, forceCheck = forceCheck)

    internal suspend fun pruneWithLimitBytes(
        limitBytes: Long,
        forceCheck: Boolean = true
    ): CachePruneReport =
        withContext(Dispatchers.IO) {
            pruneMutex.withLock {
                val now = System.currentTimeMillis()
                if (!forceCheck && now - lastPruneTimestampMs < TimeUnit.MINUTES.toMillis(5)) {
                    val existing = collectManagedFiles()
                    return@withLock CachePruneReport(
                        removedExpiredCount = 0,
                        removedSizeEvictedCount = 0,
                        freedBytes = 0L,
                        remainingBytes = existing.sumOf { it.length() },
                        remainingFileCount = existing.size,
                        timestampMs = now
                    )
                }

                var removedSizeEvictedCount = 0
                var freedBytes = 0L

                val survivingFiles = collectManagedFiles().toMutableList()
                val safeLimitBytes = limitBytes.coerceAtLeast(0L)
                val targetAfterEvictionBytes = (safeLimitBytes * EVICTION_TARGET_RATIO).toLong()

                // Speicher-Schwellenwert prüfen und älteste Einträge bis zur 90-%-Hysterese bereinigen
                var currentTotalBytes = survivingFiles.sumOf { it.length() }
                if (currentTotalBytes > safeLimitBytes) {
                    survivingFiles.sortBy { it.lastModified() }
                    val iterator = survivingFiles.iterator()
                    while (iterator.hasNext() && currentTotalBytes > targetAfterEvictionBytes) {
                        val oldest = iterator.next()
                        val size = oldest.length()
                        if (safeDeleteFile(oldest)) {
                            currentTotalBytes = (currentTotalBytes - size).coerceAtLeast(0L)
                            freedBytes += size
                            removedSizeEvictedCount++
                            iterator.remove()
                        }
                    }
                }

                managedDirectories.forEach { pruneEmptySubdirectories(it) }
                lastPruneTimestampMs = now

                CachePruneReport(
                    removedExpiredCount = 0,
                    removedSizeEvictedCount = removedSizeEvictedCount,
                    freedBytes = freedBytes,
                    remainingBytes = currentTotalBytes,
                    remainingFileCount = survivingFiles.size,
                    timestampMs = now
                )
            }
        }

    private fun collectManagedFiles(): List<File> {
        val cacheRootCanonical = runCatching { cacheRootDir.canonicalFile }.getOrNull() ?: return emptyList()
        val rootPathWithSep = cacheRootCanonical.path.let {
            if (it.endsWith(File.separator)) it else "$it${File.separator}"
        }
        val result = mutableListOf<File>()

        for (dir in managedDirectories) {
            if (!dir.exists() || !dir.isDirectory) continue
            val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: continue
            // Pfad-Sicherheit: Nur Dateien strikt innerhalb von cacheRootDir anfassen
            if (canonicalDir.path != cacheRootCanonical.path && !canonicalDir.path.startsWith(rootPathWithSep)) {
                continue
            }

            canonicalDir.walkTopDown()
                .filter { it.isFile && it.name != "journal" && !it.name.endsWith(".tmp") }
                .forEach { result.add(it) }
        }
        return result
    }

    private fun safeDeleteFile(file: File): Boolean =
        runCatching { file.exists() && file.delete() }.getOrDefault(false)

    private fun pruneEmptySubdirectories(root: File) {
        if (!root.exists() || !root.isDirectory) return
        root.walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { dir ->
                if (dir.listFiles()?.isEmpty() == true) {
                    runCatching { dir.delete() }
                }
            }
    }

    companion object {
        const val MIN_CACHE_LIMIT_MB = 512 // 512 MB
        const val DEFAULT_CACHE_LIMIT_MB = 4096 // 4 GB
        const val MAX_CONFIGURABLE_CACHE_LIMIT_MB = 32768 // 32 GB

        const val MIN_CACHE_SIZE_BYTES: Long = MIN_CACHE_LIMIT_MB.toLong() * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_SIZE_BYTES: Long = DEFAULT_CACHE_LIMIT_MB.toLong() * 1024L * 1024L
        const val MAX_CONFIGURABLE_CACHE_SIZE_BYTES: Long = MAX_CONFIGURABLE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L

        const val EVICTION_TARGET_RATIO: Double = 0.90
        val CACHE_LIMIT_PRESETS_MB = listOf(1024, 2048, 4096, 8192, 16384, 32768)

        fun clampCacheLimitBytes(bytes: Long): Long =
            bytes.coerceIn(MIN_CACHE_SIZE_BYTES, MAX_CONFIGURABLE_CACHE_SIZE_BYTES)

        fun clampCacheLimitMb(mb: Int): Int =
            mb.coerceIn(MIN_CACHE_LIMIT_MB, MAX_CONFIGURABLE_CACHE_LIMIT_MB)

        fun mbToBytes(mb: Int): Long =
            clampCacheLimitMb(mb).toLong() * 1024L * 1024L

        fun bytesToMb(bytes: Long): Int =
            clampCacheLimitMb((bytes / (1024L * 1024L)).toInt())

        fun formatCacheLimitLabel(mb: Int): String {
            val safeMb = clampCacheLimitMb(mb)
            if (safeMb < 1024) return "$safeMb MB"
            val gb = safeMb.toDouble() / 1024.0
            return if (safeMb % 1024 == 0) {
                "${safeMb / 1024} GB"
            } else {
                String.format(Locale.US, "%.1f", gb).replace('.', ',') + " GB"
            }
        }

        fun formatBytesDe(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes.toDouble() / 1024.0
            if (kb < 1024.0) {
                return String.format(Locale.US, "%.1f", kb).replace('.', ',') + " KB"
            }
            val mb = kb / 1024.0
            if (mb < 1024.0) {
                return String.format(Locale.US, "%.1f", mb).replace('.', ',') + " MB"
            }
            val gb = mb / 1024.0
            val roundedTenth = (gb * 10.0).roundToInt() / 10.0
            return String.format(Locale.US, "%.1f", roundedTenth).replace('.', ',') + " GB"
        }
    }
}
