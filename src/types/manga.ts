export type MainTab = 'library' | 'browser' | 'reader' | 'chat';
export type LibrarySubTab = 'bookmarks' | 'history' | 'downloads';
export type ReadingMode = 'webtoon' | 'paged';
export type ReadingDirection = 'ltr' | 'rtl';
export type AutoscrollSpeed = 0.5 | 1.0 | 1.5 | 2.0 | 3.0;

export type DiagnosticKind =
  | 'HTTP_404'
  | 'HTTP_403'
  | 'HTML_CHALLENGE'
  | 'HTTP_429'
  | 'HTTP_5XX'
  | 'TIMEOUT'
  | 'DECODE_ERROR'
  | 'NETWORK_ERROR';

export interface ImageDiagnostic {
  kind: DiagnosticKind;
  httpStatus?: number;
  receivedContentType?: string;
  title: string;
  description: string;
  hint: string;
  severity: 'crimson' | 'amber' | 'yellow' | 'slate';
  shouldAutoRetry: boolean;
  suggestCacheBypass: boolean;
  suggestSessionRefresh: boolean;
  suggestOpenBrowser: boolean;
}

export interface ChapterPage {
  index: number;
  url: string;
  displayUrl: string;
  fallbackAssetUrl?: string;
  simError?: '404' | '403_html' | '429' | '503' | 'null_bitmap' | 'xml_challenge';
  label?: string;
}

export interface MangaChapter {
  id: string;
  seriesTitle: string;
  chapterTitle: string;
  chapterNumber: number | null;
  chapterUrl: string;
  coverUrl: string;
  sourceName: string;
  pages: ChapterPage[];
  nextChapterUrl: string | null;
  prevChapterUrl: string | null;
  defaultMode: ReadingMode;
  isOffline?: boolean;
  isDiagnosticDemo?: boolean;
}

export interface LibraryEntry {
  id: string;
  seriesTitle: string;
  chapterTitle: string;
  chapterUrl: string;
  coverUrl: string;
  sourceName: string;
  lastReadPage: number; // 0-indexed
  totalPages: number;
  updatedAt: number;
  isOffline?: boolean;
}

export interface OfflineDownloadRecord {
  folderId: string;
  chapter: MangaChapter;
  downloadedAt: number;
  completedPages: number;
  totalPages: number;
  failedPages: number;
  status: 'completed' | 'partial' | 'downloading';
}

export interface BrowserTab {
  id: string;
  title: string;
  url: string;
  history: string[];
  historyIndex: number;
  lastExtractedChapterUrl?: string;
  lastReadPage?: number;
}

export interface ReaderSettings {
  // QoL 1: Automatisches Verlaufs- & Lesezeichen-Tracking
  historyTrackingEnabled: boolean;
  // QoL 2: Software-Dimmer & Blaulichtfilter / Sepia
  dimmerEnabled: boolean;
  dimmerStrength: number; // 0..80 (%)
  blueLightFilterEnabled: boolean;
  blueLightStrength: number; // 10..80 (%)
  // QoL 3: Autoscroll / Freihändiges Lesen
  autoscrollFeatureEnabled: boolean;
  autoscrollSpeed: AutoscrollSpeed;
  // QoL 4: Automatisches Pre-Fetching
  prefetchEnabled: boolean;
  // Reader-Anzeige
  readingMode: ReadingMode;
  readingDirection: ReadingDirection;
  keepScreenOn: boolean;
  // Konfigurierbares Bild-Cache-Limit in MB (Standard: 4096 MB = 4 GB)
  cacheLimitMb: number;
}

export interface ToastMessage {
  id: string;
  message: string;
  tone: 'info' | 'success' | 'warning' | 'error';
}

export interface PrefetchedChapterState {
  forChapterUrl: string;
  status: 'idle' | 'loading' | 'ready' | 'unavailable';
  chapter: MangaChapter | null;
}

export type ChatModelId = 'gemini-4-hydrogen' | 'gemini-3.1-flash-lite';
export type ChatPersonaId = 'manga-guide' | 'reader-tech' | 'story-analyst';

export interface ChatMessage {
  id: string;
  role: 'user' | 'model';
  text: string;
  createdAt: number;
  model?: ChatModelId;
  isError?: boolean;
}
