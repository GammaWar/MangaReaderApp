import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ChapterCacheManager,
  clampCacheLimitMb,
  ClientCacheManager,
  DEFAULT_CACHE_LIMIT_MB,
  formatBytesDe,
  formatCacheLimitLabel,
  MAX_CACHE_BYTES,
  MAX_CONFIGURABLE_CACHE_LIMIT_MB,
  mbToBytes,
  MIN_CACHE_LIMIT_MB,
  type CachedImageMeta,
  type ClientCacheAdapter,
} from './cacheManager';

const DAY_MS = 24 * 60 * 60 * 1000;
const GB = 1024 * 1024 * 1024;

function createInMemoryAdapter(initial: CachedImageMeta[] = []): {
  adapter: ClientCacheAdapter;
  deletedUrls: string[];
  clearedCount: { value: number };
} {
  let items = [...initial];
  const deletedUrls: string[] = [];
  const clearedCount = { value: 0 };

  const adapter: ClientCacheAdapter = {
    readIndex: () => items.map((i) => ({ ...i })),
    writeIndex: (next) => {
      items = next.map((i) => ({ ...i }));
    },
    async deleteBlob(url: string) {
      deletedUrls.push(url);
    },
    async clearBlobs() {
      clearedCount.value += 1;
    },
  };

  return { adapter, deletedUrls, clearedCount };
}

test('default constants enforce configurable 4 GB default threshold and 32 GB absolute maximum', () => {
  assert.equal(DEFAULT_CACHE_LIMIT_MB, 4096);
  assert.equal(MAX_CACHE_BYTES, 4 * GB);
  assert.equal(MAX_CONFIGURABLE_CACHE_LIMIT_MB, 32768);
  assert.equal(clampCacheLimitMb(128), MIN_CACHE_LIMIT_MB);
  assert.equal(clampCacheLimitMb(4096), 4096);
  assert.equal(clampCacheLimitMb(32768), 32768);
  assert.equal(clampCacheLimitMb(99999), 32768);
  assert.equal(clampCacheLimitMb(Number.NaN), DEFAULT_CACHE_LIMIT_MB);
  assert.equal(mbToBytes(2048), 2 * GB);
  assert.equal(mbToBytes(32768), 32 * GB);
  assert.equal(formatCacheLimitLabel(512), '512 MB');
  assert.equal(formatCacheLimitLabel(4096), '4 GB');
  assert.equal(formatCacheLimitLabel(32768), '32 GB');
  assert.equal(formatCacheLimitLabel(1536), '1,5 GB');
});

test('ChapterCacheManager retains entries older than 31 days by default (manual delete only)', () => {
  const manager = new ChapterCacheManager();
  const baseNow = 1_800_000_000_000;

  manager.set(
    {
      key: 'https://cdn.example.com/ch1/p1.webp',
      chapterKey: 'https://example.com/ch1',
      buffer: Buffer.alloc(1024),
      contentType: 'image/webp',
      cachedAt: baseNow - 90 * DAY_MS,
    },
    baseNow
  );

  const futureNow = baseNow + 60 * DAY_MS;
  const report = manager.prune(futureNow);

  assert.equal(report.removedExpiredCount, 0);
  assert.equal(report.removedSizeEvictedCount, 0);
  assert.equal(report.remainingEntries, 1);
  assert.ok(manager.get('https://cdn.example.com/ch1/p1.webp', futureNow));
  assert.equal(manager.getStats().maxAgeDays, null);
});

test('ChapterCacheManager evicts oldest/LRU entries when configurable threshold is exceeded', () => {
  const manager = new ChapterCacheManager({
    maxBytes: 1000,
    evictionTargetRatio: 0.8,
  });
  const t0 = 1_800_000_000_000;

  manager.set({ key: 'k1', chapterKey: 'ch1', buffer: Buffer.alloc(400), contentType: 'image/jpeg' }, t0);
  manager.set({ key: 'k2', chapterKey: 'ch1', buffer: Buffer.alloc(400), contentType: 'image/jpeg' }, t0 + 1000);
  assert.ok(manager.get('k1', t0 + 2000));

  const report = manager.set(
    { key: 'k3', chapterKey: 'ch2', buffer: Buffer.alloc(400), contentType: 'image/jpeg' },
    t0 + 3000
  );

  assert.ok(report);
  assert.equal(report.removedSizeEvictedCount, 1);
  assert.equal(report.remainingBytes, 800);
  assert.equal(manager.get('k2', t0 + 3000), null);

  // Lowering limit dynamically trims oldest remaining entry
  manager.setMaxBytes(500);
  const shrinkReport = manager.prune(t0 + 4000);
  assert.equal(shrinkReport.removedSizeEvictedCount, 1);
  assert.equal(shrinkReport.remainingBytes, 400);
});

