import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { createApp, type CreateAppOptions, type DnsLookupFn } from '../../server.ts';

// Minimal valid image byte sequences (non-HTML, non-XML)
export const VALID_JPEG_BYTES = Buffer.from([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
  0x00, 0x01, 0x00, 0x00, 0xff, 0xd9,
]);

export const VALID_PNG_BYTES = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
  0xde, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
]);

export function makeJpegPayload(sizeBytes: number, fillByte = 0x42): Buffer {
  const safeSize = Math.max(sizeBytes, VALID_JPEG_BYTES.length);
  const buf = Buffer.alloc(safeSize, fillByte);
  // JPEG SOI + APP0 JFIF prefix
  VALID_JPEG_BYTES.subarray(0, 12).copy(buf, 0);
  // JPEG EOI suffix
  buf[safeSize - 2] = 0xff;
  buf[safeSize - 1] = 0xd9;
  return buf;
}

export interface RunningHttpServer {
  server: http.Server;
  port: number;
  baseUrl: string;
  close: () => Promise<void>;
}

export async function startHttpServer(
  handler: http.RequestListener
): Promise<RunningHttpServer> {
  const server = http.createServer(handler);
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.removeListener('error', reject);
      resolve();
    });
  });
  const addr = server.address() as AddressInfo;
  const port = addr.port;
  return {
    server,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.close((err) => (err ? reject(err) : resolve()));
        server.closeIdleConnections?.();
      }),
  };
}

export async function startAppServer(options: CreateAppOptions = {}): Promise<RunningHttpServer> {
  const app = createApp(options);
  const running = await startHttpServer(app);
  const originalClose = running.close;
  running.close = async () => {
    if (typeof app.locals.disposeRateLimiters === 'function') {
      (app.locals.disposeRateLimiters as () => void)();
    }
    await originalClose();
  };
  return running;
}

export interface MockNetworkConfig {
  /** Maps synthetic public hostnames (e.g. "cdn.manga-mock.example") to local mock HTTP server base URLs */
  hostRoutes: Record<string, string>;
  /** Optional custom DNS responses per hostname; hostRoutes automatically resolve to 93.184.216.34 */
  dnsOverrides?: Record<string, string[]>;
}

/**
 * Builds deterministic, fully offline dnsLookup + fetchImpl test doubles backed by local mock HTTP servers.
 * Any attempt to fetch an unmapped host (real internet OR leaked internal IP) throws immediately.
 */
export function createMockNetwork(config: MockNetworkConfig): {
  dnsLookup: DnsLookupFn;
  fetchImpl: typeof fetch;
  unexpectedFetches: string[];
} {
  const unexpectedFetches: string[] = [];

  const dnsLookup: DnsLookupFn = async (hostname: string) => {
    const lower = hostname.toLowerCase();
    if (config.dnsOverrides && Object.hasOwn(config.dnsOverrides, lower)) {
      return config.dnsOverrides[lower].map((address) => ({ address }));
    }
    if (Object.hasOwn(config.hostRoutes, lower)) {
      return [{ address: '93.184.216.34' }];
    }
    throw new Error(`Mock DNS: unerwarteter Hostname "${hostname}"`);
  };

  const fetchImpl: typeof fetch = async (input, init) => {
    const rawUrl = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const parsed = new URL(rawUrl);
    const lowerHost = parsed.hostname.toLowerCase();

    if (!Object.hasOwn(config.hostRoutes, lowerHost)) {
      unexpectedFetches.push(rawUrl);
      throw new Error(`Mock Fetch blockiert unerwartetes Ziel: ${rawUrl}`);
    }

    const localTarget = new URL(parsed.pathname + parsed.search, config.hostRoutes[lowerHost]);
    return fetch(localTarget.toString(), init);
  };

  return { dnsLookup, fetchImpl, unexpectedFetches };
}
