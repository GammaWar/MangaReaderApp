import 'dotenv/config';
import express from 'express';
import dns from 'node:dns/promises';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { GoogleGenAI, type GenerateContentResponse } from '@google/genai';
import { isPrivateOrLoopbackIp, validateExternalUrl } from './src/utils/urlSafety.ts';
import {
  analyzeChapterUrl,
  extractImageUrlsFromHtml,
  extractTitleFromHtml,
} from './src/utils/urlChapterAnalyzer.ts';
import { isHtmlPayload, isValidImageContentType } from './src/utils/imageDiagnostics.ts';
import {
  ChapterCacheManager,
  MAX_CACHE_BYTES,
  MAX_CONFIGURABLE_CACHE_LIMIT_MB,
  MIN_CACHE_LIMIT_MB,
} from './src/utils/cacheManager.ts';
import {
  buildSystemInstruction,
  resolveAllowedModel,
  sanitizeChatHistory,
  sanitizeUserMessage,
  type ChatContextSummary,
} from './src/utils/chatHelpers.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 3000;

const CONNECT_TIMEOUT_MS = 15_000;
const READ_TIMEOUT_MS = 20_000;
const MAX_HTML_BYTES = 4 * 1024 * 1024;
const MAX_IMAGE_BYTES = 15 * 1024 * 1024;
const CACHE_PRUNE_INTERVAL_MS = 6 * 60 * 60 * 1000; // Alle 6 Stunden Speicherlimit prüfen
const MIN_SERVER_CACHE_BYTES = MIN_CACHE_LIMIT_MB * 1024 * 1024;
const MAX_SERVER_CACHE_BYTES = MAX_CONFIGURABLE_CACHE_LIMIT_MB * 1024 * 1024;

const chapterCacheManager = new ChapterCacheManager({
  maxAgeMs: null, // Keine automatische 31-Tage-Löschung (nur manuell oder bei Limitüberschreitung)
  maxBytes: MAX_CACHE_BYTES, // 4 GB Standardlimit
});

let cachedGeminiClient: GoogleGenAI | null = null;
function getGeminiClient(): GoogleGenAI | null {
  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) return null;
  if (!cachedGeminiClient) {
    cachedGeminiClient = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        },
      },
    });
  }
  return cachedGeminiClient;
}

const DEMO_ASSETS: Record<string, string> = {
  manga1: 'src/assets/images/manga_panel_01_1795260245604.jpg',
  manga2: 'src/assets/images/manga_panel_02_1795260245956.jpg',
  webtoon1: 'src/assets/images/webtoon_panel_01_1795260245598.jpg',
  cover1: 'src/assets/images/cover_kurogane_1795260244125.jpg',
  cover2: 'src/assets/images/cover_stellar_drift_1795260245712.jpg',
};

const SAFE_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36';

async function assertHostResolvesPublic(hostname: string): Promise<void> {
  if (isPrivateOrLoopbackIp(hostname)) {
    throw new Error('Private oder Loopback-IP-Adresse blockiert.');
  }
  const records = await dns.lookup(hostname, { all: true });
  if (!records.length) {
    throw new Error('Host konnte nicht aufgelöst werden.');
  }
  for (const record of records) {
    if (isPrivateOrLoopbackIp(record.address)) {
      throw new Error('DNS-Ziel zeigt auf eine interne oder lokale IP-Adresse.');
    }
  }
}

async function safeFetchWithRedirects(
  rawUrl: string,
  headers: Record<string, string>,
  maxRedirects = 3
): Promise<Response> {
  let currentUrl = rawUrl;

  for (let hop = 0; hop <= maxRedirects; hop++) {
    const check = validateExternalUrl(currentUrl);
    if (!check.valid || !check.url) {
      throw new Error(check.reason || 'Ungültige Ziel-URL.');
    }

    await assertHostResolvesPublic(check.url.hostname);

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), CONNECT_TIMEOUT_MS);

    let res: Response;
    try {
      res = await fetch(check.url.toString(), {
        method: 'GET',
        headers,
        redirect: 'manual',
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeoutId);
    }

    if (res.status >= 300 && res.status < 400) {
      const location = res.headers.get('location');
      if (!location) return res;
      currentUrl = new URL(location, check.url).toString();
      continue;
    }

    return res;
  }

  throw new Error('Zu viele Weiterleitungen.');
}

