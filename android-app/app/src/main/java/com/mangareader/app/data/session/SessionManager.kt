package com.mangareader.app.data.session

import android.content.Context
import com.mangareader.app.data.local.MangaDatabase
import com.mangareader.app.data.local.WebTabDao
import com.mangareader.app.data.local.WebTabEntity
import com.mangareader.app.data.model.ReaderMode
import com.mangareader.app.data.model.WebTab
import com.mangareader.app.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class SessionData(
    val tabs: List<WebTab>,
    val activeTabId: String,
    val readerMode: ReaderMode
)

/**
 * Reaktiver Session-Manager auf Basis von Room (`WebTabDao`) und Jetpack DataStore
 * (`SettingsDataStore`) — ersetzt synchrone `tabs_session.json`-Zugriffe vollständig.
 */
class SessionManager(
    private val webTabDao: WebTabDao,
    private val settingsDataStore: SettingsDataStore
) {
    constructor(
        context: Context,
        database: MangaDatabase = MangaDatabase.getInstance(context),
        settingsDataStore: SettingsDataStore = SettingsDataStore(context)
    ) : this(
        webTabDao = database.webTabDao(),
        settingsDataStore = settingsDataStore
    )

    val tabsFlow: Flow<List<WebTab>> = webTabDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    val readerModeFlow: Flow<ReaderMode> = settingsDataStore.readerModeFlow

    val activeTabIdFlow: Flow<String?> = settingsDataStore.activeWebTabIdFlow

    suspend fun saveSession(
        tabs: List<WebTab>,
        activeTabId: String,
        readerMode: ReaderMode
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = tabs.mapIndexed { index, tab ->
            WebTabEntity.fromDomain(tab = tab, sortOrder = index, updatedAt = now)
        }
        webTabDao.replaceAll(entities)
        if (activeTabId.isNotBlank()) {
            settingsDataStore.updateActiveWebTabId(activeTabId)
        }
        settingsDataStore.updateReaderMode(readerMode)
    }

    suspend fun updateTabLastReadPage(tabId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        webTabDao.updateLastReadPage(
            tabId = tabId,
            lastReadPage = pageIndex.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun loadSession(): SessionData? = withContext(Dispatchers.IO) {
        val tabs = webTabDao.getAll().map { it.toDomain() }
        if (tabs.isEmpty()) return@withContext null

        val settings = settingsDataStore.getSettings()
        val savedActiveId = settings.activeWebTabId
        val resolvedActiveId = if (!savedActiveId.isNullOrBlank() && tabs.any { it.id == savedActiveId }) {
            savedActiveId
        } else {
            tabs.first().id
        }

        SessionData(
            tabs = tabs,
            activeTabId = resolvedActiveId,
            readerMode = settings.readerMode
        )
    }
}
