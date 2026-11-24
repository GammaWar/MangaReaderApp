import test from 'node:test';
import assert from 'node:assert/strict';
import { ChapterCacheManager } from '../utils/cacheManager.ts';
import {
  assertHostResolvesPublic,
  readResponseWithLimit,
  SsrfBlockedError,
  UpstreamTimeoutError,
} from '../../server/security/ssrf.ts';
import { buildRefererTiers, getSecFetchSite } from '../../server/services/imageProxy.ts';
import type { GeminiClientLike } from '../../server/routes/chat.ts';
import {
  createMockNetwork,
  startAppServer,
  startHttpServer,
  VALID_JPEG_BYTES,
} from './testHelpers.ts';

test('SSRF hardening: blocks hex IPv4-mapped IPv6, NAT64, trailing dots, non-standard ports, and handles bracketed public IPv6', async () => {
  for (const host of ['[::ffff:a9fe:a9fe]', '[::ffff:7f00:1]', '[64:ff9b::7f00:1]', '198.18.0.1', '239.255.255.250']) {
    await assert.rejects(
      () => assertHostResolvesPublic(host, async () => [{ address: '93.184.216.34' }]),
      SsrfBlockedError,
      `Expected ${host} to be rejected with SsrfBlockedError`
    );
  }

  // Public bracketed IPv6 literal must resolve without failing dns.lookup on brackets
  const records = await assertHostResolvesPublic('[2606:2800:220:1:248:1893:25c8:1946]');
  assert.equal(records.length, 1);
  assert.equal(records[0].address, '2606:2800:220:1:248:1893:25c8:1946');
  assert.equal(records[0].family, 6);
});

test('STAB-01: readResponseWithLimit aborts a hanging slowloris/tarpit stream actively on timeout', async () => {
  let cancelled = false;
  const hangingStream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array([0xff, 0xd8]));
      // Intentionally never closes or emits further chunks
    },
    cancel() {
      cancelled = true;
    },
  });

  const fakeResponse = new Response(hangingStream, {
    status: 200,
    headers: { 'Content-Type': 'image/jpeg' },
  });

  const startedAt = Date.now();
  await assert.rejects(
    () => readResponseWithLimit(fakeResponse, 1024 * 1024, 120),
    UpstreamTimeoutError
  );
  const elapsed = Date.now() - startedAt;
  assert.ok(elapsed < 1000, `Expected active read timeout < 1000ms, got ${elapsed}ms`);
  assert.equal(cancelled, true, 'Underlying stream must be cancelled on read timeout');
});