async function readResponseWithLimit(res: Response, maxBytes: number): Promise<Buffer> {
  const reader = res.body?.getReader();
  if (!reader) {
    const ab = await res.arrayBuffer();
    if (ab.byteLength > maxBytes) {
      throw new Error('Antwort überschreitet das Größenlimit.');
    }
    return Buffer.from(ab);
  }

  const chunks: Uint8Array[] = [];
  let total = 0;
  const deadline = Date.now() + READ_TIMEOUT_MS;

  while (true) {
    if (Date.now() > deadline) {
      await reader.cancel();
      throw new Error('Timeout beim Lesen der Antwortdaten.');
    }
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        throw new Error('Antwort überschreitet das zulässige Größenlimit.');
      }
      chunks.push(value);
    }
  }

  return Buffer.concat(chunks);
}

function getSecFetchSite(imageUrl: URL, refererUrl?: URL): string {
  if (!refererUrl) return 'none';
  if (imageUrl.origin === refererUrl.origin) return 'same-origin';
  const imgRoot = imageUrl.hostname.split('.').slice(-2).join('.');
  const refRoot = refererUrl.hostname.split('.').slice(-2).join('.');
  return imgRoot === refRoot ? 'same-site' : 'cross-site';
}

function startProactiveCacheMaintenance(): void {
  chapterCacheManager.prune();
  const timer = setInterval(() => {
    const report = chapterCacheManager.prune();
    if (report.removedSizeEvictedCount > 0) {
      console.log(
        `[CacheManager] Speicherlimit-Prüfung: ${report.removedSizeEvictedCount} älteste Einträge entfernt.`
      );
    }
  }, CACHE_PRUNE_INTERVAL_MS);
  timer.unref();
}

