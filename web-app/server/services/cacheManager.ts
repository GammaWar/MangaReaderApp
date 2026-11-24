import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import {
  DEFAULT_EVICTION_TARGET_RATIO,
  MAX_CACHE_BYTES,
  type CacheEntry,
  type CacheEntryInput,
  type CacheManagerStats,
  type CachePruneReport,
} from '../../src/utils/cacheManager.ts';
import { CACHE_PRUNE_INTERVAL_MS, DEFAULT_DISK_CACHE_DIR } from '../config.ts';

/**
 * Unified server cache contract satisfied by both the in-memory `ChapterCacheManager`
 * (used in isolated unit/integration tests) and the production `DiskChapterCacheStore`.
 */
export interface ServerCacheStore {
  getGeneration(): number;
  setMaxBytes(nextMaxBytes: number): void;
  get(key: string, now?: number): CacheEntry | null | Promise<CacheEntry | null>;
  set(
    input: CacheEntryInput,
    now?: number,
    expectedGeneration?: number
  ): CachePruneReport | null | Promise<CachePruneReport | null>;
  delete(key: string): boolean | Promise<boolean>;
  prune(now?: number): CachePruneReport | Promise<CachePruneReport>;
  pruneWithLimits(
    maxAgeMs: number | null,
    maxBytes: number,
    targetBytesAfterEviction?: number,
    now?: number
  ): CachePruneReport | Promise<CachePruneReport>;
  clearAll(now?: number): CachePruneReport | Promise<CachePruneReport>;
  getStats(overrideMaxBytes?: number): CacheManagerStats;
}

interface DiskCacheMetadata {
  key: string;
  chapterKey: string;
  digest: string;
  contentType: string;
  sizeBytes: number;
  cachedAt: number;
  lastAccessedAt: number;
}

export interface DiskChapterCacheStoreOptions {
  cacheDir?: string;
  maxAgeMs?: number | null;
  maxBytes?: number;
  evictionTargetRatio?: number;
}

/**
 * Production disk-backed image cache store:
 * - Stores binary image payloads on disk under `cacheDir` using SHA-256 hex filenames
 *   (`<sha256>.bin` + `<sha256>.meta.json`) — immune to path traversal by construction.
 * - Keeps ONLY lightweight metadata in Node RAM (zero image Buffers in heap, preventing OOM up to 32 GB).
 * - Survives server restarts by loading existing verified metadata/payload pairs on startup.
 * - Enforces configurable limit (512 MB - 32 GB, default 4 GB), 90% LRU hysteresis,
 *   no automatic 31-day expiration, atomic temp+rename writes, and a generation guard against clearAll TOCTOU.
 */
export class DiskChapterCacheStore implements ServerCacheStore {
  private readonly cacheDir: string;
  private readonly canonicalRootWithSep: string;
  private readonly maxAgeMs: number | null;
  private maxBytes: number;
  private readonly clampedRatio: number;
  private targetBytesAfterEviction: number;

  private readonly index = new Map<string, DiskCacheMetadata>();
  private totalBytes = 0;
  private lastPruneAt: number | null = null;
  private totalExpiredEvicted = 0;
  private totalSizeEvicted = 0;
  private totalFreedBytes = 0;
  private generation = 0;
  private mutationQueue: Promise<void> = Promise.resolve();

  constructor(options: DiskChapterCacheStoreOptions = {}) {
    const targetDir = path.resolve(options.cacheDir || DEFAULT_DISK_CACHE_DIR);
    fs.mkdirSync(targetDir, { recursive: true });
    this.cacheDir = fs.realpathSync(targetDir);
    this.canonicalRootWithSep = this.cacheDir.endsWith(path.sep)
      ? this.cacheDir
      : `${this.cacheDir}${path.sep}`;

    this.maxAgeMs =
      typeof options.maxAgeMs === 'number' && Number.isFinite(options.maxAgeMs) && options.maxAgeMs > 0
        ? options.maxAgeMs
        : null;
    this.maxBytes =
      typeof options.maxBytes === 'number' && Number.isFinite(options.maxBytes) && options.maxBytes >= 0
        ? options.maxBytes
        : MAX_CACHE_BYTES;
    const ratio = options.evictionTargetRatio ?? DEFAULT_EVICTION_TARGET_RATIO;
    this.clampedRatio = Math.max(0.5, Math.min(1, ratio));
    this.targetBytesAfterEviction = Math.floor(this.maxBytes * this.clampedRatio);

    this.hydrateIndexFromDisk();
  }

