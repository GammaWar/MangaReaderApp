import { useEffect, useState } from 'react';
import {
  BookMarked,
  BookOpen,
  Download,
  HardDrive,
  History,
  ShieldAlert,
  SlidersHorizontal,
  Trash2,
} from 'lucide-react';
import type {
  LibraryEntry,
  LibrarySubTab,
  MangaChapter,
  OfflineDownloadRecord,
} from '../types/manga';
import { CATALOG_CHAPTERS } from '../data/catalog';
import {
  CacheManager,
  formatBytesDe,
  mbToBytes,
  type CacheManagerStats,
} from '../utils/cacheManager';

interface LibraryViewProps {
  bookmarks: LibraryEntry[];
  history: LibraryEntry[];
  downloads: OfflineDownloadRecord[];
  historyTrackingEnabled: boolean;
  cacheLimitMb: number;
  cacheRefreshTick: number;
  onOpenLibraryEntry: (entry: LibraryEntry) => void;
  onOpenCatalogChapter: (chapter: MangaChapter, targetPage?: number) => void;
  onOpenOfflineDownload: (record: OfflineDownloadRecord) => void;
  onRemoveBookmark: (id: string) => void;
  onRemoveHistoryItem: (id: string) => void;
  onClearHistory: () => void;
  onRemoveDownload: (folderId: string) => void;
  onSwitchToBrowser: () => void;
  onOpenSettings: () => void;
  onClearCache: () => Promise<void>;
}

function formatRelativeTime(timestamp: number): string {
  const diffMin = Math.max(0, Math.floor((Date.now() - timestamp) / 60_000));
  if (diffMin < 1) return 'Gerade eben';
  if (diffMin < 60) return `Vor ${diffMin} Min.`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `Vor ${diffHours} Std.`;
  return new Date(timestamp).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}

