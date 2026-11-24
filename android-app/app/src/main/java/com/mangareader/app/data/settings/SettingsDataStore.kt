package com.mangareader.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mangareader.app.data.cache.ChapterCacheManager
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import com.mangareader.app.ui.screens.reader.AutoScrollLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.mangaSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mangareader_settings"
)

data class AppSettings(
    val readerMode: ReaderMode = ReaderMode.VERTICAL_WEBTOON,
    val readerPreferences: ReaderPreferences = ReaderPreferences(),
    val cacheLimitBytes: Long = ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES,
    val activeWebTabId: String? = null,
    val legacyMigrationCompleted: Boolean = false
) {
    val cacheLimitMb: Int
        get() = ChapterCacheManager.bytesToMb(cacheLimitBytes)
}

/**
 * Typisierte, vollständig asynchrone Persistenz für alle Reader-Einstellungen,
 * aktiven Browser-Tab und das konfigurierbare Bild-Cache-Limit (512 MB – 32 GB, Standard 4 GB)
 * auf Basis von Jetpack DataStore (Preferences).
 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.mangaSettingsDataStore)

    private val safePreferencesFlow: Flow<Preferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val settingsFlow: Flow<AppSettings> = safePreferencesFlow
        .map { prefs -> mapToAppSettings(prefs) }
        .distinctUntilChanged()

    val readerPreferencesFlow: Flow<ReaderPreferences> = settingsFlow
        .map { it.readerPreferences }
        .distinctUntilChanged()

    val readerModeFlow: Flow<ReaderMode> = settingsFlow
        .map { it.readerMode }
        .distinctUntilChanged()

    val cacheLimitBytesFlow: Flow<Long> = settingsFlow
        .map { it.cacheLimitBytes }
        .distinctUntilChanged()

    val cacheLimitMbFlow: Flow<Int> = settingsFlow
        .map { it.cacheLimitMb }
        .distinctUntilChanged()

    val activeWebTabIdFlow: Flow<String?> = settingsFlow
        .map { it.activeWebTabId }
        .distinctUntilChanged()

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun getCacheLimitBytes(): Long = cacheLimitBytesFlow.first()

    suspend fun isLegacyMigrationCompleted(): Boolean =
        safePreferencesFlow.first()[KEY_LEGACY_MIGRATION_COMPLETED] ?: false

    suspend fun updateReaderPreferences(preferences: ReaderPreferences) {
        dataStore.edit { prefs ->
            writeReaderPreferences(prefs, preferences)
        }
    }

    suspend fun updateReaderMode(mode: ReaderMode) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_MODE] = mode.name
        }
    }

    suspend fun updateCacheLimitBytes(limitBytes: Long): Long {
        val clamped = ChapterCacheManager.clampCacheLimitBytes(limitBytes)
        dataStore.edit { prefs ->
            prefs[KEY_CACHE_LIMIT_BYTES] = clamped
        }
        return clamped
    }

    suspend fun updateCacheLimitMb(limitMb: Int): Long {
        return updateCacheLimitBytes(ChapterCacheManager.mbToBytes(limitMb))
    }

    suspend fun updateActiveWebTabId(tabId: String) {
        if (tabId.isBlank()) return
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_WEB_TAB_ID] = tabId
        }
    }

    /**
     * Übernimmt migrierte Alt-Einstellungen atomar in DataStore, ohne bereits
     * explizit gesetzte DataStore-Werte zu überschreiben.
     */
    suspend fun applyLegacyMigration(
        readerMode: ReaderMode? = null,
        readerPreferences: ReaderPreferences? = null,
        cacheLimitBytes: Long? = null,
        activeWebTabId: String? = null
    ) {
        dataStore.edit { prefs ->
            if (readerMode != null && prefs[KEY_READER_MODE] == null) {
                prefs[KEY_READER_MODE] = readerMode.name
            }
            if (readerPreferences != null && prefs[KEY_READING_DIRECTION] == null) {
                writeReaderPreferences(prefs, readerPreferences)
            }
            if (cacheLimitBytes != null && prefs[KEY_CACHE_LIMIT_BYTES] == null) {
                prefs[KEY_CACHE_LIMIT_BYTES] = ChapterCacheManager.clampCacheLimitBytes(cacheLimitBytes)
            }
            if (!activeWebTabId.isNullOrBlank() && prefs[KEY_ACTIVE_WEB_TAB_ID] == null) {
                prefs[KEY_ACTIVE_WEB_TAB_ID] = activeWebTabId
            }
            prefs[KEY_LEGACY_MIGRATION_COMPLETED] = true
        }
    }

    suspend fun markLegacyMigrationCompleted() {
        dataStore.edit { prefs ->
            prefs[KEY_LEGACY_MIGRATION_COMPLETED] = true
        }
    }

    private fun writeReaderPreferences(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        readerPreferences: ReaderPreferences
    ) {
        prefs[KEY_HISTORY_TRACKING_ENABLED] = readerPreferences.isHistoryTrackingEnabled
        prefs[KEY_AUTO_SCROLL_ENABLED] = readerPreferences.isAutoScrollEnabled
        prefs[KEY_AUTO_SCROLL_SPEED] = AutoScrollLogic.clampSpeed(readerPreferences.autoScrollSpeed)
        prefs[KEY_PREFETCH_ENABLED] = readerPreferences.isPrefetchEnabled
        prefs[KEY_CUSTOM_BRIGHTNESS_ENABLED] = readerPreferences.isCustomBrightnessEnabled
        prefs[KEY_BRIGHTNESS_LEVEL] = readerPreferences.brightnessLevel.coerceIn(0.20f, 1.0f)
        prefs[KEY_NIGHT_FILTER_ENABLED] = readerPreferences.isNightFilterEnabled
        prefs[KEY_NIGHT_FILTER_STRENGTH] = readerPreferences.nightFilterStrength.coerceIn(0.10f, 0.80f)
        prefs[KEY_READING_DIRECTION] = readerPreferences.readingDirection.name
        prefs[KEY_KEEP_SCREEN_ON] = readerPreferences.keepScreenOn
    }

    private fun mapToAppSettings(prefs: Preferences): AppSettings {
        val mode = prefs[KEY_READER_MODE]
            ?.let { runCatching { ReaderMode.valueOf(it) }.getOrNull() }
            ?: ReaderMode.VERTICAL_WEBTOON

        val direction = prefs[KEY_READING_DIRECTION]
            ?.let { runCatching { ReadingDirection.valueOf(it) }.getOrNull() }
            ?: ReadingDirection.RTL

        val defaults = ReaderPreferences()
        val readerPreferences = ReaderPreferences(
            isHistoryTrackingEnabled = prefs[KEY_HISTORY_TRACKING_ENABLED] ?: defaults.isHistoryTrackingEnabled,
            isAutoScrollEnabled = prefs[KEY_AUTO_SCROLL_ENABLED] ?: defaults.isAutoScrollEnabled,
            autoScrollSpeed = AutoScrollLogic.clampSpeed(prefs[KEY_AUTO_SCROLL_SPEED] ?: defaults.autoScrollSpeed),
            isPrefetchEnabled = prefs[KEY_PREFETCH_ENABLED] ?: defaults.isPrefetchEnabled,
            isCustomBrightnessEnabled = prefs[KEY_CUSTOM_BRIGHTNESS_ENABLED] ?: defaults.isCustomBrightnessEnabled,
            brightnessLevel = (prefs[KEY_BRIGHTNESS_LEVEL] ?: defaults.brightnessLevel).coerceIn(0.20f, 1.0f),
            isNightFilterEnabled = prefs[KEY_NIGHT_FILTER_ENABLED] ?: defaults.isNightFilterEnabled,
            nightFilterStrength = (prefs[KEY_NIGHT_FILTER_STRENGTH] ?: defaults.nightFilterStrength).coerceIn(0.10f, 0.80f),
            readingDirection = direction,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn
        )

        val cacheLimitBytes = ChapterCacheManager.clampCacheLimitBytes(
            prefs[KEY_CACHE_LIMIT_BYTES] ?: ChapterCacheManager.DEFAULT_MAX_CACHE_SIZE_BYTES
        )

        val activeTabId = prefs[KEY_ACTIVE_WEB_TAB_ID]?.takeIf { it.isNotBlank() }
        val migrated = prefs[KEY_LEGACY_MIGRATION_COMPLETED] ?: false

        return AppSettings(
            readerMode = mode,
            readerPreferences = readerPreferences,
            cacheLimitBytes = cacheLimitBytes,
            activeWebTabId = activeTabId,
            legacyMigrationCompleted = migrated
        )
    }

    companion object {
        private val KEY_READER_MODE = stringPreferencesKey("reader_mode")
        private val KEY_READING_DIRECTION = stringPreferencesKey("reading_direction")
        private val KEY_HISTORY_TRACKING_ENABLED = booleanPreferencesKey("history_tracking_enabled")
        private val KEY_AUTO_SCROLL_ENABLED = booleanPreferencesKey("auto_scroll_enabled")
        private val KEY_AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
        private val KEY_PREFETCH_ENABLED = booleanPreferencesKey("prefetch_enabled")
        private val KEY_CUSTOM_BRIGHTNESS_ENABLED = booleanPreferencesKey("custom_brightness_enabled")
        private val KEY_BRIGHTNESS_LEVEL = floatPreferencesKey("brightness_level")
        private val KEY_NIGHT_FILTER_ENABLED = booleanPreferencesKey("night_filter_enabled")
        private val KEY_NIGHT_FILTER_STRENGTH = floatPreferencesKey("night_filter_strength")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val KEY_CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        private val KEY_ACTIVE_WEB_TAB_ID = stringPreferencesKey("active_web_tab_id")
        private val KEY_LEGACY_MIGRATION_COMPLETED = booleanPreferencesKey("legacy_migration_completed")
    }
}