  getCacheDir(): string {
    return this.cacheDir;
  }

  getGeneration(): number {
    return this.generation;
  }

  setMaxBytes(nextMaxBytes: number): void {
    if (!Number.isFinite(nextMaxBytes) || nextMaxBytes < 0) return;
    this.maxBytes = nextMaxBytes;
    this.targetBytesAfterEviction = Math.floor(this.maxBytes * this.clampedRatio);
  }

  private withMutationLock<T>(task: () => Promise<T>): Promise<T> {
    const run = this.mutationQueue.then(task, task);
    this.mutationQueue = run.then(
      () => undefined,
      () => undefined
    );
    return run;
  }

  private hashKey(key: string): string {
    return crypto.createHash('sha256').update(key).digest('hex');
  }

  private resolveSafePath(fileName: string): string {
    const candidate = path.resolve(this.cacheDir, fileName);
    if (!candidate.startsWith(this.canonicalRootWithSep)) {
      throw new Error('Ungültiger Cache-Dateipfad außerhalb des Cache-Verzeichnisses.');
    }
    return candidate;
  }

  private getPathsForDigest(digest: string): { binPath: string; metaPath: string } {
    if (!/^[a-f0-9]{64}$/.test(digest)) {
      throw new Error('Ungültiger SHA-256-Cache-Digest.');
    }
    return {
      binPath: this.resolveSafePath(`${digest}.bin`),
      metaPath: this.resolveSafePath(`${digest}.meta.json`),
    };
  }

  private hydrateIndexFromDisk(): void {
    let files: string[];
    try {
      files = fs.readdirSync(this.cacheDir);
    } catch {
      return;
    }

    for (const name of files) {
      // Remove leftover temporary files from interrupted writes
      if (name.endsWith('.tmp')) {
        try {
          fs.unlinkSync(this.resolveSafePath(name));
        } catch {
          // Ignore cleanup errors
        }
        continue;
      }

      if (!name.endsWith('.meta.json')) continue;
      const digest = name.slice(0, -'.meta.json'.length);
      if (!/^[a-f0-9]{64}$/.test(digest)) continue;

      try {
        const { binPath, metaPath } = this.getPathsForDigest(digest);
        if (!fs.existsSync(binPath)) {
          fs.unlinkSync(metaPath);
          continue;
        }
        const stat = fs.statSync(binPath);
        const raw = fs.readFileSync(metaPath, 'utf-8');
        const parsed = JSON.parse(raw) as Partial<DiskCacheMetadata>;
        if (
          typeof parsed.key !== 'string' ||
          typeof parsed.chapterKey !== 'string' ||
          typeof parsed.contentType !== 'string' ||
          typeof parsed.cachedAt !== 'number' ||
          !Number.isFinite(parsed.cachedAt)
        ) {
          continue;
        }

        const meta: DiskCacheMetadata = {
          key: parsed.key,
          chapterKey: parsed.chapterKey,
          digest,
          contentType: parsed.contentType,
          sizeBytes: stat.size,
          cachedAt: parsed.cachedAt,
          lastAccessedAt:
            typeof parsed.lastAccessedAt === 'number' && Number.isFinite(parsed.lastAccessedAt)
              ? parsed.lastAccessedAt
              : parsed.cachedAt,
        };
        this.index.set(meta.key, meta);
        this.totalBytes += meta.sizeBytes;
      } catch {
        // Skip corrupt metadata safely
      }
    }
  }

  private async atomicWriteFile(targetPath: string, data: Buffer | string): Promise<void> {
    const tmpName = `${path.basename(targetPath)}.${process.pid}.${crypto.randomBytes(6).toString('hex')}.tmp`;
    const tmpPath = this.resolveSafePath(tmpName);
    await fsp.writeFile(tmpPath, data);
    await fsp.rename(tmpPath, targetPath);
  }

  private async removeFilesForDigest(digest: string): Promise<void> {
    const { binPath, metaPath } = this.getPathsForDigest(digest);
    await Promise.all([
      fsp.unlink(binPath).catch(() => undefined),
      fsp.unlink(metaPath).catch(() => undefined),
    ]);
  }

