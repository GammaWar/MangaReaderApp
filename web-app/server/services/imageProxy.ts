import {
  hasValidImageMagicBytes,
  INSPECTION_PREFIX_BYTES,
  isChallengeContentType,
  isHtmlPayload,
  isValidImageContentType,
  normalizeContentType,
  resolveSafeImageContentType,
  verifyReceivedImageBuffer,
} from '../../src/utils/imageDiagnostics.ts';
import { MAX_IMAGE_BYTES, READ_TIMEOUT_MS, SAFE_USER_AGENT } from '../config.ts';
import {
  PayloadTooLargeError,
  readResponseWithLimit,
  safeFetchWithRedirects,
  SsrfBlockedError,
  UpstreamTimeoutError,
  type SafeFetchDeps,
} from '../security/ssrf.ts';
import type { ServerCacheStore } from './cacheManager.ts';

const MULTI_LABEL_PUBLIC_SUFFIXES = new Set([
  'co.uk',
  'org.uk',
  'ac.uk',
  'gov.uk',
  'com.br',
  'com.au',
  'net.au',
  'org.au',
  'com.cn',
  'co.jp',
  'ne.jp',
  'or.jp',
  'co.kr',
  'or.kr',
  'com.tw',
  'com.mx',
  'co.nz',
  'co.za',
  'pages.dev',
  'workers.dev',
  'github.io',
  'vercel.app',
  'netlify.app',
  'cloudfront.net',
  'amazonaws.com',
]);

function getRegistrableSite(hostname: string): string {
  const parts = hostname.toLowerCase().replace(/\.+$/, '').split('.').filter(Boolean);
  if (parts.length <= 2) return parts.join('.');
  const lastTwo = parts.slice(-2).join('.');
  if (MULTI_LABEL_PUBLIC_SUFFIXES.has(lastTwo) && parts.length >= 3) {
    return parts.slice(-3).join('.');
  }
  return lastTwo;
}

export function getSecFetchSite(imageUrl: URL, refererUrl?: URL): string {
  if (!refererUrl) return 'none';
  if (imageUrl.origin === refererUrl.origin) return 'same-origin';
  return getRegistrableSite(imageUrl.hostname) === getRegistrableSite(refererUrl.hostname)
    ? 'same-site'
    : 'cross-site';
}

/**
 * Builds a deduplicated Referer fallback chain:
 * Chapter Origin -> Image Host Origin -> No Referer.
 * Avoids duplicate requests when chapter and image share the same origin.
 */
export function buildRefererTiers(imageUrl: URL, refererUrl?: URL): Array<string | null> {
  const tiers: Array<string | null> = [];
  const pushUnique = (value: string | null) => {
    if (!tiers.includes(value)) {
      tiers.push(value);
    }
  };
  if (refererUrl) pushUnique(`${refererUrl.origin}/`);
  pushUnique(`${imageUrl.origin}/`);
  pushUnique(null);
  return tiers;
}

export type ProxyImageResult =
  | {
      ok: true;
      buffer: Buffer;
      contentType: string;
      cacheStatus: 'HIT' | 'MISS' | 'REFRESHED';
    }
  | {
      ok: false;
      status: number;
      diagnostic: string;
      error: string;
      receivedContentType: string | null;
    };

export interface ImageProxyServiceDeps {
  cacheStore: ServerCacheStore;
  fetchDeps: SafeFetchDeps;
}

