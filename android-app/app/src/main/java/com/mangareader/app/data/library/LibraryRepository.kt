package com.mangareader.app.data.library

import android.content.Context
import com.mangareader.app.data.model.BookmarkItem
import com.mangareader.app.data.model.ReaderPreferences
import com.mangareader.app.data.model.ReadingDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryRepository(private val context: Context) {

    private val libraryFile = File(context.filesDir, "library_bookmarks.json")
    private val preferencesFile = File(context.filesDir, "reader_preferences.json")
    private val writeMutex = Mutex()

    // --- Bookmarks & History ---

    fun loadItemsSync(): List<BookmarkItem> {
        try {
            if (!libraryFile.exists()) return emptyList()
            val text = libraryFile.readText()
            if (text.isBlank()) return emptyList()

            val array = JSONArray(text)
            val items = mutableListOf<BookmarkItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                items.add(
                    BookmarkItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        chapterTitle = obj.optString("chapterTitle", ""),
                        chapterUrl = obj.optString("chapterUrl", ""),
                        coverImageUrl = obj.optString("coverImageUrl").takeIf { it.isNotBlank() && it != "null" },
                        lastReadPage = obj.optInt("lastReadPage", 0),
                        totalPages = obj.optInt("totalPages", 0),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isBookmark = obj.optBoolean("isBookmark", true)
                    )
                )
            }
            return items
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    suspend fun saveItems(items: List<BookmarkItem>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val array = JSONArray()
                items.forEach { item ->
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("chapterTitle", item.chapterTitle)
                        put("chapterUrl", item.chapterUrl)
                        item.coverImageUrl?.let { put("coverImageUrl", it) }
                        put("lastReadPage", item.lastReadPage)
                        put("totalPages", item.totalPages)
                        put("timestamp", item.timestamp)
                        put("isBookmark", item.isBookmark)
                    }
                    array.put(obj)
                }

                // Atomic temp-file write to prevent data corruption
                val tempFile = File(context.filesDir, "library_bookmarks.tmp")
                tempFile.writeText(array.toString())
                if (libraryFile.exists()) {
                    libraryFile.delete()
                }
                if (!tempFile.renameTo(libraryFile)) {
                    libraryFile.writeText(array.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Reader Preferences (History toggle, Pre-fetch, Autoscroll, Brightness, Night Mode, Direction, KeepScreenOn) ---

    fun loadPreferencesSync(): ReaderPreferences {
        try {
            if (!preferencesFile.exists()) return ReaderPreferences()
            val text = preferencesFile.readText()
            if (text.isBlank()) return ReaderPreferences()

            val obj = JSONObject(text)
            val directionName = obj.optString("readingDirection", ReadingDirection.RTL.name)
            val direction = runCatching { ReadingDirection.valueOf(directionName) }
                .getOrDefault(ReadingDirection.RTL)

            return ReaderPreferences(
                isHistoryTrackingEnabled = obj.optBoolean("isHistoryTrackingEnabled", true),
                isAutoScrollEnabled = obj.optBoolean("isAutoScrollEnabled", true),
                autoScrollSpeed = obj.optDouble("autoScrollSpeed", 1.0).toFloat(),
                isPrefetchEnabled = obj.optBoolean("isPrefetchEnabled", true),
                isCustomBrightnessEnabled = obj.optBoolean("isCustomBrightnessEnabled", false),
                brightnessLevel = obj.optDouble("brightnessLevel", 1.0).toFloat(),
                isNightFilterEnabled = obj.optBoolean("isNightFilterEnabled", false),
                nightFilterStrength = obj.optDouble("nightFilterStrength", 0.35).toFloat(),
                readingDirection = direction,
                keepScreenOn = obj.optBoolean("keepScreenOn", true)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return ReaderPreferences()
        }
    }

    suspend fun savePreferences(prefs: ReaderPreferences) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val obj = JSONObject().apply {
                    put("isHistoryTrackingEnabled", prefs.isHistoryTrackingEnabled)
                    put("isAutoScrollEnabled", prefs.isAutoScrollEnabled)
                    put("autoScrollSpeed", prefs.autoScrollSpeed.toDouble())
                    put("isPrefetchEnabled", prefs.isPrefetchEnabled)
                    put("isCustomBrightnessEnabled", prefs.isCustomBrightnessEnabled)
                    put("brightnessLevel", prefs.brightnessLevel.toDouble())
                    put("isNightFilterEnabled", prefs.isNightFilterEnabled)
                    put("nightFilterStrength", prefs.nightFilterStrength.toDouble())
                    put("readingDirection", prefs.readingDirection.name)
                    put("keepScreenOn", prefs.keepScreenOn)
                }

                val tempFile = File(context.filesDir, "reader_preferences.tmp")
                tempFile.writeText(obj.toString())
                if (preferencesFile.exists()) {
                    preferencesFile.delete()
                }
                if (!tempFile.renameTo(preferencesFile)) {
                    preferencesFile.writeText(obj.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
