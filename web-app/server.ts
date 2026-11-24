import 'dotenv/config';
import express from 'express';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_HOST,
  DEFAULT_PORT,
  MAX_SERVER_CACHE_BYTES,
  MIN_SERVER_CACHE_BYTES,
  PROJECT_ROOT,
} from './server/config.ts';
import { createRateLimiter, type RateLimiterMiddleware } from './server/security/rateLimit.ts';
import {
  assertHostResolvesPublic,
  safeFetchWithRedirects,
  SsrfBlockedError,
  UpstreamTimeoutError,
  type DnsLookupFn,
  type SafeFetchDeps,
} from './server/security/ssrf.ts';
import {
  createDefaultServerCacheStore,
  DiskChapterCacheStore,
  startProactiveCacheMaintenance,
  type ServerCacheStore,
} from './server/services/cacheManager.ts';
import { createCacheRouter } from './server/routes/cache.ts';
import { createChatRouter, type GeminiClientLike } from './server/routes/chat.ts';
import { createDemoRouter } from './server/routes/demo.ts';
import { createExtractRouter } from './server/routes/extract.ts';
import { createImageProxyRouter } from './server/routes/imageProxy.ts';

export {
  assertHostResolvesPublic,
  DiskChapterCacheStore,
  safeFetchWithRedirects,
  SsrfBlockedError,
  UpstreamTimeoutError,
  type DnsLookupFn,
  type SafeFetchDeps,
  type ServerCacheStore,
};

export interface CreateAppOptions extends SafeFetchDeps {
  cacheManager?: ServerCacheStore;
  minCacheBytes?: number;
  maxCacheBytes?: number;
  getGeminiClient?: () => GeminiClientLike | null;
  chatFirstTokenTimeoutMs?: number;
  chatIdleChunkTimeoutMs?: number;
  chatHeartbeatIntervalMs?: number;
  rateLimits?: {
    disabled?: boolean;
    windowMs?: number;
    chatMaxRequests?: number;
    extractMaxRequests?: number;
    proxyMaxRequests?: number;
    cacheCleanupMaxRequests?: number;
  };
}

let defaultCacheStore: ServerCacheStore | null = null;
function getDefaultCacheStore(): ServerCacheStore {
  if (!defaultCacheStore) {
    defaultCacheStore = createDefaultServerCacheStore();
  }
  return defaultCacheStore;
}

const NOOP_VITE_CLIENT = `
export class ErrorOverlay extends HTMLElement {}
const sheetsMap = new Map();
export function updateStyle(id, content) {
  let style = sheetsMap.get(id);
  if (!style) {
    style = document.createElement('style');
    style.setAttribute('type', 'text/css');
    style.setAttribute('data-vite-dev-id', id);
    document.head.appendChild(style);
    sheetsMap.set(id, style);
  }
  style.textContent = content;
}
export function removeStyle(id) {
  const style = sheetsMap.get(id);
  if (style) {
    style.remove();
    sheetsMap.delete(id);
  }
}
export function createHotContext() {
  return {
    data: {},
    accept() {},
    acceptExports() {},
    dispose() {},
    prune() {},
    decline() {},
    invalidate() {},
    on() {},
    off() {},
    send() {},
  };
}
export function injectQuery(url) {
  return url;
}
`;

