import dns from 'node:dns/promises';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import {
  isPrivateOrLoopbackIp,
  stripIpv6Brackets,
  validateExternalUrl,
} from '../../src/utils/urlSafety.ts';
import { CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS } from '../config.ts';

export interface DnsLookupRecord {
  address: string;
  family?: number;
}

export type DnsLookupFn = (hostname: string) => Promise<DnsLookupRecord[]>;

export interface SafeFetchDeps {
  dnsLookup?: DnsLookupFn;
  fetchImpl?: typeof fetch;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
}

export class SsrfBlockedError extends Error {
  readonly statusCode: 400 | 403;

  constructor(message: string, statusCode: 400 | 403 = 403) {
    super(message);
    this.name = 'SsrfBlockedError';
    this.statusCode = statusCode;
  }
}

export class UpstreamTimeoutError extends Error {
  readonly statusCode = 504;

  constructor(message: string) {
    super(message);
    this.name = 'UpstreamTimeoutError';
  }
}

export class PayloadTooLargeError extends Error {
  readonly statusCode = 413;

  constructor(message: string) {
    super(message);
    this.name = 'PayloadTooLargeError';
  }
}

const defaultDnsLookup: DnsLookupFn = (hostname) => dns.lookup(hostname, { all: true });

export async function assertHostResolvesPublic(
  hostname: string,
  dnsLookup: DnsLookupFn = defaultDnsLookup
): Promise<DnsLookupRecord[]> {
  const cleanHost = stripIpv6Brackets(hostname).replace(/\.+$/, '');
  if (!cleanHost || isPrivateOrLoopbackIp(cleanHost)) {
    throw new SsrfBlockedError('Private oder Loopback-IP-Adresse blockiert.', 403);
  }

  // If the hostname is already a public IP literal, return it directly without DNS lookup
  const literalFamily = net.isIP(cleanHost);
  if (literalFamily === 4 || literalFamily === 6) {
    return [{ address: cleanHost, family: literalFamily }];
  }

  const records = await dnsLookup(cleanHost);
  if (!records.length) {
    throw new SsrfBlockedError('Host konnte nicht aufgelöst werden.', 403);
  }

  const validated: DnsLookupRecord[] = [];
  for (const record of records) {
    const cleanAddr = stripIpv6Brackets(record.address);
    if (isPrivateOrLoopbackIp(cleanAddr)) {
      throw new SsrfBlockedError(
        'DNS-Ziel zeigt auf eine interne oder lokale IP-Adresse.',
        403
      );
    }
    const inferredFamily = record.family ?? (net.isIP(cleanAddr) || undefined);
    validated.push({ address: cleanAddr, family: inferredFamily });
  }

  return validated;
}

/**
 * Performs an HTTP(S) GET request pinned directly to a pre-validated public IP address.
 * Eliminates DNS-rebinding (TOCTOU) between DNS validation and TCP connection establishment,
 * while preserving the original Host header and TLS SNI servername for certificate validation.
 */
