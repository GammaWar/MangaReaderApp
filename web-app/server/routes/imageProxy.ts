import express from 'express';
import { validateExternalUrl } from '../../src/utils/urlSafety.ts';
import type { SafeFetchDeps } from '../security/ssrf.ts';
import type { ServerCacheStore } from '../services/cacheManager.ts';
import { createImageProxyService } from '../services/imageProxy.ts';

export interface ImageProxyRouteDeps {
  cacheStore: ServerCacheStore;
  fetchDeps: SafeFetchDeps;
  rateLimiter?: express.RequestHandler;
}

export function createImageProxyRouter(deps: ImageProxyRouteDeps): express.Router {
  const router = express.Router();
  const proxyService = createImageProxyService({
    cacheStore: deps.cacheStore,
    fetchDeps: deps.fetchDeps,
  });

  const handler: express.RequestHandler = async (req, res, next) => {
    try {
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

      const outcome = await proxyService.resolveImage(imgCheck.url, refererUrl, bypassCache);
      if (outcome.ok) {
        res.setHeader('Content-Type', outcome.contentType);
        res.setHeader('X-Content-Type-Options', 'nosniff');
        res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
        res.setHeader('X-Manga-Cache', outcome.cacheStatus);
        res.send(outcome.buffer);
        return;
      }

      if (outcome.receivedContentType) {
        res.setHeader('X-Manga-Received-Content-Type', outcome.receivedContentType);
      }
      res
        .status(outcome.status)
        .setHeader('X-Manga-Diagnostic', outcome.diagnostic)
        .json({
          error: outcome.error,
          receivedContentType: outcome.receivedContentType,
        });
    } catch (err) {
      next(err);
    }
  };

  if (deps.rateLimiter) {
    router.get('/api/image-proxy', deps.rateLimiter, handler);
  } else {
    router.get('/api/image-proxy', handler);
  }

  return router;
}
