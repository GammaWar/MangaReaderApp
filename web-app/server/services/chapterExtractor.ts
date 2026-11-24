import {
  analyzeChapterUrl,
  extractImageUrlsFromHtml,
  extractTitleFromHtml,
} from '../../src/utils/urlChapterAnalyzer.ts';
import { validateExternalUrl } from '../../src/utils/urlSafety.ts';
import { MAX_HTML_BYTES, READ_TIMEOUT_MS, SAFE_USER_AGENT } from '../config.ts';
import {
  PayloadTooLargeError,
  readResponseWithLimit,
  safeFetchWithRedirects,
  SsrfBlockedError,
  UpstreamTimeoutError,
  type SafeFetchDeps,
} from '../security/ssrf.ts';

export interface ExtractedChapterPayload {
  title: string;
  chapterUrl: string;
  sourceName: string;
  coverUrl: string;
  chapterNumber: number | null;
  nextChapterUrl: string | null;
  prevChapterUrl: string | null;
  pages: Array<{
    index: number;
    url: string;
    displayUrl: string;
    label: string;
  }>;
}

export type ChapterExtractionOutcome =
  | { ok: true; data: ExtractedChapterPayload }
  | { ok: false; status: number; error: string };

export interface ChapterExtractionOptions {
  invalidUrlFallback: string;
  emptyImagesStatus: 404 | 422;
  emptyImagesError: string;
  upstreamErrorFormatter: (status: number) => string;
  genericErrorFallback: string;
}

export async function extractChapterFromUrl(
  rawUrl: string,
  fetchDeps: SafeFetchDeps,
  options: ChapterExtractionOptions
): Promise<ChapterExtractionOutcome> {
  const check = validateExternalUrl(rawUrl);
  if (!check.valid || !check.url) {
    return {
      ok: false,
      status: 400,
      error: check.reason || options.invalidUrlFallback,
    };
  }

  try {
    const upstream = await safeFetchWithRedirects(
      check.url.toString(),
      {
        'User-Agent': SAFE_USER_AGENT,
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'de-DE,de;q=0.9,en;q=0.8',
      },
      3,
      fetchDeps
    );

    if (!upstream.ok) {
      await upstream.body?.cancel().catch(() => undefined);
      return {
        ok: false,
        status: upstream.status,
        error: options.upstreamErrorFormatter(upstream.status),
      };
    }

    const buffer = await readResponseWithLimit(
      upstream,
      MAX_HTML_BYTES,
      fetchDeps.readTimeoutMs ?? READ_TIMEOUT_MS
    );
    const html = buffer.toString('utf-8');
    const imageUrls = extractImageUrlsFromHtml(html, check.url.toString());

    if (imageUrls.length === 0) {
      return {
        ok: false,
        status: options.emptyImagesStatus,
        error: options.emptyImagesError,
      };
    }

    const title = extractTitleFromHtml(html, check.url.toString());
    const analysis = analyzeChapterUrl(check.url.toString());
    const chapterUrl = check.url.toString();
    const pages = imageUrls.slice(0, 150).map((imgUrl, idx) => ({
      index: idx,
      url: `/api/image-proxy?url=${encodeURIComponent(imgUrl)}&referer=${encodeURIComponent(
        chapterUrl
      )}`,
      displayUrl: imgUrl,
      label: `Seite ${idx + 1}`,
    }));

    return {
      ok: true,
      data: {
        title,
        chapterUrl,
        sourceName: check.url.hostname,
        coverUrl: pages[0].url,
        chapterNumber: analysis.chapterNumber,
        nextChapterUrl: analysis.nextChapterUrl,
        prevChapterUrl: analysis.prevChapterUrl,
        pages,
      },
    };
  } catch (err) {
    if (err instanceof SsrfBlockedError) {
      return { ok: false, status: err.statusCode, error: err.message };
    }
    if (err instanceof UpstreamTimeoutError) {
      return { ok: false, status: 504, error: err.message };
    }
    if (err instanceof PayloadTooLargeError) {
      return { ok: false, status: 413, error: err.message };
    }
    const msg = err instanceof Error ? err.message : options.genericErrorFallback;
    return { ok: false, status: 502, error: msg };
  }
}
