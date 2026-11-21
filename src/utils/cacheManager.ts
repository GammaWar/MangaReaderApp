export const MIN_CACHE_LIMIT_MB = 512; // 512 MB
export const DEFAULT_CACHE_LIMIT_MB = 4096; // 4 GB
export const MAX_CONFIGURABLE_CACHE_LIMIT_MB = 32768; // 32 GB absolutes Maximum
export const CACHE_LIMIT_PRESETS_MB = [1024, 2048, 4096, 8192, 16384, 32768] as const;

export const MAX_CACHE_BYTES = DEFAULT_CACHE_LIMIT_MB * 1024 * 1024; // 4 GB Standardlimit
export const DEFAULT_EVICTION_TARGET_RATIO = 0.9; // Ziel-Hysterese (90 % des Limits) nach Schwellenwert-Überschreitung

const CLIENT_CACHE_NAME = 'mangareader-image-cache-v1';
const CLIENT_CACHE_INDEX_KEY = 'mangareader_v1_image_cache_index';

export function clampCacheLimitMb(mb: number): number {
  if (!Number.isFinite(mb)) return DEFAULT_CACHE_LIMIT_MB;
  const rounded = Math.round(mb);
  return Math.max(MIN_CACHE_LIMIT_MB, Math.min(MAX_CONFIGURABLE_CACHE_LIMIT_MB, rounded));
}

export function mbToBytes(mb: number): number {
  return clampCacheLimitMb(mb) * 1024 * 1024;
}

export function formatCacheLimitLabel(mb: number): string {
  const safeMb = clampCacheLimitMb(mb);
  if (safeMb < 1024) return `${safeMb} MB`;
  const gb = safeMb / 1024;
  return Number.isInteger(gb) ? `${gb} GB` : `${gb.toFixed(1).replace('.', ',')} GB`;
}

export interface CacheEntryInput {
  key: string;
  chapterKey: string;
  buffer: Buffer;
  contentType: string;
  cachedAt?: number;
}

export interface CacheEntry {
  key: string;
  chapterKey: string;
  buffer: Buffer;
  contentType: string;
  sizeBytes: number;
  cachedAt: number;
  lastAccessedAt: number;
}

export interface CachePruneReport {
  removedExpiredCount: number;
  removedSizeEvictedCount: number;
  freedBytes: number;
  remainingEntries: number;
  remainingBytes: number;
  prunedAt: number;
}

export interface CacheManagerStats {
  entryCount: number;
  chapterCount: number;
  totalBytes: number;
  maxBytes: number;
  maxAgeDays: number | null;
  usagePercent: number;
  lastPruneAt: number | null;
  totalExpiredEvicted: number;
  totalSizeEvicted: number;
  totalFreedBytes: number;
}

export interface ChapterCacheManagerOptions {
  /** Optional age limit in ms; null (default) disables automatic time-based expiration */
  maxAgeMs?: number | null;
  maxBytes?: number;
  evictionTargetRatio?: number;
}

/**
 * Serverseitiger Kapitel- & Bild-Cache-Manager:
 * - Keine automatische 31-Tage-Ablauffrist (Einträge bleiben bis zum manuellen Löschen
 *   oder bis zum Erreichen des konfigurierbaren Speicherlimits erhalten).
 * - Überwacht den gesamten Speicherverbrauch gegen das konfigurierbare Limit (Standard: 4 GB)
 *   und bereinigt bei Überschreitung älteste / am längsten ungenutzte Einträge.
 */
export class ChapterCacheManager {
  private readonly maxAgeMs: number | null;
  private maxBytes: number;
  private readonly clampedRatio: number;
  private targetBytesAfterEviction: number;
  private readonly entries = new Map<string, CacheEntry>();

  private totalBytes = 0;
  private lastPruneAt: number | null = null;
  private totalExpiredEvicted = 0;
  private totalSizeEvicted = 0;
  private totalFreedBytes = 0;

  constructor(options: ChapterCacheManagerOptions = {}) {
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
  }

  setMaxBytes(nextMaxBytes: number): void {
    if (!Number.isFinite(nextMaxBytes) || nextMaxBytes < 0) return;
    this.maxBytes = nextMaxBytes;
    this.targetBytesAfterEviction = Math.floor(this.maxBytes * this.clampedRatio);
  }