test('SEC-04 & STAB-02: image-proxy rejects 0-byte, spoofed JSON, non-image MIME, deduplicates same-origin referer & in-flight fetches, and guards against clearAll TOCTOU', async () => {
  const hitsByPath = new Map<string, number>();
  const slowFetchGate: { release: (() => void) | null } = { release: null };
  let slowFetchStarted: (() => void) | null = null;
  const slowStartedPromise = new Promise<void>((resolve) => {
    slowFetchStarted = resolve;
  });

  const mockCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    hitsByPath.set(url.pathname, (hitsByPath.get(url.pathname) ?? 0) + 1);

    if (url.pathname === '/empty.jpg') {
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end();
      return;
    }

    if (url.pathname === '/fake-json.jpg') {
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end('{"error":"forbidden"}');
      return;
    }

    if (url.pathname === '/video-not-image.jpg') {
      res.writeHead(200, { 'Content-Type': 'video/mp4' });
      res.end(Buffer.alloc(2048, 0xaa));
      return;
    }

    if (url.pathname === '/same-origin-403.jpg') {
      res.writeHead(403);
      res.end('Forbidden');
      return;
    }

    if (url.pathname === '/thundering-herd.jpg') {
      setTimeout(() => {
        res.writeHead(200, { 'Content-Type': 'image/jpeg' });
        res.end(VALID_JPEG_BYTES);
      }, 60);
      return;
    }

    if (url.pathname === '/toctou-race.jpg') {
      slowFetchStarted?.();
      slowFetchGate.release = () => {
        res.writeHead(200, { 'Content-Type': 'image/jpeg' });
        res.end(VALID_JPEG_BYTES);
      };
      return;
    }

    res.writeHead(404);
    res.end();
  });

  const net = createMockNetwork({
    hostRoutes: {
      'cdn.manga-mock.example': mockCdn.baseUrl,
    },
    dnsOverrides: {
      'rebind-internal.example': ['127.0.0.1'],
    },
  });

  const cacheManager = new ChapterCacheManager({ maxBytes: 10 * 1024 * 1024 });
  const appServer = await startAppServer({
    cacheManager,
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  try {
    // Unit checks for buildRefererTiers and multi-label getSecFetchSite
    const sameOriginTiers = buildRefererTiers(
      new URL('https://cdn.example.com/1.jpg'),
      new URL('https://cdn.example.com/ch-1')
    );
    assert.deepEqual(sameOriginTiers, ['https://cdn.example.com/', null]);
    assert.equal(
      getSecFetchSite(new URL('https://a.co.uk/1.jpg'), new URL('https://b.co.uk/ch')),
      'cross-site'
    );
    assert.equal(
      getSecFetchSite(new URL('https://cdn.manga.co.uk/1.jpg'), new URL('https://www.manga.co.uk/ch')),
      'same-site'
    );

    // 1. 0-byte JPEG must be rejected and never cached
    const emptyRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/empty.jpg'
      )}`
    );
    assert.equal(emptyRes.status, 502);
    assert.equal(emptyRes.headers.get('x-manga-diagnostic'), 'NULL_BITMAP');
    assert.equal(cacheManager.getStats().entryCount, 0);

    // 2. JSON body with spoofed Content-Type: image/jpeg must be rejected and never cached
    const fakeJsonRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/fake-json.jpg'
      )}`
    );
    assert.equal(fakeJsonRes.status, 502);
    assert.equal(fakeJsonRes.headers.get('x-manga-diagnostic'), 'NULL_BITMAP');
    assert.equal(cacheManager.getStats().entryCount, 0);

    // 3. Declared non-image MIME type is rejected before caching
    const videoRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/video-not-image.jpg'
      )}`
    );
    assert.equal(videoRes.status, 403);
    assert.equal(cacheManager.getStats().entryCount, 0);

    // 4. Same-Origin Referer deduplication: only 2 upstream attempts (origin/, null), NOT 3
    hitsByPath.clear();
    const sameOriginRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/same-origin-403.jpg'
      )}&referer=${encodeURIComponent('https://cdn.manga-mock.example/ch-1')}`
    );
    assert.equal(sameOriginRes.status, 403);
    assert.equal(hitsByPath.get('/same-origin-403.jpg'), 2);

    // 5. SSRF DNS block returns HTTP 403 immediately (not 502) and does not retry Referer tiers
    const ssrfRes = await fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://rebind-internal.example/page.jpg'
      )}`
    );
    assert.equal(ssrfRes.status, 403);

    // 6. In-flight request deduplication (3 concurrent fetches -> 1 upstream hit)
    hitsByPath.clear();
    const herdUrl = `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
      'https://cdn.manga-mock.example/thundering-herd.jpg'
    )}`;
    const [h1, h2, h3] = await Promise.all([fetch(herdUrl), fetch(herdUrl), fetch(herdUrl)]);
    assert.equal(h1.status, 200);
    assert.equal(h2.status, 200);
    assert.equal(h3.status, 200);
    assert.equal(h1.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(hitsByPath.get('/thundering-herd.jpg'), 1);

    // 7. clearAll() during an in-flight proxy fetch must NOT let stale image populate cleared cache
    const racePromise = fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        'https://cdn.manga-mock.example/toctou-race.jpg'
      )}`
    );
    await slowStartedPromise;

    const clearRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clearAll: true }),
    });
    assert.equal(clearRes.status, 200);

    slowFetchGate.release?.();
    const raceRes = await racePromise;
    assert.equal(raceRes.status, 200);
    assert.equal(
      cacheManager.getStats().entryCount,
      0,
      'Stale in-flight fetch must not resurrect into cache cleared while fetch was pending'
    );
  } finally {
    await appServer.close();
    await mockCdn.close();
  }
});

