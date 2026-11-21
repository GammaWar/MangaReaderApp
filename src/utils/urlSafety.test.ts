import test from 'node:test';
import assert from 'node:assert/strict';
import { isPrivateOrLoopbackIp, isSafeClientUrl, validateExternalUrl } from './urlSafety';

test('validateExternalUrl accepts standard public https URLs', () => {
  const res = validateExternalUrl('https://manhuatop.example.com/manga/kurogane/chapter-12');
  assert.equal(res.valid, true);
  assert.equal(res.url?.hostname, 'manhuatop.example.com');
});

test('validateExternalUrl blocks localhost, loopback, metadata, and private IPs', () => {
  const blocked = [
    'http://localhost:3000/secret',
    'http://127.0.0.1/admin',
    'http://169.254.169.254/latest/meta-data/',
    'http://10.0.0.5/internal',
    'http://192.168.1.1/router',
    'http://172.16.0.10/db',
    'http://[::1]/loopback',
    'http://0x7f000001/',
    'http://2130706433/',
    'javascript:alert(1)',
    'file:///etc/passwd',
    'data:text/html,<h1>x</h1>',
  ];

  for (const url of blocked) {
    const res = validateExternalUrl(url);
    assert.equal(res.valid, false, `Expected ${url} to be blocked`);
  }
});

test('isPrivateOrLoopbackIp detects IPv4-mapped IPv6 and ULA ranges', () => {
  assert.equal(isPrivateOrLoopbackIp('::ffff:127.0.0.1'), true);
  assert.equal(isPrivateOrLoopbackIp('fd12:3456:789a::1'), true);
  assert.equal(isPrivateOrLoopbackIp('fe80::1'), true);
  assert.equal(isPrivateOrLoopbackIp('8.8.8.8'), false);
});

test('isSafeClientUrl allows local app assets and https URLs while blocking javascript/data', () => {
  assert.equal(isSafeClientUrl('/src/assets/images/cover.jpg'), true);
  assert.equal(isSafeClientUrl('/api/image-proxy?url=https%3A%2F%2Fexample.com%2F1.jpg'), true);
  assert.equal(isSafeClientUrl('https://cdn.example.com/p1.webp'), true);
  assert.equal(isSafeClientUrl('javascript:alert(1)'), false);
  assert.equal(isSafeClientUrl('data:image/svg+xml,<svg/>'), false);
  assert.equal(isSafeClientUrl('/src/assets/../../etc/passwd'), false);
});
