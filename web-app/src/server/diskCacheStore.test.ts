import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DiskChapterCacheStore } from '../../server/services/cacheManager.ts';
import { makeJpegPayload, VALID_JPEG_BYTES } from './testHelpers.ts';

function createTempCacheDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'mangareader-disk-cache-'));
}

test('DiskChapterCacheStore persists images across restarts and isolates keys via SHA-256 (no path traversal)', async () => {
  const dir = createTempCacheDir();
  try {
    const store1 = new DiskChapterCacheStore({
      cacheDir: dir,
      maxAgeMs: null,
      maxBytes: 10 * 1024 * 1024,
    });

    const traversalKey = 'https://cdn.example/../../etc/passwd?token=../../../secret';
    await store1.set({
      key: traversalKey,
      chapterKey: '../../evil-chapter',
      buffer: VALID_JPEG_BYTES,
      contentType: 'image/jpeg',
    });

    // Verify all written files sit directly inside cacheDir as 64-hex SHA-256 filenames
    const files = fs.readdirSync(dir);
    assert.equal(files.length, 2);
    for (const file of files) {
      assert.match(file, /^[a-f0-9]{64}\.(bin|meta\.json)$/);
    }

    // Recreate a fresh store instance pointing to the same directory -> must hydrate from disk
    const store2 = new DiskChapterCacheStore({
      cacheDir: dir,
      maxAgeMs: null,
      maxBytes: 10 * 1024 * 1024,
    });
    assert.equal(store2.getStats().entryCount, 1);
    assert.equal(store2.getStats().totalBytes, VALID_JPEG_BYTES.byteLength);

    const restored = await store2.get(traversalKey);
    assert.ok(restored);
    assert.equal(restored.contentType, 'image/jpeg');
    assert.deepEqual(restored.buffer, VALID_JPEG_BYTES);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('DiskChapterCacheStore enforces 90% LRU hysteresis without 31-day auto-expiry and guards against clearAll TOCTOU', async () => {
  const dir = createTempCacheDir();
  try {
    const DAY_MS = 24 * 60 * 60 * 1000;
    const baseNow = 1_800_000_000_000;
    const store = new DiskChapterCacheStore({
      cacheDir: dir,
      maxAgeMs: null,
      maxBytes: 3000,
      evictionTargetRatio: 0.9,
    });

    await store.set(
      {
        key: 'https://cdn.example/old.jpg',
        chapterKey: 'ch-1',
        buffer: makeJpegPayload(1000, 0x11),
        contentType: 'image/jpeg',
        cachedAt: baseNow - 90 * DAY_MS,
      },
      baseNow
    );
    await store.set(
      {
        key: 'https://cdn.example/mid.jpg',
        chapterKey: 'ch-1',
        buffer: makeJpegPayload(1000, 0x22),
        contentType: 'image/jpeg',
      },
      baseNow + 100
    );
    await store.set(
      {
        key: 'https://cdn.example/new.jpg',
        chapterKey: 'ch-2',
        buffer: makeJpegPayload(1000, 0x33),
        contentType: 'image/jpeg',
      },
      baseNow + 200
    );

    // 90-day-old entry must NOT be auto-expired by prune()
    const pruneReport = await store.prune(baseNow + 60 * DAY_MS);
    assert.equal(pruneReport.removedExpiredCount, 0);
    assert.equal(pruneReport.remainingEntries, 3);

    // Touch old.jpg so mid.jpg becomes LRU, then overflow with p4
    assert.ok(await store.get('https://cdn.example/old.jpg', baseNow + 300));
    await store.set(
      {
        key: 'https://cdn.example/p4.jpg',
        chapterKey: 'ch-2',
        buffer: makeJpegPayload(1000, 0x44),
        contentType: 'image/jpeg',
      },
      baseNow + 400
    );

    assert.equal(store.getStats().entryCount, 2);
    assert.equal(store.getStats().totalBytes, 2000);
    assert.equal(await store.get('https://cdn.example/mid.jpg'), null);
    assert.ok(await store.get('https://cdn.example/old.jpg'));

    // Generation guard: clearAll invalidates stale in-flight writes
    const genBeforeClear = store.getGeneration();
    await store.clearAll();
    assert.equal(fs.readdirSync(dir).length, 0);

    await store.set(
      {
        key: 'https://cdn.example/stale.jpg',
        chapterKey: 'ch-9',
        buffer: makeJpegPayload(500, 0x55),
        contentType: 'image/jpeg',
      },
      baseNow + 500,
      genBeforeClear
    );
    assert.equal(store.getStats().entryCount, 0);
    assert.equal(fs.readdirSync(dir).length, 0);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
