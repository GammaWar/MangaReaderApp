import express from 'express';
import { GoogleGenAI, type GenerateContentResponse } from '@google/genai';
import {
  buildSystemInstruction,
  resolveAllowedModel,
  sanitizeChatHistory,
  sanitizeUserMessage,
  type ChatContextSummary,
} from '../../src/utils/chatHelpers.ts';
import {
  CHAT_FIRST_TOKEN_TIMEOUT_MS,
  CHAT_HEARTBEAT_INTERVAL_MS,
  CHAT_IDLE_CHUNK_TIMEOUT_MS,
} from '../config.ts';

export interface ChatStreamLike {
  sendMessageStream(params: {
    message: string;
    config?: { abortSignal?: AbortSignal };
  }): Promise<AsyncIterable<{ text?: string }>>;
}

export interface GeminiClientLike {
  chats: {
    create(params: {
      model: string;
      history: Array<{ role: 'user' | 'model'; parts: Array<{ text: string }> }>;
      config: { systemInstruction: string };
    }): ChatStreamLike;
  };
}

export interface ChatRouteDeps {
  getGeminiClient?: () => GeminiClientLike | null;
  rateLimiter?: express.RequestHandler;
  firstTokenTimeoutMs?: number;
  idleChunkTimeoutMs?: number;
  heartbeatIntervalMs?: number;
}

class ChatStreamTimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ChatStreamTimeoutError';
  }
}

let cachedGeminiClient: GoogleGenAI | null = null;
function defaultGetGeminiClient(): GeminiClientLike | null {
  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) return null;
  if (!cachedGeminiClient) {
    cachedGeminiClient = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        },
      },
    });
  }
  return cachedGeminiClient as unknown as GeminiClientLike;
}

function nextWithTimeout<T>(
  iterator: AsyncIterator<T>,
  timeoutMs: number,
  signal: AbortSignal
): Promise<IteratorResult<T>> {
  return new Promise<IteratorResult<T>>((resolve, reject) => {
    if (signal.aborted) {
      reject(new Error('CLIENT_ABORTED'));
      return;
    }

    const onAbort = () => {
      clearTimeout(timer);
      reject(new Error('CLIENT_ABORTED'));
    };

    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      reject(new ChatStreamTimeoutError('Stream-Timeout erreicht.'));
    }, timeoutMs);

    signal.addEventListener('abort', onAbort, { once: true });

    iterator
      .next()
      .then((result) => {
        clearTimeout(timer);
        signal.removeEventListener('abort', onAbort);
        resolve(result);
      })
      .catch((err) => {
        clearTimeout(timer);
        signal.removeEventListener('abort', onAbort);
        reject(err);
      });
  });
}

function mapChatErrorToSafeMessage(err: unknown): string {
  if (err instanceof ChatStreamTimeoutError) {
    return 'Zeitüberschreitung bei der Antwortgenerierung. Bitte versuche es erneut oder wechsle auf den schnellen Modus.';
  }
  const rawMessage = err instanceof Error ? err.message : String(err);
  if (
    rawMessage.includes('403') ||
    rawMessage.includes('PERMISSION_DENIED') ||
    rawMessage.includes('API_KEY_INVALID')
  ) {
    return 'API-Zugriff abgelehnt. Bitte prüfe den API-Schlüssel im Bereich Settings > Secrets.';
  }
  if (rawMessage.includes('429') || rawMessage.includes('RESOURCE_EXHAUSTED')) {
    return 'Anfragelimit erreicht (HTTP 429). Bitte warte kurz oder wechsle auf den schnellen Modus.';
  }
  if (rawMessage.includes('503') || rawMessage.includes('UNAVAILABLE')) {
    return 'Der KI-Dienst ist momentan überlastet. Bitte versuche es in wenigen Sekunden erneut.';
  }
  // Never leak raw SDK / HTTP / internal stack messages to the client
  return 'Antwort konnte nicht geladen werden. Bitte versuche es erneut.';
}

