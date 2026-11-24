import test from 'node:test';
import assert from 'node:assert/strict';
import {
  analyzeChapterUrl,
  buildDownloadFolderId,
  extractImageUrlsFromHtml,
  extractTitleFromHtml,
} from './urlChapterAnalyzer';

test('analyzeChapterUrl increments chapter numbers with strict boundaries', () => {
  const res = analyzeChapterUrl('https://reader.example.com/manga/kurogane/chapter-12/');
  assert.equal(res.chapterNumber, 12);
  assert.equal(res.hasNextChapter, true);
  assert.equal(res.nextChapterUrl, 'https://reader.example.com/manga/kurogane/chapter-13/');
  assert.equal(res.prevChapterUrl, 'https://reader.example.com/manga/kurogane/chapter-11/');
  assert.equal(res.isUuidPath, false);
});

test('analyzeChapterUrl handles decimal chapters and query parameters', () => {
  const dec = analyzeChapterUrl('https://example.com/series/ch_12.5');
  assert.equal(dec.chapterNumber, 12.5);
  assert.equal(dec.nextChapterUrl, 'https://example.com/series/ch_13');

  const query = analyzeChapterUrl('https://example.com/read?chapter=104&lang=de');
  assert.equal(query.chapterNumber, 104);
  assert.equal(query.nextChapterUrl, 'https://example.com/read?chapter=105&lang=de');
});

test('analyzeChapterUrl protects MangaDex UUID paths and ignores mid-word c123 matches', () => {
  const mangadex = analyzeChapterUrl(
    'https://mangadex.org/chapter/a1b2c3d4-e5f6-7890-abcd-ef1234567890'
  );
  assert.equal(mangadex.isUuidPath, true);
  assert.equal(mangadex.hasNextChapter, false);
  assert.equal(mangadex.nextChapterUrl, null);

  const falsePositive = analyzeChapterUrl('https://example.com/manga/abc123/overview');
  assert.equal(falsePositive.chapterNumber, null);
  assert.equal(falsePositive.hasNextChapter, false);
});

test('buildDownloadFolderId avoids collisions for same chapter title and supports CJK titles', () => {
  const idA = buildDownloadFolderId('Chapter 5', 'https://site-a.example.com/manga-1/chapter-5');
  const idB = buildDownloadFolderId('Chapter 5', 'https://site-b.example.com/manga-2/chapter-5');
  assert.notEqual(idA, idB);

  const jp = buildDownloadFolderId('黒鉄の刃 第12話', 'https://jp.example.com/kurogane/12');
  assert.ok(jp.includes('黒鉄の刃'));
  assert.ok(!jp.startsWith('____'));
});

test('extractImageUrlsFromHtml prefers lazy attributes and filters logos/icons', () => {
  const html = `
    <html>
      <head><title>Kurogane Chapter 12 - MangaSite</title></head>
      <body>
        <img src="/assets/logo.png" />
        <img src="/placeholder.gif" data-src="https://cdn.example.com/ch12/01.webp" />
        <img data-original="/ch12/02.jpg" src="/spinner.svg" />
        <img src="https://cdn.example.com/avatar/user.png" />
      </body>
    </html>
  `;
  const urls = extractImageUrlsFromHtml(html, 'https://manga.example.com/read/ch12');
  assert.deepEqual(urls, [
    'https://cdn.example.com/ch12/01.webp',
    'https://manga.example.com/ch12/02.jpg',
  ]);
  assert.equal(
    extractTitleFromHtml(html, 'https://manga.example.com/read/ch12'),
    'Kurogane Chapter 12 - MangaSite'
  );
});
