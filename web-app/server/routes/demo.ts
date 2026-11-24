import express from 'express';
import fs from 'node:fs';
import path from 'node:path';
import { DEMO_ASSETS, PROJECT_ROOT } from '../config.ts';

export function createDemoRouter(): express.Router {
  const router = express.Router();

  // Diagnostic Demo Endpoint (safe, local, deterministic for testing HTTP 404 / 403 HTML-Challenge / 429 / 503 / null_bitmap / xml_challenge)
  router.get('/api/demo-page', (req, res) => {
    const simError = typeof req.query.simError === 'string' ? req.query.simError : '';
    const assetKey = typeof req.query.asset === 'string' ? req.query.asset : 'manga1';
    const bypassCache = req.query.bypassCache === '1';
    const attempt = Number(req.query.attempt || '0');
    const sessionToken = Number(req.query.sessionToken || '0');

    // Guard against Object.prototype keys like "constructor" or "toString"
    const relAsset = Object.hasOwn(DEMO_ASSETS, assetKey)
      ? DEMO_ASSETS[assetKey]
      : DEMO_ASSETS.manga1;
    const assetPath = path.resolve(PROJECT_ROOT, relAsset);

    const sendAsset = () => {
      if (!assetPath.startsWith(path.resolve(PROJECT_ROOT, 'src/assets') + path.sep)) {
        res.status(400).json({ error: 'Ungültiges Asset.' });
        return;
      }
      if (!fs.existsSync(assetPath)) {
        res.status(404).setHeader('X-Manga-Diagnostic', 'HTTP_404').end();
        return;
      }
      res.setHeader('Content-Type', 'image/jpeg');
      res.setHeader('Cache-Control', 'public, max-age=2592000, immutable');
      res.sendFile(assetPath);
    };

    if (simError === '404') {
      res
        .status(404)
        .setHeader('X-Manga-Diagnostic', 'HTTP_404')
        .json({ error: 'HTTP 404: Datei existiert nicht auf dem Server.' });
      return;
    }

    if (simError === '403_html') {
      if (sessionToken > 0 || bypassCache) {
        sendAsset();
        return;
      }
      res
        .status(403)
        .setHeader('X-Manga-Diagnostic', 'HTML_CHALLENGE')
        .json({
          error: 'HTTP 403: Server lieferte HTML-Sicherheitsabfrage statt Bilddaten.',
        });
      return;
    }

    if (simError === '429') {
      if (attempt >= 2 || bypassCache || sessionToken > 0) {
        sendAsset();
        return;
      }
      res
        .status(429)
        .setHeader('X-Manga-Diagnostic', 'HTTP_429')
        .json({ error: 'HTTP 429: Rate-Limit überschritten.' });
      return;
    }

    if (simError === '503') {
      if (attempt >= 3 || bypassCache || sessionToken > 0) {
        sendAsset();
        return;
      }
      res
        .status(503)
        .setHeader('X-Manga-Diagnostic', 'HTTP_5XX')
        .json({ error: 'HTTP 503: CDN-Knoten temporär überlastet.' });
      return;
    }

    if (simError === 'null_bitmap') {
      if (bypassCache) {
        sendAsset();
        return;
      }
      res.setHeader('Content-Type', 'image/jpeg');
      res.setHeader('Cache-Control', 'no-store');
      res.setHeader('X-Manga-Diagnostic', 'NULL_BITMAP');
      res.send(Buffer.from([0xff, 0xd8, 0x00, 0x00, 0xde, 0xad, 0xbe, 0xef]));
      return;
    }

    if (simError === 'xml_challenge') {
      if (sessionToken > 0 || bypassCache) {
        sendAsset();
        return;
      }
      res.setHeader('Content-Type', 'application/xml; charset=utf-8');
      res.setHeader('Cache-Control', 'no-store');
      res.send(
        '<?xml version="1.0" encoding="UTF-8"?><Error><Code>AccessDenied</Code><Message>CDN security verification required</Message></Error>'
      );
      return;
    }

    sendAsset();
  });

  return router;
}
