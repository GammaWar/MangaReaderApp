import test from 'node:test';
import assert from 'node:assert/strict';
import { assertHostResolvesPublic, safeFetchWithRedirects } from '../../server.ts';
import {
  createMockNetwork,
  startAppServer,
  startHttpServer,
  VALID_JPEG_BYTES,
} from './testHelpers.ts';

test('SSRF unit: assertHostResolvesPublic blocks private/loopback literals and DNS records', async () => {
  for (const literal of ['127.0.0.1', '10.0.0.1', '169.254.169.254', '::1', '::ffff:127.0.0.1']) {
    await assert.rejects(
      () => assertHostResolvesPublic(literal, async () => [{ address: '93.184.216.34' }]),
      /Private oder Loopback/i,
      `Expected IP literal ${literal} to be blocked`
    );
  }

  const privateDnsTargets: Array<{ host: string; records: string[] }> = [
    { host: 'loopback.example', records: ['127.0.0.1'] },
    { host: 'rfc1918-10.example', records: ['10.20.30.40'] },
    { host: 'metadata.example', records: ['169.254.169.254'] },
    { host: 'ipv6-loopback.example', records: ['::1'] },
    { host: 'mixed-records.example', records: ['93.184.216.34', '10.0.0.5'] },
  ];

  for (const target of privateDnsTargets) {
    await assert.rejects(
      () =>
        assertHostResolvesPublic(
          target.host,
          async () => target.records.map((address) => ({ address }))
        ),
      /interne oder lokale IP/i,
      `Expected DNS records ${target.records.join(',')} to be blocked`
    );
  }

  await assert.rejects(
    () => assertHostResolvesPublic('empty.example', async () => []),
    /nicht aufgelöst/i
  );

  await assert.doesNotReject(() =>
    assertHostResolvesPublic('public.example', async () => [{ address: '93.184.216.34' }])
  );
});

test('SSRF unit: safeFetchWithRedirects blocks direct internal URLs without issuing any fetch', async () => {
  let fetchCalled = 0;
  const mockFetch: typeof fetch = async () => {
    fetchCalled += 1;
    throw new Error('fetch darf für blockierte URLs niemals aufgerufen werden');
  };

  const blockedUrls = [
    'http://localhost/secret',
    'http://localhost:3000/api/cache/status',
    'http://sub.localhost/secret',
    'http://127.0.0.1/admin',
    'http://127.255.255.254:8080/internal',
    'http://10.0.0.1/internal',
    'http://10.255.0.42/db',
    'http://169.254.169.254/latest/meta-data/',
    'http://[::1]/loopback',
    'http://[::1]:8080/secret',
    'http://192.168.1.1/router',
    'http://172.16.0.1/private',
    'http://0.0.0.0/',
    'http://metadata.google.internal/computeMetadata/v1/',
    'file:///etc/passwd',
    'javascript:alert(1)',
  ];

  for (const url of blockedUrls) {
    await assert.rejects(
      () =>
        safeFetchWithRedirects(url, {}, 3, {
          dnsLookup: async () => [{ address: '93.184.216.34' }],
          fetchImpl: mockFetch,
        }),
      Error,
      `Expected ${url} to be rejected by safeFetchWithRedirects`
    );
  }

  assert.equal(fetchCalled, 0);
});

test('SSRF integration: safeFetchWithRedirects blocks redirects to internal targets and enforces maxRedirects', async () => {
  let internalHits = 0;
  const internalServer = await startHttpServer((_req, res) => {
    internalHits += 1;
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('INTERNAL_SECRET');
  });

  const redirectServer = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    switch (url.pathname) {
      case '/to-localhost':
        res.writeHead(302, { Location: `http://localhost:${internalServer.port}/secret` });
        res.end();
        return;
      case '/to-127':
        res.writeHead(302, { Location: `${internalServer.baseUrl}/secret` });
        res.end();
        return;
      case '/to-10':
        res.writeHead(302, { Location: 'http://10.0.0.99/admin' });
        res.end();
        return;
      case '/to-metadata':
        res.writeHead(302, { Location: 'http://169.254.169.254/latest/meta-data/' });
        res.end();
        return;
      case '/to-ipv6-loopback':
        res.writeHead(302, { Location: 'http://[::1]:8080/secret' });
        res.end();
        return;
      case '/to-private-dns':
        res.writeHead(302, { Location: 'https://dns-internal.example/secret' });
        res.end();
        return;
      case '/multi-hop-to-internal':
        res.writeHead(302, { Location: 'https://redirect.manga-mock.example/to-127' });
        res.end();
        return;
      case '/loop':
        res.writeHead(302, { Location: 'https://redirect.manga-mock.example/loop' });
        res.end();
        return;
      case '/safe-redirect':
        res.writeHead(302, { Location: 'https://redirect.manga-mock.example/final-image.jpg' });
        res.end();
        return;
      case '/final-image.jpg':
        res.writeHead(200, { 'Content-Type': 'image/jpeg' });
        res.end(VALID_JPEG_BYTES);
        return;
      default:
        res.writeHead(404);
        res.end();
    }
  });

  try {
    const net = createMockNetwork({
      hostRoutes: {
        'redirect.manga-mock.example': redirectServer.baseUrl,
      },
      dnsOverrides: {
        'dns-internal.example': ['127.0.0.1'],
      },
    });

    const dangerousPaths = [
      '/to-localhost',
      '/to-127',
      '/to-10',
      '/to-metadata',
      '/to-ipv6-loopback',
      '/to-private-dns',
      '/multi-hop-to-internal',
    ];

    for (const p of dangerousPaths) {
      await assert.rejects(
        () =>
          safeFetchWithRedirects(`https://redirect.manga-mock.example${p}`, {}, 3, {
            dnsLookup: net.dnsLookup,
            fetchImpl: net.fetchImpl,
          }),
        Error,
        `Expected redirect path ${p} to be blocked`
      );
    }

    assert.equal(internalHits, 0, 'Internal target server must never receive a redirected request');
    assert.deepEqual(net.unexpectedFetches, []);

    // Redirect loop must fail cleanly
    await assert.rejects(
      () =>
        safeFetchWithRedirects('https://redirect.manga-mock.example/loop', {}, 2, {
          dnsLookup: net.dnsLookup,
          fetchImpl: net.fetchImpl,
        }),
      /Zu viele Weiterleitungen/i
    );

    // Public-to-public redirect still succeeds
    const okRes = await safeFetchWithRedirects(
      'https://redirect.manga-mock.example/safe-redirect',
      {},
      3,
      { dnsLookup: net.dnsLookup, fetchImpl: net.fetchImpl }
    );
    assert.equal(okRes.status, 200);
    const body = Buffer.from(await okRes.arrayBuffer());
    assert.deepEqual(body, VALID_JPEG_BYTES);
  } finally {
    await redirectServer.close();
    await internalServer.close();
  }
});

