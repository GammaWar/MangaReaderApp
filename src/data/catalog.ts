import type { BrowserTab, LibraryEntry, MangaChapter, ReaderSettings } from '../types/manga';

export const APP_ICON_URL = '/src/assets/images/app_icon_manga_1795260244646.jpg';
export const COVER_KUROGANE = '/src/assets/images/cover_kurogane_1795260244125.jpg';
export const COVER_STELLAR = '/src/assets/images/cover_stellar_drift_1795260245712.jpg';
export const MANGA_PANEL_1 = '/src/assets/images/manga_panel_01_1795260245604.jpg';
export const MANGA_PANEL_2 = '/src/assets/images/manga_panel_02_1795260245956.jpg';
export const WEBTOON_PANEL_1 = '/src/assets/images/webtoon_panel_01_1795260245598.jpg';

export const DEFAULT_READER_SETTINGS: ReaderSettings = {
  historyTrackingEnabled: true,
  dimmerEnabled: false,
  dimmerStrength: 28,
  blueLightFilterEnabled: false,
  blueLightStrength: 35,
  autoscrollFeatureEnabled: true,
  autoscrollSpeed: 1.0,
  prefetchEnabled: true,
  readingMode: 'webtoon',
  readingDirection: 'rtl',
  keepScreenOn: true,
  cacheLimitMb: 4096,
};