test('ClientCacheManager calculateStorageUsage sums bytes, chapters, and usagePercent against custom limit', async () => {
  const { adapter } = createInMemoryAdapter();
  const cm = new ClientCacheManager(adapter);
  const t0 = 1_800_000_000_000;

  await cm.recordCachedImage('/api/demo-page?p=1', 512 * 1024 * 1024, 'https://manga.test/ch-1', 'image/jpeg', undefined, t0);
  await cm.recordCachedImage('/api/demo-page?p=2', 512 * 1024 * 1024, 'https://manga.test/ch-2', 'image/webp', undefined, t0 + 1000);

  const usage4Gb = cm.calculateStorageUsage(4 * GB);
  assert.equal(usage4Gb.totalBytes, 1 * GB);
  assert.equal(usage4Gb.entryCount, 2);
  assert.equal(usage4Gb.chapterCount, 2);
  assert.equal(usage4Gb.oldestEntryAt, t0);
  assert.equal(usage4Gb.usagePercent, 25);

  const usage2Gb = cm.calculateStorageUsage(2 * GB);
  assert.equal(usage2Gb.usagePercent, 50);
});

test('ClientCacheManager clearOldCache preserves 60-day-old items when maxAgeDays is null and enforces configurable size limit', async () => {
  const now = 1_800_000_000_000;
  const { adapter, deletedUrls } = createInMemoryAdapter([
    {
      url: 'https://cdn.test/very-old-but-within-limit.jpg',
      chapterUrl: 'https://manga.test/ch-1',
      sizeBytes: 1.5 * GB, // 65 days old -> must NOT be auto-expired by 31-day rule!
      contentType: 'image/jpeg',
      cachedAt: now - 65 * DAY_MS,
      lastAccessedAt: now - 65 * DAY_MS,
    },
    {
      url: 'https://cdn.test/medium-age.jpg',
      chapterUrl: 'https://manga.test/ch-2',
      sizeBytes: 1.5 * GB,
      contentType: 'image/webp',
      cachedAt: now - 10 * DAY_MS,
      lastAccessedAt: now - 10 * DAY_MS,
    },
    {
      url: 'https://cdn.test/recent.jpg',
      chapterUrl: 'https://manga.test/ch-3',
      sizeBytes: 1.5 * GB,
      contentType: 'image/webp',
      cachedAt: now - 1 * DAY_MS,
      lastAccessedAt: now - 1 * DAY_MS,
    },
  ]);

  const cm = new ClientCacheManager(adapter);

  // Within a 6 GB custom limit: even the 65-day-old item stays intact
  const keepAllResult = await cm.clearOldCache(null, 6 * GB, now);
  assert.equal(keepAllResult.removedExpiredCount, 0);
  assert.equal(keepAllResult.removedSizeEvictedCount, 0);
  assert.equal(keepAllResult.remainingEntries, 3);

  // Within default 4 GB limit (4.5 GB total > 4 GB): oldest entry is evicted by size only
  const enforce4Gb = await cm.clearOldCache(null, 4 * GB, now);
  assert.equal(enforce4Gb.removedExpiredCount, 0);
  assert.equal(enforce4Gb.removedSizeEvictedCount, 1);
  assert.equal(enforce4Gb.remainingEntries, 2);
  assert.equal(enforce4Gb.remainingBytes, 3 * GB);
  assert.deepEqual(deletedUrls, ['https://cdn.test/very-old-but-within-limit.jpg']);
});

test('ClientCacheManager clearAll manually deletes all cached entries and blobs', async () => {
  const now = 1_800_000_000_000;
  const { adapter, clearedCount } = createInMemoryAdapter([
    {
      url: 'https://cdn.test/a.jpg',
      chapterUrl: 'https://manga.test/ch-1',
      sizeBytes: 5000,
      contentType: 'image/jpeg',
      cachedAt: now - 120 * DAY_MS,
      lastAccessedAt: now - 120 * DAY_MS,
    },
  ]);

  const cm = new ClientCacheManager(adapter);
  const res = await cm.clearAll(now);
  assert.equal(res.removedCount, 1);
  assert.equal(res.freedBytes, 5000);
  assert.equal(clearedCount.value, 1);
  assert.equal(cm.calculateStorageUsage().totalBytes, 0);
});

test('formatBytesDe formats B, KB, MB, and GB cleanly in German notation', () => {
  assert.equal(formatBytesDe(0), '0 B');
  assert.equal(formatBytesDe(512), '512 B');
  assert.equal(formatBytesDe(2048), '2,0 KB');
  assert.equal(formatBytesDe(512 * 1024 * 1024), '512,0 MB');
  assert.equal(formatBytesDe(4 * GB), '4,0 GB');
  assert.equal(formatBytesDe(32 * GB), '32,0 GB');
  assert.equal(formatBytesDe(1.5 * GB), '1,5 GB');
});