  get(key: string, now: number = Date.now()): CacheEntry | null {
    const entry = this.entries.get(key);
    if (!entry) return null;

    if (this.maxAgeMs !== null && now - entry.cachedAt > this.maxAgeMs) {
      this.deleteEntry(key, 'expired');
      return null;
    }

    entry.lastAccessedAt = now;
    return entry;
  }

  set(input: CacheEntryInput, now: number = Date.now()): CachePruneReport | null {
    const sizeBytes = input.buffer.byteLength;
    if (sizeBytes > this.maxBytes) {
      return null;
    }

    const existing = this.entries.get(input.key);
    if (existing) {
      this.totalBytes -= existing.sizeBytes;
      this.entries.delete(input.key);
    }

    const cachedAt = input.cachedAt ?? now;
    if (this.maxAgeMs !== null && now - cachedAt > this.maxAgeMs) {
      return null;
    }

    const entry: CacheEntry = {
      key: input.key,
      chapterKey: input.chapterKey,
      buffer: input.buffer,
      contentType: input.contentType,
      sizeBytes,
      cachedAt,
      lastAccessedAt: now,
    };

    this.entries.set(input.key, entry);
    this.totalBytes += sizeBytes;

    if (this.totalBytes > this.maxBytes) {
      return this.prune(now);
    }

    return null;
  }

  prune(now: number = Date.now()): CachePruneReport {
    return this.pruneWithLimits(
      this.maxAgeMs,
      this.maxBytes,
      this.targetBytesAfterEviction,
      now
    );
  }

  pruneWithLimits(
    maxAgeMs: number | null,
    maxBytes: number,
    targetBytesAfterEviction: number = Math.floor(maxBytes * this.clampedRatio),
    now: number = Date.now()
  ): CachePruneReport {
    let removedExpiredCount = 0;
    let removedSizeEvictedCount = 0;
    let freedBytes = 0;

    // Phase 1: Optionale zeitbasierte Bereinigung (standardmäßig deaktiviert, maxAgeMs = null)
    if (typeof maxAgeMs === 'number' && Number.isFinite(maxAgeMs) && maxAgeMs > 0) {
      for (const [key, entry] of this.entries.entries()) {
        if (now - entry.cachedAt > maxAgeMs) {
          freedBytes += entry.sizeBytes;
          this.deleteEntry(key, 'expired');
          removedExpiredCount += 1;
        }
      }
    }

    // Phase 2: Speicher-Schwellenwert (> maxBytes) -> älteste / LRU zuerst entfernen
    if (this.totalBytes > maxBytes) {
      const sortedOldestFirst = Array.from(this.entries.values()).sort((a, b) => {
        if (a.lastAccessedAt !== b.lastAccessedAt) {
          return a.lastAccessedAt - b.lastAccessedAt;
        }
        return a.cachedAt - b.cachedAt;
      });

      for (const entry of sortedOldestFirst) {
        if (this.totalBytes <= targetBytesAfterEviction) break;
        freedBytes += entry.sizeBytes;
        this.deleteEntry(entry.key, 'size');
        removedSizeEvictedCount += 1;
      }
    }

    this.lastPruneAt = now;

    return {
      removedExpiredCount,
      removedSizeEvictedCount,
      freedBytes,
      remainingEntries: this.entries.size,
      remainingBytes: this.totalBytes,
      prunedAt: now,
    };
  }

  clearAll(now: number = Date.now()): CachePruneReport {
    const removedSizeEvictedCount = this.entries.size;
    const freedBytes = this.totalBytes;

    this.entries.clear();
    this.totalBytes = 0;
    this.totalSizeEvicted += removedSizeEvictedCount;
    this.totalFreedBytes += freedBytes;
    this.lastPruneAt = now;

    return {
      removedExpiredCount: 0,
      removedSizeEvictedCount,
      freedBytes,
      remainingEntries: 0,
      remainingBytes: 0,
      prunedAt: now,
    };
  }

