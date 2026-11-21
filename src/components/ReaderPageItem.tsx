import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChapterPage, ImageDiagnostic } from '../types/manga';
import {
  classifyImageError,
  getBackoffDelayMs,
  isHtmlPayload,
  isValidImageContentType,
  MAX_AUTO_RETRIES,
} from '../utils/imageDiagnostics';
import { isSafeClientUrl } from '../utils/urlSafety';
import { CacheManager } from '../utils/cacheManager';
import { ImageErrorView } from './ImageErrorView';

interface ReaderPageItemProps {
  page: ChapterPage;
  chapterUrl: string;
  sessionVersion: number;
  mode: 'webtoon' | 'paged';
  zoomScale: number;
  onRefreshSession: () => void;
  onOpenBrowser: () => void;
  onCopyUrl: (url: string) => void;
  onNotify: (message: string, tone?: 'info' | 'success' | 'warning' | 'error') => void;
}

type LoadStatus = 'loading' | 'loaded' | 'error';

export function ReaderPageItem({
  page,
  chapterUrl,
  sessionVersion,
  mode,
  zoomScale,
  onRefreshSession,
  onOpenBrowser,
  onCopyUrl,
  onNotify,
}: ReaderPageItemProps) {
  const [status, setStatus] = useState<LoadStatus>('loading');
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [diagnostic, setDiagnostic] = useState<ImageDiagnostic | null>(null);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [isAutoRetrying, setIsAutoRetrying] = useState(false);
  const [manualRetryNonce, setManualRetryNonce] = useState(0);

  const objectUrlRef = useRef<string | null>(null);
  const onNotifyRef = useRef(onNotify);
  onNotifyRef.current = onNotify;

  const isLocalStaticAsset = page.url.startsWith('/src/assets/');

  const buildFetchUrl = useCallback(
    (attempt: number, bypassCache: boolean): string => {
      if (!page.url.startsWith('/api/')) return page.url;
      const sep = page.url.includes('?') ? '&' : '?';
      const params = new URLSearchParams();
      if (attempt > 0) params.set('attempt', String(attempt));
      if (bypassCache) params.set('bypassCache', '1');
      if (sessionVersion > 0) params.set('sessionToken', String(sessionVersion));
      const qs = params.toString();
      return qs ? `${page.url}${sep}${qs}` : page.url;
    },
    [page.url, sessionVersion]
  );

  useEffect(() => {
    // Cleanup previous blob URL if any
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }

    if (!isSafeClientUrl(page.url)) {
      setDiagnostic(
        classifyImageError({
          httpStatus: 400,
          errorMessage: 'Unsichere Bild-URL blockiert.',
        })
      );
      setStatus('error');
      return;
    }

    // Fast path for static bundled catalog assets (flicker-free direct rendering)
    if (isLocalStaticAsset) {
      setObjectUrl(page.url);
      setDiagnostic(null);
      setStatus('loaded');
      return;
    }

    let cancelled = false;
    const controller = new AbortController();

    async function fetchWithBackoff() {
      const bypass = manualRetryNonce > 0 || sessionVersion > 0;
      setStatus('loading');
      setDiagnostic(null);
      setIsAutoRetrying(false);

      for (let attempt = 0; attempt <= MAX_AUTO_RETRIES; attempt++) {
        if (cancelled) return;
        setRetryAttempt(attempt);

        try {
          const targetUrl = buildFetchUrl(attempt, bypass);
          const response = await fetch(targetUrl, {
            signal: controller.signal,
            cache: bypass ? 'reload' : 'default',
          });

          if (!response.ok) {
            const diagHeader = response.headers.get('X-Manga-Diagnostic');
            const classified = classifyImageError({
              httpStatus: response.status,
              diagnosticHeader: diagHeader,
            });

            // Only auto-retry on 429 / 5xx / timeout — never loop on 404 or 403/HTML-Challenge
            if (classified.shouldAutoRetry && attempt < MAX_AUTO_RETRIES) {
              const delay = getBackoffDelayMs(attempt);
              setIsAutoRetrying(true);
              onNotifyRef.current(
                `Seite ${page.index + 1}: ${classified.httpStatus ? `HTTP ${classified.httpStatus}` : 'Fehler'} — Auto-Retry ${attempt + 1}/${MAX_AUTO_RETRIES} in ${delay} ms…`,
                'warning'
              );
              await new Promise((r) => setTimeout(r, delay));
              continue;
            }

            if (!cancelled) {
              setIsAutoRetrying(false);
              setDiagnostic(classified);
              setStatus('error');
            }
            return;
          }

          const contentType = response.headers.get('content-type');
          const blob = await response.blob();
          const headBytes = new Uint8Array(await blob.slice(0, 256).arrayBuffer());

          if (!isValidImageContentType(contentType) || isHtmlPayload(headBytes)) {
            if (!cancelled) {
              setIsAutoRetrying(false);
              setDiagnostic(
                classifyImageError({
                  httpStatus: 403,
                  diagnosticHeader: 'HTML_CHALLENGE',
                })
              );
              setStatus('error');
            }
            return;
          }

          if (cancelled) return;
          void CacheManager.recordCachedImage(
            page.url,
            blob.size,
            chapterUrl,
            (contentType || 'image/jpeg').split(';')[0].trim(),
            blob
          );
          const nextUrl = URL.createObjectURL(blob);
          objectUrlRef.current = nextUrl;
          setObjectUrl(nextUrl);
          setIsAutoRetrying(false);
          setDiagnostic(null);
          setStatus('loaded');

          if (attempt > 0) {
            onNotifyRef.current(
              `Seite ${page.index + 1} nach Retry ${attempt}/${MAX_AUTO_RETRIES} erfolgreich geladen.`,
              'success'
            );
          }
          return;
        } catch (err) {
          if (cancelled) return;
          const msg = err instanceof Error ? err.message : 'Netzwerkfehler';
          const classified = classifyImageError({ errorMessage: msg });

          if (classified.shouldAutoRetry && attempt < MAX_AUTO_RETRIES) {
            const delay = getBackoffDelayMs(attempt);
            setIsAutoRetrying(true);
            onNotifyRef.current(
              `Seite ${page.index + 1}: Verbindungsproblem — Retry ${attempt + 1}/${MAX_AUTO_RETRIES}…`,
              'warning'
            );
            await new Promise((r) => setTimeout(r, delay));
            continue;
          }

          setIsAutoRetrying(false);
          setDiagnostic(classified);
          setStatus('error');
          return;
        }
      }
    }

    void fetchWithBackoff();

    return () => {
      cancelled = true;
      controller.abort();
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current);
        objectUrlRef.current = null;
      }
    };
  }, [buildFetchUrl, chapterUrl, isLocalStaticAsset, manualRetryNonce, page.index, page.url, sessionVersion]);

  if (status === 'error' && diagnostic) {
    return (
      <div className="flex min-h-[360px] w-full items-center justify-center bg-black px-4 py-6">
        <ImageErrorView
          pageIndex={page.index}
          diagnostic={diagnostic}
          imageUrl={page.displayUrl}
          retryAttempt={retryAttempt}
          isAutoRetrying={isAutoRetrying}
          onRetry={() => setManualRetryNonce((n) => n + 1)}
          onRefreshSession={onRefreshSession}
          onOpenBrowser={onOpenBrowser}
          onCopyUrl={onCopyUrl}
        />
      </div>
    );
  }

  if (status === 'loading' || !objectUrl) {
    // AMOLED pure black placeholder with subtle thin spinner — prevents bright flash / layout flicker
    return (
      <div
        className="flex min-h-[520px] w-full flex-col items-center justify-center gap-3 bg-black"
        aria-label={`Seite ${page.index + 1} wird geladen`}
      >
        <div className="h-7 w-7 animate-spin rounded-full border-2 border-[#2A2444] border-t-[#BB86FC]" />
        <span className="font-mono-tabular text-xs text-[#8D86A8]">
          {isAutoRetrying
            ? `Seite ${page.index + 1} · Retry ${retryAttempt}/${MAX_AUTO_RETRIES}…`
            : `Seite ${page.index + 1} wird geladen…`}
        </span>
      </div>
    );
  }

  return (
    <div
      className={`relative flex w-full justify-center bg-black ${
        mode === 'paged' ? 'min-h-[70vh] items-center' : ''
      }`}
    >
      <img
        src={objectUrl}
        alt={page.label || `Seite ${page.index + 1}`}
        referrerPolicy="no-referrer"
        draggable={false}
        onError={() => {
          setDiagnostic(
            classifyImageError({
              errorMessage: 'BitmapFactory returned a null bitmap. Could not decode image data',
            })
          );
          setStatus('error');
        }}
        style={
          mode === 'webtoon' && zoomScale !== 1
            ? { width: `${Math.min(100 * zoomScale, 240)}%`, maxWidth: 'none' }
            : undefined
        }
        className={
          mode === 'webtoon'
            ? 'block h-auto w-full max-w-[820px] select-none bg-black object-contain'
            : 'block max-h-[82vh] w-auto max-w-full select-none bg-black object-contain'
        }
      />
    </div>
  );
}