  async get(key: string, now: number = Date.now()): Promise<CacheEntry | null> {
    const meta = this.index.get(key);
    if (!meta) return null;

    if (this.maxAgeMs !== null && now - meta.cachedAt > this.maxAgeMs) {
      await this.withMutationLock(() => this.deleteEntryUnlocked(key, 'expired'));
      return null;
    }

    try {
      const { binPath } = this.getPathsForDigest(meta.digest);
      const buffer = await fsp.readFile(binPath);
      meta.lastAccessedAt = now;
      return {
        key: meta.key,
        chapterKey: meta.chapterKey,
        buffer,
        contentType: meta.contentType,
        sizeBytes: buffer.byteLength,
        cachedAt: meta.cachedAt,
        lastAccessedAt: meta.lastAccessedAt,
      };
    } catch {
      // Backing file missing/unreadable -> remove stale index record
      await this.withMutationLock(() => this.deleteEntryUnlocked(key, 'size'));
      return null;
    }
  }

  async set(
    input: CacheEntryInput,
    now: number = Date.now(),
    expectedGeneration?: number
  ): Promise<CachePruneReport | null> {
    return this.withMutationLock(async () => {
      if (expectedGeneration !== undefined && expectedGeneration !== this.generation) {
        return null;
      }

      const sizeBytes = input.buffer.byteLength;
      if (sizeBytes <= 0 || sizeBytes > this.maxBytes) {
        return null;
      }

      const cachedAt = input.cachedAt ?? now;
      if (this.maxAgeMs !== null && now - cachedAt > this.maxAgeMs) {
        return null;
      }

      const digest = this.hashKey(input.key);
      const { binPath, metaPath } = this.getPathsForDigest(digest);

      const meta: DiskCacheMetadata = {
        key: input.key,
        chapterKey: input.chapterKey,
        digest,
        contentType: input.contentType,
        sizeBytes,
        cachedAt,
        lastAccessedAt: now,
      };

      await this.atomicWriteFile(binPath, input.buffer);
      await this.atomicWriteFile(metaPath, JSON.stringify(meta));

      // Re-check generation after async disk write in case clearAll raced
      if (expectedGeneration !== undefined && expectedGeneration !== this.generation) {
        await this.removeFilesForDigest(digest);
        return null;
      }

      const existing = this.index.get(input.key);
      if (existing) {
        this.totalBytes = Math.max(0, this.totalBytes - existing.sizeBytes);
      }

      this.index.set(input.key, meta);
      this.totalBytes += sizeBytes;

      if (this.totalBytes > this.maxBytes) {
        return this.pruneWithLimitsUnlocked(
          this.maxAgeMs,
          this.maxBytes,
          this.targetBytesAfterEviction,
          now
        );
      }

      return null;
    });
  }

  async delete(key: string): Promise<boolean> {
    return this.withMutationLock(async () => {
      if (!this.index.has(key)) return false;
      await this.deleteEntryUnlocked(key, 'size');
      return true;
    });
  }

  async prune(now: number = Date.now()): Promise<CachePruneReport> {
    return this.withMutationLock(() =>
      this.pruneWithLimitsUnlocked(this.maxAgeMs, this.maxBytes, this.targetBytesAfterEviction, now)
    );
  }

  async pruneWithLimits(
    maxAgeMs: number | null,
    maxBytes: number,
    targetBytesAfterEviction: number = Math.floor(maxBytes * this.clampedRatio),
    now: number = Date.now()
  ): Promise<CachePruneReport> {
    return this.withMutationLock(() =>
      this.pruneWithLimitsUnlocked(maxAgeMs, maxBytes, targetBytesAfterEviction, now)
    );
  }

  async clearAll(now: number = Date.now()): Promise<CachePruneReport> {
    // Increment generation immediately so any in-flight fetches already know the cache was cleared
    this.generation += 1;
    return this.withMutationLock(async () => {
      const removedSizeEvictedCount = this.index.size;
      const freedBytes = this.totalBytes;
      const digests = Array.from(this.index.values()).map((entry) => entry.digest);

      this.index.clear();
      this.totalBytes = 0;
      this.totalSizeEvicted += removedSizeEvictedCount;
      this.totalFreedBytes += freedBytes;
      this.lastPruneAt = now;

      await Promise.all(digests.map((digest) => this.removeFilesForDigest(digest)));

      return {
        removedExpiredCount: 0,
        removedSizeEvictedCount,
        freedBytes,
        remainingEntries: 0,
        remainingBytes: 0,
        prunedAt: now,
      };
    });
  }