  getStats(overrideMaxBytes?: number): CacheManagerStats {
    const chapters = new Set<string>();
    for (const entry of this.entries.values()) {
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
      entryCount: this.entries.size,
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

  private deleteEntry(key: string, reason: 'expired' | 'size'): void {
    const existing = this.entries.get(key);
    if (!existing) return;
    this.entries.delete(key);
    this.totalBytes = Math.max(0, this.totalBytes - existing.sizeBytes);
    this.totalFreedBytes += existing.sizeBytes;
    if (reason === 'expired') {
      this.totalExpiredEvicted += 1;
    } else {
      this.totalSizeEvicted += 1;
    }
  }
}

/* -------------------------------------------------------------------------- */
/*  Client-side CacheManager Utility (Browser Cache API + Metadata Index)     */
/* -------------------------------------------------------------------------- */

export interface CachedImageMeta {
  url: string;
  chapterUrl: string;
  sizeBytes: number;
  contentType: string;
  cachedAt: number;
  lastAccessedAt: number;
}

export interface StorageUsageSummary {
  totalBytes: number;
  entryCount: number;
  chapterCount: number;
  oldestEntryAt: number | null;
  maxBytes: number;
  usagePercent: number;
}

export interface ClearCacheResult {
  removedExpiredCount: number;
  removedSizeEvictedCount: number;
  removedCount: number;
  freedBytes: number;
  remainingEntries: number;
  remainingBytes: number;
  prunedAt: number;
}

export interface ClientCacheAdapter {
  readIndex(): CachedImageMeta[];
  writeIndex(entries: CachedImageMeta[]): void;
  putBlob?(url: string, blob: Blob, contentType: string): Promise<void>;
  getBlob?(url: string): Promise<Blob | null>;
  deleteBlob?(url: string): Promise<void>;
  clearBlobs?(): Promise<void>;
}

function createBrowserCacheAdapter(): ClientCacheAdapter {
  const memoryFallback: CachedImageMeta[] = [];

  return {
    readIndex(): CachedImageMeta[] {
      if (typeof window === 'undefined') return [...memoryFallback];
      try {
        const raw = window.localStorage.getItem(CLIENT_CACHE_INDEX_KEY);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        return parsed.filter(
          (item): item is CachedImageMeta =>
            typeof item === 'object' &&
            item !== null &&
            typeof item.url === 'string' &&
            typeof item.sizeBytes === 'number' &&
            Number.isFinite(item.sizeBytes) &&
            item.sizeBytes >= 0 &&
            typeof item.cachedAt === 'number'
        );
      } catch {
        return [];
      }
    },

    writeIndex(entries: CachedImageMeta[]): void {
      if (typeof window === 'undefined') {
        memoryFallback.splice(0, memoryFallback.length, ...entries);
        return;
      }
      try {
        window.localStorage.setItem(CLIENT_CACHE_INDEX_KEY, JSON.stringify(entries));
      } catch {
        // Ignore storage quota errors safely
      }
    },

    async putBlob(url: string, blob: Blob, contentType: string): Promise<void> {
      if (typeof window === 'undefined' || !('caches' in window)) return;
      try {
        const cache = await window.caches.open(CLIENT_CACHE_NAME);
        const response = new Response(blob, {
          headers: {
            'Content-Type': contentType,
            'Cache-Control': 'public, max-age=2592000, immutable',
          },
        });
        await cache.put(url, response);
      } catch {
        // Gracefully ignore Cache API unavailability (e.g. opaque origin)
      }
    },

    async getBlob(url: string): Promise<Blob | null> {
      if (typeof window === 'undefined' || !('caches' in window)) return null;
      try {
        const cache = await window.caches.open(CLIENT_CACHE_NAME);
        const matched = await cache.match(url);
        if (!matched) return null;
        return await matched.blob();
      } catch {
        return null;
      }
    },

    async deleteBlob(url: string): Promise<void> {
      if (typeof window === 'undefined' || !('caches' in window)) return;
      try {
        const cache = await window.caches.open(CLIENT_CACHE_NAME);
        await cache.delete(url);
      } catch {
        // Ignore Cache API errors
      }
    },

    async clearBlobs(): Promise<void> {
      if (typeof window === 'undefined' || !('caches' in window)) return;
      try {
        await window.caches.delete(CLIENT_CACHE_NAME);
      } catch {
        // Ignore Cache API errors
      }
    },
  };
}

/**
 * Client-side CacheManager utility for calculating cached image storage usage,
 * enforcing the configurable storage limit (default: 4 GB), and manual cache clearing.
 * Automatic 31-day expiration is disabled; entries remain until manually cleared
 * or evicted to stay within `maxSizeBytes`.
 */
export class ClientCacheManager {
  private readonly adapter: ClientCacheAdapter;

  constructor(adapter: ClientCacheAdapter = createBrowserCacheAdapter()) {
    this.adapter = adapter;
  }

  getCachedEntries(): CachedImageMeta[] {
    return this.adapter.readIndex();
  }

  /**
   * Calculates current total storage usage of all cached images against `maxBytes`.
   */
  calculateStorageUsage(maxBytes: number = MAX_CACHE_BYTES): StorageUsageSummary {
    const entries = this.adapter.readIndex();
    let totalBytes = 0;
    let oldestEntryAt: number | null = null;
    const chapters = new Set<string>();

    for (const entry of entries) {
      totalBytes += Math.max(0, entry.sizeBytes);
      if (entry.chapterUrl) chapters.add(entry.chapterUrl);
      if (oldestEntryAt === null || entry.cachedAt < oldestEntryAt) {
        oldestEntryAt = entry.cachedAt;
      }
    }

    const safeMaxBytes =
      Number.isFinite(maxBytes) && maxBytes > 0 ? maxBytes : MAX_CACHE_BYTES;
    const usagePercent = Math.min(
      100,
      Number(((totalBytes / safeMaxBytes) * 100).toFixed(2))
    );

    return {
      totalBytes,
      entryCount: entries.length,
      chapterCount: chapters.size,
      oldestEntryAt,
      maxBytes: safeMaxBytes,
      usagePercent,
    };
  }

  /**
   * Records a verified cached image (and persists the Blob into Browser Cache Storage when provided).
   */
  async recordCachedImage(
    url: string,
    sizeBytes: number,
    chapterUrl: string = '',
    contentType: string = 'image/jpeg',
    blob?: Blob,
    now: number = Date.now()
  ): Promise<void> {
    if (!url || !Number.isFinite(sizeBytes) || sizeBytes < 0) return;

    const entries = this.adapter.readIndex().filter((e) => e.url !== url);
    entries.push({
      url,
      chapterUrl,
      sizeBytes,
      contentType,
      cachedAt: now,
      lastAccessedAt: now,
    });
    this.adapter.writeIndex(entries);

    if (blob && this.adapter.putBlob) {
      await this.adapter.putBlob(url, blob, contentType);
    }
  }

  /**
   * Retrieves a previously cached image Blob.
   * When `maxAgeDays` is null (default), no automatic age expiration is applied.
   */
  async getCachedImageBlob(
    url: string,
    maxAgeDays: number | null = null,
    now: number = Date.now()
  ): Promise<Blob | null> {
    if (!this.adapter.getBlob) return null;
    const entries = this.adapter.readIndex();
    const target = entries.find((e) => e.url === url);
    if (!target) return null;

    if (typeof maxAgeDays === 'number' && Number.isFinite(maxAgeDays) && maxAgeDays > 0) {
      const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;
      if (now - target.cachedAt > maxAgeMs) {
        await this.removeUrl(url);
        return null;
      }
    }

    const blob = await this.adapter.getBlob(url);
    if (!blob) return null;

    target.lastAccessedAt = now;
    this.adapter.writeIndex(entries);
    return blob;
  }

  /**
   * Enforces `maxSizeBytes` (default 4 GB) by evicting the oldest / least-recently-used entries
   * until total cached size is within the limit.
   * Automatic time-based expiration is disabled by default (`maxAgeDays = null`);
   * pass an explicit positive `maxAgeDays` only if manual age filtering is desired.
   */
  async clearOldCache(
    maxAgeDays: number | null = null,
    maxSizeBytes: number = MAX_CACHE_BYTES,
    now: number = Date.now()
  ): Promise<ClearCacheResult> {
    const useAgeLimit =
      typeof maxAgeDays === 'number' && Number.isFinite(maxAgeDays) && maxAgeDays > 0;
    const maxAgeMs = useAgeLimit ? maxAgeDays * 24 * 60 * 60 * 1000 : null;
    const safeMaxBytes = Math.max(0, Number.isFinite(maxSizeBytes) ? maxSizeBytes : MAX_CACHE_BYTES);

    const entries = this.adapter.readIndex();
    const surviving: CachedImageMeta[] = [];
    const urlsToDelete: string[] = [];

    let removedExpiredCount = 0;
    let removedSizeEvictedCount = 0;
    let freedBytes = 0;

    // Step 1: Optional age filter (disabled when maxAgeDays is null)
    if (maxAgeMs !== null) {
      for (const entry of entries) {
        if (now - entry.cachedAt > maxAgeMs) {
          removedExpiredCount += 1;
          freedBytes += Math.max(0, entry.sizeBytes);
          urlsToDelete.push(entry.url);
        } else {
          surviving.push(entry);
        }
      }
    } else {
      surviving.push(...entries);
    }

    // Step 2: Evict oldest / least-recently-used entries if total exceeds maxSizeBytes
    let remainingBytes = surviving.reduce((sum, item) => sum + Math.max(0, item.sizeBytes), 0);

    if (remainingBytes > safeMaxBytes) {
      surviving.sort((a, b) => {
        const accessA = a.lastAccessedAt ?? a.cachedAt;
        const accessB = b.lastAccessedAt ?? b.cachedAt;
        if (accessA !== accessB) return accessA - accessB;
        return a.cachedAt - b.cachedAt;
      });

      const kept: CachedImageMeta[] = [];
      for (const entry of surviving) {
        if (remainingBytes > safeMaxBytes) {
          remainingBytes = Math.max(0, remainingBytes - Math.max(0, entry.sizeBytes));
          freedBytes += Math.max(0, entry.sizeBytes);
          removedSizeEvictedCount += 1;
          urlsToDelete.push(entry.url);
        } else {
          kept.push(entry);
        }
      }
      surviving.length = 0;
      surviving.push(...kept);
    }

    if (urlsToDelete.length > 0) {
      this.adapter.writeIndex(surviving);
      if (this.adapter.deleteBlob) {
        await Promise.all(urlsToDelete.map((u) => this.adapter.deleteBlob!(u)));
      }
    }

    return {
      removedExpiredCount,
      removedSizeEvictedCount,
      removedCount: removedExpiredCount + removedSizeEvictedCount,
      freedBytes,
      remainingEntries: surviving.length,
      remainingBytes,
      prunedAt: now,
    };
  }

  /**
   * Manual deletion of all cached images and metadata.
   */
  async clearAll(now: number = Date.now()): Promise<ClearCacheResult> {
    const existing = this.adapter.readIndex();
    const freedBytes = existing.reduce((sum, item) => sum + Math.max(0, item.sizeBytes), 0);
    this.adapter.writeIndex([]);
    if (this.adapter.clearBlobs) {
      await this.adapter.clearBlobs();
    }
    return {
      removedExpiredCount: 0,
      removedSizeEvictedCount: existing.length,
      removedCount: existing.length,
      freedBytes,
      remainingEntries: 0,
      remainingBytes: 0,
      prunedAt: now,
    };
  }

  private async removeUrl(url: string): Promise<void> {
    const remaining = this.adapter.readIndex().filter((e) => e.url !== url);
    this.adapter.writeIndex(remaining);
    if (this.adapter.deleteBlob) {
      await this.adapter.deleteBlob(url);
    }
  }
}

export const CacheManager = new ClientCacheManager();

export function calculateStorageUsage(maxBytes: number = MAX_CACHE_BYTES): StorageUsageSummary {
  return CacheManager.calculateStorageUsage(maxBytes);
}

export function clearOldCache(
  maxAgeDays: number | null = null,
  maxSizeBytes: number = MAX_CACHE_BYTES
): Promise<ClearCacheResult> {
  return CacheManager.clearOldCache(maxAgeDays, maxSizeBytes);
}

export function formatBytesDe(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1).replace('.', ',')} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1).replace('.', ',')} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(1).replace('.', ',')} GB`;
}
