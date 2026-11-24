import { useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  Bot,
  RotateCcw,
  Send,
  Square,
  User,
  Zap,
} from 'lucide-react';
import type { ChatMessage, ChatModelId, ChatPersonaId } from '../types/manga';
import {
  CHAT_MODELS,
  CHAT_PERSONAS,
  resolvePersona,
  type ChatContextSummary,
} from '../utils/chatHelpers';

interface ChatViewProps {
  messages: ChatMessage[];
  onUpdateMessages: (updater: (prev: ChatMessage[]) => ChatMessage[]) => void;
  contextSummary: ChatContextSummary;
}

function formatMessageTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('de-DE', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function ChatView({
  messages,
  onUpdateMessages,
  contextSummary,
}: ChatViewProps) {
  const [personaId, setPersonaId] = useState<ChatPersonaId>('manga-guide');
  const [modelId, setModelId] = useState<ChatModelId>('gemini-4-hydrogen');
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);

  const threadEndRef = useRef<HTMLDivElement | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const activePersona = resolvePersona(personaId);

  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, isStreaming]);

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  const handleStopStreaming = () => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    setIsStreaming(false);
  };

  const handleResetChat = () => {
    handleStopStreaming();
    onUpdateMessages(() => []);
  };

  const handleSendMessage = async (overrideText?: string) => {
    const trimmed = (overrideText ?? input).trim();
    if (!trimmed || isStreaming) return;

    const userMessage: ChatMessage = {
      id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      role: 'user',
      text: trimmed,
      createdAt: Date.now(),
    };

    const assistantId = `model-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const assistantPlaceholder: ChatMessage = {
      id: assistantId,
      role: 'model',
      text: '',
      createdAt: Date.now(),
      model: modelId,
    };

    const historyPayload = messages
      .filter((m) => !m.isError && m.text.trim().length > 0)
      .map((m) => ({ role: m.role, text: m.text }));

    setInput('');
    onUpdateMessages((prev) => [...prev, userMessage, assistantPlaceholder]);
    setIsStreaming(true);

    const controller = new AbortController();
    abortControllerRef.current = controller;

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: trimmed,
          history: historyPayload,
          personaId,
          model: modelId,
          context: contextSummary,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errBody = (await response.json().catch(() => null)) as { error?: string } | null;
        throw new Error(
          errBody?.error || `Serverfehler (HTTP ${response.status}) beim Abruf der Antwort.`
        );
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('Streaming-Antwort konnte nicht gelesen werden.');
      }

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          const cleaned = line.trim();
          if (!cleaned.startsWith('data:')) continue;
          const jsonStr = cleaned.slice(5).trim();
          if (!jsonStr) continue;

          let parsed: { chunk?: string; error?: string; done?: boolean };
          try {
            parsed = JSON.parse(jsonStr) as { chunk?: string; error?: string; done?: boolean };
          } catch {
            continue;
          }

          if (parsed.error) {
            throw new Error(parsed.error);
          }

          if (parsed.chunk) {
            const delta = parsed.chunk;
            onUpdateMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, text: m.text + delta } : m))
            );
          }
        }
      }

      // Ensure non-empty fallback notice if stream ended with zero chunks
      onUpdateMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId && !m.text.trim()
            ? {
                ...m,
                text: 'Keine Textantwort erhalten. Bitte stelle deine Frage noch einmal.',
                isError: true,
              }
            : m
        )
      );
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        onUpdateMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId && !m.text.trim()
              ? { ...m, text: 'Antwort abgebrochen.' }
              : m
          )
        );
      } else {
        const errorText =
          err instanceof Error ? err.message : 'Verbindungsfehler zum Assistenten.';
        onUpdateMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? {
                  ...m,
                  text: errorText,
                  isError: true,
                }
              : m
          )
        );
      }
    } finally {
      abortControllerRef.current = null;
      setIsStreaming(false);
    }
  };

  return (
    <div
      data-testid="chat-view"
      className="mx-auto flex h-[calc(100vh-3.5rem-3.5rem)] w-full max-w-5xl flex-col px-4 py-3 sm:px-6 sm:py-5 md:h-[calc(100vh-3.5rem)] lg:px-8"
    >
      {/* Header: Title, Persona Selector & Model Speed Mode */}
      <div className="shrink-0 space-y-2.5 border-b border-[#221E36] pb-3 sm:space-y-4 sm:pb-4">
        <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-0.5">
            <h1 className="font-display text-xl font-bold tracking-tight text-white sm:text-2xl">
              Manga-Assistent
            </h1>
            <p className="hidden text-sm text-[#A29BBE] sm:block">
              Multi-Turn-Chat für Empfehlungen, Kapitel-Fragen und Reader-Diagnose.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Model Speed Selector */}
            <div
              role="group"
              aria-label="Antwortmodus"
              className="inline-flex rounded-xl border border-[#241F3A] bg-[#131022] p-1"
            >
              {CHAT_MODELS.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  data-testid={`chat-model-${option.id}`}
                  onClick={() => setModelId(option.id)}
                  title={option.description}
                  className={`inline-flex min-h-[36px] items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] ${
                    modelId === option.id
                      ? 'bg-[#7C4DFF] text-white'
                      : 'text-[#A7A0C4] hover:text-white'
                  }`}
                >
                  {option.id === 'gemini-3.1-flash-lite' && (
                    <Zap className="h-3.5 w-3.5" aria-hidden="true" />
                  )}
                  <span>{option.label}</span>
                </button>
              ))}
            </div>

            <button
              type="button"
              data-testid="chat-reset-btn"
              disabled={messages.length === 0 && !isStreaming}
              onClick={handleResetChat}
              className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl border border-[#2B2545] bg-[#141124] px-3.5 py-2 text-xs font-medium text-[#D5CFF0] whitespace-nowrap transition-colors hover:border-[#3F366A] hover:text-white disabled:cursor-not-allowed disabled:opacity-45"
            >
              <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
              Neuer Chat
            </button>
          </div>
        </div>

        {/* Role / System-Instruction Selector */}
        <div
          role="tablist"
          aria-label="Assistenten-Rolle"
          className="flex gap-2 overflow-x-auto pb-0.5 sm:grid sm:grid-cols-3 sm:overflow-visible sm:pb-0"
        >
          {CHAT_PERSONAS.map((persona) => {
            const active = persona.id === personaId;
            return (
              <button
                key={persona.id}
                type="button"
                role="tab"
                aria-selected={active}
                data-testid={`chat-persona-${persona.id}`}
                onClick={() => setPersonaId(persona.id)}
                className={`flex shrink-0 flex-col items-start rounded-xl border px-3 py-2 text-left transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] sm:px-3.5 sm:py-2.5 ${
                  active
                    ? 'border-[#7C4DFF] bg-[#191430] text-white'
                    : 'border-[#221E36] bg-[#110E1E] text-[#A9A2C7] hover:border-[#342D54] hover:text-white'
                }`}
              >
                <span className="whitespace-nowrap text-xs font-semibold">{persona.label}</span>
                <span className="mt-0.5 hidden text-[11px] text-[#8E87AB] sm:block">
                  {persona.subtitle}
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Scrollable Multi-Turn Conversation Thread */}
      <div
        data-testid="chat-thread"
        role="log"
        aria-live="polite"
        aria-label="Chatverlauf"
        className="flex-1 space-y-4 overflow-y-auto py-5 pr-1"
      >
        {messages.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center py-6 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl border border-[#2C254A] bg-[#151128] text-[#BB86FC]">
              <Bot className="h-6 w-6" aria-hidden="true" />
            </div>
            <h2 className="mt-4 font-display text-lg font-semibold text-white">
              {activePersona.label} bereit
            </h2>
            <p className="mt-1 max-w-md text-sm text-[#9D96BA]">
              Stelle eine Frage oder starte direkt mit einem Thema. Der Gesprächsverlauf bleibt über
              mehrere Rückfragen hinweg erhalten.
            </p>

            <div className="mt-6 grid w-full max-w-2xl grid-cols-1 gap-2.5 text-left">
              {activePersona.starterPrompts.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  onClick={() => void handleSendMessage(prompt)}
                  className="rounded-xl border border-[#241F3A] bg-[#131022] px-4 py-3 text-sm text-[#DBD5F2] transition-colors hover:border-[#493D7A] hover:bg-[#1A1530] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((msg) => {
            const isUser = msg.role === 'user';
            return (
              <div
                key={msg.id}
                data-testid={`chat-message-${msg.role}`}
                className={`flex items-start gap-3 ${isUser ? 'justify-end' : 'justify-start'}`}
              >
                {!isUser && (
                  <div
                    className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border ${
                      msg.isError
                        ? 'border-rose-900/70 bg-rose-950/50 text-rose-300'
                        : 'border-[#2E274E] bg-[#17132B] text-[#BB86FC]'
                    }`}
                  >
                    {msg.isError ? (
                      <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                    ) : (
                      <Bot className="h-4 w-4" aria-hidden="true" />
                    )}
                  </div>
                )}

                <div
                  className={`max-w-[84%] rounded-2xl px-4 py-3 text-sm leading-relaxed sm:max-w-[75%] ${
                    isUser
                      ? 'bg-[#7C4DFF] text-white'
                      : msg.isError
                      ? 'border border-rose-900/60 bg-rose-950/30 text-rose-100'
                      : 'border border-[#25203C] bg-[#131022] text-[#ECE8FA]'
                  }`}
                >
                  <div className="whitespace-pre-wrap break-words">
                    {msg.text || (isStreaming ? 'Schreibt…' : '')}
                  </div>
                  <div
                    className={`mt-2 flex items-center gap-2 font-mono-tabular text-[11px] ${
                      isUser ? 'justify-end text-white/75' : 'text-[#8C85A8]'
                    }`}
                  >
                    <span>{formatMessageTime(msg.createdAt)}</span>
                    {!isUser && msg.model && (
                      <>
                        <span aria-hidden="true">·</span>
                        <span>
                          {msg.model === 'gemini-3.1-flash-lite' ? 'Schnell' : 'Standard'}
                        </span>
                      </>
                    )}
                  </div>
                </div>

                {isUser && (
                  <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-[#3B3166] bg-[#1E1838] text-[#D8CEFF]">
                    <User className="h-4 w-4" aria-hidden="true" />
                  </div>
                )}
              </div>
            );
          })
        )}
        <div ref={threadEndRef} />
      </div>

      {/* Bottom Message Composer */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void handleSendMessage();
        }}
        className="shrink-0 border-t border-[#221E36] pt-4"
      >
        <div className="flex items-end gap-2.5 rounded-2xl border border-[#2A2444] bg-[#120F20] p-2.5 focus-within:border-[#7C4DFF]">
          <textarea
            data-testid="chat-input"
            rows={2}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                void handleSendMessage();
              }
            }}
            placeholder={`Frage an ${activePersona.label} stellen… (Enter zum Senden)`}
            aria-label="Nachricht an den Manga-Assistenten"
            className="max-h-36 min-h-[44px] flex-1 resize-none bg-transparent px-2 py-1.5 text-sm text-white placeholder-[#7C7599] focus:outline-none"
          />

          {isStreaming ? (
            <button
              type="button"
              data-testid="chat-stop-btn"
              onClick={handleStopStreaming}
              aria-label="Antwort stoppen"
              className="inline-flex h-11 shrink-0 items-center gap-1.5 rounded-xl border border-rose-800/70 bg-rose-950/40 px-4 text-sm font-medium text-rose-100 whitespace-nowrap transition-colors hover:bg-rose-950/70"
            >
              <Square className="h-3.5 w-3.5 fill-current" aria-hidden="true" />
              Stopp
            </button>
          ) : (
            <button
              type="submit"
              data-testid="chat-send-btn"
              disabled={!input.trim()}
              aria-label="Nachricht senden"
              className="inline-flex h-11 shrink-0 items-center gap-1.5 rounded-xl bg-[#7C4DFF] px-4 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2] focus-visible:outline-2 focus-visible:outline-[#BB86FC] disabled:cursor-not-allowed disabled:opacity-45"
            >
              <Send className="h-4 w-4" aria-hidden="true" />
              Senden
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