test('STAB-02 & Express hardening: /api/demo-page blocks prototype keys, global security headers & JSON error middleware work, and /api/extract + /api/prefetch succeed', async () => {
  const htmlServer = await startHttpServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(
      '<html><head><title>Kurogane Chapter 12</title></head><body><img data-src="https://cdn.manga-mock.example/p1.jpg" /><img src="https://cdn.manga-mock.example/p2.webp" /></body></html>'
    );
  });

  const net = createMockNetwork({
    hostRoutes: {
      'reader.manga-mock.example': htmlServer.baseUrl,
    },
  });

  const appServer = await startAppServer({
    cacheManager: new ChapterCacheManager({ maxBytes: 5 * 1024 * 1024 }),
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  try {
    // Prototype-property asset keys must not crash with HTTP 500
    for (const key of ['constructor', 'toString', '__proto__']) {
      const demoRes = await fetch(`${appServer.baseUrl}/api/demo-page?asset=${encodeURIComponent(key)}`);
      assert.equal(demoRes.status, 200, `/api/demo-page?asset=${key} should fall back safely`);
      assert.equal(demoRes.headers.get('content-type'), 'image/jpeg');
      assert.equal(demoRes.headers.get('x-content-type-options'), 'nosniff');
      assert.equal(demoRes.headers.get('referrer-policy'), 'no-referrer');
      const bytes = Buffer.from(await demoRes.arrayBuffer());
      assert.ok(bytes.byteLength > 0);
    }

    // Malformed JSON body returns structured JSON 400 instead of Express HTML
    const badJsonRes = await fetch(`${appServer.baseUrl}/api/extract`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{"url": broken-json',
    });
    assert.equal(badJsonRes.status, 400);
    const badJsonBody = (await badJsonRes.json()) as { error: string };
    assert.match(badJsonBody.error, /JSON/i);

    // /api/extract and /api/prefetch share extraction logic and preserve contract
    for (const endpoint of ['/api/extract', '/api/prefetch']) {
      const res = await fetch(`${appServer.baseUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: 'https://reader.manga-mock.example/manga/kurogane/chapter-12' }),
      });
      assert.equal(res.status, 200);
      const body = (await res.json()) as {
        title: string;
        chapterNumber: number;
        nextChapterUrl: string;
        pages: Array<{ displayUrl: string }>;
      };
      assert.equal(body.title, 'Kurogane Chapter 12');
      assert.equal(body.chapterNumber, 12);
      assert.equal(body.nextChapterUrl, 'https://reader.manga-mock.example/manga/kurogane/chapter-13');
      assert.equal(body.pages.length, 2);
    }
  } finally {
    await appServer.close();
    await htmlServer.close();
  }
});

test('CHAT-01 & CHAT-02: /api/chat sets SSE anti-buffering headers, handles timeouts, hides raw errors, and enforces rate limits', async () => {
  let mode: 'ok' | 'timeout' | 'secret-error' = 'ok';

  const mockGemini: GeminiClientLike = {
    chats: {
      create() {
        return {
          async sendMessageStream() {
            if (mode === 'secret-error') {
              throw new Error('INTERNAL_DB_DSN=postgres://secret@10.0.0.5:5432 failed');
            }
            if (mode === 'timeout') {
              return {
                async *[Symbol.asyncIterator]() {
                  await new Promise((r) => setTimeout(r, 500));
                  yield { text: 'too late' };
                },
              };
            }
            return {
              async *[Symbol.asyncIterator]() {
                yield { text: 'Hallo ' };
                yield { text: 'Leser!' };
              },
            };
          },
        };
      },
    },
  };

  const appServer = await startAppServer({
    cacheManager: new ChapterCacheManager({ maxBytes: 5 * 1024 * 1024 }),
    getGeminiClient: () => mockGemini,
    chatFirstTokenTimeoutMs: 120,
    chatIdleChunkTimeoutMs: 120,
    chatHeartbeatIntervalMs: 5000,
    rateLimits: {
      windowMs: 60_000,
      chatMaxRequests: 3,
    },
  });

  try {
    // 1. Happy path SSE stream + X-Accel-Buffering: no
    mode = 'ok';
    const okRes = await fetch(`${appServer.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Hallo', model: 'gemini-3.1-flash-lite' }),
    });
    assert.equal(okRes.status, 200);
    assert.equal(okRes.headers.get('x-accel-buffering'), 'no');
    const okText = await okRes.text();
    assert.match(okText, /"chunk":"Hallo "/);
    assert.match(okText, /"chunk":"Leser!"/);
    assert.match(okText, /"done":true/);

    // 2. First-token timeout yields clean SSE error event
    mode = 'timeout';
    const timeoutRes = await fetch(`${appServer.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Langsame Anfrage' }),
    });
    assert.equal(timeoutRes.status, 200);
    const timeoutText = await timeoutRes.text();
    assert.match(timeoutText, /Zeitüberschreitung/i);

    // 3. Raw internal error message must NEVER be leaked in SSE error payload
    mode = 'secret-error';
    const errRes = await fetch(`${appServer.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Trigger error' }),
    });
    assert.equal(errRes.status, 200);
    const errText = await errRes.text();
    assert.equal(errText.includes('INTERNAL_DB_DSN'), false, 'Must not leak raw error text');
    assert.equal(errText.includes('postgres://'), false, 'Must not leak raw error text');
    assert.match(errText, /"error":/);

    // 4. 4th request exceeds chatMaxRequests=3 -> deterministic HTTP 429 + Retry-After
    const limitedRes = await fetch(`${appServer.baseUrl}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Rate limited' }),
    });
    assert.equal(limitedRes.status, 429);
    assert.ok(Number(limitedRes.headers.get('retry-after')) >= 1);
  } finally {
    await appServer.close();
  }
});
