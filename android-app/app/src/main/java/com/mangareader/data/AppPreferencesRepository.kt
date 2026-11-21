package com.mangareader.data

import android.content.Context
import com.mangareader.model.BookmarkItem
import com.mangareader.model.HistoryItem
import com.mangareader.model.ReaderSettings
import com.mangareader.model.ReadingMode
import org.json.JSONArray
import org.json.JSONObject

class AppPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): ReaderSettings {
        val modeName = prefs.getString(KEY_READING_MODE, ReadingMode.WEBTOON.name)
        val mode = runCatching { ReadingMode.valueOf(modeName ?: ReadingMode.WEBTOON.name) }
            .getOrDefault(ReadingMode.WEBTOON)
        return ReaderSettings(
            readingMode = mode,
            historyEnabled = prefs.getBoolean(KEY_HISTORY_ENABLED, true),
            dimmerEnabled = prefs.getBoolean(KEY_DIMMER_ENABLED, false),
            dimmerStrength = prefs.getFloat(KEY_DIMMER_STRENGTH, 0.25f).coerceIn(0f, 0.80f),
            sepiaEnabled = prefs.getBoolean(KEY_SEPIA_ENABLED, false),
            sepiaStrength = prefs.getFloat(KEY_SEPIA_STRENGTH, 0.25f).coerceIn(0.10f, 0.80f),
            autoscrollEnabled = prefs.getBoolean(KEY_AUTOSCROLL_ENABLED, false),
            autoscrollSpeed = prefs.getFloat(KEY_AUTOSCROLL_SPEED, 1.0f).coerceIn(0.5f, 3.0f),
            autoPrefetchEnabled = prefs.getBoolean(KEY_PREFETCH_ENABLED, true),
            cacheLimitMb = prefs.getInt(KEY_CACHE_LIMIT_MB, DEFAULT_CACHE_LIMIT_MB)
                .coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB),
        )
    }

    fun saveSettings(settings: ReaderSettings) {
        prefs.edit()
            .putString(KEY_READING_MODE, settings.readingMode.name)
            .putBoolean(KEY_HISTORY_ENABLED, settings.historyEnabled)
            .putBoolean(KEY_DIMMER_ENABLED, settings.dimmerEnabled)
            .putFloat(KEY_DIMMER_STRENGTH, settings.dimmerStrength.coerceIn(0f, 0.80f))
            .putBoolean(KEY_SEPIA_ENABLED, settings.sepiaEnabled)
            .putFloat(KEY_SEPIA_STRENGTH, settings.sepiaStrength.coerceIn(0.10f, 0.80f))
            .putBoolean(KEY_AUTOSCROLL_ENABLED, settings.autoscrollEnabled)
            .putFloat(KEY_AUTOSCROLL_SPEED, settings.autoscrollSpeed.coerceIn(0.5f, 3.0f))
            .putBoolean(KEY_PREFETCH_ENABLED, settings.autoPrefetchEnabled)
            .putInt(
                KEY_CACHE_LIMIT_MB,
                settings.cacheLimitMb.coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB)
            )
            .apply()
    }

    fun loadBookmarks(): List<BookmarkItem> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        BookmarkItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            chapterLabel = obj.optString("chapterLabel", "Kapitel"),
                            chapterUrl = obj.getString("chapterUrl"),
                            lastReadPage = obj.optInt("lastReadPage", 0),
                            totalPages = obj.optInt("totalPages", 1),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveBookmarks(items: List<BookmarkItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("chapterLabel", item.chapterLabel)
                    .put("chapterUrl", item.chapterUrl)
                    .put("lastReadPage", item.lastReadPage)
                    .put("totalPages", item.totalPages)
                    .put("updatedAtMs", item.updatedAtMs)
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    fun loadHistory(): List<HistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        HistoryItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            chapterLabel = obj.optString("chapterLabel", "Kapitel"),
                            chapterUrl = obj.getString("chapterUrl"),
                            lastReadPage = obj.optInt("lastReadPage", 0),
                            totalPages = obj.optInt("totalPages", 1),
                            readAtMs = obj.optLong("readAtMs", System.currentTimeMillis()),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(items: List<HistoryItem>) {
        val arr = JSONArray()
        items.take(100).forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("chapterLabel", item.chapterLabel)
                    .put("chapterUrl", item.chapterUrl)
                    .put("lastReadPage", item.lastReadPage)
                    .put("totalPages", item.totalPages)
                    .put("readAtMs", item.readAtMs)
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mangareader_app_state"
        private const val KEY_READING_MODE = "reading_mode"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_DIMMER_ENABLED = "dimmer_enabled"
        private const val KEY_DIMMER_STRENGTH = "dimmer_strength"
        private const val KEY_SEPIA_ENABLED = "sepia_enabled"
        private const val KEY_SEPIA_STRENGTH = "sepia_strength"
        private const val KEY_AUTOSCROLL_ENABLED = "autoscroll_enabled"
        private const val KEY_AUTOSCROLL_SPEED = "autoscroll_speed"
        private const val KEY_PREFETCH_ENABLED = "prefetch_enabled"
        private const val KEY_CACHE_LIMIT_MB = "cache_limit_mb"
        private const val KEY_BOOKMARKS = "bookmarks_json"
        private const val KEY_HISTORY = "history_json"

        const val MIN_CACHE_LIMIT_MB = 512
        const val DEFAULT_CACHE_LIMIT_MB = 4096 // 4 GB
        const val MAX_CACHE_LIMIT_MB = 32768    // 32 GB
    }
}
