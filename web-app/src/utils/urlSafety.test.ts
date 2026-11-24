import test from 'node:test';
import assert from 'node:assert/strict';
import { isPrivateOrLoopbackIp, isSafeClientUrl, stripIpv6Brackets, validateExternalUrl } from './urlSafety';

test('validateExternalUrl accepts standard public https URLs and default ports', () => {
  const res = validateExternalUrl('https://manhuatop.example.com/manga/kurogane/chapter-12');
  assert.equal(res.valid, true);
  assert.equal(res.url?.hostname, 'manhuatop.example.com');

  assert.equal(validateExternalUrl('https://cdn.example.com:443/page.jpg').valid, true);
  assert.equal(validateExternalUrl('http://cdn.example.com:80/page.jpg').valid, true);
  assert.equal(validateExternalUrl('https://[2606:2800:220:1:248:1893:25c8:1946]/page.jpg').valid, true);
});

test('validateExternalUrl blocks localhost, loopback, metadata, private IPs, trailing dots, and non-standard ports', () => {
  const blocked = [
    'http://localhost:3000/secret',
    'http://localhost./secret',
    'http://sub.localhost./secret',
    'http://metadata.google.internal./computeMetadata/v1/',
    'http://router.local./admin',
    'http://127.0.0.1/admin',
    'http://169.254.169.254/latest/meta-data/',
    'http://10.0.0.5/internal',
    'http://192.168.1.1/router',
    'http://172.16.0.10/db',
    'http://198.18.0.1/benchmark',
    'http://225.0.0.1/multicast',
    'http://239.255.255.250/ssdp',
    'http://241.0.0.1/reserved',
    'http://192.0.2.1/testnet',
    'http://[::1]/loopback',
    'http://[::ffff:169.254.169.254]/latest/meta-data/',
    'http://[::ffff:a9fe:a9fe]/latest/meta-data/',
    'http://[::ffff:7f00:1]/admin',
    'http://[0:0:0:0:0:ffff:127.0.0.1]/admin',
    'http://[64:ff9b::127.0.0.1]/nat64',
    'http://[64:ff9b::7f00:1]/nat64',
    'http://[2002:7f00:0001::]/6to4',
    'http://[ff02::1]/multicast',
    'http://[fec0::1]/site-local',
    'http://[2001:db8::1]/doc',
    'http://example.com:22/ssh',
    'http://example.com:6379/redis',
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

test('isPrivateOrLoopbackIp detects IPv4 CIDRs, IPv4-mapped IPv6 hex, NAT64, Zone-IDs, and ULA ranges', () => {
  const privateIps = [
    '::ffff:127.0.0.1',
    '::ffff:7f00:1',
    '::ffff:a9fe:a9fe',
    '0:0:0:0:0:ffff:0a00:0001',
    '::ffff:0:127.0.0.1',
    '64:ff9b::7f00:1',
    '64:ff9b:1::1',
    '2002:7f00:1::',
    'fd12:3456:789a::1',
    'fe80::1',
    'fe80::1%eth0',
    'fec0::1',
    'ff02::1',
    '198.18.0.1',
    '225.0.0.1',
    '239.255.255.250',
    '241.0.0.1',
  ];
  for (const ip of privateIps) {
    assert.equal(isPrivateOrLoopbackIp(ip), true, `Expected IP ${ip} to be private/blocked`);
  }

  const publicIps = [
    '8.8.8.8',
    '93.184.216.34',
    '1.1.1.1',
    '::ffff:93.184.216.34',
    '::ffff:5db8:d822',
    '2606:2800:220:1:248:1893:25c8:1946',
  ];
  for (const ip of publicIps) {
    assert.equal(isPrivateOrLoopbackIp(ip), false, `Expected IP ${ip} to be public`);
  }

  assert.equal(stripIpv6Brackets('[2606:2800:220:1::1]'), '2606:2800:220:1::1');
});

test('isSafeClientUrl allows local app assets and https URLs while blocking javascript/data', () => {
  assert.equal(isSafeClientUrl('/src/assets/images/cover.jpg'), true);
  assert.equal(isSafeClientUrl('/api/image-proxy?url=https%3A%2F%2Fexample.com%2F1.jpg'), true);
  assert.equal(isSafeClientUrl('https://cdn.example.com/p1.webp'), true);
  assert.equal(isSafeClientUrl('javascript:alert(1)'), false);
  assert.equal(isSafeClientUrl('data:image/svg+xml,<svg/>'), false);
  assert.equal(isSafeClientUrl('/src/assets/../../etc/passwd'), false);
});
