import type { ImageDiagnostic } from '../types/manga';

export const BACKOFF_DELAYS_MS = [400, 800, 1600] as const;
export const MAX_AUTO_RETRIES = BACKOFF_DELAYS_MS.length;

/**
 * Inspects initial payload bytes/text (first 256 bytes) to detect HTML or XML security/CDN challenges
 * disguised as HTTP 200 image responses (Cloudflare Turnstile, Bot Protection, S3/CDN XML AccessDenied).
 */
export function isXmlPayload(prefixBytes: Uint8Array | string): boolean {
  const text =
    typeof prefixBytes === 'string'
      ? prefixBytes.slice(0, 256)
      : new TextDecoder('utf-8', { fatal: false }).decode(prefixBytes.slice(0, 256));

  const trimmed = text.trimStart().toLowerCase();
  return (
    trimmed.startsWith('<?xml') ||
    trimmed.startsWith('<error>') ||
    trimmed.startsWith('<error ') ||
    trimmed.includes('<code') && trimmed.includes('accessdenied')
  );
}

export function isHtmlPayload(prefixBytes: Uint8Array | string): boolean {
  const text =
    typeof prefixBytes === 'string'
      ? prefixBytes.slice(0, 256)
      : new TextDecoder('utf-8', { fatal: false }).decode(prefixBytes.slice(0, 256));

  const trimmed = text.trimStart().toLowerCase();
  return (
    trimmed.startsWith('<!doctype html') ||
    trimmed.startsWith('<html') ||
    trimmed.startsWith('<?xml') ||
    trimmed.startsWith('<error>') ||
    trimmed.startsWith('<error ') ||
    trimmed.includes('<title>just a moment') ||
    trimmed.includes('cf-browser-verification') ||
    trimmed.includes('attention required! | cloudflare')
  );
}

export function normalizeContentType(contentType: string | null | undefined): string {
  if (!contentType) return '';
  return contentType.split(';')[0].trim().toLowerCase();
}

export function isChallengeContentType(contentType: string | null | undefined): boolean {
  const normalized = normalizeContentType(contentType);
  return (
    normalized === 'text/html' ||
    normalized === 'application/xhtml+xml' ||
    normalized === 'application/xml' ||
    normalized === 'text/xml' ||
    normalized.includes('html') ||
    normalized.includes('xml')
  );
}

export function isValidImageContentType(contentType: string | null): boolean {
  const normalized = normalizeContentType(contentType);
  if (!normalized) return false;
  if (isChallengeContentType(normalized) || normalized.startsWith('text/')) return false;
  return normalized.startsWith('image/');
}

/**
 * Verifies the effective Content-Type of a received response buffer by inspecting both
 * the HTTP Content-Type header and the leading bytes of the buffer.
 * Detects disguised `text/html` and `application/xml` responses even if the server sent `image/*`.
 */
export function verifyReceivedImageBuffer(
  headerContentType: string | null | undefined,
  bufferPrefix?: Uint8Array | string | null
): {
  detectedContentType: string;
  isSecurityChallenge: boolean;
  isValidImageHeader: boolean;
} {
  const normalizedHeader = normalizeContentType(headerContentType);
  const hasXmlBytes = bufferPrefix != null && isXmlPayload(bufferPrefix);
  const hasHtmlBytes = bufferPrefix != null && isHtmlPayload(bufferPrefix);

  if (normalizedHeader === 'application/xml' || normalizedHeader === 'text/xml') {
    return {
      detectedContentType: normalizedHeader,
      isSecurityChallenge: true,
      isValidImageHeader: false,
    };
  }

  if (normalizedHeader === 'text/html' || normalizedHeader === 'application/xhtml+xml') {
    return {
      detectedContentType: normalizedHeader,
      isSecurityChallenge: true,
      isValidImageHeader: false,
    };
  }

  if (hasXmlBytes) {
    return {
      detectedContentType: 'application/xml',
      isSecurityChallenge: true,
      isValidImageHeader: false,
    };
  }

  if (hasHtmlBytes) {
    return {
      detectedContentType: 'text/html',
      isSecurityChallenge: true,
      isValidImageHeader: false,
    };
  }

  if (isChallengeContentType(normalizedHeader)) {
    return {
      detectedContentType: normalizedHeader,
      isSecurityChallenge: true,
      isValidImageHeader: false,
    };
  }

  return {
    detectedContentType: normalizedHeader || 'application/octet-stream',
    isSecurityChallenge: false,
    isValidImageHeader: isValidImageContentType(headerContentType ?? null),
  };
}

