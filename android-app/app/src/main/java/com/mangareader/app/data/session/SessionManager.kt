package com.mangareader.app.data.session

import android.content.Context
import com.mangareader.app.data.model.Chapter
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionData(
    val tabs: List<WebTab>,
    val activeTabId: String,
    val readerMode: ReaderMode
)

class SessionManager(private val context: Context) {

    private val sessionFile = File(context.filesDir, "tabs_session.json")
    private val writeMutex = Mutex()

    suspend fun saveSession(
        tabs: List<WebTab>,
        activeTabId: String,
        readerMode: ReaderMode
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
            val root = JSONObject()
            root.put("activeTabId", activeTabId)
            root.put("readerMode", readerMode.name)

            val tabsArray = JSONArray()
            tabs.forEach { tab ->
                val tabJson = JSONObject().apply {
                    put("id", tab.id)
                    put("title", tab.title)
                    put("url", tab.url)
                    put("lastReadPage", tab.lastReadPage)

                    tab.chapter?.let { ch ->
                        val chJson = JSONObject().apply {
                            put("title", ch.title)
                            put("chapterUrl", ch.chapterUrl)
                            ch.nextChapterUrl?.let { put("nextChapterUrl", it) }
                            ch.prevChapterUrl?.let { put("prevChapterUrl", it) }
                            put("isOffline", ch.isOffline)
                            val imgArr = JSONArray()
                            ch.images.forEach { imgArr.put(it) }
                            put("images", imgArr)
                        }
                        put("chapter", chJson)
                    }
                }
                tabsArray.put(tabJson)
            }
            root.put("tabs", tabsArray)

            // Safe atomic write
            val tempFile = File(context.filesDir, "tabs_session.tmp")
            tempFile.writeText(root.toString())
            if (sessionFile.exists()) {
                sessionFile.delete()
            }
            if (!tempFile.renameTo(sessionFile)) {
                // Fallback direct write if rename fails
                sessionFile.writeText(root.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

    fun loadSessionSync(): SessionData? {
        try {
            if (!sessionFile.exists()) return null
            val content = sessionFile.readText()
            if (content.isBlank()) return null

            val root = JSONObject(content)
            val activeTabId = root.optString("activeTabId", "")
            val readerModeStr = root.optString("readerMode", ReaderMode.VERTICAL_WEBTOON.name)
            val readerMode = try {
                ReaderMode.valueOf(readerModeStr)
            } catch (e: Exception) {
                ReaderMode.VERTICAL_WEBTOON
            }

            val tabsArray = root.optJSONArray("tabs") ?: return null
            val tabsList = mutableListOf<WebTab>()

            for (i in 0 until tabsArray.length()) {
                val tabObj = tabsArray.getJSONObject(i)
                val id = tabObj.optString("id", java.util.UUID.randomUUID().toString())
                val title = tabObj.optString("title", "Manga")
                val url = tabObj.optString("url", "https://mangadex.org")
                val lastReadPage = tabObj.optInt("lastReadPage", 0)

                var chapter: Chapter? = null
                if (tabObj.has("chapter") && !tabObj.isNull("chapter")) {
                    val chObj = tabObj.getJSONObject("chapter")
                    val chTitle = chObj.optString("title", "")
                    val chUrl = chObj.optString("chapterUrl", "")
                    val nextUrl = chObj.optString("nextChapterUrl").takeIf { it.isNotBlank() && it != "null" }
                    val prevUrl = chObj.optString("prevChapterUrl").takeIf { it.isNotBlank() && it != "null" }
                    val isOffline = chObj.optBoolean("isOffline", false)

                    val imgArr = chObj.optJSONArray("images") ?: JSONArray()
                    val images = mutableListOf<String>()
                    for (j in 0 until imgArr.length()) {
                        images.add(imgArr.getString(j))
                    }

                    if (images.isNotEmpty()) {
                        chapter = Chapter(
                            title = chTitle,
                            chapterUrl = chUrl,
                            images = images,
                            nextChapterUrl = nextUrl,
                            prevChapterUrl = prevUrl,
                            isOffline = isOffline
                        )
                    }
                }

                tabsList.add(
                    WebTab(
                        id = id,
                        title = title,
                        url = url,
                        chapter = chapter,
                        lastReadPage = lastReadPage
                    )
                )
            }

            return if (tabsList.isNotEmpty()) {
                val resolvedActiveId = if (tabsList.any { it.id == activeTabId }) activeTabId else tabsList.first().id
                SessionData(tabsList, resolvedActiveId, readerMode)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    suspend fun loadSession(): SessionData? = withContext(Dispatchers.IO) {
        loadSessionSync()
    }
}