function pinnedNodeFetch(
  targetUrl: URL,
  pinnedRecord: DnsLookupRecord,
  headers: Record<string, string>,
  signal: AbortSignal
): Promise<Response> {
  return new Promise<Response>((resolve, reject) => {
    const isHttps = targetUrl.protocol === 'https:';
    const transport = isHttps ? https : http;
    const family = pinnedRecord.family ?? (net.isIP(pinnedRecord.address) || 4);
    const serverHost = stripIpv6Brackets(targetUrl.hostname);

    const requestOptions: https.RequestOptions = {
      protocol: targetUrl.protocol,
      hostname: serverHost,
      port: targetUrl.port || (isHttps ? 443 : 80),
      path: `${targetUrl.pathname}${targetUrl.search}`,
      method: 'GET',
      headers: {
        ...headers,
        Host: targetUrl.host,
      },
      // Pin TCP connect strictly to the already-validated public IP address
      lookup: (_host, options, callback) => {
        if (typeof options === 'object' && options !== null && 'all' in options && options.all) {
          (
            callback as (
              err: NodeJS.ErrnoException | null,
              addresses: Array<{ address: string; family: number }>
            ) => void
          )(null, [{ address: pinnedRecord.address, family }]);
          return;
        }
        (
          callback as (err: NodeJS.ErrnoException | null, address: string, family: number) => void
        )(null, pinnedRecord.address, family);
      },
      ...(isHttps ? { servername: serverHost } : {}),
    };

    let settled = false;
    const req = transport.request(requestOptions, (incoming) => {
      if (settled) {
        incoming.destroy();
        return;
      }
      settled = true;

      const status = incoming.statusCode ?? 502;
      const responseHeaders = new Headers();
      for (const [key, value] of Object.entries(incoming.headers)) {
        if (value === undefined) continue;
        if (Array.isArray(value)) {
          for (const item of value) responseHeaders.append(key, item);
        } else {
          responseHeaders.set(key, value);
        }
      }

      // Statuses 204, 205, 304, and null-body responses must not attach a ReadableStream body
      const isNullBodyStatus = status === 204 || status === 205 || status === 304;
      if (isNullBodyStatus) {
        incoming.resume();
        resolve(new Response(null, { status, headers: responseHeaders }));
        return;
      }

      const bodyStream = new ReadableStream<Uint8Array>({
        start(controller) {
          const onData = (chunk: Buffer | Uint8Array) => {
            try {
              controller.enqueue(new Uint8Array(chunk.buffer, chunk.byteOffset, chunk.byteLength));
            } catch {
              incoming.destroy();
            }
          };
          const onEnd = () => {
            cleanup();
            try {
              controller.close();
            } catch {
              // Ignore already-closed controller
            }
          };
          const onError = (err: Error) => {
            cleanup();
            try {
              controller.error(err);
            } catch {
              // Ignore already-errored controller
            }
          };
          const onAbort = () => {
            cleanup();
            incoming.destroy();
            try {
              controller.error(new UpstreamTimeoutError('Timeout beim Lesen der Antwortdaten.'));
            } catch {
              // Ignore already-errored controller
            }
          };
          const cleanup = () => {
            incoming.off('data', onData);
            incoming.off('end', onEnd);
            incoming.off('error', onError);
            signal.removeEventListener('abort', onAbort);
          };

          incoming.on('data', onData);
          incoming.on('end', onEnd);
          incoming.on('error', onError);
          if (signal.aborted) {
            onAbort();
          } else {
            signal.addEventListener('abort', onAbort, { once: true });
          }
        },
        cancel() {
          incoming.destroy();
        },
      });

      resolve(new Response(bodyStream, { status, headers: responseHeaders }));
    });

    const onAbort = () => {
      if (!settled) {
        settled = true;
        req.destroy();
        reject(new UpstreamTimeoutError('Zeitüberschreitung beim Verbindungsaufbau.'));
      }
    };

    if (signal.aborted) {
      onAbort();
      return;
    }
    signal.addEventListener('abort', onAbort, { once: true });

    req.on('error', (err) => {
      signal.removeEventListener('abort', onAbort);
      if (!settled) {
        settled = true;
        reject(err);
      }
    });

    req.on('close', () => {
      signal.removeEventListener('abort', onAbort);
    });

    req.end();
  });
}

function sanitizeHeadersForRedirect(
  headers: Record<string, string>,
  previousUrl: URL,
  nextUrl: URL
): Record<string, string> {
  const nextHeaders: Record<string, string> = { ...headers };
  if (previousUrl.origin !== nextUrl.origin) {
    // Strip sensitive credentials across origins and update Sec-Fetch-Site accurately
    for (const key of Object.keys(nextHeaders)) {
      const lower = key.toLowerCase();
      if (lower === 'authorization' || lower === 'cookie' || lower === 'proxy-authorization') {
        delete nextHeaders[key];
      }
    }
    if ('Sec-Fetch-Site' in nextHeaders) {
      nextHeaders['Sec-Fetch-Site'] = 'cross-site';
    }
  }
  return nextHeaders;
}

