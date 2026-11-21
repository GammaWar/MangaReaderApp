import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  Bookmark,
  BookmarkCheck,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Download,
  FastForward,
  Pause,
  Play,
  RefreshCw,
  SlidersHorizontal,
  X,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import type {
  AutoscrollSpeed,
  MangaChapter,
  PrefetchedChapterState,
  ReaderSettings,
} from '../types/manga';
import { ReaderPageItem } from './ReaderPageItem';

interface ReaderViewProps {
  chapter: MangaChapter | null;
  initialPage: number;
  isBookmarked: boolean;
  isDownloaded: boolean;
  isDownloading: boolean;
  settings: ReaderSettings;
  isAutoscrolling: boolean;
  prefetchState: PrefetchedChapterState;
  onBack: () => void;
  onPageChange: (pageIndex: number) => void;
  onToggleBookmark: () => void;
  onDownloadChapter: () => void;
  onOpenSettings: () => void;
  onOpenNextChapter: () => void;
  onOpenPrevChapter: () => void;
  onOpenBrowserForChapter: () => void;
  onUpdateSettings: (patch: Partial<ReaderSettings>) => void;
  onSetAutoscrolling: (running: boolean) => void;
  onNotify: (message: string, tone?: 'info' | 'success' | 'warning' | 'error') => void;
}

const SPEED_CYCLE: AutoscrollSpeed[] = [0.5, 1.0, 1.5, 2.0, 3.0];