  getStats(overrideMaxBytes?: number): CacheManagerStats {
    const chapters = new Set<string>();
    for (const entry of this.index.values()) {
      chapters.add(entry.chapterKey);
    }
    const effectiveMaxBytes =
      typeof overrideMaxBytes === 'number' && Number.isFinite(overrideMaxBytes) && overrideMaxBytes > 0
        ? overrideMaxBytes
        : this.maxBytes;

    const usagePercent =
      effectiveMaxBytes > 0
        ? Math.min(100, Number(((this.totalBytes / effectiveMaxBytes) * 100).toFixed(2)))
        : 0;

    return {
      entryCount: this.index.size,
      chapterCount: chapters.size,
      totalBytes: this.totalBytes,
      maxBytes: effectiveMaxBytes,
      maxAgeDays:
        this.maxAgeMs !== null ? Math.round(this.maxAgeMs / (24 * 60 * 60 * 1000)) : null,
      usagePercent,
      lastPruneAt: this.lastPruneAt,
      totalExpiredEvicted: this.totalExpiredEvicted,
      totalSizeEvicted: this.totalSizeEvicted,
      totalFreedBytes: this.totalFreedBytes,
    };
  }

  private async deleteEntryUnlocked(key: string, reason: 'expired' | 'size'): Promise<void> {
    const existing = this.index.get(key);
    if (!existing) return;
    this.index.delete(key);
    this.totalBytes = Math.max(0, this.totalBytes - existing.sizeBytes);
    this.totalFreedBytes += existing.sizeBytes;
    if (reason === 'expired') {
      this.totalExpiredEvicted += 1;
    } else {
      this.totalSizeEvicted += 1;
    }
    await this.removeFilesForDigest(existing.digest);
  }

  private async pruneWithLimitsUnlocked(
    maxAgeMs: number | null,
    maxBytes: number,
    targetBytesAfterEviction: number,
    now: number
  ): Promise<CachePruneReport> {
    let removedExpiredCount = 0;
    let removedSizeEvictedCount = 0;
    let freedBytes = 0;

    if (typeof maxAgeMs === 'number' && Number.isFinite(maxAgeMs) && maxAgeMs > 0) {
      for (const [key, entry] of Array.from(this.index.entries())) {
        if (now - entry.cachedAt > maxAgeMs) {
          freedBytes += entry.sizeBytes;
          await this.deleteEntryUnlocked(key, 'expired');
          removedExpiredCount += 1;
        }
      }
    }

    if (this.totalBytes > maxBytes) {
      const sortedOldestFirst = Array.from(this.index.values()).sort((a, b) => {
        if (a.lastAccessedAt !== b.lastAccessedAt) {
          return a.lastAccessedAt - b.lastAccessedAt;
        }
        return a.cachedAt - b.cachedAt;
      });

      for (const entry of sortedOldestFirst) {
        if (this.totalBytes <= targetBytesAfterEviction) break;
        freedBytes += entry.sizeBytes;
        await this.deleteEntryUnlocked(entry.key, 'size');
        removedSizeEvictedCount += 1;
      }
    }

    this.lastPruneAt = now;

    return {
      removedExpiredCount,
      removedSizeEvictedCount,
      freedBytes,
      remainingEntries: this.index.size,
      remainingBytes: this.totalBytes,
      prunedAt: now,
    };
  }
}

export function createDefaultServerCacheStore(cacheDir?: string): DiskChapterCacheStore {
  return new DiskChapterCacheStore({
    cacheDir,
    maxAgeMs: null, // Keine automatische 31-Tage-Löschung (nur manuell oder bei Limitüberschreitung)
    maxBytes: MAX_CACHE_BYTES, // 4 GB Standardlimit
  });
}

export function startProactiveCacheMaintenance(store: ServerCacheStore): () => void {
  void Promise.resolve(store.prune()).catch((err) => {
    console.warn('[CacheManager] Initiale Speicherlimit-Prüfung fehlgeschlagen:', err);
  });

  const timer = setInterval(() => {
    void Promise.resolve(store.prune())
      .then((report) => {
        if (report.removedSizeEvictedCount > 0) {
          console.log(
            `[CacheManager] Speicherlimit-Prüfung: ${report.removedSizeEvictedCount} älteste Einträge entfernt.`
          );
        }
      })
      .catch((err) => {
        console.warn('[CacheManager] Periodische Speicherlimit-Prüfung fehlgeschlagen:', err);
      });
  }, CACHE_PRUNE_INTERVAL_MS);
  timer.unref();

  return () => {
    clearInterval(timer);
  };
}
