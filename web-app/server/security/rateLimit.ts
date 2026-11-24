import type express from 'express';

export interface RateLimiterOptions {
  windowMs: number;
  maxRequests: number;
  message?: string;
}

export interface RateLimiterMiddleware {
  middleware: express.RequestHandler;
  reset: () => void;
  dispose: () => void;
}

function resolveClientKey(req: express.Request): string {
  const forwarded = req.headers['x-forwarded-for'];
  if (typeof forwarded === 'string' && forwarded.trim()) {
    return forwarded.split(',')[0].trim();
  }
  return req.ip || req.socket.remoteAddress || 'unknown';
}

/**
 * Lightweight, zero-dependency in-memory sliding-window rate limiter per client IP.
 */
export function createRateLimiter(options: RateLimiterOptions): RateLimiterMiddleware {
  const windowMs = Math.max(1000, options.windowMs);
  const maxRequests = Math.max(1, options.maxRequests);
  const message =
    options.message || 'Zu viele Anfragen in kurzer Zeit (HTTP 429). Bitte warte einen Moment.';

  const buckets = new Map<string, number[]>();

  const pruneTimer = setInterval(() => {
    const cutoff = Date.now() - windowMs;
    for (const [key, timestamps] of buckets.entries()) {
      const active = timestamps.filter((ts) => ts > cutoff);
      if (active.length === 0) {
        buckets.delete(key);
      } else {
        buckets.set(key, active);
      }
    }
  }, Math.max(windowMs, 30_000));
  pruneTimer.unref();

  const middleware: express.RequestHandler = (req, res, next) => {
    const now = Date.now();
    const cutoff = now - windowMs;
    const key = resolveClientKey(req);

    const existing = buckets.get(key) ?? [];
    const recent = existing.filter((ts) => ts > cutoff);

    if (recent.length >= maxRequests) {
      const oldestActive = recent[0] ?? now;
      const retryAfterSec = Math.max(1, Math.ceil((oldestActive + windowMs - now) / 1000));
      res.setHeader('Retry-After', String(retryAfterSec));
      res.status(429).json({ error: message });
      return;
    }

    recent.push(now);
    buckets.set(key, recent);
    next();
  };

  return {
    middleware,
    reset: () => buckets.clear(),
    dispose: () => {
      clearInterval(pruneTimer);
      buckets.clear();
    },
  };
}
