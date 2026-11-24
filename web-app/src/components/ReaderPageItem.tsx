import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChapterPage, ImageDiagnostic } from '../types/manga';
import {
  classifyImageError,
  diagnoseBitmapDecodeFailure,
  getBackoffDelayMs,
  MAX_AUTO_RETRIES,
  verifyReceivedImageBuffer,
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
  onImageCacheBypass?: (pageIndex: number, imageUrl: string) => void;
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
  onImageCacheBypass,
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
  const [cacheBypassState, setCacheBypassState] = useState<{ nonce: number; ts: number }>({
    nonce: 0,
    ts: 0,
  });

  const objectUrlRef = useRef<string | null>(null);
  const pendingBypassSuccessToastRef = useRef(false);
  const lastReceivedBufferRef = useRef<{
    contentType: string | null;
    headBytes: Uint8Array | null;
    httpStatus?: number;
  }>({
    contentType: null,
    headBytes: null,
  });
  const onNotifyRef = useRef(onNotify);
  onNotifyRef.current = onNotify;

  const isLocalStaticAsset = page.url.startsWith('/src/assets/');

  const buildFetchUrl = useCallback(
    (attempt: number, forceBypassCache: boolean, cacheBustTs: number): string => {
      if (!page.url.startsWith('/api/')) {
        if (forceBypassCache && cacheBustTs > 0) {
          const sep = page.url.includes('?') ? '&' : '?';
          return `${page.url}${sep}cb=${cacheBustTs}`;
        }
        return page.url;
      }
      const sep = page.url.includes('?') ? '&' : '?';
      const params = new URLSearchParams();
      if (attempt > 0) params.set('attempt', String(attempt));
      if (forceBypassCache) {
        params.set('bypassCache', '1');
        if (cacheBustTs > 0) params.set('cb', String(cacheBustTs));
      }
      if (sessionVersion > 0) params.set('sessionToken', String(sessionVersion));
      const qs = params.toString();
      return qs ? `${page.url}${sep}${qs}` : page.url;
    },
    [page.url, sessionVersion]
  );

  const handleCacheBypassForThisImage = useCallback(async () => {
    await CacheManager.invalidateUrl(page.url);
    pendingBypassSuccessToastRef.current = true;
    onImageCacheBypass?.(page.index, page.displayUrl);
    setCacheBypassState((prev) => ({
      nonce: prev.nonce + 1,
      ts: Date.now(),
    }));
  }, [onImageCacheBypass, page.displayUrl, page.index, page.url]);

  useEffect(() => {
    // Cleanup previous blob URL if any
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }

    lastReceivedBufferRef.current = { contentType: null, headBytes: null };

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
      const staticUrl =
        cacheBypassState.nonce > 0
          ? `${page.url}${page.url.includes('?') ? '&' : '?'}cb=${cacheBypassState.ts}`
          : page.url;
      setObjectUrl(staticUrl);
      setDiagnostic(null);
      setStatus('loaded');
      return;
    }

    let cancelled = false;
    const controller = new AbortController();

    async function fetchWithBackoff() {
      const explicitCacheBypass = cacheBypassState.nonce > 0;
      const bypass = explicitCacheBypass || sessionVersion > 0;
      setStatus('loading');
      setDiagnostic(null);
      setIsAutoRetrying(false);

      for (let attempt = 0; attempt <= MAX_AUTO_RETRIES; attempt++) {
        if (cancelled) return;
        setRetryAttempt(attempt);

        try {
          const targetUrl = buildFetchUrl(
            attempt,
            bypass,
            explicitCacheBypass ? cacheBypassState.ts : 0
          );
          const response = await fetch(targetUrl, {
            signal: controller.signal,
            cache: explicitCacheBypass ? 'no-store' : bypass ? 'reload' : 'default',
            headers: explicitCacheBypass
              ? {
                  'Cache-Control': 'no-cache, no-store, must-revalidate',
                  Pragma: 'no-cache',
                }
              : undefined,
          });

          if (!response.ok) {
            const diagHeader = response.headers.get('X-Manga-Diagnostic');
            const receivedTypeHeader = response.headers.get('X-Manga-Received-Content-Type');
            const classified = classifyImageError({
              httpStatus: response.status,
              diagnosticHeader: diagHeader,
              receivedContentType: receivedTypeHeader,
            });

            if (classified.kind === 'HTML_CHALLENGE') {
              console.warn(
                `[MangaReader][Security/CDN Challenge] Seite ${page.index + 1} (${page.displayUrl}):`,
                {
                  httpStatus: response.status,
                  diagnosticHeader: diagHeader,
                  receivedContentType: classified.receivedContentType,
                }
              );
            }

            // Only auto-retry on 429 / 5xx / timeout — never loop on 404, 403/HTML-Challenge, or Decode errors
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
              pendingBypassSuccessToastRef.current = false;
              setIsAutoRetrying(false);
              setDiagnostic(classified);
              setStatus('error');
            }
            return;
          }

          const contentType = response.headers.get('content-type');
          const blob = await response.blob();
          const headBytes = new Uint8Array(await blob.slice(0, 256).arrayBuffer());
          lastReceivedBufferRef.current = {
            contentType,
            headBytes,
            httpStatus: response.status,
          };

          // Step 1: Verify received buffer Content-Type BEFORE decoding or caching (detects text/html & application/xml)
          const verification = verifyReceivedImageBuffer(contentType, headBytes);
          if (verification.isSecurityChallenge || !verification.isValidImageHeader) {
            const diagnosed = diagnoseBitmapDecodeFailure({
              headerContentType: contentType,
              bufferPrefix: headBytes,
              httpStatus: response.status,
            });

            if (diagnosed.kind === 'HTML_CHALLENGE') {
              console.warn(
                `[MangaReader][Security/CDN Challenge] Nicht-Bild-Antwort statt image/* empfangen (Seite ${page.index + 1}):`,
                {
                  url: page.displayUrl,
                  headerContentType: contentType,
                  detectedContentType: diagnosed.receivedContentType,
                }
              );
            }

            void CacheManager.invalidateUrl(page.url);
            if (!cancelled) {
              pendingBypassSuccessToastRef.current = false;
              setIsAutoRetrying(false);
              setDiagnostic(diagnosed);
              setStatus('error');
            }
            return;
          }

          // Step 2: Early "null bitmap" stream pre-decode check before caching or rendering <img>
          if (typeof createImageBitmap === 'function') {
            try {
              const decodedBitmap = await createImageBitmap(blob);
              decodedBitmap.close();
            } catch (decodeErr) {
              if (cancelled) return;
              const decodeMsg =
                decodeErr instanceof Error
                  ? decodeErr.message
                  : 'BitmapFactory returned a null bitmap. Could not decode image data';
              const diagnosed = diagnoseBitmapDecodeFailure({
                headerContentType: contentType,
                bufferPrefix: headBytes,
                httpStatus: response.status,
                errorMessage: `BitmapFactory returned a null bitmap: ${decodeMsg}`,
              });

              if (diagnosed.kind === 'HTML_CHALLENGE') {
                console.warn(
                  `[MangaReader][Security/CDN Challenge] Null-Bitmap nach Pufferprüfung als ${diagnosed.receivedContentType} erkannt (Seite ${page.index + 1}):`,
                  {
                    url: page.displayUrl,
                    headerContentType: contentType,
                    detectedContentType: diagnosed.receivedContentType,
                  }
                );
              } else {
                console.warn(
                  `[MangaReader][Null Bitmap] Früher Dekodierungsfehler erkannt (Seite ${page.index + 1}):`,
                  {
                    url: page.displayUrl,
                    headerContentType: contentType,
                    reason: decodeMsg,
                  }
                );
              }

              void CacheManager.invalidateUrl(page.url);
              pendingBypassSuccessToastRef.current = false;
              setIsAutoRetrying(false);
              setDiagnostic(diagnosed);
              setStatus('error');
              return;
            }
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

          pendingBypassSuccessToastRef.current = false;
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
  }, [
    buildFetchUrl,
    cacheBypassState.nonce,
    cacheBypassState.ts,
    chapterUrl,
    isLocalStaticAsset,
    manualRetryNonce,
    page.displayUrl,
    page.index,
    page.url,
    sessionVersion,
  ]);

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
          onCacheBypass={() => void handleCacheBypassForThisImage()}
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
            : cacheBypassState.nonce > 0
            ? `Seite ${page.index + 1} · Cache-Bypass aktiv…`
            : `Seite ${page.index + 1} wird geladen…`}
        </span>
      </div>
    );
  }

  return (
    <div
      data-testid={`reader-page-loaded-${page.index}`}
      className={`relative flex w-full justify-center bg-black ${
        mode === 'paged' ? 'min-h-[70vh] items-center' : ''
      }`}
    >
      <img
        src={objectUrl}
        alt={page.label || `Seite ${page.index + 1}`}
        referrerPolicy="no-referrer"
        draggable={false}
        onLoad={() => {
          if (pendingBypassSuccessToastRef.current) {
            pendingBypassSuccessToastRef.current = false;
            onNotifyRef.current(
              `Seite ${page.index + 1} über Cache-Bypass erfolgreich neu vom Server geladen.`,
              'success'
            );
          }
        }}
        onError={() => {
          pendingBypassSuccessToastRef.current = false;
          // Immediately discard the un-decodable / corrupted blob from the local cache
          void CacheManager.invalidateUrl(page.url);
          const lastBuf = lastReceivedBufferRef.current;
          const diagnosed = diagnoseBitmapDecodeFailure({
            headerContentType: lastBuf.contentType,
            bufferPrefix: lastBuf.headBytes,
            httpStatus: lastBuf.httpStatus,
            errorMessage: 'BitmapFactory returned a null bitmap. Could not decode image data',
          });
          if (diagnosed.kind === 'HTML_CHALLENGE') {
            console.warn(
              `[MangaReader][Security/CDN Challenge] <img> Decode-Fehler als ${diagnosed.receivedContentType} erkannt (Seite ${page.index + 1}):`,
              { url: page.displayUrl, detectedContentType: diagnosed.receivedContentType }
            );
          }
          setDiagnostic(diagnosed);
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