export function getBackoffDelayMs(attemptIndex: number): number {
  if (attemptIndex < 0) return BACKOFF_DELAYS_MS[0];
  return BACKOFF_DELAYS_MS[Math.min(attemptIndex, BACKOFF_DELAYS_MS.length - 1)];
}

export interface DiagnosticInput {
  httpStatus?: number;
  diagnosticHeader?: string | null;
  errorMessage?: string;
  receivedContentType?: string | null;
}

/**
 * Early handler for BitmapFactory / browser stream decode failures ("null bitmap").
 * Automatically verifies the Content-Type of the received buffer:
 * - If it is `text/html` or `application/xml` (instead of `image/*`), classifies it immediately
 *   as a Security/CDN challenge (`HTML_CHALLENGE`) with `suggestOpenBrowser: true` and `shouldAutoRetry: false`.
 * - Otherwise classifies it as `DECODE_ERROR` (Null Bitmap) with `suggestCacheBypass: true`.
 */
export function diagnoseBitmapDecodeFailure(params: {
  headerContentType?: string | null;
  bufferPrefix?: Uint8Array | string | null;
  httpStatus?: number;
  errorMessage?: string;
}): ImageDiagnostic {
  const verification = verifyReceivedImageBuffer(params.headerContentType, params.bufferPrefix);

  if (verification.isSecurityChallenge) {
    return classifyImageError({
      httpStatus: params.httpStatus ?? 403,
      diagnosticHeader: 'HTML_CHALLENGE',
      receivedContentType: verification.detectedContentType,
    });
  }

  return classifyImageError({
    httpStatus: params.httpStatus,
    diagnosticHeader: 'NULL_BITMAP',
    receivedContentType: verification.detectedContentType,
    errorMessage:
      params.errorMessage || 'BitmapFactory returned a null bitmap. Could not decode image data',
  });
}

