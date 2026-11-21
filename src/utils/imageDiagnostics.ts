import type { ImageDiagnostic } from '../types/manga';

export const BACKOFF_DELAYS_MS = [400, 800, 1600] as const;
export const MAX_AUTO_RETRIES = BACKOFF_DELAYS_MS.length;

/**
 * Inspects initial payload bytes/text (first 256 bytes) to detect HTML security challenges
 * disguised as HTTP 200 image responses (Cloudflare Turnstile / Bot Protection).
 */
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
    trimmed.includes('<title>just a moment') ||
    trimmed.includes('cf-browser-verification') ||
    trimmed.includes('attention required! | cloudflare')
  );
}

export function isValidImageContentType(contentType: string | null): boolean {
  if (!contentType) return false;
  const normalized = contentType.split(';')[0].trim().toLowerCase();
  if (normalized.includes('html') || normalized.includes('text/')) return false;
  return normalized.startsWith('image/');
}

export function getBackoffDelayMs(attemptIndex: number): number {
  if (attemptIndex < 0) return BACKOFF_DELAYS_MS[0];
  return BACKOFF_DELAYS_MS[Math.min(attemptIndex, BACKOFF_DELAYS_MS.length - 1)];
}

export interface DiagnosticInput {
  httpStatus?: number;
  diagnosticHeader?: string | null;
  errorMessage?: string;
}

export function classifyImageError(input: DiagnosticInput): ImageDiagnostic {
  const status = input.httpStatus;
  const header = (input.diagnosticHeader || '').toUpperCase();
  const msg = (input.errorMessage || '').toLowerCase();

  if (
    header === 'HTML_CHALLENGE' ||
    msg.includes('html-sicherheitsabfrage') ||
    msg.includes('null bitmap') ||
    msg.includes('could not decode image')
  ) {
    return {
      kind: 'HTML_CHALLENGE',
      httpStatus: status ?? 403,
      title: 'HTTP 403: HTML-Sicherheitsabfrage erkannt',
      description:
        'Der Bildserver lieferte statt einer Bilddatei eine Cloudflare-/Bot-Prüfseite (verhindert Null-Bitmap-Dekodierungsfehler).',
      hint: 'Sitzung erneuern oder Seite kurz im Browser öffnen, um frische Cookies zu erhalten.',
      severity: 'amber',
      shouldAutoRetry: false,
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
    hint: 'Mit „Erneut versuchen“ (Cache-Bypass) direkt neu anfordern.',
    severity: 'slate',
    shouldAutoRetry: true,
    suggestSessionRefresh: true,
    suggestOpenBrowser: false,
  };
}
