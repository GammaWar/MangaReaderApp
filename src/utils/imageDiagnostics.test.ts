import test from 'node:test';
import assert from 'node:assert/strict';
import {
  classifyImageError,
  getBackoffDelayMs,
  isHtmlPayload,
  isValidImageContentType,
} from './imageDiagnostics';

test('isHtmlPayload detects Cloudflare/HTML responses disguised as images', () => {
  assert.equal(isHtmlPayload('<!DOCTYPE html><html><title>Just a moment...</title>'), true);
  assert.equal(isHtmlPayload('   <HTML lang="en"><body>Blocked</body></html>'), true);
  assert.equal(isHtmlPayload(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10])), false); // JPEG magic bytes
  assert.equal(isHtmlPayload(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a])), false); // PNG magic bytes
});

test('isValidImageContentType strictly allows image/* and rejects text/html', () => {
  assert.equal(isValidImageContentType('image/webp'), true);
  assert.equal(isValidImageContentType('image/jpeg; charset=binary'), true);
  assert.equal(isValidImageContentType('text/html; charset=utf-8'), false);
  assert.equal(isValidImageContentType(null), false);
});

test('classifyImageError distinguishes 404, 403 HTML-Challenge, 429, and 503 properly', () => {
  const d404 = classifyImageError({ httpStatus: 404 });
  assert.equal(d404.kind, 'HTTP_404');
  assert.equal(d404.shouldAutoRetry, false);
  assert.equal(d404.severity, 'crimson');

  const dHtml = classifyImageError({ httpStatus: 403, diagnosticHeader: 'HTML_CHALLENGE' });
  assert.equal(dHtml.kind, 'HTML_CHALLENGE');
  assert.equal(dHtml.shouldAutoRetry, false);
  assert.equal(dHtml.suggestSessionRefresh, true);

  const dBitmap = classifyImageError({
    errorMessage: 'BitmapFactory returned a null bitmap. Could not decode image data',
  });
  assert.equal(dBitmap.kind, 'HTML_CHALLENGE');
  assert.equal(dBitmap.suggestOpenBrowser, true);

  const d429 = classifyImageError({ httpStatus: 429 });
  assert.equal(d429.kind, 'HTTP_429');
  assert.equal(d429.shouldAutoRetry, true);

  const d503 = classifyImageError({ httpStatus: 503 });
  assert.equal(d503.kind, 'HTTP_5XX');
  assert.equal(d503.shouldAutoRetry, true);
});

test('getBackoffDelayMs follows 400ms -> 800ms -> 1600ms exponential backoff', () => {
  assert.equal(getBackoffDelayMs(0), 400);
  assert.equal(getBackoffDelayMs(1), 800);
  assert.equal(getBackoffDelayMs(2), 1600);
  assert.equal(getBackoffDelayMs(5), 1600);
});
