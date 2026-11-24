import express from 'express';
import type { ServerCacheStore } from '../services/cacheManager.ts';

export interface CacheRoutesDeps {
  cacheStore: ServerCacheStore;
  minCacheBytes: number;
  maxCacheBytes: number;
  cleanupRateLimiter?: express.RequestHandler;
}

export function createCacheRouter(deps: CacheRoutesDeps): express.Router {
  const { cacheStore, minCacheBytes, maxCacheBytes, cleanupRateLimiter } = deps;
  const router = express.Router();

  router.get('/api/cache/status', (req, res) => {
    const rawMaxBytes = typeof req.query.maxBytes === 'string' ? Number(req.query.maxBytes) : NaN;
    if (Number.isFinite(rawMaxBytes) && rawMaxBytes > 0) {
      const clamped = Math.max(minCacheBytes, Math.min(maxCacheBytes, rawMaxBytes));
      res.json(cacheStore.getStats(clamped));
      return;
    }
    res.json(cacheStore.getStats());
  });

  const cleanupHandler: express.RequestHandler = async (req, res, next) => {
    try {
      const clearAll = req.body?.clearAll === true;
      const rawBytes = typeof req.body?.maxSizeBytes === 'number' ? req.body.maxSizeBytes : null;

      let report;
      if (clearAll) {
        report = await cacheStore.clearAll();
      } else if (rawBytes !== null && Number.isFinite(rawBytes) && rawBytes > 0) {
        const clampedBytes = Math.max(minCacheBytes, Math.min(maxCacheBytes, rawBytes));
        cacheStore.setMaxBytes(clampedBytes);
        report = await cacheStore.pruneWithLimits(null, clampedBytes);
      } else {
        report = await cacheStore.prune();
      }

      res.json({
        report,
        stats: cacheStore.getStats(),
      });
    } catch (err) {
      next(err);
    }
  };

  if (cleanupRateLimiter) {
    router.post('/api/cache/cleanup', cleanupRateLimiter, cleanupHandler);
  } else {
    router.post('/api/cache/cleanup', cleanupHandler);
  }

  return router;
}
