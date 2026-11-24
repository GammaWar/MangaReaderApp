export interface ChapterAnalysis {
  chapterNumber: number | null;
  hasNextChapter: boolean;
  nextChapterUrl: string | null;
  prevChapterUrl: string | null;
  isUuidPath: boolean;
}

const UUID_REGEX =
  /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

/**
 * Strict boundary chapter patterns to avoid false positives on UUIDs or strings like "abc123".
 * Matches e.g.:
 * - /chapter-12, /chapter_12, /chapter/12, /chapter-12.5
 * - /kapitel-4, /episode-24, /ep-3, /ch-7, /c-15
 * - ?chapter=104, &ch=9
 */
const CHAPTER_PATTERNS: RegExp[] = [
  /(?<=[/_?&=-]|^)(chapter|kapitel|episode|ep|ch|c)([-_/=])(\d+(?:\.\d+)?)(?=[/_?&#.-]|$)/i,
  /(?<=[/_?&=-]|^)(chapter|kapitel|episode|ep|ch)(\d+(?:\.\d+)?)(?=[/_?&#.-]|$)/i,
];

export function analyzeChapterUrl(rawUrl: string): ChapterAnalysis {
  const trimmed = rawUrl.trim();
  if (!trimmed) {
    return {
      chapterNumber: null,
      hasNextChapter: false,
      nextChapterUrl: null,
      prevChapterUrl: null,
      isUuidPath: false,
    };
  }

  // MangaDex & UUID-based chapter URLs cannot be mathematically incremented
  if (UUID_REGEX.test(trimmed)) {
    return {
      chapterNumber: null,
      hasNextChapter: false,
      nextChapterUrl: null,
      prevChapterUrl: null,
      isUuidPath: true,
    };
  }

  let parsedUrl: URL | null = null;
  try {
    parsedUrl = new URL(trimmed);
  } catch {
    // Allow internal catalog pseudo-URLs or relative paths
  }

  const targetSegment = parsedUrl ? `${parsedUrl.pathname}${parsedUrl.search}` : trimmed;

  for (const pattern of CHAPTER_PATTERNS) {
    const match = pattern.exec(targetSegment);
    if (!match) continue;

    // Pattern 1 has 3 groups (prefix, sep, num), Pattern 2 has 2 groups (prefix, num)
    const hasSep = match.length === 4;
    const prefix = match[1];
    const sep = hasSep ? match[2] : '';
    const numStr = hasSep ? match[3] : match[2];
    const num = Number.parseFloat(numStr);

    if (!Number.isFinite(num) || num < 0) continue;

    const isDecimal = numStr.includes('.');
    const nextNum = isDecimal ? Math.floor(num) + 1 : num + 1;
    const prevNum = isDecimal ? Math.floor(num) : num - 1;

    const replaceInTarget = (newVal: number): string => {
      const replacement = `${prefix}${sep}${newVal}`;
      const matchStart = match.index;
      const matchEnd = matchStart + match[0].length;
      const updatedSegment =
        targetSegment.slice(0, matchStart) + replacement + targetSegment.slice(matchEnd);
      if (parsedUrl) {
        return `${parsedUrl.origin}${updatedSegment}${parsedUrl.hash}`;
      }
      return updatedSegment;
    };

    return {
      chapterNumber: num,
      hasNextChapter: true,
      nextChapterUrl: replaceInTarget(nextNum),
      prevChapterUrl: prevNum >= 1 ? replaceInTarget(prevNum) : null,
      isUuidPath: false,
    };
  }

  return {
    chapterNumber: null,
    hasNextChapter: false,
    nextChapterUrl: null,
    prevChapterUrl: null,
    isUuidPath: false,
  };
}

/**
 * Generates a collision-safe folder/storage ID combining sanitized title + FNV-1a hex hash of URL.
 * Handles Japanese/Korean/Unicode titles cleanly without collapsing into "_____".
 */
export function buildDownloadFolderId(title: string, chapterUrl: string): string {
  const sanitized = title
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-zA-Z0-9\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40) || 'chapter';

  let hash = 0x811c9dc5;
  const input = `${title}|${chapterUrl}`;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  const hexHash = (hash >>> 0).toString(16).padStart(8, '0');
  return `${sanitized}_${hexHash}`;
}

const EXCLUDED_IMAGE_PATTERNS = [
  /logo/i,
  /icon/i,
  /favicon/i,
  /avatar/i,
  /banner/i,
  /ads?[-_/]/i,
  /spinner/i,
  /loading\.(gif|svg)/i,
  /pixel\.(gif|png)/i,
  /\.svg(\?|$)/i,
  /gravatar\.com/i,
  /facebook\.com\/tr/i,
];

/**
 * Extracts manga page image URLs from raw HTML string cleanly without executing any scripts.
 */
export function extractImageUrlsFromHtml(html: string, baseUrl: string): string[] {
  const results: string[] = [];
  const seen = new Set<string>();

  const imgTagRegex = /<img\b[^>]*>/gi;
  const attrPriority = ['data-src', 'data-original', 'data-lazy-src', 'data-cfsrc', 'src'];

  let tagMatch: RegExpExecArray | null;
  while ((tagMatch = imgTagRegex.exec(html)) !== null) {
    const tag = tagMatch[0];
    let rawSrc: string | null = null;

    for (const attr of attrPriority) {
      const attrRegex = new RegExp(`\\b${attr}\\s*=\\s*(?:"([^"]+)"|'([^']+)'|([^\\s>]+))`, 'i');
      const m = attrRegex.exec(tag);
      const val = (m?.[1] || m?.[2] || m?.[3] || '').trim();
      if (val && !val.startsWith('data:')) {
        rawSrc = val;
        break;
      }
    }

    if (!rawSrc) continue;

    let resolved: string;
    try {
      resolved = new URL(rawSrc, baseUrl).toString();
    } catch {
      continue;
    }

    if (!resolved.startsWith('http://') && !resolved.startsWith('https://')) continue;
    if (EXCLUDED_IMAGE_PATTERNS.some((re) => re.test(resolved))) continue;
    if (seen.has(resolved)) continue;

    seen.add(resolved);
    results.push(resolved);
  }

  return results;
}

export function extractTitleFromHtml(html: string, fallbackUrl: string): string {
  const titleMatch = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  if (titleMatch?.[1]) {
    const cleaned = titleMatch[1]
      .replace(/\s+/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .trim();
    if (cleaned) return cleaned.slice(0, 120);
  }
  try {
    const u = new URL(fallbackUrl);
    return `${u.hostname}${u.pathname}`.slice(0, 120);
  } catch {
    return 'Extrahiertes Kapitel';
  }
}