export async function safeFetchWithRedirects(
  rawUrl: string,
  headers: Record<string, string>,
  maxRedirects = 3,
  deps: SafeFetchDeps = {}
): Promise<Response> {
  const dnsLookup = deps.dnsLookup ?? defaultDnsLookup;
  const connectTimeoutMs = deps.connectTimeoutMs ?? CONNECT_TIMEOUT_MS;
  let currentUrl = rawUrl;
  let activeHeaders = { ...headers };

  for (let hop = 0; hop <= maxRedirects; hop++) {
    const check = validateExternalUrl(currentUrl);
    if (!check.valid || !check.url) {
      throw new SsrfBlockedError(check.reason || 'Ungültige Ziel-URL.', 400);
    }

    const validatedRecords = await assertHostResolvesPublic(check.url.hostname, dnsLookup);

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), connectTimeoutMs);

    let res: Response;
    try {
      if (deps.fetchImpl) {
        res = await deps.fetchImpl(check.url.toString(), {
          method: 'GET',
          headers: activeHeaders,
          redirect: 'manual',
          signal: controller.signal,
        });
      } else {
        res = await pinnedNodeFetch(check.url, validatedRecords[0], activeHeaders, controller.signal);
      }
    } catch (err) {
      if (err instanceof SsrfBlockedError || err instanceof UpstreamTimeoutError) {
        throw err;
      }
      if (controller.signal.aborted) {
        throw new UpstreamTimeoutError('Zeitüberschreitung beim Verbindungsaufbau.');
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
    }

    if (res.status >= 300 && res.status < 400) {
      const location = res.headers.get('location');
      if (!location) return res;
      // Release socket / stream resources of redirect response before hopping
      await res.body?.cancel().catch(() => undefined);
      const nextUrl = new URL(location, check.url);
      activeHeaders = sanitizeHeadersForRedirect(activeHeaders, check.url, nextUrl);
      currentUrl = nextUrl.toString();
      continue;
    }

    return res;
  }

  throw new Error('Zu viele Weiterleitungen.');
}

/**
 * Reads a Response body into a Buffer with strict max-byte and active read-timeout enforcement.
 * Actively aborts hanging `reader.read()` calls against slowloris / tarpit upstreams.
 */
export async function readResponseWithLimit(
  res: Response,
  maxBytes: number,
  readTimeoutMs: number = READ_TIMEOUT_MS
): Promise<Buffer> {
  const declaredLength = Number(res.headers.get('content-length') || '');
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    await res.body?.cancel().catch(() => undefined);
    throw new PayloadTooLargeError('Antwort überschreitet das zulässige Größenlimit.');
  }

  const reader = res.body?.getReader();
  if (!reader) {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const ab = await Promise.race([
        res.arrayBuffer(),
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(
            () => reject(new UpstreamTimeoutError('Timeout beim Lesen der Antwortdaten.')),
            readTimeoutMs
          );
        }),
      ]);
      if (ab.byteLength > maxBytes) {
        throw new PayloadTooLargeError('Antwort überschreitet das Größenlimit.');
      }
      return Buffer.from(ab);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  const chunks: Uint8Array[] = [];
  let total = 0;
  let timedOut = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  const timeoutPromise = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      timedOut = true;
      reject(new UpstreamTimeoutError('Timeout beim Lesen der Antwortdaten.'));
    }, readTimeoutMs);
  });

  try {
    while (true) {
      const { done, value } = await Promise.race([reader.read(), timeoutPromise]);
      if (done) break;
      if (value) {
        total += value.byteLength;
        if (total > maxBytes) {
          await reader.cancel().catch(() => undefined);
          throw new PayloadTooLargeError('Antwort überschreitet das zulässige Größenlimit.');
        }
        chunks.push(value);
      }
    }
    return Buffer.concat(chunks);
  } catch (err) {
    if (timedOut) {
      await reader.cancel().catch(() => undefined);
      throw new UpstreamTimeoutError('Timeout beim Lesen der Antwortdaten.');
    }
    throw err;
  } finally {
    if (timer) clearTimeout(timer);
  }
}