export const CATALOG_CHAPTERS: MangaChapter[] = [
  {
    id: 'kurogane-ch-12',
    seriesTitle: 'Kurogane: Klingen im Regen',
    chapterTitle: 'Kapitel 12 — Brücke im Bambuswald',
    chapterNumber: 12,
    chapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-12',
    coverUrl: COVER_KUROGANE,
    sourceName: 'Seinen Katalog',
    defaultMode: 'paged',
    prevChapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-11',
    nextChapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-13',
    pages: [
      { index: 0, url: COVER_KUROGANE, displayUrl: COVER_KUROGANE, label: 'Titelblatt' },
      { index: 1, url: MANGA_PANEL_1, displayUrl: MANGA_PANEL_1, label: 'Seite 2' },
      { index: 2, url: MANGA_PANEL_2, displayUrl: MANGA_PANEL_2, label: 'Seite 3' },
      { index: 3, url: MANGA_PANEL_1, displayUrl: MANGA_PANEL_1, label: 'Seite 4' },
      { index: 4, url: MANGA_PANEL_2, displayUrl: MANGA_PANEL_2, label: 'Seite 5' },
      { index: 5, url: COVER_KUROGANE, displayUrl: COVER_KUROGANE, label: 'Seite 6' },
    ],
  },
  {
    id: 'kurogane-ch-13',
    seriesTitle: 'Kurogane: Klingen im Regen',
    chapterTitle: 'Kapitel 13 — Funken im Tempelhof',
    chapterNumber: 13,
    chapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-13',
    coverUrl: COVER_KUROGANE,
    sourceName: 'Seinen Katalog',
    defaultMode: 'paged',
    prevChapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-12',
    nextChapterUrl: null,
    pages: [
      { index: 0, url: MANGA_PANEL_2, displayUrl: MANGA_PANEL_2, label: 'Seite 1' },
      { index: 1, url: MANGA_PANEL_1, displayUrl: MANGA_PANEL_1, label: 'Seite 2' },
      { index: 2, url: MANGA_PANEL_2, displayUrl: MANGA_PANEL_2, label: 'Seite 3' },
      { index: 3, url: COVER_KUROGANE, displayUrl: COVER_KUROGANE, label: 'Seite 4' },
    ],
  },
  {
    id: 'kurogane-ch-11',
    seriesTitle: 'Kurogane: Klingen im Regen',
    chapterTitle: 'Kapitel 11 — Nacht am Steintor',
    chapterNumber: 11,
    chapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-11',
    coverUrl: COVER_KUROGANE,
    sourceName: 'Seinen Katalog',
    defaultMode: 'paged',
    prevChapterUrl: null,
    nextChapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-12',
    pages: [
      { index: 0, url: COVER_KUROGANE, displayUrl: COVER_KUROGANE, label: 'Seite 1' },
      { index: 1, url: MANGA_PANEL_1, displayUrl: MANGA_PANEL_1, label: 'Seite 2' },
      { index: 2, url: MANGA_PANEL_2, displayUrl: MANGA_PANEL_2, label: 'Seite 3' },
    ],
  },
  {
    id: 'stellar-drift-ep-24',
    seriesTitle: 'Stellar Drift: Orbital Kurier',
    chapterTitle: 'Episode 24 — Abstieg über Sektor 9',
    chapterNumber: 24,
    chapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-24',
    coverUrl: COVER_STELLAR,
    sourceName: 'Webtoon Katalog',
    defaultMode: 'webtoon',
    prevChapterUrl: null,
    nextChapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-25',
    pages: [
      { index: 0, url: COVER_STELLAR, displayUrl: COVER_STELLAR, label: 'Titelpanel' },
      { index: 1, url: WEBTOON_PANEL_1, displayUrl: WEBTOON_PANEL_1, label: 'Szene 2' },
      { index: 2, url: WEBTOON_PANEL_1, displayUrl: WEBTOON_PANEL_1, label: 'Szene 3' },
      { index: 3, url: COVER_STELLAR, displayUrl: COVER_STELLAR, label: 'Szene 4' },
      { index: 4, url: WEBTOON_PANEL_1, displayUrl: WEBTOON_PANEL_1, label: 'Szene 5' },
    ],
  },
  {
    id: 'stellar-drift-ep-25',
    seriesTitle: 'Stellar Drift: Orbital Kurier',
    chapterTitle: 'Episode 25 — Glasbrücke im Zwielicht',
    chapterNumber: 25,
    chapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-25',
    coverUrl: COVER_STELLAR,
    sourceName: 'Webtoon Katalog',
    defaultMode: 'webtoon',
    prevChapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-24',
    nextChapterUrl: null,
    pages: [
      { index: 0, url: WEBTOON_PANEL_1, displayUrl: WEBTOON_PANEL_1, label: 'Szene 1' },
      { index: 1, url: COVER_STELLAR, displayUrl: COVER_STELLAR, label: 'Szene 2' },
      { index: 2, url: WEBTOON_PANEL_1, displayUrl: WEBTOON_PANEL_1, label: 'Szene 3' },
    ],
  },
  {
    id: 'cdn-diagnose-lab',
    seriesTitle: 'CDN-Diagnose-Test: ManhuaTop Simulation',
    chapterTitle: 'Kapitel 88 — Fehlerdiagnose & Recovery',
    chapterNumber: 88,
    chapterUrl: 'https://manhuatop.example.com/manga/diagnose-test/chapter-88',
    coverUrl: COVER_STELLAR,
    sourceName: 'Diagnose-Labor',
    defaultMode: 'webtoon',
    prevChapterUrl: null,
    nextChapterUrl: null,
    isDiagnosticDemo: true,
    pages: [
      {
        index: 0,
        url: COVER_STELLAR,
        displayUrl: 'https://manhuatop.example.com/uploads/cover-88.jpg',
        label: 'Cover (Hauptdomain OK)',
      },
      {
        index: 1,
        url: '/api/demo-page?simError=403_html&asset=manga1',
        displayUrl: 'https://cdn.manhuatop.example.com/ch88/page-02.webp',
        fallbackAssetUrl: MANGA_PANEL_1,
        simError: '403_html',
        label: 'Seite 2 (HTTP 403 / HTML-Challenge)',
      },
      {
        index: 2,
        url: '/api/demo-page?simError=429&asset=webtoon1',
        displayUrl: 'https://cdn.manhuatop.example.com/ch88/page-03.webp',
        fallbackAssetUrl: WEBTOON_PANEL_1,
        simError: '429',
        label: 'Seite 3 (HTTP 429 Rate-Limit + Auto-Retry)',
      },
      {
        index: 3,
        url: '/api/demo-page?simError=404&asset=manga2',
        displayUrl: 'https://cdn.manhuatop.example.com/ch88/page-04-missing.jpg',
        fallbackAssetUrl: MANGA_PANEL_2,
        simError: '404',
        label: 'Seite 4 (HTTP 404 Datei fehlt)',
      },
      {
        index: 4,
        url: '/api/demo-page?simError=503&asset=manga2',
        displayUrl: 'https://cdn.manhuatop.example.com/ch88/page-05.webp',
        fallbackAssetUrl: MANGA_PANEL_2,
        simError: '503',
        label: 'Seite 5 (HTTP 503 Server gestört)',
      },
    ],
  },
];