test('SSRF HTTP integration: /api/image-proxy and /api/extract block direct internal IPs, private DNS, and internal redirects', async () => {
  let internalHits = 0;
  const internalServer = await startHttpServer((_req, res) => {
    internalHits += 1;
    res.writeHead(200, { 'Content-Type': 'image/jpeg' });
    res.end(VALID_JPEG_BYTES);
  });

  const redirectCdn = await startHttpServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/redirect-127') {
      res.writeHead(302, { Location: `${internalServer.baseUrl}/secret.jpg` });
      res.end();
      return;
    }
    if (url.pathname === '/redirect-metadata') {
      res.writeHead(302, { Location: 'http://169.254.169.254/latest/meta-data/' });
      res.end();
      return;
    }
    if (url.pathname === '/redirect-ipv6') {
      res.writeHead(302, { Location: 'http://[::1]/secret.jpg' });
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  });

  const net = createMockNetwork({
    hostRoutes: {
      'cdn.manga-mock.example': redirectCdn.baseUrl,
    },
    dnsOverrides: {
      'dns-10.example': ['10.0.0.5'],
      'dns-127.example': ['127.0.0.1'],
      'dns-169.example': ['169.254.169.254'],
      'dns-ipv6.example': ['::1'],
    },
  });

  const appServer = await startAppServer({
    dnsLookup: net.dnsLookup,
    fetchImpl: net.fetchImpl,
  });

  try {
    // 1. Direct SSRF targets must be rejected immediately with HTTP 400
    const directTargets = [
      'http://localhost:3000/secret.jpg',
      `http://127.0.0.1:${internalServer.port}/secret.jpg`,
      'http://10.0.0.5/secret.jpg',
      'http://169.254.169.254/latest/meta-data/',
      'http://[::1]/secret.jpg',
    ];

    for (const target of directTargets) {
      const proxyRes = await fetch(
        `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(target)}`
      );
      assert.equal(proxyRes.status, 400, `/api/image-proxy should return 400 for ${target}`);

      const extractRes = await fetch(`${appServer.baseUrl}/api/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: target }),
      });
      assert.equal(extractRes.status, 400, `/api/extract should return 400 for ${target}`);
    }

    // 2. Hostnames resolving to private/loopback DNS records must be blocked
    for (const host of ['dns-10.example', 'dns-127.example', 'dns-169.example', 'dns-ipv6.example']) {
      const target = `https://${host}/page1.jpg`;
      const proxyRes = await fetch(
        `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(target)}`
      );
      assert.equal(proxyRes.ok, false, `/api/image-proxy must block private DNS ${host}`);
      assert.ok(proxyRes.status >= 400);

      const extractRes = await fetch(`${appServer.baseUrl}/api/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: target }),
      });
      assert.equal(extractRes.ok, false, `/api/extract must block private DNS ${host}`);
      assert.ok(extractRes.status >= 400);
    }

    // 3. Redirects from a public host to internal IPs must be blocked on both endpoints
    for (const path of ['/redirect-127', '/redirect-metadata', '/redirect-ipv6']) {
      const target = `https://cdn.manga-mock.example${path}`;
      const proxyRes = await fetch(
        `${appServer.baseUrl}/api/image-proxy?url=${encodeURIComponent(target)}`
      );
      assert.equal(proxyRes.ok, false, `/api/image-proxy must block redirect ${path}`);
      assert.ok(proxyRes.status >= 400);

      const extractRes = await fetch(`${appServer.baseUrl}/api/extract`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: target }),
      });
      assert.equal(extractRes.ok, false, `/api/extract must block redirect ${path}`);
      assert.ok(extractRes.status >= 400);
    }

    assert.equal(internalHits, 0, 'Internal mock server must never be reached');
    assert.deepEqual(net.unexpectedFetches, []);
  } finally {
    await appServer.close();
    await redirectCdn.close();
    await internalServer.close();
  }
});
