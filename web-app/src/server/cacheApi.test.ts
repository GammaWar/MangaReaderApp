import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ChapterCacheManager,
  MAX_CACHE_BYTES,
  MAX_CONFIGURABLE_CACHE_LIMIT_MB,
  MIN_CACHE_LIMIT_MB,
  type CacheManagerStats,
  type CachePruneReport,
} from '../utils/cacheManager.ts';
import {
  createMockNetwork,
  makeJpegPayload,
  startAppServer,
  startHttpServer,
} from './testHelpers.ts';

const MB = 1024 * 1024;
const MIN_SERVER_BYTES = MIN_CACHE_LIMIT_MB * MB; // 512 MB
const MAX_SERVER_BYTES = MAX_CONFIGURABLE_CACHE_LIMIT_MB * MB; // 32 GB

test('/api/cache/status & /api/cache/cleanup: empty cache, limit clamping, and idempotent cleanup', async () => {
  const cacheManager = new ChapterCacheManager({
    maxAgeMs: null,
    maxBytes: MAX_CACHE_BYTES,
  });
  const appServer = await startAppServer({ cacheManager });

  try {
    // 1. Initial empty cache status
    const statusRes = await fetch(`${appServer.baseUrl}/api/cache/status`);
    assert.equal(statusRes.status, 200);
    const stats = (await statusRes.json()) as CacheManagerStats;
    assert.equal(stats.entryCount, 0);
    assert.equal(stats.chapterCount, 0);
    assert.equal(stats.totalBytes, 0);
    assert.equal(stats.maxBytes, MAX_CACHE_BYTES);
    assert.equal(stats.maxAgeDays, null);
    assert.equal(stats.usagePercent, 0);
    assert.equal(stats.totalExpiredEvicted, 0);
    assert.equal(stats.totalSizeEvicted, 0);
    assert.equal(stats.totalFreedBytes, 0);

    // 2. Query maxBytes clamping (below 512 MB -> 512 MB, above 32 GB -> 32 GB, invalid -> default)
    const tooLow = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status?maxBytes=1024`)
    ).json()) as CacheManagerStats;
    assert.equal(tooLow.maxBytes, MIN_SERVER_BYTES);

    const tooHigh = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status?maxBytes=${MAX_SERVER_BYTES * 4}`)
    ).json()) as CacheManagerStats;
    assert.equal(tooHigh.maxBytes, MAX_SERVER_BYTES);

    const invalidQuery = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status?maxBytes=not-a-number`)
    ).json()) as CacheManagerStats;
    assert.equal(invalidQuery.maxBytes, MAX_CACHE_BYTES);

    const negativeQuery = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status?maxBytes=-500`)
    ).json()) as CacheManagerStats;
    assert.equal(negativeQuery.maxBytes, MAX_CACHE_BYTES);

    // 3. Cleanup on empty cache (default prune, explicit maxSizeBytes, and clearAll) succeeds without error
    const emptyPruneRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    assert.equal(emptyPruneRes.status, 200);
    const emptyPrune = (await emptyPruneRes.json()) as {
      report: CachePruneReport;
      stats: CacheManagerStats;
    };
    assert.equal(emptyPrune.report.removedExpiredCount, 0);
    assert.equal(emptyPrune.report.removedSizeEvictedCount, 0);
    assert.equal(emptyPrune.report.freedBytes, 0);
    assert.equal(emptyPrune.report.remainingEntries, 0);
    assert.equal(emptyPrune.stats.totalBytes, 0);

    // 4. Cleanup clamps out-of-range maxSizeBytes cleanly (production 512 MB .. 32 GB bounds)
    const clampLowRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxSizeBytes: 128 }),
    });
    assert.equal(clampLowRes.status, 200);
    const clampLow = (await clampLowRes.json()) as { stats: CacheManagerStats };
    assert.equal(clampLow.stats.maxBytes, MIN_SERVER_BYTES);

    const clampHighRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxSizeBytes: MAX_SERVER_BYTES * 2 }),
    });
    assert.equal(clampHighRes.status, 200);
    const clampHigh = (await clampHighRes.json()) as { stats: CacheManagerStats };
    assert.equal(clampHigh.stats.maxBytes, MAX_SERVER_BYTES);

    // 5. clearAll on empty cache is idempotent
    const clearEmptyRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clearAll: true }),
    });
    assert.equal(clearEmptyRes.status, 200);
    const clearEmpty = (await clearEmptyRes.json()) as {
      report: CachePruneReport;
      stats: CacheManagerStats;
    };
    assert.equal(clearEmpty.report.removedSizeEvictedCount, 0);
    assert.equal(clearEmpty.report.freedBytes, 0);
    assert.equal(clearEmpty.stats.entryCount, 0);
  } finally {
    await appServer.close();
  }
});