async function startServer() {
  startProactiveCacheMaintenance();
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '256kb' }));

  // 0. Cache-Manager Status & Manuelle Bereinigung (konfigurierbares Limit, Standard 4 GB, keine 31-Tage-Auto-Löschung)
  app.get('/api/cache/status', (req, res) => {
    const rawMaxBytes = typeof req.query.maxBytes === 'string' ? Number(req.query.maxBytes) : NaN;
    if (Number.isFinite(rawMaxBytes) && rawMaxBytes > 0) {
      const clamped = Math.max(MIN_SERVER_CACHE_BYTES, Math.min(MAX_SERVER_CACHE_BYTES, rawMaxBytes));
      res.json(chapterCacheManager.getStats(clamped));
      return;
    }
    res.json(chapterCacheManager.getStats());
  });

  app.post('/api/cache/cleanup', (req, res) => {
    const clearAll = req.body?.clearAll === true;
    const rawBytes = typeof req.body?.maxSizeBytes === 'number' ? req.body.maxSizeBytes : null;

    let report;
    if (clearAll) {
      report = chapterCacheManager.clearAll();
    } else if (rawBytes !== null && Number.isFinite(rawBytes) && rawBytes > 0) {
      const clampedBytes = Math.max(
        MIN_SERVER_CACHE_BYTES,
        Math.min(MAX_SERVER_CACHE_BYTES, rawBytes)
      );
      chapterCacheManager.setMaxBytes(clampedBytes);
      report = chapterCacheManager.pruneWithLimits(null, clampedBytes);
    } else {
      report = chapterCacheManager.prune();
    }

    res.json({
      report,
      stats: chapterCacheManager.getStats(),
    });
  });

  // 1. Diagnostic Demo Endpoint (safe, local, deterministic for testing HTTP 404 / 403 HTML-Challenge / 429 / 503)
  app.get('/api/demo-page', (req, res) => {
    const simError = typeof req.query.simError === 'string' ? req.query.simError : '';
    const assetKey = typeof req.query.asset === 'string' ? req.query.asset : 'manga1';
    const bypassCache = req.query.bypassCache === '1';
    const attempt = Number(req.query.attempt || '0');
    const sessionToken = Number(req.query.sessionToken || '0');

    const relAsset = DEMO_ASSETS[assetKey] || DEMO_ASSETS.manga1;
    const assetPath = path.resolve(__dirname, relAsset);

    const sendAsset = () => {
      if (!assetPath.startsWith(path.resolve(__dirname, 'src/assets') + path.sep)) {
        res.status(400).json({ error: 'Ungültiges Asset.' });
        return;
      }
      if (!fs.existsSync(assetPath)) {
        res.status(404).setHeader('X-Manga-Diagnostic', 'HTTP_404').end();
        return;
      }
      res.setHeader('Content-Type', 'image/jpeg');
      res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
      res.sendFile(assetPath);
    };

    // HTTP 404 never recovers unless explicitly replaced, demonstrating true missing file
    if (simError === '404') {
      res
        .status(404)
        .setHeader('X-Manga-Diagnostic', 'HTTP_404')
        .json({ error: 'HTTP 404: Datei existiert nicht auf dem Server.' });
      return;
    }

    // HTTP 403 / HTML-Challenge recovers when user triggers "Sitzung erneuern" (sessionToken > 0) or manual bypassCache
    if (simError === '403_html') {
      if (sessionToken > 0 || bypassCache) {
        sendAsset();
        return;
      }
      res
        .status(403)
        .setHeader('X-Manga-Diagnostic', 'HTML_CHALLENGE')
        .json({
          error: 'HTTP 403: Server lieferte HTML-Sicherheitsabfrage statt Bilddaten.',
        });
      return;
    }

    // HTTP 429 Rate-Limit recovers automatically on retry attempt >= 2 or manual bypass
    if (simError === '429') {
      if (attempt >= 2 || bypassCache || sessionToken > 0) {
        sendAsset();
        return;
      }
      res
        .status(429)
        .setHeader('X-Manga-Diagnostic', 'HTTP_429')
        .json({ error: 'HTTP 429: Rate-Limit überschritten.' });
      return;
    }

    // HTTP 503 recovers on retry attempt >= 3 or manual retry
    if (simError === '503') {
      if (attempt >= 3 || bypassCache || sessionToken > 0) {
        sendAsset();
        return;
      }
      res
        .status(503)
        .setHeader('X-Manga-Diagnostic', 'HTTP_5XX')
        .json({ error: 'HTTP 503: CDN-Knoten temporär überlastet.' });
      return;
    }

    sendAsset();
  });

  // 2. Hardened Image Proxy with 3-Tier Referer Fallback, HTML-Challenge Detection & Verified-Image-Only Cache
  app.get('/api/image-proxy', async (req, res) => {
    const rawImageUrl = typeof req.query.url === 'string' ? req.query.url : '';
    const rawReferer = typeof req.query.referer === 'string' ? req.query.referer : '';
    const bypassCache = req.query.bypassCache === '1';

    const imgCheck = validateExternalUrl(rawImageUrl);
    if (!imgCheck.valid || !imgCheck.url) {
      res.status(400).json({ error: imgCheck.reason || 'Ungültige Bild-URL.' });
      return;
    }

    let refererUrl: URL | undefined;
    if (rawReferer) {
      const refCheck = validateExternalUrl(rawReferer);
      if (refCheck.valid && refCheck.url) {
        refererUrl = refCheck.url;
      }
    }

    const cacheKey = imgCheck.url.toString();
    const chapterKey = refererUrl ? refererUrl.toString() : imgCheck.url.origin;
    if (!bypassCache) {
      const cached = chapterCacheManager.get(cacheKey);
      if (cached) {
        res.setHeader('Content-Type', cached.contentType);
        res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
        res.setHeader('X-Manga-Cache', 'HIT');
        res.send(cached.buffer);
        return;
      }
    }

    // 3-Tier Referer Fallback Strategy: Chapter Origin -> Image Host Origin -> No Referer
    const refererTiers: Array<string | null> = [];
    if (refererUrl) refererTiers.push(`${refererUrl.origin}/`);
    refererTiers.push(`${imgCheck.url.origin}/`);
    refererTiers.push(null);

    let lastStatus = 502;
    let lastDiagnostic = 'NETWORK_ERROR';
    let lastErrorMessage = 'Bild konnte nicht geladen werden.';

    for (const tierReferer of refererTiers) {
      const headers: Record<string, string> = {
        'User-Agent': SAFE_USER_AGENT,
        // Safe Accept header (no forced image/avif to avoid Bitmap/decode failures)
        Accept: 'image/webp,image/png,image/jpeg,image/*;q=0.8',
        'Accept-Language': 'de-DE,de;q=0.9,en;q=0.8',
        'Sec-Fetch-Dest': 'image',
        'Sec-Fetch-Mode': 'no-cors',
        'Sec-Fetch-Site': getSecFetchSite(
          imgCheck.url,
          tierReferer ? new URL(tierReferer) : undefined
        ),
      };
      // Deliberately omit synthetic Origin header (many manga CDNs reject it)
      if (tierReferer) {
        headers['Referer'] = tierReferer;
      }

      try {
        const upstream = await safeFetchWithRedirects(imgCheck.url.toString(), headers);
        lastStatus = upstream.status;

        if (upstream.status === 404) {
          res
            .status(404)
            .setHeader('X-Manga-Diagnostic', 'HTTP_404')
            .json({ error: 'HTTP 404: Datei existiert nicht auf dem Server.' });
          return;
        }

        if (upstream.status === 429) {
          res
            .status(429)
            .setHeader('X-Manga-Diagnostic', 'HTTP_429')
            .json({ error: 'HTTP 429: Rate-Limit überschritten.' });
          return;
        }

        if (upstream.status === 403 || upstream.status === 401) {
          lastDiagnostic = 'HTTP_403';
          lastErrorMessage = `HTTP ${upstream.status}: Zugriff verweigert.`;
          continue; // Try next Referer tier
        }

        if (!upstream.ok) {
          lastDiagnostic = upstream.status >= 500 ? 'HTTP_5XX' : 'NETWORK_ERROR';
          lastErrorMessage = `HTTP ${upstream.status}: Bildserver-Fehler.`;
          continue;
        }

        const contentType = upstream.headers.get('content-type');
        const buffer = await readResponseWithLimit(upstream, MAX_IMAGE_BYTES);

        // Peek first 256 bytes + strict image/* check to block HTML-Challenge disguised as HTTP 200
        if (!isValidImageContentType(contentType) || isHtmlPayload(buffer.subarray(0, 256))) {
          lastStatus = 403;
          lastDiagnostic = 'HTML_CHALLENGE';
          lastErrorMessage = 'HTTP 403: Server lieferte HTML-Sicherheitsabfrage statt Bilddaten.';
          continue; // Try next Referer tier
        }

        const safeContentType = (contentType || 'image/jpeg').split(';')[0].trim();
        chapterCacheManager.set({
          key: cacheKey,
          chapterKey,
          buffer,
          contentType: safeContentType,
        });

        res.setHeader('Content-Type', safeContentType);
        res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
        res.setHeader('X-Manga-Cache', bypassCache ? 'REFRESHED' : 'MISS');
        res.send(buffer);
        return;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (msg.toLowerCase().includes('abort') || msg.toLowerCase().includes('timeout')) {
          lastStatus = 504;
          lastDiagnostic = 'TIMEOUT';
          lastErrorMessage = 'Zeitüberschreitung beim Bildabruf (15s/20s).';
        } else {
          lastStatus = 502;
          lastDiagnostic = 'NETWORK_ERROR';
          lastErrorMessage = msg;
        }
      }
    }

    res
      .status(lastStatus)
      .setHeader('X-Manga-Diagnostic', lastDiagnostic)
      .json({ error: lastErrorMessage });
  });

  // 3. Chapter HTML Image Extraction Endpoint
  app.post('/api/extract', async (req, res) => {
    const rawUrl = typeof req.body?.url === 'string' ? req.body.url : '';
    const check = validateExternalUrl(rawUrl);
    if (!check.valid || !check.url) {
      res.status(400).json({ error: check.reason || 'Ungültige Kapitel-URL.' });
      return;
    }

    try {
      const upstream = await safeFetchWithRedirects(check.url.toString(), {
        'User-Agent': SAFE_USER_AGENT,
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'de-DE,de;q=0.9,en;q=0.8',
      });

      if (!upstream.ok) {
        res.status(upstream.status).json({
          error: `Kapitelseite antwortete mit HTTP ${upstream.status}.`,
        });
        return;
      }

      const buffer = await readResponseWithLimit(upstream, MAX_HTML_BYTES);
      const html = buffer.toString('utf-8');
      const title = extractTitleFromHtml(html, check.url.toString());
      const imageUrls = extractImageUrlsFromHtml(html, check.url.toString());

      if (imageUrls.length === 0) {
        res.status(422).json({
          error:
            'Keine Manga-Seitenbilder im HTML gefunden (ggf. Cloudflare-Prüfung oder dynamisches JS).',
        });
        return;
      }

      const analysis = analyzeChapterUrl(check.url.toString());
      const pages = imageUrls.slice(0, 150).map((imgUrl, idx) => ({
        index: idx,
        url: `/api/image-proxy?url=${encodeURIComponent(imgUrl)}&referer=${encodeURIComponent(
          check.url!.toString()
        )}`,
        displayUrl: imgUrl,
        label: `Seite ${idx + 1}`,
      }));

      res.json({
        title,
        chapterUrl: check.url.toString(),
        sourceName: check.url.hostname,
        coverUrl: pages[0].url,
        chapterNumber: analysis.chapterNumber,
        nextChapterUrl: analysis.nextChapterUrl,
        prevChapterUrl: analysis.prevChapterUrl,
        pages,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Extraktion fehlgeschlagen.';
      res.status(502).json({ error: msg });
    }
  });

  // 4. Background Pre-Fetch Endpoint
  app.post('/api/prefetch', async (req, res) => {
    const rawUrl = typeof req.body?.url === 'string' ? req.body.url : '';
    const check = validateExternalUrl(rawUrl);
    if (!check.valid || !check.url) {
      res.status(400).json({ error: check.reason || 'Ungültige Pre-Fetch-URL.' });
      return;
    }

    try {
      const upstream = await safeFetchWithRedirects(check.url.toString(), {
        'User-Agent': SAFE_USER_AGENT,
        Accept: 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'de-DE,de;q=0.9,en;q=0.8',
      });
      if (!upstream.ok) {
        res.status(upstream.status).json({ error: `HTTP ${upstream.status}` });
        return;
      }
      const buffer = await readResponseWithLimit(upstream, MAX_HTML_BYTES);
      const html = buffer.toString('utf-8');
      const imageUrls = extractImageUrlsFromHtml(html, check.url.toString());
      if (!imageUrls.length) {
        res.status(404).json({ error: 'Keine Folgekapitel-Bilder gefunden.' });
        return;
      }

      const title = extractTitleFromHtml(html, check.url.toString());
      const analysis = analyzeChapterUrl(check.url.toString());
      const pages = imageUrls.slice(0, 150).map((imgUrl, idx) => ({
        index: idx,
        url: `/api/image-proxy?url=${encodeURIComponent(imgUrl)}&referer=${encodeURIComponent(
          check.url!.toString()
        )}`,
        displayUrl: imgUrl,
        label: `Seite ${idx + 1}`,
      }));

      res.json({
        title,
        chapterUrl: check.url.toString(),
        sourceName: check.url.hostname,
        coverUrl: pages[0].url,
        chapterNumber: analysis.chapterNumber,
        nextChapterUrl: analysis.nextChapterUrl,
        prevChapterUrl: analysis.prevChapterUrl,
        pages,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Pre-Fetching fehlgeschlagen.';
      res.status(502).json({ error: msg });
    }
  });

  // 5. Server-side Multi-Turn Gemini Chatbot Endpoint (SSE Streaming)
  app.post('/api/chat', async (req, res) => {
    const ai = getGeminiClient();
    if (!ai) {
      res.status(503).json({
        error:
          'Der Gemini-Assistent ist derzeit nicht konfiguriert. Bitte prüfe den API-Schlüssel in den Einstellungen (Settings > Secrets).',
      });
      return;
    }

    const message = sanitizeUserMessage(req.body?.message);
    if (!message) {
      res.status(400).json({ error: 'Bitte gib eine Nachricht ein.' });
      return;
    }

    const model = resolveAllowedModel(req.body?.model);
    const historyTurns = sanitizeChatHistory(req.body?.history);
    const context =
      req.body?.context && typeof req.body.context === 'object'
        ? (req.body.context as ChatContextSummary)
        : undefined;
    const systemInstruction = buildSystemInstruction(req.body?.personaId, context);

    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders?.();

    const writeEvent = (payload: Record<string, unknown>) => {
      if (!res.writableEnded) {
        res.write(`data: ${JSON.stringify(payload)}\n\n`);
      }
    };

    try {
      const sdkHistory = historyTurns.map((turn) => ({
        role: turn.role,
        parts: [{ text: turn.text }],
      }));

      const chat = ai.chats.create({
        model,
        history: sdkHistory,
        config: {
          systemInstruction,
        },
      });

      const streamResponse = await chat.sendMessageStream({
        message,
      });

      for await (const chunk of streamResponse) {
        if (res.writableEnded) break;
        const c = chunk as GenerateContentResponse;
        const textChunk = c.text;
        if (textChunk) {
          writeEvent({ chunk: textChunk });
        }
      }

      writeEvent({ done: true, model });
      res.end();
    } catch (err) {
      const rawMessage = err instanceof Error ? err.message : String(err);
      let friendlyError = 'Antwort konnte nicht geladen werden. Bitte versuche es erneut.';
      if (rawMessage.includes('403') || rawMessage.includes('PERMISSION_DENIED') || rawMessage.includes('API_KEY_INVALID')) {
        friendlyError =
          'API-Zugriff abgelehnt. Bitte prüfe den API-Schlüssel im Bereich Settings > Secrets.';
      } else if (rawMessage.includes('429') || rawMessage.includes('RESOURCE_EXHAUSTED')) {
        friendlyError =
          'Anfragelimit erreicht (HTTP 429). Bitte warte kurz oder wechsle auf den schnellen Modus.';
      } else if (rawMessage.trim()) {
        friendlyError = rawMessage;
      }
      writeEvent({ error: friendlyError });
      res.end();
    }
  });

  // Dev Vite middleware or production static files
  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(__dirname, 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`MangaReaderApp Server aktiv auf http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Server-Start fehlgeschlagen:', err);
  process.exit(1);
});
