import test from 'node:test';
import assert from 'node:assert/strict';
import { ChapterCacheManager } from '../utils/cacheManager.ts';
import {
  createMockNetwork,
  startAppServer,
  startHttpServer,
  VALID_JPEG_BYTES,
  VALID_PNG_BYTES,
} from './testHelpers.ts';

interface CapturedRequest {
  path: string;
  referer: string | undefined;
  origin: string | undefined;
  accept: string | undefined;
  secFetchSite: string | undefined;
}

test('/api/image-proxy: 3-tier Referer fallback (Chapter Origin -> Image Host Origin -> No Referer)', async () => {
  const recorded: CapturedRequest[] = [];

  const mockCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    const referer = typeof req.headers.referer === 'string' ? req.headers.referer : undefined;
    recorded.push({
      path: url.pathname,
      referer,
      origin: typeof req.headers.origin === 'string' ? req.headers.origin : undefined,
      accept: typeof req.headers.accept === 'string' ? req.headers.accept : undefined,
      secFetchSite:
        typeof req.headers['sec-fetch-site'] === 'string' ? req.headers['sec-fetch-site'] : undefined,
    });

    // Tier 1 succeeds immediately
    if (url.pathname === '/tier1-ok.jpg') {
      if (referer === 'https://reader.manga-mock.example/') {
        res.writeHead(200, { 'Content-Type': 'image/jpeg' });
        res.end(VALID_JPEG_BYTES);
        return;
      }
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    // Tier 1 fails 403 -> Tier 2 (Image Host Origin) succeeds
    if (url.pathname === '/tier2-ok.png') {
      if (referer === 'https://cdn.manga-mock.example/') {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(VALID_PNG_BYTES);
        return;
      }
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    // Tier 1 & Tier 2 fail 403 -> Tier 3 (No Referer) succeeds
    if (url.pathname === '/tier3-ok.jpg') {
      if (referer === undefined) {
        res.writeHead(200, { 'Content-Type': 'image/jpeg' });
        res.end(VALID_JPEG_BYTES);
        return;
      }
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    // All 3 tiers fail 403
    if (url.pathname === '/always-403.jpg') {
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    // Fast-fail 404 and 429 (must NOT retry other tiers)
    if (url.pathname === '/missing-404.jpg') {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    if (url.pathname === '/ratelimit-429.jpg') {
      res.writeHead(429);
      res.end('Too Many Requests');
      return;
    }

    res.writeHead(404);
    res.end();
  });

  const net = createMockNetwork({
    hostRoutes: {
      'cdn.manga-mock.example': mockCdn.baseUrl,
    },
  });
  const cacheManager = new ChapterCacheManager({ maxBytes: 10 * 1024 * 1024 });
  const appServer = await startAppServer({
    cacheManager,
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  const chapterReferer = 'https://reader.manga-mock.example/manga/kurogane/chapter-12';

  try {
    // 1. Tier 1 succeeds
    recorded.length = 0;
    const r1 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/tier1-ok.jpg'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r1.status, 200);
    assert.equal(r1.headers.get('content-type'), 'image/jpeg');
    assert.equal(r1.headers.get('x-manga-cache'), 'MISS');
    assert.equal(r1.headers.get('cache-control'), 'public, max-age=2592000, immutable');
    assert.deepEqual(Buffer.from(await r1.arrayBuffer()), VALID_JPEG_BYTES);
    assert.equal(recorded.length, 1);
    assert.equal(recorded[0].referer, 'https://reader.manga-mock.example/');
    assert.equal(recorded[0].origin, undefined, 'Must not send synthetic Origin header');
    assert.ok(recorded[0].accept && !recorded[0].accept.includes('image/avif'));
    assert.equal(recorded[0].secFetchSite, 'same-site');

    // 2. Tier 1 (403) -> Tier 2 succeeds
    recorded.length = 0;
    const r2 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/tier2-ok.png'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r2.status, 200);
    assert.equal(r2.headers.get('content-type'), 'image/png');
    assert.deepEqual(Buffer.from(await r2.arrayBuffer()), VALID_PNG_BYTES);
    assert.deepEqual(
      recorded.map((r) => r.referer),
      ['https://reader.manga-mock.example/', 'https://cdn.manga-mock.example/']
    );
    assert.equal(recorded[1].secFetchSite, 'same-origin');

    // 3. Tier 1 (403) -> Tier 2 (403) -> Tier 3 (no Referer) succeeds
    recorded.length = 0;
    const r3 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/tier3-ok.jpg'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r3.status, 200);
    assert.deepEqual(Buffer.from(await r3.arrayBuffer()), VALID_JPEG_BYTES);
    assert.deepEqual(
      recorded.map((r) => r.referer),
      ['https://reader.manga-mock.example/', 'https://cdn.manga-mock.example/', undefined]
    );
    assert.equal(recorded[2].secFetchSite, 'none');

    // 4. All 3 tiers fail -> HTTP 403 + X-Manga-Diagnostic: HTTP_403
    recorded.length = 0;
    const r4 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/always-403.jpg'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r4.status, 403);
    assert.equal(r4.headers.get('x-manga-diagnostic'), 'HTTP_403');
    assert.equal(recorded.length, 3);

    // 5. HTTP 404 fails fast on first attempt without trying other Referer tiers
    recorded.length = 0;
    const r404 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/missing-404.jpg'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r404.status, 404);
    assert.equal(r404.headers.get('x-manga-diagnostic'), 'HTTP_404');
    assert.equal(recorded.length, 1);

    // 6. HTTP 429 fails fast on first attempt without trying other Referer tiers
    recorded.length = 0;
    const r429 = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/ratelimit-429.jpg'
      )}&referer=${encodeURIComponent(chapterReferer)}`
    );
    assert.equal(r429.status, 429);
    assert.equal(r429.headers.get('x-manga-diagnostic'), 'HTTP_429');
    assert.equal(recorded.length, 1);

    assert.deepEqual(net.unexpectedFetches, []);
  } finally {
    await appServer.close();
    await mockCdn.close();
  }
});

test('/api/image-proxy: HTML & XML security challenges return 403 with X-Manga-Diagnostic and are never cached', async () => {
  const mockCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    const referer = typeof req.headers.referer === 'string' ? req.headers.referer : undefined;

    if (url.pathname === '/cf-html.jpg') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end('<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Check</body></html>');
      return;
    }

    if (url.pathname === '/spoofed-jpeg-html.jpg') {
      // Upstream lies with image/jpeg Content-Type, but payload bytes are HTML
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end('<!DOCTYPE html><html><title>Attention Required! | Cloudflare</title></html>');
      return;
    }

    if (url.pathname === '/s3-xml.jpg') {
      res.writeHead(200, { 'Content-Type': 'application/xml; charset=utf-8' });
      res.end('<?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied</Code></Error>');
      return;
    }

    if (url.pathname === '/html-then-image.jpg') {
      // Tier 1 returns HTML challenge, Tier 2 returns valid image
      if (referer === 'https://reader.manga-mock.example/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end('<!DOCTYPE html><html><title>Just a moment...</title></html>');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end(VALID_JPEG_BYTES);
      return;
    }

    res.writeHead(404);
    res.end();
  });

  const net = createMockNetwork({
    hostRoutes: {
      'cdn.manga-mock.example': mockCdn.baseUrl,
    },
  });
  const cacheManager = new ChapterCacheManager({ maxBytes: 10 * 1024 * 1024 });
  const appServer = await startAppServer({
    cacheManager,
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  try {
    // 1. text/html challenge disguised as HTTP 200
    const htmlRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/cf-html.jpg'
      )}`
    );
    assert.equal(htmlRes.status, 403);
    assert.equal(htmlRes.headers.get('x-manga-diagnostic'), 'HTML_CHALLENGE');
    assert.equal(htmlRes.headers.get('x-manga-received-content-type'), 'text/html');
    const htmlBody = (await htmlRes.json()) as { error: string; receivedContentType: string };
    assert.equal(htmlBody.receivedContentType, 'text/html');
    assert.equal(cacheManager.getStats().entryCount, 0, 'HTML challenge must never be cached');

    // 2. Spoofed image/jpeg header with HTML payload bytes
    const spoofRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/spoofed-jpeg-html.jpg'
      )}`
    );
    assert.equal(spoofRes.status, 403);
    assert.equal(spoofRes.headers.get('x-manga-diagnostic'), 'HTML_CHALLENGE');
    assert.equal(spoofRes.headers.get('x-manga-received-content-type'), 'text/html');
    assert.equal(cacheManager.getStats().entryCount, 0, 'Spoofed HTML must never be cached');

    // 3. application/xml AccessDenied challenge disguised as HTTP 200
    const xmlRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/s3-xml.jpg'
      )}`
    );
    assert.equal(xmlRes.status, 403);
    assert.equal(xmlRes.headers.get('x-manga-diagnostic'), 'XML_CHALLENGE');
    assert.equal(xmlRes.headers.get('x-manga-received-content-type'), 'application/xml');
    assert.equal(cacheManager.getStats().entryCount, 0, 'XML challenge must never be cached');

    // 4. Tier 1 HTML challenge falls back to Tier 2 valid image
    const recovered = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/html-then-image.jpg'
      )}&referer=${encodeURIComponent('https://reader.manga-mock.example/ch-1')}`
    );
    assert.equal(recovered.status, 200);
    assert.equal(recovered.headers.get('content-type'), 'image/jpeg');
    assert.equal(recovered.headers.get('x-manga-cache'), 'MISS');
    assert.deepEqual(Buffer.from(await recovered.arrayBuffer()), VALID_JPEG_BYTES);
    assert.equal(cacheManager.getStats().entryCount, 1);

    assert.deepEqual(net.unexpectedFetches, []);
  } finally {
    await appServer.close();
    await mockCdn.close();
  }
});

test('/api/image-proxy: serves cached HIT and refreshes or evicts on bypassCache=1', async () => {
  let upstreamHits = 0;
  let mode: 'v1' | 'v2' | '404' = 'v1';

  const mockCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/page-cache.jpg') {
      upstreamHits += 1;
      if (mode === '404') {
        res.writeHead(404);
        res.end('Not Found');
        return;
      }
      if (mode === 'v2') {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(VALID_PNG_BYTES);
        return;
      }
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end(VALID_JPEG_BYTES);
      return;
    }
    res.writeHead(404);
    res.end();
  });

  const net = createMockNetwork({
    hostRoutes: {
      'cdn.manga-mock.example': mockCdn.baseUrl,
    },
  });
  const cacheManager = new ChapterCacheManager({ maxBytes: 10 * 1024 * 1024 });
  const appServer = await startAppServer({
    cacheManager,
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  const imageUrl = 'https://cdn.manga-mock.example/page-cache.jpg';

  try {
    // 1. First request -> MISS, populates cache
    const first = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}`
    );
    assert.equal(first.status, 200);
    assert.equal(first.headers.get('x-manga-cache'), 'MISS');
    assert.equal(first.headers.get('content-type'), 'image/jpeg');
    assert.deepEqual(Buffer.from(await first.arrayBuffer()), VALID_JPEG_BYTES);
    assert.equal(upstreamHits, 1);

    // 2. Second request -> HIT from cache (even though upstream changed to v2!)
    mode = 'v2';
    const cached = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}`
    );
    assert.equal(cached.status, 200);
    assert.equal(cached.headers.get('x-manga-cache'), 'HIT');
    assert.deepEqual(Buffer.from(await cached.arrayBuffer()), VALID_JPEG_BYTES);
    assert.equal(upstreamHits, 1, 'Cached HIT must not contact upstream');

    // 3. Request with bypassCache=1 -> evicts old entry, fetches v2, returns REFRESHED
    const bypassed = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}&bypassCache=1`
    );
    assert.equal(bypassed.status, 200);
    assert.equal(bypassed.headers.get('x-manga-cache'), 'REFRESHED');
    assert.equal(bypassed.headers.get('content-type'), 'image/png');
    assert.deepEqual(Buffer.from(await bypassed.arrayBuffer()), VALID_PNG_BYTES);
    assert.equal(upstreamHits, 2);

    // 4. Subsequent normal request -> HIT serving refreshed v2
    const afterRefresh = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}`
    );
    assert.equal(afterRefresh.status, 200);
    assert.equal(afterRefresh.headers.get('x-manga-cache'), 'HIT');
    assert.deepEqual(Buffer.from(await afterRefresh.arrayBuffer()), VALID_PNG_BYTES);
    assert.equal(upstreamHits, 2);

    // 5. bypassCache=1 when upstream now returns 404 -> evicts entry and does not resurrect stale bytes
    mode = '404';
    const failBypass = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}&bypassCache=1`
    );
    assert.equal(failBypass.status, 404);
    assert.equal(failBypass.headers.get('x-manga-diagnostic'), 'HTTP_404');
    assert.equal(upstreamHits, 3);
    assert.equal(cacheManager.getStats().entryCount, 0);

    const afterEvict = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(imageUrl)}`
    );
    assert.equal(afterEvict.status, 404);
    assert.equal(upstreamHits, 4);

    assert.deepEqual(net.unexpectedFetches, []);
  } finally {
    await appServer.close();
    await mockCdn.close();
  }
});