test('/api/cache/status & /api/cache/cleanup: enforces limits on a full cache via LRU eviction and clearAll', async () => {
  const PAGE_SIZE = 1000;
  const payloads = new Map<string, Buffer>([
    ['/p1.jpg', makeJpegPayload(PAGE_SIZE, 0x11)],
    ['/p2.jpg', makeJpegPayload(PAGE_SIZE, 0x22)],
    ['/p3.jpg', makeJpegPayload(PAGE_SIZE, 0x33)],
    ['/p4.jpg', makeJpegPayload(PAGE_SIZE, 0x44)],
  ]);

  const mockCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    const data = payloads.get(url.pathname);
    if (data) {
      res.writeHead(200, { 'Content-Type': 'image/jpeg' });
      res.end(data);
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

  // 3000-byte limit -> fits 3 pages cleanly; 4th page triggers LRU eviction to <= 2700 (90% hysteresis)
  const cacheManager = new ChapterCacheManager({
    maxAgeMs: null,
    maxBytes: 3000,
    evictionTargetRatio: 0.9,
  });

  const appServer = await startAppServer({
    cacheManager,
    minCacheBytes: 1000,
    maxCacheBytes: 10_000,
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  const proxyFetch = (path: string, chapter: string) =>
    fetch(
      `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(
        `https://cdn.manga-mock.example${path}`
      )}&referer=${encodeURIComponent(`https://reader.manga-mock.example/${chapter}`)}`
    );

  try {
    // 1. Fill cache to exact 100% capacity (3 x 1000 bytes across 2 chapters)
    assert.equal((await proxyFetch('/p1.jpg', 'ch-1')).headers.get('x-manga-cache'), 'MISS');
    await new Promise((r) => setTimeout(r, 15));
    assert.equal((await proxyFetch('/p2.jpg', 'ch-1')).headers.get('x-manga-cache'), 'MISS');
    await new Promise((r) => setTimeout(r, 15));
    assert.equal((await proxyFetch('/p3.jpg', 'ch-2')).headers.get('x-manga-cache'), 'MISS');

    const fullStats = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status`)
    ).json()) as CacheManagerStats;
    assert.equal(fullStats.entryCount, 3);
    assert.equal(fullStats.chapterCount, 2);
    assert.equal(fullStats.totalBytes, 3000);
    assert.equal(fullStats.maxBytes, 3000);
    assert.equal(fullStats.usagePercent, 100);
    assert.equal(fullStats.maxAgeDays, null);

    // 2. Touch p1 so p2 becomes the least-recently-used entry, then insert p4 (overflows 3000 -> evicts LRU)
    await new Promise((r) => setTimeout(r, 15));
    assert.equal((await proxyFetch('/p1.jpg', 'ch-1')).headers.get('x-manga-cache'), 'HIT');
    await new Promise((r) => setTimeout(r, 15));
    assert.equal((await proxyFetch('/p4.jpg', 'ch-2')).headers.get('x-manga-cache'), 'MISS');

    const afterOverflow = (await (
      await fetch(`${appServer.baseUrl}/api/cache/status`)
    ).json()) as CacheManagerStats;
    assert.ok(afterOverflow.totalBytes <= 3000, 'Cache must stay within maxBytes after overflow');
    assert.equal(afterOverflow.entryCount, 2, '90% hysteresis target (2700 B) keeps 2 x 1000 B entries');
    assert.equal(afterOverflow.totalBytes, 2000);
    assert.equal(afterOverflow.totalExpiredEvicted, 0);
    assert.equal(afterOverflow.totalSizeEvicted, 2);
    assert.equal(afterOverflow.totalFreedBytes, 2000);

    // p1 and p4 are the two most recently used -> must still be HIT
    assert.equal((await proxyFetch('/p1.jpg', 'ch-1')).headers.get('x-manga-cache'), 'HIT');
    assert.equal((await proxyFetch('/p4.jpg', 'ch-2')).headers.get('x-manga-cache'), 'HIT');

    // 3. Dynamically shrink limit via POST /api/cache/cleanup { maxSizeBytes: 1200 }
    // Target after eviction = floor(1200 * 0.9) = 1080 -> evicts 1 entry (1000 B remains <= 1080 B)
    const shrinkRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxSizeBytes: 1200 }),
    });
    assert.equal(shrinkRes.status, 200);
    const shrinkBody = (await shrinkRes.json()) as {
      report: CachePruneReport;
      stats: CacheManagerStats;
    };
    assert.equal(shrinkBody.report.removedExpiredCount, 0);
    assert.equal(shrinkBody.report.removedSizeEvictedCount, 1);
    assert.equal(shrinkBody.report.freedBytes, 1000);
    assert.equal(shrinkBody.report.remainingEntries, 1);
    assert.equal(shrinkBody.report.remainingBytes, 1000);
    assert.equal(shrinkBody.stats.maxBytes, 1200);
    assert.equal(shrinkBody.stats.totalBytes, 1000);
    assert.ok(shrinkBody.stats.totalBytes <= shrinkBody.stats.maxBytes);

    // 4. clearAll removes all remaining cached entries cleanly
    const clearRes = await fetch(`${appServer.baseUrl}/api/cache/cleanup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clearAll: true }),
    });
    assert.equal(clearRes.status, 200);
    const clearBody = (await clearRes.json()) as {
      report: CachePruneReport;
      stats: CacheManagerStats;
    };
    assert.equal(clearBody.report.removedSizeEvictedCount, 1);
    assert.equal(clearBody.report.freedBytes, 1000);
    assert.equal(clearBody.report.remainingEntries, 0);
    assert.equal(clearBody.report.remainingBytes, 0);
    assert.equal(clearBody.stats.entryCount, 0);
    assert.equal(clearBody.stats.chapterCount, 0);
    assert.equal(clearBody.stats.totalBytes, 0);
    assert.equal(clearBody.stats.usagePercent, 0);

    // Subsequent fetch for previously cached p4 must now be a MISS
    assert.equal((await proxyFetch('/p4.jpg', 'ch-2')).headers.get('x-manga-cache'), 'MISS');

    assert.deepEqual(net.unexpectedFetches, []);
  } finally {
    await appServer.close();
    await mockCdn.close();
  }
});