export function LibraryView({
  bookmarks,
  history,
  downloads,
  historyTrackingEnabled,
  cacheLimitMb,
  cacheRefreshTick,
  onOpenLibraryEntry,
  onOpenCatalogChapter,
  onOpenOfflineDownload,
  onRemoveBookmark,
  onRemoveHistoryItem,
  onClearHistory,
  onRemoveDownload,
  onSwitchToBrowser,
  onOpenSettings,
  onClearCache,
}: LibraryViewProps) {
  const [subTab, setSubTab] = useState<LibrarySubTab>('bookmarks');
  const [isClearingCache, setIsClearingCache] = useState(false);

  const limitBytes = mbToBytes(cacheLimitMb);
  const [cacheUsage, setCacheUsage] = useState(() => {
    const client = CacheManager.calculateStorageUsage(limitBytes);
    return {
      totalBytes: client.totalBytes,
      entryCount: client.entryCount,
      maxBytes: limitBytes,
      usagePercent: client.usagePercent,
    };
  });

  useEffect(() => {
    let cancelled = false;

    async function refreshCacheUsage() {
      const client = CacheManager.calculateStorageUsage(limitBytes);
      let serverBytes = 0;
      let serverEntries = 0;

      try {
        const res = await fetch(`/api/cache/status?maxBytes=${encodeURIComponent(String(limitBytes))}`);
        if (res.ok) {
          const stats = (await res.json()) as CacheManagerStats;
          serverBytes = stats.totalBytes ?? 0;
          serverEntries = stats.entryCount ?? 0;
        }
      } catch {
        // Offline or proxy unreachable — local client usage remains accurate
      }

      if (cancelled) return;
      const totalBytes = client.totalBytes + serverBytes;
      const usagePercent =
        limitBytes > 0 ? Math.min(100, Number(((totalBytes / limitBytes) * 100).toFixed(1))) : 0;

      setCacheUsage({
        totalBytes,
        entryCount: client.entryCount + serverEntries,
        maxBytes: limitBytes,
        usagePercent,
      });
    }

    void refreshCacheUsage();
    return () => {
      cancelled = true;
    };
  }, [cacheRefreshTick, limitBytes]);

  const handleQuickClearCache = async () => {
    if (isClearingCache) return;
    setIsClearingCache(true);
    try {
      await onClearCache();
    } finally {
      setIsClearingCache(false);
    }
  };

  const activeItems = subTab === 'bookmarks' ? bookmarks : history;
  const cacheBarTone =
    cacheUsage.usagePercent >= 90
      ? 'bg-rose-500'
      : cacheUsage.usagePercent >= 75
      ? 'bg-amber-400'
      : 'bg-[#BB86FC]';

  return (
    <div className="mx-auto w-full max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
      {/* Page Heading, Segmented Sub-Tab Controls & Compact Image-Cache Storage Bar */}
      <div className="space-y-4 border-b border-[#221E36] pb-6">
        <div className="flex flex-col gap-5 md:flex-row md:items-end md:justify-between">
        <div className="space-y-1">
          <h1 className="font-display text-2xl font-bold tracking-tight text-white sm:text-3xl">
            Bibliothek
          </h1>
          <p className="text-sm text-[#A29BBE]">
            Lesezeichen, chronologischer Leseverlauf und heruntergeladene Offline-Kapitel.
          </p>
        </div>

        <div className="flex w-full flex-col gap-3 sm:w-auto sm:flex-row sm:flex-wrap sm:items-center">
          <div
            role="tablist"
            aria-label="Bibliothek-Ansichten"
            className="grid w-full grid-cols-3 items-center gap-1 rounded-xl border border-[#241F3A] bg-[#131022] p-1 sm:inline-flex sm:w-auto"
          >
            <button
              type="button"
              role="tab"
              aria-selected={subTab === 'bookmarks'}
              onClick={() => setSubTab('bookmarks')}
              className={`inline-flex min-h-[40px] items-center justify-center gap-1 rounded-lg px-1.5 py-2 text-[11px] font-medium whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:gap-1.5 sm:px-3.5 sm:text-sm ${
                subTab === 'bookmarks'
                  ? 'bg-[#7C4DFF] text-white'
                  : 'text-[#A7A0C4] hover:text-white'
              }`}
            >
              <BookMarked className="h-3.5 w-3.5 shrink-0 sm:h-4 sm:w-4" aria-hidden="true" />
              <span>Lesezeichen</span>
              <span className="font-mono-tabular text-[11px] opacity-80 sm:text-xs">
                ({bookmarks.length})
              </span>
            </button>

            <button
              type="button"
              role="tab"
              aria-selected={subTab === 'history'}
              onClick={() => setSubTab('history')}
              className={`inline-flex min-h-[40px] items-center justify-center gap-1 rounded-lg px-1.5 py-2 text-[11px] font-medium whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:gap-1.5 sm:px-3.5 sm:text-sm ${
                subTab === 'history'
                  ? 'bg-[#7C4DFF] text-white'
                  : 'text-[#A7A0C4] hover:text-white'
              }`}
            >
              <History className="h-3.5 w-3.5 shrink-0 sm:h-4 sm:w-4" aria-hidden="true" />
              <span>Verlauf</span>
              <span className="font-mono-tabular text-[11px] opacity-80 sm:text-xs">
                ({history.length})
              </span>
            </button>

            <button
              type="button"
              role="tab"
              aria-selected={subTab === 'downloads'}
              onClick={() => setSubTab('downloads')}
              className={`inline-flex min-h-[40px] items-center justify-center gap-1 rounded-lg px-1.5 py-2 text-[11px] font-medium whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:gap-1.5 sm:px-3.5 sm:text-sm ${
                subTab === 'downloads'
                  ? 'bg-[#7C4DFF] text-white'
                  : 'text-[#A7A0C4] hover:text-white'
              }`}
            >
              <Download className="h-3.5 w-3.5 shrink-0 sm:h-4 sm:w-4" aria-hidden="true" />
              <span>Offline</span>
              <span className="font-mono-tabular text-[11px] opacity-80 sm:text-xs">
                ({downloads.length})
              </span>
            </button>
          </div>

          {subTab === 'history' && history.length > 0 && (
            <button
              type="button"
              onClick={onClearHistory}
              className="inline-flex min-h-[44px] items-center gap-2 rounded-xl border border-rose-900/60 bg-rose-950/30 px-4 py-2 text-sm font-medium text-rose-200 whitespace-nowrap transition-colors hover:bg-rose-950/60 focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
            >
              <Trash2 className="h-4 w-4" aria-hidden="true" />
              Verlauf leeren
            </button>
          )}
        </div>
        </div>

        {/* Compact Local Image Cache Storage Indicator */}
        <div
          data-testid="library-cache-indicator"
          className="flex flex-col gap-2.5 rounded-xl border border-[#221E36] bg-[#120F20] px-3.5 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4"
        >
          <div className="min-w-0 flex-1 space-y-1.5">
            <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs">
              <div className="flex items-center gap-2 text-[#D5CFF0]">
                <HardDrive className="h-3.5 w-3.5 shrink-0 text-[#BB86FC]" aria-hidden="true" />
                <span className="font-medium text-white">Bild-Cache</span>
                <span aria-hidden="true" className="text-[#5F587D]">
                  ·
                </span>
                <span className="font-mono-tabular text-[#CFCBE2]">
                  {formatBytesDe(cacheUsage.totalBytes)} / {formatBytesDe(limitBytes)}
                </span>
              </div>

              <div className="flex items-center gap-2 font-mono-tabular text-[#9C95BA]">
                <span
                  className={
                    cacheUsage.usagePercent >= 90
                      ? 'text-rose-300'
                      : cacheUsage.usagePercent >= 75
                      ? 'text-amber-300'
                      : 'text-[#CFCBE2]'
                  }
                >
                  {cacheUsage.usagePercent.toFixed(1).replace('.', ',')} %
                </span>
                <span aria-hidden="true" className="hidden sm:inline text-[#5F587D]">
                  ·
                </span>
                <span className="hidden sm:inline">
                  {cacheUsage.entryCount} {cacheUsage.entryCount === 1 ? 'Datei' : 'Dateien'}
                </span>
                {cacheUsage.usagePercent >= 90 && (
                  <>
                    <span aria-hidden="true" className="text-[#5F587D]">
                      ·
                    </span>
                    <span className="font-sans text-rose-300">Limit fast erreicht</span>
                  </>
                )}
              </div>
            </div>

            <div
              role="progressbar"
              aria-label="Bild-Cache Auslastung"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(cacheUsage.usagePercent)}
              aria-valuetext={`${formatBytesDe(cacheUsage.totalBytes)} von ${formatBytesDe(limitBytes)} (${cacheUsage.usagePercent.toFixed(1).replace('.', ',')} %)`}
              className="h-1.5 w-full overflow-hidden rounded-full bg-[#241F3A]"
            >
              <div
                className={`h-full transition-all duration-300 ${cacheBarTone}`}
                style={{ width: `${Math.max(0, Math.min(100, cacheUsage.usagePercent))}%` }}
              />
            </div>
          </div>

          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              data-testid="library-cache-limit-btn"
              onClick={onOpenSettings}
              className="inline-flex min-h-[38px] items-center gap-1.5 rounded-lg border border-[#2C2647] bg-[#18142C] px-3 py-1.5 text-xs font-medium text-[#D8D1F5] whitespace-nowrap transition-colors hover:border-[#3E3568] hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
            >
              <SlidersHorizontal className="h-3.5 w-3.5" aria-hidden="true" />
              Limit ändern
            </button>
            <button
              type="button"
              data-testid="library-cache-clear-btn"
              disabled={isClearingCache || cacheUsage.totalBytes === 0}
              onClick={() => void handleQuickClearCache()}
              className="inline-flex min-h-[38px] items-center gap-1.5 rounded-lg border border-rose-900/50 bg-rose-950/25 px-3 py-1.5 text-xs font-medium text-rose-200 whitespace-nowrap transition-colors hover:bg-rose-950/55 focus-visible:outline-2 focus-visible:outline-[#BB86FC] disabled:cursor-not-allowed disabled:opacity-45"
            >
              <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
              Leeren
            </button>
          </div>
        </div>
      </div>

      {/* Status notice if automatic history tracking is manually switched off */}
      {subTab === 'history' && !historyTrackingEnabled && (
        <div className="mt-5 rounded-xl border border-amber-800/60 bg-[#1C150C] px-4 py-3 text-sm text-amber-100">
          Der automatische Leseverlauf ist in den Einstellungen deaktiviert. Neue Kapitel werden
          aktuell nicht protokolliert.
        </div>
      )}

      {/* Active Sub-Tab Content */}
      <section className="mt-6" aria-label="Einträge">
        {subTab === 'downloads' ? (
          downloads.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-2xl border border-[#221E36] bg-[#120F20] px-6 py-14 text-center">
              <Download className="h-9 w-9 text-[#6F6791]" aria-hidden="true" />
              <h2 className="mt-4 font-display text-lg font-semibold text-white">
                Noch keine Offline-Kapitel gespeichert
              </h2>
              <p className="mt-1.5 max-w-md text-sm text-[#9D96BA]">
                Öffne ein Kapitel im Reader und tippe auf das Download-Symbol, um alle Seiten
                kollisionssicher lokal abzulegen.
              </p>
              <button
                type="button"
                onClick={onSwitchToBrowser}
                className="mt-5 inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-5 py-2.5 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
              >
                Im Browser stöbern
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              {downloads.map((record) => (
                <article
                  key={record.folderId}
                  className="flex items-stretch gap-4 rounded-2xl border border-[#25203C] bg-[#131022] p-4 transition-colors hover:border-[#3A3261]"
                >
                  <img
                    src={record.chapter.coverUrl}
                    alt={record.chapter.seriesTitle}
                    referrerPolicy="no-referrer"
                    className="h-32 w-24 shrink-0 rounded-xl border border-[#2A2444] object-cover"
                  />
                  <div className="flex min-w-0 flex-1 flex-col justify-between gap-2">
                    <div className="space-y-1">
                      <h3 className="truncate font-display text-base font-semibold text-white">
                        {record.chapter.seriesTitle}
                      </h3>
                      <p className="truncate text-sm text-[#CFCBE2]">
                        {record.chapter.chapterTitle}
                      </p>
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-[#9C95BA]">
                        <span className="font-mono-tabular">
                          {record.completedPages} von {record.totalPages} Seiten
                        </span>
                        <span aria-hidden="true">·</span>
                        <span>
                          {record.status === 'completed'
                            ? 'Vollständig offline'
                            : 'Teilweise gespeichert'}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span className="font-mono-tabular">{record.folderId}</span>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 pt-1">
                      <button
                        type="button"
                        onClick={() => onOpenOfflineDownload(record)}
                        className="inline-flex min-h-[42px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-4 py-2 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                      >
                        <BookOpen className="h-4 w-4" aria-hidden="true" />
                        Offline lesen
                      </button>
                      <button
                        type="button"
                        onClick={() => onRemoveDownload(record.folderId)}
                        aria-label={`Download '${record.chapter.seriesTitle}' löschen`}
                        className="inline-flex h-[42px] w-[42px] items-center justify-center rounded-xl border border-[#2B2545] text-[#9E97BC] transition-colors hover:border-rose-800/70 hover:bg-rose-950/40 hover:text-rose-200"
                      >
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )
        ) : activeItems.length === 0 ? (
          <div className="flex flex-col items-center justify-center rounded-2xl border border-[#221E36] bg-[#120F20] px-6 py-14 text-center">
            {subTab === 'bookmarks' ? (
              <BookMarked className="h-9 w-9 text-[#6F6791]" aria-hidden="true" />
            ) : (
              <History className="h-9 w-9 text-[#6F6791]" aria-hidden="true" />
            )}
            <h2 className="mt-4 font-display text-lg font-semibold text-white">
              {subTab === 'bookmarks'
                ? 'Keine Lesezeichen gespeichert'
                : 'Dein Leseverlauf ist leer'}
            </h2>
            <p className="mt-1.5 max-w-md text-sm text-[#9D96BA]">
              {subTab === 'bookmarks'
                ? 'Tippe im Reader oben rechts auf das Lesezeichen-Symbol, um Kapitel samt exakter Seitenzahl zu merken.'
                : 'Sobald du ein Kapitel im Reader öffnest, wird dein Fortschritt hier automatisch erfasst.'}
            </p>
            <button
              type="button"
              onClick={onSwitchToBrowser}
              className="mt-5 inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-5 py-2.5 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
            >
              Browser öffnen
            </button>
          </div>
        ) : (
          <div
            data-testid={`library-list-${subTab}`}
            className="grid grid-cols-1 gap-4 md:grid-cols-2"
          >
            {activeItems.map((entry) => {
              const progressPct = Math.min(
                100,
                Math.round(((entry.lastReadPage + 1) / Math.max(1, entry.totalPages)) * 100)
              );
              return (
                <article
                  key={`${subTab}-${entry.id}`}
                  className="flex items-stretch gap-4 rounded-2xl border border-[#25203C] bg-[#131022] p-4 transition-colors hover:border-[#3A3261]"
                >
                  <img
                    src={entry.coverUrl}
                    alt={entry.seriesTitle}
                    referrerPolicy="no-referrer"
                    className="h-32 w-24 shrink-0 rounded-xl border border-[#2A2444] object-cover"
                  />

                  <div className="flex min-w-0 flex-1 flex-col justify-between gap-2">
                    <div className="space-y-1">
                      <h3 className="truncate font-display text-base font-semibold text-white">
                        {entry.seriesTitle}
                      </h3>
                      <p className="truncate text-sm text-[#CFCBE2]">{entry.chapterTitle}</p>

                      {/* Clean unboxed metadata with typographic separators */}
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-[#9C95BA]">
                        <span className="font-mono-tabular text-[#D7CEFF]">
                          Seite {entry.lastReadPage + 1} von {entry.totalPages}
                        </span>
                        <span aria-hidden="true">·</span>
                        <span>{formatRelativeTime(entry.updatedAt)}</span>
                        <span aria-hidden="true">·</span>
                        <span>{entry.sourceName}</span>
                      </div>

                      {/* Subtle progress hairline */}
                      <div className="pt-1.5">
                        <div className="h-1 w-full overflow-hidden rounded-full bg-[#241F3A]">
                          <div
                            className="h-full bg-[#BB86FC] transition-all"
                            style={{ width: `${progressPct}%` }}
                          />
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 pt-1">
                      <button
                        type="button"
                        onClick={() => onOpenLibraryEntry(entry)}
                        className="inline-flex min-h-[42px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-4 py-2 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                      >
                        <BookOpen className="h-4 w-4" aria-hidden="true" />
                        Weiterlesen
                      </button>

                      <button
                        type="button"
                        onClick={() =>
                          subTab === 'bookmarks'
                            ? onRemoveBookmark(entry.id)
                            : onRemoveHistoryItem(entry.id)
                        }
                        aria-label={
                          subTab === 'bookmarks'
                            ? `Lesezeichen '${entry.seriesTitle}' entfernen`
                            : `Verlaufseintrag '${entry.seriesTitle}' entfernen`
                        }
                        className="inline-flex h-[42px] w-[42px] items-center justify-center rounded-xl border border-[#2B2545] text-[#9E97BC] transition-colors hover:border-rose-800/70 hover:bg-rose-950/40 hover:text-rose-200 focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                      >
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                      </button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>

      {/* Direktstart-Katalog & Diagnose-Labor */}
      <section className="mt-10 border-t border-[#221E36] pt-8" aria-labelledby="catalog-heading">
        <div className="flex flex-col gap-1 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 id="catalog-heading" className="font-display text-xl font-bold text-white">
              Sofort lesen &amp; Diagnose-Labor
            </h2>
            <p className="text-sm text-[#9C95BA]">
              Starte Beispielkapitel für Webtoon-Autoscroll, Pre-Fetching oder teste die
              HTTP-Fehlerdiagnose (404 / 403 HTML-Challenge / 429 Auto-Retry).
            </p>
          </div>
        </div>

        <div className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {CATALOG_CHAPTERS.filter((c) =>
            ['kurogane-ch-12', 'stellar-drift-ep-24', 'cdn-diagnose-lab'].includes(c.id)
          ).map((chapter) => (
            <article
              key={chapter.id}
              className="flex flex-col justify-between rounded-2xl border border-[#25203C] bg-[#131022] p-4 transition-colors hover:border-[#3A3261]"
            >
              <div className="flex gap-4">
                <img
                  src={chapter.coverUrl}
                  alt={chapter.seriesTitle}
                  referrerPolicy="no-referrer"
                  className="h-28 w-20 shrink-0 rounded-xl border border-[#2A2444] object-cover"
                />
                <div className="min-w-0 flex-1 space-y-1">
                  <div className="flex items-center gap-1.5 text-xs text-[#A7A0C4]">
                    {chapter.isDiagnosticDemo && (
                      <ShieldAlert className="h-3.5 w-3.5 text-amber-400" aria-hidden="true" />
                    )}
                    <span>{chapter.sourceName}</span>
                    <span aria-hidden="true">·</span>
                    <span className="font-mono-tabular">{chapter.pages.length} Seiten</span>
                  </div>
                  <h3 className="font-display text-base font-semibold leading-snug text-white">
                    {chapter.seriesTitle}
                  </h3>
                  <p className="text-xs leading-relaxed text-[#ABA4C7]">{chapter.chapterTitle}</p>
                </div>
              </div>

              <div className="mt-4 flex items-center justify-between gap-2 border-t border-[#221D38] pt-3">
                <span className="text-xs text-[#8D86A8]">
                  {chapter.isDiagnosticDemo
                    ? '404 · 403 HTML · 429 Retry'
                    : chapter.defaultMode === 'webtoon'
                    ? 'Webtoon · Pre-Fetch bereit'
                    : 'Manga · Folgekapitel 13'}
                </span>
                <button
                  type="button"
                  onClick={() => onOpenCatalogChapter(chapter, 0)}
                  className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-[#3B3166] bg-[#1C1634] px-3.5 py-2 text-xs font-medium text-[#E5DCFF] whitespace-nowrap transition-colors hover:bg-[#271F48] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                >
                  <BookOpen className="h-3.5 w-3.5" aria-hidden="true" />
                  Öffnen
                </button>
              </div>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