export function ReaderView({
  chapter,
  initialPage,
  isBookmarked,
  isDownloaded,
  isDownloading,
  settings,
  isAutoscrolling,
  prefetchState,
  onBack,
  onPageChange,
  onToggleBookmark,
  onDownloadChapter,
  onOpenSettings,
  onOpenNextChapter,
  onOpenPrevChapter,
  onOpenBrowserForChapter,
  onUpdateSettings,
  onSetAutoscrolling,
  onNotify,
}: ReaderViewProps) {
  const [currentPage, setCurrentPage] = useState(initialPage);
  const [hudVisible, setHudVisible] = useState(true);
  const [zoomScale, setZoomScale] = useState(1.0);
  const [panOffset, setPanOffset] = useState({ x: 0, y: 0 });
  const [sessionVersion, setSessionVersion] = useState(0);

  const webtoonContainerRef = useRef<HTMLDivElement | null>(null);
  const pageRefs = useRef<Array<HTMLDivElement | null>>([]);
  const lastReportedPageRef = useRef(initialPage);
  const dragStateRef = useRef<{
    active: boolean;
    startX: number;
    startY: number;
    originX: number;
    originY: number;
    moved: boolean;
  }>({ active: false, startX: 0, startY: 0, originX: 0, originY: 0, moved: false });

  // Sync exact target page when opening a chapter (fixes bug where "Weiterlesen" reset to page 1!)
  useEffect(() => {
    if (!chapter) return;
    const clamped = Math.max(0, Math.min(initialPage, Math.max(0, chapter.pages.length - 1)));
    setCurrentPage(clamped);
    lastReportedPageRef.current = clamped;
    setZoomScale(1.0);
    setPanOffset({ x: 0, y: 0 });
    setSessionVersion(0);

    if (settings.readingMode === 'webtoon' && clamped > 0) {
      requestAnimationFrame(() => {
        const targetEl = pageRefs.current[clamped];
        if (targetEl) {
          targetEl.scrollIntoView({ behavior: 'auto', block: 'start' });
        }
      });
    } else if (settings.readingMode === 'webtoon' && webtoonContainerRef.current) {
      webtoonContainerRef.current.scrollTop = 0;
    }
  }, [chapter?.id, initialPage, settings.readingMode]);

  // Keep Screen On (Wake Lock API) while reading / autoscrolling
  useEffect(() => {
    if (!chapter || !settings.keepScreenOn) return;
    let wakeLock: { release: () => Promise<void> } | null = null;
    const nav = navigator as Navigator & {
      wakeLock?: { request: (type: 'screen') => Promise<{ release: () => Promise<void> }> };
    };

    if (nav.wakeLock) {
      nav.wakeLock
        .request('screen')
        .then((lock) => {
          wakeLock = lock;
        })
        .catch(() => {
          // Gracefully ignore when unsupported or tab unfocused
        });
    }

    return () => {
      if (wakeLock) {
        void wakeLock.release().catch(() => {});
      }
    };
  }, [chapter, settings.keepScreenOn, isAutoscrolling]);

  // Autoscroll rAF loop in Webtoon mode
  useEffect(() => {
    if (!isAutoscrolling || !chapter || settings.readingMode !== 'webtoon') return;
    let frameId = 0;
    let lastTime = performance.now();

    const step = (now: number) => {
      const dt = Math.min(64, now - lastTime);
      lastTime = now;

      const container = webtoonContainerRef.current;
      if (container) {
        const pxPerSecond = 85 * settings.autoscrollSpeed;
        const delta = (pxPerSecond * dt) / 1000;
        const nextScroll = container.scrollTop + delta;
        const maxScroll = container.scrollHeight - container.clientHeight;

        if (nextScroll >= maxScroll - 2) {
          container.scrollTop = maxScroll;
          onSetAutoscrolling(false);
          onNotify('Kapitelende erreicht — Autoscroll gestoppt.', 'info');
          return;
        }
        container.scrollTop = nextScroll;
      }

      frameId = requestAnimationFrame(step);
    };

    frameId = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frameId);
  }, [isAutoscrolling, chapter, settings.readingMode, settings.autoscrollSpeed, onSetAutoscrolling, onNotify]);

  // Report page index changes upward without causing re-render thrashing on every pixel
  const reportPage = useCallback(
    (nextIdx: number) => {
      if (!chapter) return;
      const clamped = Math.max(0, Math.min(nextIdx, chapter.pages.length - 1));
      if (clamped !== lastReportedPageRef.current) {
        lastReportedPageRef.current = clamped;
        setCurrentPage(clamped);
        onPageChange(clamped);
      }
    },
    [chapter, onPageChange]
  );

  // Track visible page in Webtoon mode on scroll
  const handleWebtoonScroll = useCallback(() => {
    const container = webtoonContainerRef.current;
    if (!container || !chapter) return;
    const midY = container.getBoundingClientRect().top + container.clientHeight * 0.35;

    let closestIndex = lastReportedPageRef.current;
    let bestDist = Number.POSITIVE_INFINITY;

    for (let i = 0; i < chapter.pages.length; i++) {
      const el = pageRefs.current[i];
      if (!el) continue;
      const rect = el.getBoundingClientRect();
      const dist = Math.abs(rect.top - midY);
      if (dist < bestDist) {
        bestDist = dist;
        closestIndex = i;
      }
    }

    reportPage(closestIndex);
  }, [chapter, reportPage]);

  // Keyboard shortcuts for Paged & Webtoon navigation
  useEffect(() => {
    if (!chapter) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (settings.readingMode === 'paged') {
        if (e.key === 'ArrowRight') {
          e.preventDefault();
          reportPage(
            settings.readingDirection === 'rtl' ? currentPage - 1 : currentPage + 1
          );
        } else if (e.key === 'ArrowLeft') {
          e.preventDefault();
          reportPage(
            settings.readingDirection === 'rtl' ? currentPage + 1 : currentPage - 1
          );
        }
      }
      if (e.key === ' ') {
        if (settings.readingMode === 'webtoon' && settings.autoscrollFeatureEnabled) {
          e.preventDefault();
          onSetAutoscrolling(!isAutoscrolling);
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [
    chapter,
    currentPage,
    isAutoscrolling,
    onSetAutoscrolling,
    reportPage,
    settings.autoscrollFeatureEnabled,
    settings.readingDirection,
    settings.readingMode,
  ]);

  const handleRefreshSession = useCallback(() => {
    setSessionVersion((v) => v + 1);
    onNotify('Sitzung erneuert — fehlerhafte Seiten werden mit Cache-Bypass neu geladen.', 'info');
  }, [onNotify]);

  const handleCopyUrl = useCallback(
    async (url: string) => {
      try {
        await navigator.clipboard.writeText(url);
        onNotify('Bild-URL in die Zwischenablage kopiert.', 'success');
      } catch {
        onNotify(`URL: ${url}`, 'info');
      }
    },
    [onNotify]
  );

  const cycleAutoscrollSpeed = () => {
    const idx = SPEED_CYCLE.indexOf(settings.autoscrollSpeed);
    const next = SPEED_CYCLE[(idx + 1) % SPEED_CYCLE.length];
    onUpdateSettings({ autoscrollSpeed: next });
  };

  const adjustZoom = (delta: number) => {
    setZoomScale((prev) => {
      const next = Math.max(1.0, Math.min(4.0, Number((prev + delta).toFixed(2))));
      if (next <= 1.05) setPanOffset({ x: 0, y: 0 });
      return next;
    });
  };

  if (!chapter) {
    return (
      <div className="flex min-h-[75vh] flex-col items-center justify-center bg-black px-6 text-center">
        <h2 className="font-display text-xl font-semibold text-white">
          Kein Kapitel im Reader geladen
        </h2>
        <p className="mt-2 max-w-md text-sm text-[#9C95BA]">
          Wähle ein Kapitel aus deiner Bibliothek oder extrahiere Bilder im Multi-Tab-Browser.
        </p>
        <button
          type="button"
          onClick={onBack}
          className="mt-5 inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[#6E3FF2]"
        >
          Zur Bibliothek
        </button>
      </div>
    );
  }

  const totalPages = chapter.pages.length;
  const hasNextChapter = Boolean(chapter.nextChapterUrl);
  const isNextPrefetched =
    prefetchState.status === 'ready' &&
    prefetchState.forChapterUrl === chapter.nextChapterUrl &&
    Boolean(prefetchState.chapter);

  // Paged mode non-blocking tap zone handler (Left 22% / Center 56% immediate HUD / Right 22%)
  const handlePagedSurfaceClick = (e: React.MouseEvent<HTMLDivElement>) => {
    // Ignore clicks on buttons inside Error Cards or Footer
    if ((e.target as HTMLElement).closest('button, a, input')) return;
    if (dragStateRef.current.moved) {
      dragStateRef.current.moved = false;
      return;
    }

    const rect = e.currentTarget.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / Math.max(1, rect.width);

    if (relX < 0.22) {
      const target = settings.readingDirection === 'rtl' ? currentPage + 1 : currentPage - 1;
      if (target >= 0 && target < totalPages) {
        reportPage(target);
      } else if (target >= totalPages && hasNextChapter) {
        onOpenNextChapter();
      }
    } else if (relX > 0.78) {
      const target = settings.readingDirection === 'rtl' ? currentPage - 1 : currentPage + 1;
      if (target >= 0 && target < totalPages) {
        reportPage(target);
      } else if (target >= totalPages && hasNextChapter) {
        onOpenNextChapter();
      }
    } else {
      // Immediate HUD toggle with zero 300ms double-tap delay
      setHudVisible((v) => !v);
    }
  };

  // Pointer drag: ONLY pan when zoomed (> 1.05x); at 1.0x horizontal swipe changes pages!
  const handlePagedPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if ((e.target as HTMLElement).closest('button, a, input')) return;
    dragStateRef.current = {
      active: true,
      startX: e.clientX,
      startY: e.clientY,
      originX: panOffset.x,
      originY: panOffset.y,
      moved: false,
    };
  };

  const handlePagedPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragStateRef.current.active) return;
    const dx = e.clientX - dragStateRef.current.startX;
    const dy = e.clientY - dragStateRef.current.startY;
    if (Math.hypot(dx, dy) > 8) {
      dragStateRef.current.moved = true;
    }
    if (zoomScale > 1.05) {
      setPanOffset({
        x: Math.max(-420, Math.min(420, dragStateRef.current.originX + dx)),
        y: Math.max(-420, Math.min(420, dragStateRef.current.originY + dy)),
      });
    }
  };

  const handlePagedPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragStateRef.current.active) return;
    const dx = e.clientX - dragStateRef.current.startX;
    dragStateRef.current.active = false;

    // Swipe navigation when at normal zoom (<= 1.05x)
    if (zoomScale <= 1.05 && Math.abs(dx) > 55) {
      const swipedLeft = dx < 0;
      const step =
        settings.readingDirection === 'rtl' ? (swipedLeft ? -1 : 1) : swipedLeft ? 1 : -1;
      const target = currentPage + step;
      if (target >= 0 && target < totalPages) {
        reportPage(target);
      }
    }
  };

  return (
    <div
      data-testid="reader-root"
      data-current-page={currentPage + 1}
      data-total-pages={totalPages}
      className="relative flex h-[100dvh] flex-col overflow-hidden bg-black text-[#F1EFFA] md:h-[calc(100dvh-3.5rem)]"
    >
      {/* Reader Top Bar (auto-hides on center tap in Paged mode) */}
      <div
        className={`z-30 flex items-center justify-between gap-2 border-b border-[#1E1A30] bg-[#0B0914]/95 px-2.5 py-2 backdrop-blur-md transition-opacity duration-150 sm:gap-3 sm:px-5 ${
          hudVisible ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
      >
        <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-2.5">
          <button
            type="button"
            onClick={onBack}
            aria-label="Zurück zur Bibliothek"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-[#272140] bg-[#141124] text-[#E4DEFA] transition-colors hover:bg-[#1E1938] focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          </button>

          <div className="min-w-0 flex-1">
            <h1 className="truncate font-display text-sm font-semibold text-white sm:text-base">
              {chapter.seriesTitle}
            </h1>
            <div className="flex items-center gap-1.5 text-xs text-[#9E97BC] sm:gap-2">
              <span className="hidden truncate sm:inline">{chapter.chapterTitle}</span>
              <span className="hidden sm:inline" aria-hidden="true">
                ·
              </span>
              <span
                data-testid="reader-page-counter"
                className="font-mono-tabular shrink-0 text-[#D7CEFF]"
              >
                Seite {currentPage + 1} / {totalPages}
              </span>
              {chapter.isOffline && (
                <>
                  <span aria-hidden="true">·</span>
                  <span className="shrink-0 text-emerald-300">Offline</span>
                </>
              )}
            </div>
          </div>
        </div>

        {/* Reader Contextual Toolbar Actions */}
        <div className="flex items-center gap-1 shrink-0 sm:gap-1.5">
          {/* 1-Click Session Refresh */}
          <button
            type="button"
            onClick={handleRefreshSession}
            aria-label="Sitzung erneuern"
            title="Sitzung erneuern & fehlgeschlagene Seiten neu laden"
            className="flex h-9 w-9 items-center justify-center rounded-xl border border-[#272140] bg-[#141124] text-[#D2CCE8] transition-colors hover:border-[#493E78] hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
          </button>

          {/* Autoscroll Play/Pause (when QoL 3 is enabled) */}
          {settings.autoscrollFeatureEnabled && (
            <button
              type="button"
              onClick={() => {
                if (settings.readingMode !== 'webtoon') {
                  onUpdateSettings({ readingMode: 'webtoon' });
                }
                onSetAutoscrolling(!isAutoscrolling);
              }}
              aria-label={isAutoscrolling ? 'Autoscroll pausieren' : 'Autoscroll starten'}
              title={isAutoscrolling ? 'Autoscroll pausieren' : 'Autoscroll starten'}
              className={`flex h-9 w-9 items-center justify-center rounded-xl border transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10 ${
                isAutoscrolling
                  ? 'border-[#BB86FC] bg-[#7C4DFF] text-white'
                  : 'border-[#272140] bg-[#141124] text-[#D2CCE8] hover:border-[#493E78] hover:text-white'
              }`}
            >
              {isAutoscrolling ? (
                <Pause className="h-4 w-4" aria-hidden="true" />
              ) : (
                <Play className="h-4 w-4" aria-hidden="true" />
              )}
            </button>
          )}

          {/* Offline Download Button */}
          <button
            type="button"
            disabled={isDownloading}
            onClick={onDownloadChapter}
            aria-label={isDownloaded ? 'Kapitel offline gespeichert' : 'Kapitel herunterladen'}
            title={isDownloaded ? 'Kapitel offline gespeichert' : 'Für Offline-Lesen herunterladen'}
            className={`flex h-9 w-9 items-center justify-center rounded-xl border transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10 ${
              isDownloaded
                ? 'border-emerald-700/70 bg-emerald-950/50 text-emerald-300'
                : 'border-[#272140] bg-[#141124] text-[#D2CCE8] hover:border-[#493E78] hover:text-white'
            }`}
          >
            {isDownloaded ? (
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
            ) : (
              <Download className="h-4 w-4" aria-hidden="true" />
            )}
          </button>

          {/* Bookmark Toggle (QoL 1) */}
          <button
            type="button"
            onClick={onToggleBookmark}
            aria-pressed={isBookmarked}
            aria-label={isBookmarked ? 'Lesezeichen entfernen' : 'Lesezeichen setzen'}
            title={isBookmarked ? 'Lesezeichen entfernen' : 'Lesezeichen setzen'}
            className={`flex h-9 w-9 items-center justify-center rounded-xl border transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10 ${
              isBookmarked
                ? 'border-[#BB86FC] bg-[#2B1F54] text-[#E5DCFF]'
                : 'border-[#272140] bg-[#141124] text-[#D2CCE8] hover:border-[#493E78] hover:text-white'
            }`}
          >
            {isBookmarked ? (
              <BookmarkCheck className="h-4 w-4" aria-hidden="true" />
            ) : (
              <Bookmark className="h-4 w-4" aria-hidden="true" />
            )}
          </button>

          {/* Settings Sheet Trigger (QoL 2/3/4) */}
          <button
            type="button"
            onClick={onOpenSettings}
            aria-label="Reader-Einstellungen öffnen"
            title="Helligkeit, Sepia, Autoscroll & Pre-Fetching"
            className="flex h-9 w-9 items-center justify-center rounded-xl border border-[#272140] bg-[#141124] text-[#BB86FC] transition-colors hover:border-[#493E78] hover:bg-[#1E1938] focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:h-10 sm:w-10"
          >
            <SlidersHorizontal className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* Main AMOLED Reading Viewport */}
      {settings.readingMode === 'webtoon' ? (
        <div
          ref={webtoonContainerRef}
          onScroll={handleWebtoonScroll}
          className="flex-1 overflow-y-auto bg-black"
        >
          <div className="mx-auto flex w-full max-w-[860px] flex-col items-center bg-black">
            {chapter.pages.map((page) => (
              <div
                key={`${chapter.id}-p-${page.index}`}
                ref={(el) => {
                  pageRefs.current[page.index] = el;
                }}
                className="w-full"
              >
                <ReaderPageItem
                  page={page}
                  chapterUrl={chapter.chapterUrl}
                  sessionVersion={sessionVersion}
                  mode="webtoon"
                  zoomScale={zoomScale}
                  onRefreshSession={handleRefreshSession}
                  onOpenBrowser={onOpenBrowserForChapter}
                  onCopyUrl={handleCopyUrl}
                  onNotify={onNotify}
                />
              </div>
            ))}

            {/* ChapterEndFooter properly honoring hasNextChapter & Pre-Fetch readiness */}
            <footer
              data-testid="chapter-end-footer"
              className="mt-6 mb-20 flex w-full max-w-xl flex-col items-center gap-3 rounded-2xl border border-[#241F3A] bg-[#110E1E] px-6 py-7 text-center"
            >
              <span className="font-mono-tabular text-xs text-[#9C95BA]">
                Ende von {chapter.chapterTitle}
              </span>

              {hasNextChapter ? (
                <>
                  <h2 className="font-display text-lg font-semibold text-white">
                    {isNextPrefetched
                      ? 'Folgekapitel vorgeladen — sofort bereit'
                      : 'Nächstes Kapitel verfügbar'}
                  </h2>
                  <p className="text-xs text-[#A39CC0]">
                    {isNextPrefetched
                      ? 'Durch Automatisches Pre-Fetching startet das nächste Kapitel ohne Ladezeit.'
                      : 'Lade das nächste Kapitel direkt im Reader.'}
                  </p>
                  <div className="mt-2 flex flex-wrap items-center justify-center gap-3">
                    {chapter.prevChapterUrl && (
                      <button
                        type="button"
                        onClick={onOpenPrevChapter}
                        className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#2D274A] bg-[#18142C] px-4 py-2 text-sm font-medium text-[#D8D3EE] whitespace-nowrap transition-colors hover:bg-[#221C3E]"
                      >
                        <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                        Vorheriges Kapitel
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={onOpenNextChapter}
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl bg-[#7C4DFF] px-5 py-2 text-sm font-semibold text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
                    >
                      Nächstes Kapitel
                      <ChevronRight className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <h2 className="font-display text-lg font-semibold text-white">
                    Neuestes Kapitel erreicht
                  </h2>
                  <p className="text-xs text-[#A39CC0]">
                    Für diese Serie wurde kein weiteres Folgekapitel erkannt.
                  </p>
                  <div className="mt-2 flex flex-wrap items-center justify-center gap-3">
                    {chapter.prevChapterUrl && (
                      <button
                        type="button"
                        onClick={onOpenPrevChapter}
                        className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#2D274A] bg-[#18142C] px-4 py-2 text-sm font-medium text-[#D8D3EE] whitespace-nowrap transition-colors hover:bg-[#221C3E]"
                      >
                        <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                        Vorheriges Kapitel
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={onBack}
                      className="inline-flex min-h-[44px] items-center gap-1.5 rounded-xl border border-[#342C57] bg-[#1B1633] px-5 py-2 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#261F47]"
                    >
                      Zur Bibliothek
                    </button>
                  </div>
                </>
              )}
            </footer>
          </div>
        </div>
      ) : (
        /* Manga Paged Mode with non-blocking Tap Zones + smooth Zoom/Pan + Swipe */
        <div
          onClick={handlePagedSurfaceClick}
          onPointerDown={handlePagedPointerDown}
          onPointerMove={handlePagedPointerMove}
          onPointerUp={handlePagedPointerUp}
          onDoubleClick={() => {
            if (zoomScale > 1.05) {
              setZoomScale(1.0);
              setPanOffset({ x: 0, y: 0 });
            } else {
              setZoomScale(2.0);
            }
          }}
          className="relative flex flex-1 touch-pan-y items-center justify-center overflow-hidden bg-black select-none"
        >
          <div
            style={{
              transform: `translate3d(${panOffset.x}px, ${panOffset.y}px, 0) scale(${zoomScale})`,
              transition: dragStateRef.current.active ? 'none' : 'transform 160ms cubic-bezier(0.16, 1, 0.3, 1)',
            }}
            className="flex w-full items-center justify-center"
          >
            <ReaderPageItem
              key={`${chapter.id}-paged-${currentPage}`}
              page={chapter.pages[currentPage] || chapter.pages[0]}
              chapterUrl={chapter.chapterUrl}
              sessionVersion={sessionVersion}
              mode="paged"
              zoomScale={zoomScale}
              onRefreshSession={handleRefreshSession}
              onOpenBrowser={onOpenBrowserForChapter}
              onCopyUrl={handleCopyUrl}
              onNotify={onNotify}
            />
          </div>
        </div>
      )}

      {/* Bottom Reader Scrubber & Zoom Bar */}
      <div
        className={`z-30 flex flex-wrap items-center justify-between gap-3 border-t border-[#1E1A30] bg-[#0B0914]/95 px-4 py-2 backdrop-blur-md transition-opacity duration-150 ${
          hudVisible ? 'opacity-100' : 'pointer-events-none opacity-0'
        }`}
      >
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={currentPage <= 0}
            onClick={() => {
              const target = Math.max(0, currentPage - 1);
              reportPage(target);
              if (settings.readingMode === 'webtoon') {
                pageRefs.current[target]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
              }
            }}
            aria-label="Vorherige Seite"
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-[#272140] bg-[#141124] text-[#D8D3EE] transition-colors hover:bg-[#1E1938] disabled:opacity-40"
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
          </button>

          <input
            type="range"
            min={0}
            max={Math.max(0, totalPages - 1)}
            value={currentPage}
            aria-label="Zur Seite springen"
            onChange={(e) => {
              const idx = Number(e.target.value);
              reportPage(idx);
              if (settings.readingMode === 'webtoon') {
                pageRefs.current[idx]?.scrollIntoView({ behavior: 'auto', block: 'start' });
              }
            }}
            className="h-2 w-28 cursor-pointer accent-[#BB86FC] sm:w-48"
          />

          <button
            type="button"
            disabled={currentPage >= totalPages - 1}
            onClick={() => {
              const target = Math.min(totalPages - 1, currentPage + 1);
              reportPage(target);
              if (settings.readingMode === 'webtoon') {
                pageRefs.current[target]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
              }
            }}
            aria-label="Nächste Seite"
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-[#272140] bg-[#141124] text-[#D8D3EE] transition-colors hover:bg-[#1E1938] disabled:opacity-40"
          >
            <ChevronRight className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>

        <div className="flex items-center gap-2">
          {hasNextChapter && currentPage >= totalPages - 1 && settings.readingMode === 'paged' && (
            <button
              type="button"
              onClick={onOpenNextChapter}
              className="inline-flex h-9 items-center gap-1 rounded-lg bg-[#7C4DFF] px-3 text-xs font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
            >
              {isNextPrefetched ? 'Nächstes Kap. (bereit)' : 'Nächstes Kapitel'}
              <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          )}

          <div className="flex items-center gap-1 rounded-lg border border-[#272140] bg-[#141124] p-0.5">
            <button
              type="button"
              onClick={() => adjustZoom(-0.25)}
              disabled={zoomScale <= 1.0}
              aria-label="Verkleinern"
              className="flex h-8 w-8 items-center justify-center rounded-md text-[#CFCBE2] hover:bg-white/10 disabled:opacity-40"
            >
              <ZoomOut className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
            <span className="px-1.5 font-mono-tabular text-xs text-[#D8D3EE]">
              {Math.round(zoomScale * 100)}%
            </span>
            <button
              type="button"
              onClick={() => adjustZoom(0.25)}
              disabled={zoomScale >= 4.0}
              aria-label="Vergrößern"
              className="flex h-8 w-8 items-center justify-center rounded-md text-[#CFCBE2] hover:bg-white/10 disabled:opacity-40"
            >
              <ZoomIn className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>

      {/* Floating-Pill Autoscroll Controller (QoL 3) */}
      {settings.autoscrollFeatureEnabled && isAutoscrolling && (
        <div
          data-testid="autoscroll-floating-pill"
          className="fixed bottom-16 left-1/2 z-40 flex -translate-x-1/2 items-center gap-2 rounded-full border border-[#3C3366] bg-[#151126]/95 px-3 py-1.5 shadow-2xl backdrop-blur-md"
        >
          <button
            type="button"
            onClick={() => onSetAutoscrolling(!isAutoscrolling)}
            aria-label="Autoscroll pausieren"
            className="inline-flex h-9 items-center gap-1.5 rounded-full bg-[#7C4DFF] px-3.5 text-xs font-semibold text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
          >
            <Pause className="h-3.5 w-3.5" aria-hidden="true" />
            Pause
          </button>

          <button
            type="button"
            onClick={cycleAutoscrollSpeed}
            aria-label={`Geschwindigkeit umschalten (aktuell ${settings.autoscrollSpeed.toFixed(1)}x)`}
            className="inline-flex h-9 items-center gap-1.5 rounded-full border border-[#342B59] bg-[#1F1A38] px-3.5 font-mono-tabular text-xs font-medium text-[#E5DCFF] whitespace-nowrap transition-colors hover:bg-[#2A234B]"
          >
            <FastForward className="h-3.5 w-3.5 text-[#BB86FC]" aria-hidden="true" />
            {settings.autoscrollSpeed.toFixed(1)}x
          </button>

          <button
            type="button"
            onClick={() => onSetAutoscrolling(false)}
            aria-label="Autoscroll beenden"
            className="flex h-9 w-9 items-center justify-center rounded-full bg-white/5 text-[#CFCBE2] transition-colors hover:bg-rose-500/25 hover:text-white"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      )}

      {/* QoL 2: Software-Dimmer Overlay (pointer-events-none so gestures stay 100% responsive) */}
      {settings.dimmerEnabled && settings.dimmerStrength > 0 && (
        <div
          data-testid="reader-dimmer-overlay"
          aria-hidden="true"
          className="pointer-events-none fixed inset-0 z-40 bg-black transition-opacity duration-150"
          style={{ opacity: Math.min(0.82, settings.dimmerStrength / 100) }}
        />
      )}

      {/* QoL 2: Blaulichtfilter / Sepia Warm Overlay */}
      {settings.blueLightFilterEnabled && settings.blueLightStrength > 0 && (
        <div
          data-testid="reader-sepia-overlay"
          aria-hidden="true"
          className="pointer-events-none fixed inset-0 z-40 mix-blend-multiply transition-opacity duration-150"
          style={{
            backgroundColor: '#FF9E2C',
            opacity: Math.min(0.55, (settings.blueLightStrength / 100) * 0.65),
          }}
        />
      )}
    </div>
  );
}
