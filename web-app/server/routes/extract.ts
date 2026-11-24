import express from 'express';
import type { SafeFetchDeps } from '../security/ssrf.ts';
import { extractChapterFromUrl } from '../services/chapterExtractor.ts';

export interface ExtractRoutesDeps {
  fetchDeps: SafeFetchDeps;
  rateLimiter?: express.RequestHandler;
}

export function createExtractRouter(deps: ExtractRoutesDeps): express.Router {
  const router = express.Router();

  const extractHandler: express.RequestHandler = async (req, res, next) => {
    try {
      const rawUrl = typeof req.body?.url === 'string' ? req.body.url : '';
      const outcome = await extractChapterFromUrl(rawUrl, deps.fetchDeps, {
        invalidUrlFallback: 'Ungültige Kapitel-URL.',
        emptyImagesStatus: 422,
        emptyImagesError:
          'Keine Manga-Seitenbilder im HTML gefunden (ggf. Cloudflare-Prüfung oder dynamisches JS).',
        upstreamErrorFormatter: (status) => `Kapitelseite antwortete mit HTTP ${status}.`,
        genericErrorFallback: 'Extraktion fehlgeschlagen.',
      });

      if (!outcome.ok) {
        res.status(outcome.status).json({ error: outcome.error });
        return;
      }
      res.json(outcome.data);
    } catch (err) {
      next(err);
    }
  };

  const prefetchHandler: express.RequestHandler = async (req, res, next) => {
    try {
      const rawUrl = typeof req.body?.url === 'string' ? req.body.url : '';
      const outcome = await extractChapterFromUrl(rawUrl, deps.fetchDeps, {
        invalidUrlFallback: 'Ungültige Pre-Fetch-URL.',
        emptyImagesStatus: 404,
        emptyImagesError: 'Keine Folgekapitel-Bilder gefunden.',
        upstreamErrorFormatter: (status) => `HTTP ${status}`,
        genericErrorFallback: 'Pre-Fetching fehlgeschlagen.',
      });

      if (!outcome.ok) {
        res.status(outcome.status).json({ error: outcome.error });
        return;
      }
      res.json(outcome.data);
    } catch (err) {
      next(err);
    }
  };

  if (deps.rateLimiter) {
    router.post('/api/extract', deps.rateLimiter, extractHandler);
    router.post('/api/prefetch', deps.rateLimiter, prefetchHandler);
  } else {
    router.post('/api/extract', extractHandler);
    router.post('/api/prefetch', prefetchHandler);
  }

  return router;
}