export function findCatalogChapterByUrl(url: string): MangaChapter | undefined {
  const normalized = url.trim().replace(/\/+$/, '');
  return CATALOG_CHAPTERS.find((c) => c.chapterUrl.replace(/\/+$/, '') === normalized);
}

export const INITIAL_BOOKMARKS: LibraryEntry[] = [
  {
    id: 'kurogane-ch-12',
    seriesTitle: 'Kurogane: Klingen im Regen',
    chapterTitle: 'Kapitel 12 — Brücke im Bambuswald',
    chapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-12',
    coverUrl: COVER_KUROGANE,
    sourceName: 'Seinen Katalog',
    lastReadPage: 2, // Seite 3 von 6 -> proves "Weiterlesen" jumps to page 3, not page 1!
    totalPages: 6,
    updatedAt: Date.now() - 1000 * 60 * 38,
  },
  {
    id: 'stellar-drift-ep-24',
    seriesTitle: 'Stellar Drift: Orbital Kurier',
    chapterTitle: 'Episode 24 — Abstieg über Sektor 9',
    chapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-24',
    coverUrl: COVER_STELLAR,
    sourceName: 'Webtoon Katalog',
    lastReadPage: 1, // Seite 2 von 5
    totalPages: 5,
    updatedAt: Date.now() - 1000 * 60 * 145,
  },
];

export const INITIAL_HISTORY: LibraryEntry[] = [
  {
    id: 'kurogane-ch-12',
    seriesTitle: 'Kurogane: Klingen im Regen',
    chapterTitle: 'Kapitel 12 — Brücke im Bambuswald',
    chapterUrl: 'https://katalog.mangareader.local/kurogane/chapter-12',
    coverUrl: COVER_KUROGANE,
    sourceName: 'Seinen Katalog',
    lastReadPage: 2,
    totalPages: 6,
    updatedAt: Date.now() - 1000 * 60 * 38,
  },
  {
    id: 'stellar-drift-ep-24',
    seriesTitle: 'Stellar Drift: Orbital Kurier',
    chapterTitle: 'Episode 24 — Abstieg über Sektor 9',
    chapterUrl: 'https://katalog.mangareader.local/stellar-drift/episode-24',
    coverUrl: COVER_STELLAR,
    sourceName: 'Webtoon Katalog',
    lastReadPage: 1,
    totalPages: 5,
    updatedAt: Date.now() - 1000 * 60 * 145,
  },
];

export const INITIAL_BROWSER_TABS: BrowserTab[] = [
  {
    id: 'tab-kurogane',
    title: 'Kurogane — Kap. 12',
    url: 'https://katalog.mangareader.local/kurogane/chapter-12',
    history: ['https://katalog.mangareader.local/kurogane/chapter-12'],
    historyIndex: 0,
    lastReadPage: 2,
  },
  {
    id: 'tab-stellar',
    title: 'Stellar Drift — Ep. 24',
    url: 'https://katalog.mangareader.local/stellar-drift/episode-24',
    history: ['https://katalog.mangareader.local/stellar-drift/episode-24'],
    historyIndex: 0,
    lastReadPage: 1,
  },
  {
    id: 'tab-diagnose',
    title: 'ManhuaTop Diagnose',
    url: 'https://manhuatop.example.com/manga/diagnose-test/chapter-88',
    history: ['https://manhuatop.example.com/manga/diagnose-test/chapter-88'],
    historyIndex: 0,
    lastReadPage: 0,
  },
];