export function createChatRouter(deps: ChatRouteDeps = {}): express.Router {
  const router = express.Router();
  const resolveClient = deps.getGeminiClient ?? defaultGetGeminiClient;
  const firstTokenTimeoutMs = deps.firstTokenTimeoutMs ?? CHAT_FIRST_TOKEN_TIMEOUT_MS;
  const idleChunkTimeoutMs = deps.idleChunkTimeoutMs ?? CHAT_IDLE_CHUNK_TIMEOUT_MS;
  const heartbeatIntervalMs = deps.heartbeatIntervalMs ?? CHAT_HEARTBEAT_INTERVAL_MS;

  const handler: express.RequestHandler = async (req, res) => {
    const ai = resolveClient();
    if (!ai) {
      res.status(503).json({
        error:
          'Der Gemini-Assistent ist derzeit nicht konfiguriert. Bitte prüfe den API-Schlüssel in den Einstellungen (Settings > Secrets).',
      });
      return;
    }

    const message = sanitizeUserMessage(req.body?.message);
    if (!message) {
      res.status(400).json({ error: 'Bitte gib eine Nachricht ein.' });
      return;
    }

    const model = resolveAllowedModel(req.body?.model);
    const historyTurns = sanitizeChatHistory(req.body?.history);
    const context =
      req.body?.context && typeof req.body.context === 'object'
        ? (req.body.context as ChatContextSummary)
        : undefined;
    const systemInstruction = buildSystemInstruction(req.body?.personaId, context);

    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');
    res.flushHeaders?.();

    const abortController = new AbortController();
    let clientDisconnected = false;

    const onDisconnect = () => {
      // In Node 16+, `res.on('close')` fires both on normal finish and premature client disconnect;
      // only treat it as a disconnect if the response has not finished cleanly.
      if (!res.writableFinished) {
        clientDisconnected = true;
        abortController.abort();
      }
    };
    res.on('close', onDisconnect);
    req.on('aborted', onDisconnect);

    const canWrite = () => !res.writableEnded && !res.destroyed && !clientDisconnected;

    const writeEvent = (payload: Record<string, unknown>) => {
      if (canWrite()) {
        res.write(`data: ${JSON.stringify(payload)}\n\n`);
      }
    };

    const heartbeatTimer = setInterval(() => {
      if (canWrite()) {
        res.write(': ping\n\n');
      }
    }, heartbeatIntervalMs);
    heartbeatTimer.unref();

    let iterator: AsyncIterator<{ text?: string }> | null = null;

    try {
      const sdkHistory = historyTurns.map((turn) => ({
        role: turn.role,
        parts: [{ text: turn.text }],
      }));

      const chat = ai.chats.create({
        model,
        history: sdkHistory,
        config: {
          systemInstruction,
        },
      });

      let startTimer: ReturnType<typeof setTimeout> | undefined;
      let streamResponse: AsyncIterable<{ text?: string }>;
      try {
        streamResponse = await Promise.race([
          chat.sendMessageStream({
            message,
            config: { abortSignal: abortController.signal },
          }),
          new Promise<never>((_resolve, reject) => {
            startTimer = setTimeout(() => {
              abortController.abort();
              reject(new ChatStreamTimeoutError('First-Token-Timeout erreicht.'));
            }, firstTokenTimeoutMs);
          }),
        ]);
      } finally {
        if (startTimer) clearTimeout(startTimer);
      }

      iterator = streamResponse[Symbol.asyncIterator]();
      let receivedFirstChunk = false;

      while (canWrite()) {
        const stepTimeout = receivedFirstChunk ? idleChunkTimeoutMs : firstTokenTimeoutMs;
        const step = await nextWithTimeout(iterator, stepTimeout, abortController.signal);
        if (step.done) break;
        receivedFirstChunk = true;
        const c = step.value as GenerateContentResponse | { text?: string };
        const textChunk = c?.text;
        if (textChunk) {
          writeEvent({ chunk: textChunk });
        }
      }

      if (canWrite()) {
        writeEvent({ done: true, model });
        res.end();
      }
    } catch (err) {
      if (!clientDisconnected && canWrite()) {
        console.error('[ChatAPI] Stream-Fehler:', err);
        const friendlyError = mapChatErrorToSafeMessage(err);
        writeEvent({ error: friendlyError });
        res.end();
      }
    } finally {
      clearInterval(heartbeatTimer);
      res.off('close', onDisconnect);
      req.off('aborted', onDisconnect);
      if (iterator && typeof iterator.return === 'function') {
        void Promise.resolve(iterator.return()).catch(() => undefined);
      }
      if (!res.writableEnded && !res.destroyed) {
        res.end();
      }
    }
  };

  if (deps.rateLimiter) {
    router.post('/api/chat', deps.rateLimiter, handler);
  } else {
    router.post('/api/chat', handler);
  }

  return router;
}