export function createImageProxyService(deps: ImageProxyServiceDeps) {
  const { cacheStore, fetchDeps } = deps;
  const inFlight = new Map<string, Promise<ProxyImageResult>>();

  async function fetchFromUpstream(
    imageUrl: URL,
    refererUrl: URL | undefined,
    bypassCache: boolean
  ): Promise<ProxyImageResult> {
    const cacheKey = imageUrl.toString();
    const chapterKey = refererUrl ? refererUrl.toString() : imageUrl.origin;
    const startGeneration = cacheStore.getGeneration();
    const refererTiers = buildRefererTiers(imageUrl, refererUrl);

    let lastStatus = 502;
    let lastDiagnostic = 'NETWORK_ERROR';
    let lastErrorMessage = 'Bild konnte nicht geladen werden.';
    let lastReceivedContentType: string | null = null;

    for (const tierReferer of refererTiers) {
      const headers: Record<string, string> = {
        'User-Agent': SAFE_USER_AGENT,
        // Safe Accept header (no forced image/avif to avoid Bitmap/decode failures)
        Accept: 'image/webp,image/png,image/jpeg,image/*;q=0.8',
        'Accept-Language': 'de-DE,de;q=0.9,en;q=0.8',
        'Sec-Fetch-Dest': 'image',
        'Sec-Fetch-Mode': 'no-cors',
        'Sec-Fetch-Site': getSecFetchSite(
          imageUrl,
          tierReferer ? new URL(tierReferer) : undefined
        ),
      };
      // Deliberately omit synthetic Origin header (many manga CDNs reject it)
      if (tierReferer) {
        headers['Referer'] = tierReferer;
      }

      try {
        const upstream = await safeFetchWithRedirects(imageUrl.toString(), headers, 3, fetchDeps);
        lastStatus = upstream.status;

        if (upstream.status === 404) {
          await upstream.body?.cancel().catch(() => undefined);
          return {
            ok: false,
            status: 404,
            diagnostic: 'HTTP_404',
            error: 'HTTP 404: Datei existiert nicht auf dem Server.',
            receivedContentType: null,
          };
        }

        if (upstream.status === 429) {
          await upstream.body?.cancel().catch(() => undefined);
          return {
            ok: false,
            status: 429,
            diagnostic: 'HTTP_429',
            error: 'HTTP 429: Rate-Limit überschritten.',
            receivedContentType: null,
          };
        }

        if (upstream.status === 403 || upstream.status === 401) {
          await upstream.body?.cancel().catch(() => undefined);
          lastDiagnostic = 'HTTP_403';
          lastErrorMessage = `HTTP ${upstream.status}: Zugriff verweigert.`;
          continue; // Try next Referer tier
        }

        if (!upstream.ok) {
          await upstream.body?.cancel().catch(() => undefined);
          return {
            ok: false,
            status: upstream.status,
            diagnostic: upstream.status >= 500 ? 'HTTP_5XX' : 'NETWORK_ERROR',
            error: `HTTP ${upstream.status}: Bildserver-Fehler.`,
            receivedContentType: null,
          };
        }

        // Pre-header checks BEFORE buffering up to 15 MB into memory
        const rawContentType = upstream.headers.get('content-type');
        const normalizedHeaderType = normalizeContentType(rawContentType);
        const declaredLength = Number(upstream.headers.get('content-length') || '');

        if (Number.isFinite(declaredLength) && declaredLength > MAX_IMAGE_BYTES) {
          await upstream.body?.cancel().catch(() => undefined);
          return {
            ok: false,
            status: 413,
            diagnostic: 'NETWORK_ERROR',
            error: 'Bilddatei überschreitet das zulässige 15-MB-Größenlimit.',
            receivedContentType: normalizedHeaderType || null,
          };
        }

        // Fast-reject declared HTML/XML challenges without buffering up to 15 MB
        if (isChallengeContentType(rawContentType)) {
          await upstream.body?.cancel().catch(() => undefined);
          lastStatus = 403;
          lastReceivedContentType = normalizedHeaderType || 'text/html';
          lastDiagnostic = lastReceivedContentType.includes('xml')
            ? 'XML_CHALLENGE'
            : 'HTML_CHALLENGE';
          lastErrorMessage = `HTTP 403: Sicherheits-/CDN-Abfrage (${lastReceivedContentType}) statt Bilddaten empfangen.`;
          console.warn(
            `[ImageProxy][Security/CDN Challenge] ${imageUrl.toString()} -> ${lastReceivedContentType}`
          );
          continue; // Try next Referer tier
        }

        // Reject declared non-image MIME types (e.g. application/octet-stream, video/mp4) before reading body
        if (!isValidImageContentType(rawContentType)) {
          await upstream.body?.cancel().catch(() => undefined);
          return {
            ok: false,
            status: 403,
            diagnostic: 'HTML_CHALLENGE',
            error: `HTTP 403: Ungültiger Bild-Content-Type (${normalizedHeaderType || 'unbekannt'}).`,
            receivedContentType: normalizedHeaderType || 'application/octet-stream',
          };
        }

        const buffer = await readResponseWithLimit(
          upstream,
          MAX_IMAGE_BYTES,
          fetchDeps.readTimeoutMs ?? READ_TIMEOUT_MS
        );
        const prefix = buffer.subarray(0, INSPECTION_PREFIX_BYTES);
        const verification = verifyReceivedImageBuffer(rawContentType, prefix);

        // Block text/html & application/xml security/CDN challenges disguised as HTTP 200 image/*
        if (verification.isSecurityChallenge || isHtmlPayload(prefix)) {
          lastStatus = 403;
          lastReceivedContentType = verification.detectedContentType;
          lastDiagnostic = verification.detectedContentType.includes('xml')
            ? 'XML_CHALLENGE'
            : 'HTML_CHALLENGE';
          lastErrorMessage = `HTTP 403: Sicherheits-/CDN-Abfrage (${verification.detectedContentType}) statt Bilddaten empfangen.`;
          console.warn(
            `[ImageProxy][Security/CDN Challenge] ${imageUrl.toString()} -> ${verification.detectedContentType}`
          );
          continue; // Try next Referer tier
        }

        const safeContentType = resolveSafeImageContentType(rawContentType);
        // Reject 0-byte payloads, spoofed JSON/text bodies, or unknown non-image payloads before caching
        if (
          buffer.byteLength === 0 ||
          !safeContentType ||
          !hasValidImageMagicBytes(prefix, safeContentType)
        ) {
          return {
            ok: false,
            status: 502,
            diagnostic: 'NULL_BITMAP',
            error:
              'Bilddaten sind leer oder besitzen keine gültige Bildsignatur (Schutz vor Cache-Poisoning).',
            receivedContentType: normalizedHeaderType || 'application/octet-stream',
          };
        }

        // Write with generation guard so an interleaved clearAll() cannot be undone by a stale fetch
        await cacheStore.set(
          {
            key: cacheKey,
            chapterKey,
            buffer,
            contentType: safeContentType,
          },
          Date.now(),
          startGeneration
        );

        return {
          ok: true,
          buffer,
          contentType: safeContentType,
          cacheStatus: bypassCache ? 'REFRESHED' : 'MISS',
        };
      } catch (err) {
        // SSRF blocks must NEVER be retried across Referer tiers or misreported as 502
        if (err instanceof SsrfBlockedError) {
          return {
            ok: false,
            status: err.statusCode,
            diagnostic: 'NETWORK_ERROR',
            error: err.message,
            receivedContentType: null,
          };
        }
        if (err instanceof PayloadTooLargeError) {
          return {
            ok: false,
            status: err.statusCode,
            diagnostic: 'NETWORK_ERROR',
            error: err.message,
            receivedContentType: null,
          };
        }
        if (err instanceof UpstreamTimeoutError) {
          return {
            ok: false,
            status: 504,
            diagnostic: 'TIMEOUT',
            error: 'Zeitüberschreitung beim Bildabruf (15s/20s).',
            receivedContentType: null,
          };
        }

        const msg = err instanceof Error ? err.message : String(err);
        if (msg.toLowerCase().includes('abort') || msg.toLowerCase().includes('timeout')) {
          return {
            ok: false,
            status: 504,
            diagnostic: 'TIMEOUT',
            error: 'Zeitüberschreitung beim Bildabruf (15s/20s).',
            receivedContentType: null,
          };
        }
        return {
          ok: false,
          status: 502,
          diagnostic: 'NETWORK_ERROR',
          error: msg,
          receivedContentType: null,
        };
      }
    }

    return {
      ok: false,
      status: lastStatus,
      diagnostic: lastDiagnostic,
      error: lastErrorMessage,
      receivedContentType: lastReceivedContentType,
    };
  }

  async function resolveImage(
    imageUrl: URL,
    refererUrl: URL | undefined,
    bypassCache: boolean
  ): Promise<ProxyImageResult> {
    const cacheKey = imageUrl.toString();

    if (bypassCache) {
      await cacheStore.delete(cacheKey);
      return fetchFromUpstream(imageUrl, refererUrl, true);
    }

    const cached = await cacheStore.get(cacheKey);
    if (cached) {
      return {
        ok: true,
        buffer: cached.buffer,
        contentType: cached.contentType,
        cacheStatus: 'HIT',
      };
    }

    // In-flight request deduplication (thundering-herd protection)
    const dedupeKey = `${cacheKey}|${refererUrl ? refererUrl.origin : ''}`;
    const existingFlight = inFlight.get(dedupeKey);
    if (existingFlight) {
      return existingFlight;
    }

    const flightPromise = fetchFromUpstream(imageUrl, refererUrl, false).finally(() => {
      inFlight.delete(dedupeKey);
    });
    inFlight.set(dedupeKey, flightPromise);
    return flightPromise;
  }

  return { resolveImage };
}