export function createApp(options: CreateAppOptions = {}): express.Express {
  const cacheStore = options.cacheManager ?? getDefaultCacheStore();
  const minCacheBytes = options.minCacheBytes ?? MIN_SERVER_CACHE_BYTES;
  const maxCacheBytes = options.maxCacheBytes ?? MAX_SERVER_CACHE_BYTES;
  const fetchDeps: SafeFetchDeps = {
    dnsLookup: options.dnsLookup,
    fetchImpl: options.fetchImpl,
    connectTimeoutMs: options.connectTimeoutMs,
    readTimeoutMs: options.readTimeoutMs,
  };

  const app = express();
  app.disable('x-powered-by');

  // Global security headers
  app.use((_req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    next();
  });

  app.use(express.json({ limit: '256kb' }));

  // Lightweight per-IP sliding-window rate limiters
  const limiters: RateLimiterMiddleware[] = [];
  const rateLimitDisabled = options.rateLimits?.disabled === true;
  const rateWindowMs = options.rateLimits?.windowMs ?? 60_000;

  const makeLimiter = (maxRequests: number, message: string): express.RequestHandler | undefined => {
    if (rateLimitDisabled) return undefined;
    const limiter = createRateLimiter({ windowMs: rateWindowMs, maxRequests, message });
    limiters.push(limiter);
    return limiter.middleware;
  };

  const chatLimiter = makeLimiter(
    options.rateLimits?.chatMaxRequests ?? 30,
    'Zu viele Chat-Anfragen (HTTP 429). Bitte warte einen Moment.'
  );
  const extractLimiter = makeLimiter(
    options.rateLimits?.extractMaxRequests ?? 60,
    'Zu viele Kapitel-Extraktionen (HTTP 429). Bitte warte einen Moment.'
  );
  const proxyLimiter = makeLimiter(
    options.rateLimits?.proxyMaxRequests ?? 600,
    'Zu viele Bild-Proxy-Anfragen (HTTP 429). Bitte warte einen Moment.'
  );
  const cacheCleanupLimiter = makeLimiter(
    options.rateLimits?.cacheCleanupMaxRequests ?? 60,
    'Zu viele Cache-Bereinigungen (HTTP 429). Bitte warte einen Moment.'
  );

  app.locals.disposeRateLimiters = () => {
    for (const limiter of limiters) {
      limiter.dispose();
    }
  };

  // Mount modular API routes (unchanged paths & contracts)
  app.use(
    createCacheRouter({
      cacheStore,
      minCacheBytes,
      maxCacheBytes,
      cleanupRateLimiter: cacheCleanupLimiter,
    })
  );
  app.use(createDemoRouter());
  app.use(
    createImageProxyRouter({
      cacheStore,
      fetchDeps,
      rateLimiter: proxyLimiter,
    })
  );
  app.use(
    createExtractRouter({
      fetchDeps,
      rateLimiter: extractLimiter,
    })
  );
  app.use(
    createChatRouter({
      getGeminiClient: options.getGeminiClient,
      rateLimiter: chatLimiter,
      firstTokenTimeoutMs: options.chatFirstTokenTimeoutMs,
      idleChunkTimeoutMs: options.chatIdleChunkTimeoutMs,
      heartbeatIntervalMs: options.chatHeartbeatIntervalMs,
    })
  );

  // Global JSON error middleware (catches malformed JSON payloads & unexpected route errors)
  const jsonErrorHandler: express.ErrorRequestHandler = (err, _req, res, _next) => {
    if (res.headersSent) return;
    const status =
      typeof (err as { status?: unknown })?.status === 'number'
        ? (err as { status: number }).status
        : typeof (err as { statusCode?: unknown })?.statusCode === 'number'
          ? (err as { statusCode: number }).statusCode
          : 500;
    const isBodyParseError =
      status === 400 || (err as { type?: unknown })?.type === 'entity.parse.failed';
    if (isBodyParseError) {
      res.status(400).json({ error: 'Ungültiger JSON-Request-Body.' });
      return;
    }
    console.error('[Server] Unerwarteter Fehler:', err);
    res.status(status >= 400 && status < 600 ? status : 500).json({
      error: 'Interner Serverfehler.',
    });
  };
  app.use(jsonErrorHandler);

  return app;
}

export async function startServer() {
  if (process.argv.includes('--production') || process.argv.includes('--prod')) {
    process.env.NODE_ENV = 'production';
  }

  const cacheStore = getDefaultCacheStore();
  const stopCacheMaintenance = startProactiveCacheMaintenance(cacheStore);
  const app = createApp({ cacheManager: cacheStore });
  const httpServer = http.createServer(app);

  // Dev Vite middleware or production static files
  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const hmrDisabled = process.env.DISABLE_HMR === 'true';

    if (hmrDisabled) {
      app.get('/@vite/client', (_req, res) => {
        res.setHeader('Content-Type', 'application/javascript; charset=utf-8');
        res.setHeader('Cache-Control', 'no-cache');
        res.send(NOOP_VITE_CLIENT);
      });
    }

    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        allowedHosts: true,
        hmr: !hmrDisabled,
        ws: hmrDisabled ? false : { server: httpServer },
        watch: hmrDisabled ? null : {},
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(PROJECT_ROOT, 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  let shuttingDown = false;
  const shutdown = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[Server] Empfange ${signal}, fahre sauber herunter...`);
    stopCacheMaintenance();
    if (typeof app.locals.disposeRateLimiters === 'function') {
      (app.locals.disposeRateLimiters as () => void)();
    }
    httpServer.close(() => {
      process.exit(0);
    });
    setTimeout(() => process.exit(1), 5_000).unref();
  };

  process.once('SIGINT', () => shutdown('SIGINT'));
  process.once('SIGTERM', () => shutdown('SIGTERM'));

  httpServer.listen(DEFAULT_PORT, DEFAULT_HOST, () => {
    console.log(`MangaReaderApp Server aktiv auf http://${DEFAULT_HOST}:${DEFAULT_PORT}`);
  });
}

function isDirectExecution(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  const resolvedEntry = path.resolve(entry);
  const resolvedSelf = path.resolve(fileURLToPath(import.meta.url));
  if (process.platform === 'win32') {
    return resolvedEntry.toLowerCase() === resolvedSelf.toLowerCase();
  }
  return resolvedEntry === resolvedSelf;
}

if (isDirectExecution()) {
  startServer().catch((err) => {
    console.error('Server-Start fehlgeschlagen:', err);
    process.exit(1);
  });
}
