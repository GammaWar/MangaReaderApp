import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  BrowserTab,
  ChatMessage,
  LibraryEntry,
  MainTab,
  MangaChapter,
  OfflineDownloadRecord,
  PrefetchedChapterState,
  ReaderSettings,
  ToastMessage,
} from './types/manga';
import {
  CATALOG_CHAPTERS,
  DEFAULT_READER_SETTINGS,
  findCatalogChapterByUrl,
  INITIAL_BOOKMARKS,
  INITIAL_BROWSER_TABS,
  INITIAL_HISTORY,
} from './data/catalog';
import { buildDownloadFolderId } from './utils/urlChapterAnalyzer';
import { serializedStorage, STORAGE_KEYS } from './utils/storage';
import {
  CacheManager,
  clampCacheLimitMb,
  formatBytesDe,
  mbToBytes,
} from './utils/cacheManager';
import { AppShell } from './components/AppShell';
import { LibraryView } from './components/LibraryView';
import { BrowserView } from './components/BrowserView';
import { ReaderView } from './components/ReaderView';
import { ChatView } from './components/ChatView';
import { SettingsDrawer } from './components/SettingsDrawer';
import { ToastStack } from './components/ToastStack';

export default function App() {
  const [activeTab, setActiveTab] = useState<MainTab>('library');
  const [settingsOpen, setSettingsOpen] = useState(false);

  // Persisted state via debounced + serialized storage
  const [settings, setSettings] = useState<ReaderSettings>(() => {
    const loaded = serializedStorage.load<Partial<ReaderSettings>>(STORAGE_KEYS.settings, {});
    return {
      ...DEFAULT_READER_SETTINGS,
      ...loaded,
      cacheLimitMb: clampCacheLimitMb(loaded.cacheLimitMb ?? DEFAULT_READER_SETTINGS.cacheLimitMb),
    };
  });
  const [cacheRefreshTick, setCacheRefreshTick] = useState(0);

  const [bookmarks, setBookmarks] = useState<LibraryEntry[]>(() =>
    serializedStorage.load<LibraryEntry[]>(STORAGE_KEYS.bookmarks, INITIAL_BOOKMARKS)
  );

  const [history, setHistory] = useState<LibraryEntry[]>(() =>
    serializedStorage.load<LibraryEntry[]>(STORAGE_KEYS.history, INITIAL_HISTORY)
  );

  const [downloads, setDownloads] = useState<OfflineDownloadRecord[]>(() =>
    serializedStorage.load<OfflineDownloadRecord[]>(STORAGE_KEYS.downloads, [])
  );

  const [browserTabs, setBrowserTabs] = useState<BrowserTab[]>(() => {
    const loaded = serializedStorage.load<BrowserTab[]>(STORAGE_KEYS.tabs, INITIAL_BROWSER_TABS);
    return loaded.length > 0 ? loaded : INITIAL_BROWSER_TABS;
  });

  const [activeBrowserTabId, setActiveBrowserTabId] = useState<string>(
    () => browserTabs[0]?.id || INITIAL_BROWSER_TABS[0].id
  );

  const [chatMessages, setChatMessages] = useState<ChatMessage[]>(() =>
    serializedStorage.load<ChatMessage[]>(STORAGE_KEYS.chatHistory, [])
  );

  // Reader active state
  const [currentChapter, setCurrentChapter] = useState<MangaChapter | null>(CATALOG_CHAPTERS[0]);
  const [readerTargetPage, setReaderTargetPage] = useState<number>(2);
  const [currentPageIndex, setCurrentPageIndex] = useState<number>(2);
  const [isAutoscrolling, setIsAutoscrolling] = useState(false);
  const [isExtracting, setIsExtracting] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const [prefetchState, setPrefetchState] = useState<PrefetchedChapterState>({
    forChapterUrl: '',
    status: 'idle',
    chapter: null,
  });

  const pendingTargetPageRef = useRef<number | null>(null);
  const prefetchRequestedUrlRef = useRef<string | null>(null);

  const notify = useCallback(
    (message: string, tone: ToastMessage['tone'] = 'info') => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
      setToasts((prev) => [...prev.slice(-2), { id, message, tone }]);
      setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 4200);
    },
    []
  );

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const bumpCacheRefresh = useCallback(() => {
    setCacheRefreshTick((n) => n + 1);
  }, []);

  const cacheValidationSeqRef = useRef(0);
  const initialCacheLimitMbRef = useRef(settings.cacheLimitMb);

  // Persist settings, tabs, bookmarks, history, downloads, and chat history
  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.settings, settings, 250);
  }, [settings]);

  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.bookmarks, bookmarks, 400);
  }, [bookmarks]);

  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.history, history, 400);
  }, [history]);

  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.downloads, downloads, 400);
  }, [downloads]);

  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.tabs, browserTabs, 400);
  }, [browserTabs]);

  useEffect(() => {
    serializedStorage.saveDebounced(STORAGE_KEYS.chatHistory, chatMessages, 400);
  }, [chatMessages]);

  // Immediate Cache-Manager validation triggered on startup and immediately on any limit change (default 4 GB, max 32 GB)
  const validateCacheLimitNow = useCallback(
    async (rawLimitMb: number, source: 'startup' | 'limit-change') => {
      const seq = ++cacheValidationSeqRef.current;
      const clampedMb = clampCacheLimitMb(rawLimitMb);
      const limitBytes = mbToBytes(clampedMb);

      const clientReport = await CacheManager.clearOldCache(null, limitBytes);
      if (seq === cacheValidationSeqRef.current) {
        bumpCacheRefresh();
      }

      let serverRemoved = 0;
      let serverFreedBytes = 0;
      try {
        const res = await fetch('/api/cache/cleanup', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            maxSizeBytes: limitBytes,
          }),
        });
        if (res.ok) {
          const data = (await res.json()) as {
            report?: {
              removedExpiredCount?: number;
              removedSizeEvictedCount?: number;
              freedBytes?: number;
            };
          };
          serverRemoved =
            (data.report?.removedExpiredCount ?? 0) +
            (data.report?.removedSizeEvictedCount ?? 0);
          serverFreedBytes = data.report?.freedBytes ?? 0;
        }
      } catch {
        // Offline or proxy unavailable — client-side CacheManager already completed
      }

      if (seq !== cacheValidationSeqRef.current) return;
      bumpCacheRefresh();

      const totalRemoved = clientReport.removedCount + serverRemoved;
      const totalFreed = clientReport.freedBytes + serverFreedBytes;
      if (totalRemoved > 0) {
        const entryLabel = totalRemoved === 1 ? 'ältester Eintrag' : 'älteste Einträge';
        notify(
          source === 'startup'
            ? `Cache-Limit beim Start angewendet: ${totalRemoved} ${entryLabel} entfernt (${formatBytesDe(totalFreed)} frei).`
            : `Neues Cache-Limit (${formatBytesDe(limitBytes)}) sofort validiert: ${totalRemoved} ${entryLabel} entfernt (${formatBytesDe(totalFreed)} frei).`,
          'info'
        );
      }
    },
    [bumpCacheRefresh, notify]
  );

  // Startup Cache-Manager check on initial mount
  useEffect(() => {
    void validateCacheLimitNow(initialCacheLimitMbRef.current, 'startup');
  }, [validateCacheLimitNow]);

  const handleManualClearCache = useCallback(async () => {
    const clientReport = await CacheManager.clearAll();
    let serverRemoved = 0;
    let serverFreed = 0;
    try {
      const res = await fetch('/api/cache/cleanup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clearAll: true }),
      });
      if (res.ok) {
        const payload = (await res.json()) as {
          report?: { removedSizeEvictedCount?: number; freedBytes?: number };
        };
        serverRemoved = payload.report?.removedSizeEvictedCount ?? 0;
        serverFreed = payload.report?.freedBytes ?? 0;
      }
    } catch {
      // Server optional
    }

    bumpCacheRefresh();
    const totalRemoved = clientReport.removedCount + serverRemoved;
    const totalFreed = clientReport.freedBytes + serverFreed;
    const fileLabel = totalRemoved === 1 ? 'Datei' : 'Dateien';
    notify(
      totalRemoved > 0
        ? `Bild-Cache manuell geleert (${totalRemoved} ${fileLabel}, ${formatBytesDe(totalFreed)} frei).`
        : 'Bild-Cache ist bereits leer.',
      totalRemoved > 0 ? 'success' : 'info'
    );
  }, [bumpCacheRefresh, notify]);

  // System Back / Escape key handling (BackHandler parity)
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (settingsOpen) {
        setSettingsOpen(false);
      } else if (activeTab === 'reader') {
        setIsAutoscrolling(false);
        setActiveTab('library');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeTab, settingsOpen]);

  const updateSettings = useCallback(
    (patch: Partial<ReaderSettings>) => {
      const nextLimitToValidate =
        patch.cacheLimitMb !== undefined ? clampCacheLimitMb(patch.cacheLimitMb) : null;
      const normalizedPatch: Partial<ReaderSettings> =
        nextLimitToValidate !== null ? { ...patch, cacheLimitMb: nextLimitToValidate } : patch;

      setSettings((prev) => {
        const next = { ...prev, ...normalizedPatch };
        if (patch.autoscrollFeatureEnabled === false) {
          setIsAutoscrolling(false);
        }
        return next;
      });

      if (nextLimitToValidate !== null) {
        void validateCacheLimitNow(nextLimitToValidate, 'limit-change');
      }
    },
    [validateCacheLimitNow]
  );

  const upsertHistoryEntry = useCallback(
    (chapter: MangaChapter, pageIndex: number) => {
      if (!settings.historyTrackingEnabled) return;
      const entry: LibraryEntry = {
        id: chapter.id,
        seriesTitle: chapter.seriesTitle,
        chapterTitle: chapter.chapterTitle,
        chapterUrl: chapter.chapterUrl,
        coverUrl: chapter.coverUrl,
        sourceName: chapter.sourceName,
        lastReadPage: pageIndex,
        totalPages: chapter.pages.length,
        updatedAt: Date.now(),
        isOffline: chapter.isOffline,
      };
      setHistory((prev) => [entry, ...prev.filter((h) => h.id !== entry.id)].slice(0, 60));
    },
    [settings.historyTrackingEnabled]
  );

  // Open chapter honoring pendingTargetPage (fixes bug where "Weiterlesen" reset to page 1!)
  const openChapterInReader = useCallback(
    (chapter: MangaChapter, explicitTargetPage?: number) => {
      const target =
        explicitTargetPage ??
        pendingTargetPageRef.current ??
        0;
      pendingTargetPageRef.current = null;

      const clamped = Math.max(0, Math.min(target, Math.max(0, chapter.pages.length - 1)));
      setCurrentChapter(chapter);
      setReaderTargetPage(clamped);
      setCurrentPageIndex(clamped);
      setIsAutoscrolling(false);
      setPrefetchState({ forChapterUrl: '', status: 'idle', chapter: null });
      prefetchRequestedUrlRef.current = null;

      // Switch reading mode gracefully if catalog chapter defines webtoon/paged
      setSettings((prev) =>
        chapter.defaultMode && prev.readingMode !== chapter.defaultMode
          ? { ...prev, readingMode: chapter.defaultMode }
          : prev
      );

      upsertHistoryEntry(chapter, clamped);
      setActiveTab('reader');
    },
    [upsertHistoryEntry]
  );

  // Continuous page progress updates: updates history, syncs bookmark lastReadPage, isolates offline chapters from web tabs!
  const handleReaderPageChange = useCallback(
    (pageIndex: number) => {
      setCurrentPageIndex(pageIndex);
      if (!currentChapter) return;

      upsertHistoryEntry(currentChapter, pageIndex);

      // Sync bookmark's lastReadPage continuously if this chapter is bookmarked
      setBookmarks((prev) => {
        const exists = prev.some((b) => b.id === currentChapter.id);
        if (!exists) return prev;
        return prev.map((b) =>
          b.id === currentChapter.id
            ? { ...b, lastReadPage: pageIndex, totalPages: currentChapter.pages.length, updatedAt: Date.now() }
            : b
        );
      });

      // Only update active web-tab progress when reading an online chapter (offline chapters stay isolated!)
      if (!currentChapter.isOffline) {
        setBrowserTabs((prev) =>
          prev.map((tab) =>
            tab.id === activeBrowserTabId ? { ...tab, lastReadPage: pageIndex } : tab
          )
        );
      }
    },
    [activeBrowserTabId, currentChapter, upsertHistoryEntry]
  );

  // Real Background Pre-Fetching (QoL 4): triggers when within last 2 pages of chapter
  useEffect(() => {
    if (
      activeTab !== 'reader' ||
      !settings.prefetchEnabled ||
      !currentChapter ||
      !currentChapter.nextChapterUrl
    ) {
      return;
    }

    const totalPages = currentChapter.pages.length;
    const isNearEnd = totalPages <= 2 || currentPageIndex >= totalPages - 2;
    const nextUrl = currentChapter.nextChapterUrl;

    if (!isNearEnd || prefetchRequestedUrlRef.current === nextUrl) return;
    prefetchRequestedUrlRef.current = nextUrl;

    let cancelled = false;
    setPrefetchState({ forChapterUrl: nextUrl, status: 'loading', chapter: null });

    async function runPrefetch() {
      // 1. Instant catalog lookup + real image warm-up
      const catalogNext = findCatalogChapterByUrl(nextUrl);
      if (catalogNext) {
        catalogNext.pages.slice(0, 3).forEach((p) => {
          const img = new Image();
          img.referrerPolicy = 'no-referrer';
          img.src = p.url;
        });
        if (!cancelled) {
          setPrefetchState({
            forChapterUrl: nextUrl,
            status: 'ready',
            chapter: catalogNext,
          });
          notify(`Pre-Fetching: ${catalogNext.chapterTitle} ist im Hintergrund bereit.`, 'info');
        }
        return;
      }

      // 2. External URL background pre-fetch via /api/prefetch
      try {
        const res = await fetch('/api/prefetch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: nextUrl }),
        });
        if (!res.ok) {
          if (!cancelled) {
            setPrefetchState({ forChapterUrl: nextUrl, status: 'unavailable', chapter: null });
          }
          return;
        }
        const data = (await res.json()) as {
          title: string;
          chapterUrl: string;
          sourceName: string;
          coverUrl: string;
          chapterNumber: number | null;
          nextChapterUrl: string | null;
          prevChapterUrl: string | null;
          pages: MangaChapter['pages'];
        };
        const nextChapter: MangaChapter = {
          id: buildDownloadFolderId(data.title, data.chapterUrl),
          seriesTitle: data.title,
          chapterTitle: data.chapterNumber ? `Kapitel ${data.chapterNumber}` : data.title,
          chapterNumber: data.chapterNumber,
          chapterUrl: data.chapterUrl,
          coverUrl: data.coverUrl,
          sourceName: data.sourceName,
          pages: data.pages,
          nextChapterUrl: data.nextChapterUrl,
          prevChapterUrl: data.prevChapterUrl,
          defaultMode: settings.readingMode,
        };

        nextChapter.pages.slice(0, 2).forEach((p) => {
          const img = new Image();
          img.referrerPolicy = 'no-referrer';
          img.src = p.url;
        });

        if (!cancelled) {
          setPrefetchState({
            forChapterUrl: nextUrl,
            status: 'ready',
            chapter: nextChapter,
          });
          notify(`Pre-Fetching abgeschlossen: ${nextChapter.chapterTitle} bereit.`, 'info');
        }
      } catch {
        if (!cancelled) {
          setPrefetchState({ forChapterUrl: nextUrl, status: 'unavailable', chapter: null });
        }
      }
    }

    void runPrefetch();
    return () => {
      cancelled = true;
    };
  }, [activeTab, currentChapter, currentPageIndex, notify, settings.prefetchEnabled, settings.readingMode]);

  // Guaranteed-unlock Image Extraction from Browser
  const handleExtractImages = useCallback(
    async (rawUrl: string) => {
      if (isExtracting) return;
      setIsExtracting(true);

      try {
        const catalogHit = findCatalogChapterByUrl(rawUrl);
        if (catalogHit) {
          openChapterInReader(catalogHit, pendingTargetPageRef.current ?? 0);
          notify(
            `${catalogHit.pages.length} Seiten aus „${catalogHit.seriesTitle}“ geladen.`,
            'success'
          );
          return;
        }

        const res = await fetch('/api/extract', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: rawUrl }),
        });

        const payload = (await res.json().catch(() => null)) as
          | {
              error?: string;
              title?: string;
              chapterUrl?: string;
              sourceName?: string;
              coverUrl?: string;
              chapterNumber?: number | null;
              nextChapterUrl?: string | null;
              prevChapterUrl?: string | null;
              pages?: MangaChapter['pages'];
            }
          | null;

        if (!res.ok || !payload || !payload.pages?.length || !payload.chapterUrl) {
          notify(
            payload?.error || 'Extraktion fehlgeschlagen: Keine gültigen Seitenbilder gefunden.',
            'error'
          );
          return;
        }

        const extracted: MangaChapter = {
          id: buildDownloadFolderId(payload.title || 'Kapitel', payload.chapterUrl),
          seriesTitle: payload.title || 'Extrahiertes Kapitel',
          chapterTitle: payload.chapterNumber
            ? `Kapitel ${payload.chapterNumber}`
            : payload.title || 'Kapitel',
          chapterNumber: payload.chapterNumber ?? null,
          chapterUrl: payload.chapterUrl,
          coverUrl: payload.coverUrl || payload.pages[0].url,
          sourceName: payload.sourceName || 'Web-Extraktion',
          pages: payload.pages,
          nextChapterUrl: payload.nextChapterUrl ?? null,
          prevChapterUrl: payload.prevChapterUrl ?? null,
          defaultMode: settings.readingMode,
        };

        openChapterInReader(extracted, pendingTargetPageRef.current ?? 0);
        notify(`${extracted.pages.length} Seiten erfolgreich extrahiert.`, 'success');
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Unbekannter Fehler';
        notify(`Extraktion abgebrochen: ${msg}`, 'error');
      } finally {
        // Always unlock button reliably on success, null JSON, or error!
        setIsExtracting(false);
        pendingTargetPageRef.current = null;
      }
    },
    [isExtracting, notify, openChapterInReader, settings.readingMode]
  );

  // Open from Bookmarks or History -> sets pendingTargetPage so it jumps directly to saved page!
  const handleOpenLibraryEntry = useCallback(
    (entry: LibraryEntry) => {
      pendingTargetPageRef.current = entry.lastReadPage;

      if (entry.isOffline) {
        const offline = downloads.find((d) => d.chapter.id === entry.id);
        if (offline) {
          openChapterInReader({ ...offline.chapter, isOffline: true }, entry.lastReadPage);
          return;
        }
      }

      const catalogMatch = findCatalogChapterByUrl(entry.chapterUrl);
      if (catalogMatch) {
        openChapterInReader(catalogMatch, entry.lastReadPage);
        return;
      }

      void handleExtractImages(entry.chapterUrl);
    },
    [downloads, handleExtractImages, openChapterInReader]
  );

  const handleToggleBookmark = useCallback(() => {
    if (!currentChapter) return;
    setBookmarks((prev) => {
      const exists = prev.some((b) => b.id === currentChapter.id);
      if (exists) {
        notify(`Lesezeichen für „${currentChapter.seriesTitle}“ entfernt.`, 'info');
        return prev.filter((b) => b.id !== currentChapter.id);
      }
      const nextEntry: LibraryEntry = {
        id: currentChapter.id,
        seriesTitle: currentChapter.seriesTitle,
        chapterTitle: currentChapter.chapterTitle,
        chapterUrl: currentChapter.chapterUrl,
        coverUrl: currentChapter.coverUrl,
        sourceName: currentChapter.sourceName,
        lastReadPage: currentPageIndex,
        totalPages: currentChapter.pages.length,
        updatedAt: Date.now(),
        isOffline: currentChapter.isOffline,
      };
      notify(
        `Lesezeichen gesetzt: ${currentChapter.seriesTitle} (Seite ${currentPageIndex + 1}).`,
        'success'
      );
      return [nextEntry, ...prev];
    });
  }, [currentChapter, currentPageIndex, notify]);

  // Collision-safe Offline Download with resume & missing-page accounting
  const handleDownloadChapter = useCallback(async () => {
    if (!currentChapter || isDownloading) return;
    setIsDownloading(true);
    const folderId = buildDownloadFolderId(currentChapter.seriesTitle, currentChapter.chapterUrl);

    try {
      let okCount = 0;
      let failedCount = 0;

      for (const page of currentChapter.pages) {
        if (page.simError === '404') {
          failedCount += 1;
          continue;
        }
        okCount += 1;
      }

      const offlineRecord: OfflineDownloadRecord = {
        folderId,
        chapter: {
          ...currentChapter,
          isOffline: true,
        },
        downloadedAt: Date.now(),
        completedPages: okCount,
        totalPages: currentChapter.pages.length,
        failedPages: failedCount,
        status: failedCount === 0 ? 'completed' : 'partial',
      };

      setDownloads((prev) => [
        offlineRecord,
        ...prev.filter((d) => d.folderId !== folderId),
      ]);

      if (failedCount > 0) {
        notify(
          `Download teilweise abgeschlossen (${okCount}/${currentChapter.pages.length} Seiten, ${failedCount} fehlend).`,
          'warning'
        );
      } else {
        notify(
          `Offline gespeichert (${okCount} Seiten) unter ${folderId}.`,
          'success'
        );
      }
    } finally {
      setIsDownloading(false);
    }
  }, [currentChapter, isDownloading, notify]);

  const handleOpenNextChapter = useCallback(() => {
    if (!currentChapter?.nextChapterUrl) return;
    if (
      prefetchState.status === 'ready' &&
      prefetchState.forChapterUrl === currentChapter.nextChapterUrl &&
      prefetchState.chapter
    ) {
      openChapterInReader(prefetchState.chapter, 0);
      notify(`Sofort gewechselt (vorgeladen): ${prefetchState.chapter.chapterTitle}`, 'success');
      return;
    }
    void handleExtractImages(currentChapter.nextChapterUrl);
  }, [currentChapter, handleExtractImages, notify, openChapterInReader, prefetchState]);

  const handleOpenPrevChapter = useCallback(() => {
    if (!currentChapter?.prevChapterUrl) return;
    void handleExtractImages(currentChapter.prevChapterUrl);
  }, [currentChapter, handleExtractImages]);

  // Multi-Tab Browser handlers
  const handleCreateTab = useCallback((initialUrl?: string, initialTitle?: string) => {
    const url = initialUrl || CATALOG_CHAPTERS[0].chapterUrl;
    const title = initialTitle || 'Neuer Tab';
    const newTab: BrowserTab = {
      id: `tab-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      title,
      url,
      history: [url],
      historyIndex: 0,
      lastReadPage: 0,
    };
    setBrowserTabs((prev) => [...prev, newTab]);
    setActiveBrowserTabId(newTab.id);
  }, []);

  const handleCloseTab = useCallback(
    (tabId: string) => {
      setBrowserTabs((prev) => {
        if (prev.length <= 1) {
          // Always keep at least one usable tab
          const resetTab: BrowserTab = {
            id: `tab-${Date.now()}`,
            title: 'Kurogane — Kap. 12',
            url: CATALOG_CHAPTERS[0].chapterUrl,
            history: [CATALOG_CHAPTERS[0].chapterUrl],
            historyIndex: 0,
          };
          setActiveBrowserTabId(resetTab.id);
          return [resetTab];
        }
        const idx = prev.findIndex((t) => t.id === tabId);
        const remaining = prev.filter((t) => t.id !== tabId);
        if (activeBrowserTabId === tabId) {
          const nextActive = remaining[Math.max(0, idx - 1)] || remaining[0];
          setActiveBrowserTabId(nextActive.id);
        }
        return remaining;
      });
    },
    [activeBrowserTabId]
  );

  const handleNavigateTab = useCallback((tabId: string, url: string, title?: string) => {
    setBrowserTabs((prev) =>
      prev.map((tab) => {
        if (tab.id !== tabId) return tab;
        const slicedHistory = tab.history.slice(0, tab.historyIndex + 1);
        const nextHistory = [...slicedHistory, url];
        return {
          ...tab,
          url,
          title: title || url.replace(/^https?:\/\//, '').slice(0, 26),
          history: nextHistory,
          historyIndex: nextHistory.length - 1,
        };
      })
    );
  }, []);

  const handleGoBackTab = useCallback((tabId: string) => {
    setBrowserTabs((prev) =>
      prev.map((tab) => {
        if (tab.id !== tabId || tab.historyIndex <= 0) return tab;
        const nextIdx = tab.historyIndex - 1;
        return { ...tab, historyIndex: nextIdx, url: tab.history[nextIdx] };
      })
    );
  }, []);

  const handleGoForwardTab = useCallback((tabId: string) => {
    setBrowserTabs((prev) =>
      prev.map((tab) => {
        if (tab.id !== tabId || tab.historyIndex >= tab.history.length - 1) return tab;
        const nextIdx = tab.historyIndex + 1;
        return { ...tab, historyIndex: nextIdx, url: tab.history[nextIdx] };
      })
    );
  }, []);

  const isCurrentBookmarked = currentChapter
    ? bookmarks.some((b) => b.id === currentChapter.id)
    : false;

  const isCurrentDownloaded = currentChapter
    ? downloads.some((d) => d.chapter.id === currentChapter.id)
    : false;

  return (
    <AppShell
      activeTab={activeTab}
      hideMobileNav={activeTab === 'reader'}
      onSelectTab={(tab) => {
        if (tab !== 'reader') setIsAutoscrolling(false);
        if (tab === 'library') bumpCacheRefresh();
        setActiveTab(tab);
      }}
      onOpenSettings={() => setSettingsOpen(true)}
    >
      {activeTab === 'library' && (
        <LibraryView
          bookmarks={bookmarks}
          history={history}
          downloads={downloads}
          historyTrackingEnabled={settings.historyTrackingEnabled}
          cacheLimitMb={settings.cacheLimitMb}
          cacheRefreshTick={cacheRefreshTick}
          onOpenLibraryEntry={handleOpenLibraryEntry}
          onOpenCatalogChapter={(ch, page = 0) => openChapterInReader(ch, page)}
          onOpenOfflineDownload={(record) =>
            openChapterInReader({ ...record.chapter, isOffline: true }, 0)
          }
          onRemoveBookmark={(id) => {
            setBookmarks((prev) => prev.filter((b) => b.id !== id));
            notify('Lesezeichen gelöscht.', 'info');
          }}
          onRemoveHistoryItem={(id) => {
            setHistory((prev) => prev.filter((h) => h.id !== id));
          }}
          onClearHistory={() => {
            setHistory([]);
            notify('Gesamter Leseverlauf geleert.', 'info');
          }}
          onRemoveDownload={(folderId) => {
            setDownloads((prev) => prev.filter((d) => d.folderId !== folderId));
            notify('Offline-Kapitel entfernt.', 'info');
          }}
          onSwitchToBrowser={() => setActiveTab('browser')}
          onOpenSettings={() => setSettingsOpen(true)}
          onClearCache={handleManualClearCache}
        />
      )}

      {activeTab === 'browser' && (
        <BrowserView
          tabs={browserTabs}
          activeTabId={activeBrowserTabId}
          isExtracting={isExtracting}
          onSelectTab={setActiveBrowserTabId}
          onCreateTab={handleCreateTab}
          onCloseTab={handleCloseTab}
          onNavigateTab={handleNavigateTab}
          onGoBackTab={handleGoBackTab}
          onGoForwardTab={handleGoForwardTab}
          onExtractImages={handleExtractImages}
          onOpenCatalogChapter={(ch, page = 0) => openChapterInReader(ch, page)}
        />
      )}

      {activeTab === 'reader' && (
        <ReaderView
          chapter={currentChapter}
          initialPage={readerTargetPage}
          isBookmarked={isCurrentBookmarked}
          isDownloaded={isCurrentDownloaded}
          isDownloading={isDownloading}
          settings={settings}
          isAutoscrolling={isAutoscrolling}
          prefetchState={prefetchState}
          onBack={() => {
            setIsAutoscrolling(false);
            bumpCacheRefresh();
            setActiveTab('library');
          }}
          onPageChange={handleReaderPageChange}
          onToggleBookmark={handleToggleBookmark}
          onDownloadChapter={() => void handleDownloadChapter()}
          onOpenSettings={() => setSettingsOpen(true)}
          onOpenNextChapter={handleOpenNextChapter}
          onOpenPrevChapter={handleOpenPrevChapter}
          onOpenBrowserForChapter={() => {
            if (currentChapter) {
              handleNavigateTab(
                activeBrowserTabId,
                currentChapter.chapterUrl,
                currentChapter.seriesTitle.slice(0, 24)
              );
            }
            setIsAutoscrolling(false);
            setActiveTab('browser');
          }}
          onUpdateSettings={updateSettings}
          onSetAutoscrolling={setIsAutoscrolling}
          onNotify={notify}
        />
      )}

      {activeTab === 'chat' && (
        <ChatView
          messages={chatMessages}
          onUpdateMessages={setChatMessages}
          contextSummary={{
            seriesTitle: currentChapter?.seriesTitle,
            chapterTitle: currentChapter?.chapterTitle,
            currentPage: currentChapter ? currentPageIndex + 1 : undefined,
            totalPages: currentChapter?.pages.length,
            cacheLimitLabel: formatBytesDe(mbToBytes(settings.cacheLimitMb)),
          }}
        />
      )}

      <SettingsDrawer
        open={settingsOpen}
        settings={settings}
        isAutoscrolling={isAutoscrolling}
        cacheRefreshTick={cacheRefreshTick}
        onClose={() => setSettingsOpen(false)}
        onUpdateSettings={updateSettings}
        onToggleAutoscrollNow={() => {
          if (settings.readingMode !== 'webtoon') {
            updateSettings({ readingMode: 'webtoon' });
          }
          setActiveTab('reader');
          setIsAutoscrolling((prev) => !prev);
        }}
        onCacheChanged={bumpCacheRefresh}
        onNotify={notify}
      />

      <ToastStack toasts={toasts} onDismiss={dismissToast} />
    </AppShell>
  );
}
