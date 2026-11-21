import test from 'node:test';
import assert from 'node:assert/strict';
import {
  classifyImageError,
  diagnoseBitmapDecodeFailure,
  getBackoffDelayMs,
  isChallengeContentType,
  isHtmlPayload,
  isValidImageContentType,
  verifyReceivedImageBuffer,
} from './imageDiagnostics';

test('isHtmlPayload detects Cloudflare/HTML and XML responses disguised as images', () => {
  assert.equal(isHtmlPayload('<!DOCTYPE html><html><title>Just a moment...</title>'), true);
  assert.equal(isHtmlPayload('   <HTML lang="en"><body>Blocked</body></html>'), true);
  assert.equal(isHtmlPayload('<?xml version="1.0"?><Error><Code>AccessDenied</Code></Error>'), true);
  assert.equal(isHtmlPayload(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10])), false); // JPEG magic bytes
  assert.equal(isHtmlPayload(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a])), false); // PNG magic bytes
});

test('isValidImageContentType strictly allows image/* and rejects text/html and application/xml', () => {
  assert.equal(isValidImageContentType('image/webp'), true);
  assert.equal(isValidImageContentType('image/jpeg; charset=binary'), true);
  assert.equal(isValidImageContentType('text/html; charset=utf-8'), false);
  assert.equal(isValidImageContentType('application/xml; charset=utf-8'), false);
  assert.equal(isValidImageContentType('text/xml'), false);
  assert.equal(isValidImageContentType(null), false);
  assert.equal(isChallengeContentType('text/html; charset=utf-8'), true);
  assert.equal(isChallengeContentType('application/xml'), true);
  assert.equal(isChallengeContentType('image/png'), false);
});

test('diagnoseBitmapDecodeFailure detects text/html and application/xml buffers as security/CDN challenges', () => {
  // Header is text/html on decode failure -> Security/CDN challenge with Open in Browser
  const htmlDiag = diagnoseBitmapDecodeFailure({
    headerContentType: 'text/html; charset=utf-8',
    bufferPrefix: '<!DOCTYPE html><html><title>Just a moment...</title></html>',
    httpStatus: 200,
  });
  assert.equal(htmlDiag.kind, 'HTML_CHALLENGE');
  assert.equal(htmlDiag.receivedContentType, 'text/html');
  assert.equal(htmlDiag.shouldAutoRetry, false);
  assert.equal(htmlDiag.suggestOpenBrowser, true);

  // Header is application/xml (e.g. S3/CDN AccessDenied) -> Security/CDN challenge with Open in Browser
  const xmlDiag = diagnoseBitmapDecodeFailure({
    headerContentType: 'application/xml',
    bufferPrefix: '<?xml version="1.0"?><Error><Code>AccessDenied</Code></Error>',
    httpStatus: 200,
  });
  assert.equal(xmlDiag.kind, 'HTML_CHALLENGE');
  assert.equal(xmlDiag.receivedContentType, 'application/xml');
  assert.equal(xmlDiag.shouldAutoRetry, false);
  assert.equal(xmlDiag.suggestOpenBrowser, true);

  // Server lied with image/jpeg header, but buffer bytes are XML -> still detected as application/xml challenge!
  const spoofedXmlDiag = diagnoseBitmapDecodeFailure({
    headerContentType: 'image/jpeg',
    bufferPrefix: new TextEncoder().encode('<?xml version="1.0"?><Error><Code>AccessDenied</Code></Error>'),
  });
  assert.equal(spoofedXmlDiag.kind, 'HTML_CHALLENGE');
  assert.equal(spoofedXmlDiag.receivedContentType, 'application/xml');
  assert.equal(spoofedXmlDiag.suggestOpenBrowser, true);

  // Corrupted binary image bytes (true image/jpeg header, non-HTML/XML bytes) -> DECODE_ERROR with Cache-Bypass
  const corruptBinary = diagnoseBitmapDecodeFailure({
    headerContentType: 'image/jpeg',
    bufferPrefix: new Uint8Array([0xff, 0xd8, 0x00, 0x00, 0xde, 0xad, 0xbe, 0xef]),
  });
  assert.equal(corruptBinary.kind, 'DECODE_ERROR');
  assert.equal(corruptBinary.suggestCacheBypass, true);
  assert.equal(corruptBinary.shouldAutoRetry, false);

  const verified = verifyReceivedImageBuffer('application/xml; charset=utf-8', null);
  assert.equal(verified.isSecurityChallenge, true);
  assert.equal(verified.detectedContentType, 'application/xml');
});

test('classifyImageError distinguishes 404, 403 HTML-Challenge, DECODE_ERROR, 429, and 503 properly', () => {
  const d404 = classifyImageError({ httpStatus: 404 });
  assert.equal(d404.kind, 'HTTP_404');
  assert.equal(d404.shouldAutoRetry, false);
  assert.equal(d404.suggestCacheBypass, false);
  assert.equal(d404.severity, 'crimson');

  const dHtml = classifyImageError({ httpStatus: 403, diagnosticHeader: 'HTML_CHALLENGE' });
  assert.equal(dHtml.kind, 'HTML_CHALLENGE');
  assert.equal(dHtml.shouldAutoRetry, false);
  assert.equal(dHtml.suggestSessionRefresh, true);
  assert.equal(dHtml.suggestCacheBypass, true);
  assert.equal(dHtml.suggestOpenBrowser, true);

  const dBitmap = classifyImageError({
    errorMessage: 'BitmapFactory returned a null bitmap. Could not decode image data',
  });
  assert.equal(dBitmap.kind, 'DECODE_ERROR');
  assert.equal(dBitmap.shouldAutoRetry, false);
  assert.equal(dBitmap.suggestCacheBypass, true);
  assert.equal(dBitmap.suggestOpenBrowser, true);
  assert.match(dBitmap.title, /Null Bitmap/i);

  const dBitmapHeader = classifyImageError({ diagnosticHeader: 'NULL_BITMAP' });
  assert.equal(dBitmapHeader.kind, 'DECODE_ERROR');
  assert.equal(dBitmapHeader.suggestCacheBypass, true);

  const d429 = classifyImageError({ httpStatus: 429 });
  assert.equal(d429.kind, 'HTTP_429');
  assert.equal(d429.shouldAutoRetry, true);
  assert.equal(d429.suggestCacheBypass, false);

  const d503 = classifyImageError({ httpStatus: 503 });
  assert.equal(d503.kind, 'HTTP_5XX');
  assert.equal(d503.shouldAutoRetry, true);
  assert.equal(d503.suggestCacheBypass, false);
});

test('getBackoffDelayMs follows 400ms -> 800ms -> 1600ms exponential backoff', () => {
  assert.equal(getBackoffDelayMs(0), 400);
  assert.equal(getBackoffDelayMs(1), 800);
  assert.equal(getBackoffDelayMs(2), 1600);
  assert.equal(getBackoffDelayMs(5), 1600);
});