export function classifyImageError(input: DiagnosticInput): ImageDiagnostic {
  const status = input.httpStatus;
  const header = (input.diagnosticHeader || '').toUpperCase();
  const msg = (input.errorMessage || '').toLowerCase();
  const receivedType = normalizeContentType(input.receivedContentType);

  if (
    header === 'HTML_CHALLENGE' ||
    header === 'XML_CHALLENGE' ||
    header === 'CDN_CHALLENGE' ||
    isChallengeContentType(receivedType) ||
    msg.includes('html-sicherheitsabfrage') ||
    msg.includes('cdn-sicherheitsabfrage')
  ) {
    const isXml = receivedType.includes('xml') || header === 'XML_CHALLENGE';
    const displayType = receivedType || (isXml ? 'application/xml' : 'text/html');
    return {
      kind: 'HTML_CHALLENGE',
      httpStatus: status ?? 403,
      receivedContentType: displayType,
      title: isXml
        ? 'Sicherheits-/CDN-Prüfung (application/xml erkannt)'
        : 'Sicherheits-/CDN-Prüfung (text/html erkannt)',
      description: isXml
        ? `Statt eines Bildes (image/*) lieferte der Server eine XML-Antwort (${displayType}, z. B. CDN-/AccessDenied-Challenge), wodurch der Bild-Decoder abgebrochen wurde.`
        : `Statt eines Bildes (image/*) lieferte der Server eine HTML-Sicherheitsseite (${displayType}, z. B. Cloudflare-/Bot-Prüfung), was zu einem Null-Bitmap-Fehler führt.`,
      hint: 'Öffne die Seite direkt im Browser, um die Sicherheits-/CDN-Prüfung abzuschließen und frische Cookies zu erhalten.',
      severity: 'amber',
      shouldAutoRetry: false,
      suggestCacheBypass: true,
      suggestSessionRefresh: true,
      suggestOpenBrowser: true,
    };
  }

  if (
    header === 'NULL_BITMAP' ||
    header === 'DECODE_ERROR' ||
    msg.includes('null bitmap') ||
    msg.includes('could not decode image') ||
    msg.includes('bitmapfactory')
  ) {
    return {
      kind: 'DECODE_ERROR',
      httpStatus: status,
      receivedContentType: receivedType || undefined,
      title: 'Dekodierungsfehler (Null Bitmap)',
      description:
        'Die Bilddatei konnte nicht dekodiert werden (Null Bitmap) — häufig verursacht durch einen beschädigten oder unvollständigen Eintrag im lokalen bzw. Server-Cache.',
      hint: 'Nutze „Cache-Bypass für dieses Bild“, um den defekten Cache-Eintrag zu verwerfen und die Datei frisch vom Server zu laden.',
      severity: 'amber',
      shouldAutoRetry: false,
      suggestCacheBypass: true,
      suggestSessionRefresh: true,
      suggestOpenBrowser: true,
    };
  }

  if (status === 404) {
    return {
      kind: 'HTTP_404',
      httpStatus: 404,
      title: 'HTTP 404: Datei existiert nicht',
      description:
        'Diese Bilddatei ist auf dem Manga-Server nicht vorhanden oder der Link im Seitenquelltext ist veraltet.',
      hint: 'Kein automatischer Retry – fehlende Seiten werden sofort markiert.',
      severity: 'crimson',
      shouldAutoRetry: false,
      suggestCacheBypass: false,
      suggestSessionRefresh: false,
      suggestOpenBrowser: false,
    };
  }

  if (status === 403 || status === 401) {
    return {
      kind: 'HTTP_403',
      httpStatus: status,
      title: `HTTP ${status}: Zugriff verweigert`,
      description:
        'Die Datei existiert, aber das CDN blockiert den direkten Abruf (Anti-Hotlinking oder abgelaufene Sitzung).',
      hint: 'Referer-Fallback und Sitzungserneuerung mit Cache-Bypass ausführen.',
      severity: 'amber',
      shouldAutoRetry: false,
      suggestCacheBypass: false,
      suggestSessionRefresh: true,
      suggestOpenBrowser: true,
    };
  }

  if (status === 429) {
    return {
      kind: 'HTTP_429',
      httpStatus: 429,
      title: 'HTTP 429: Zu viele Anfragen (Rate-Limit)',
      description:
        'Die Datei ist vorhanden, aber der Bildserver drosselt temporär wegen vieler paralleler Seitenabrufe.',
      hint: 'Automatischer Retry mit Exponential Backoff (400 ms → 800 ms → 1600 ms) aktiv.',
      severity: 'yellow',
      shouldAutoRetry: true,
      suggestCacheBypass: false,
      suggestSessionRefresh: false,
      suggestOpenBrowser: false,
    };
  }

  if (
    status === 408 ||
    status === 504 ||
    header === 'TIMEOUT' ||
    msg.includes('timeout') ||
    msg.includes('aborted')
  ) {
    return {
      kind: 'TIMEOUT',
      httpStatus: status ?? 504,
      title: `HTTP ${status ?? 504}: Zeitüberschreitung (Timeout)`,
      description:
        'Der CDN-Knoten antwortete nicht innerhalb des 15s/20s-Zeitfensters.',
      hint: 'Kürzeres Timeout schützt vor ewigen Ladekreisen – erneuter Versuch behebt Knoten-Hänger oft sofort.',
      severity: 'slate',
      shouldAutoRetry: true,
      suggestCacheBypass: false,
      suggestSessionRefresh: false,
      suggestOpenBrowser: false,
    };
  }

  if (status && status >= 500 && status <= 599) {
    return {
      kind: 'HTTP_5XX',
      httpStatus: status,
      title: `HTTP ${status}: Server vorübergehend gestört`,
      description:
        'Der Bildserver meldet eine temporäre Überlastung oder einen internen Fehler.',
      hint: 'Automatischer Retry im Hintergrund wird durchgeführt.',
      severity: 'slate',
      shouldAutoRetry: true,
      suggestCacheBypass: false,
      suggestSessionRefresh: false,
      suggestOpenBrowser: false,
    };
  }

  return {
    kind: 'NETWORK_ERROR',
    httpStatus: status,
    title: 'Verbindungsfehler beim Bildabruf',
    description:
      'Die Bilddatei konnte wegen eines Verbindungsabbruchs oder ungültiger Bilddaten nicht geladen werden.',
    hint: 'Mit „Erneut versuchen“ oder „Cache-Bypass für dieses Bild“ direkt neu anfordern.',
    severity: 'slate',
    shouldAutoRetry: true,
    suggestCacheBypass: true,
    suggestSessionRefresh: true,
    suggestOpenBrowser: false,
  };
}
