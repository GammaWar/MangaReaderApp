import type { ChatModelId, ChatPersonaId } from '../types/manga';

export interface ChatModelOption {
  id: ChatModelId;
  label: string;
  description: string;
}

export interface ChatPersonaOption {
  id: ChatPersonaId;
  label: string;
  subtitle: string;
  systemInstruction: string;
  starterPrompts: string[];
}

export interface ChatContextSummary {
  seriesTitle?: string;
  chapterTitle?: string;
  currentPage?: number;
  totalPages?: number;
  cacheLimitLabel?: string;
}

export interface SanitizedHistoryTurn {
  role: 'user' | 'model';
  text: string;
}

export const CHAT_MODELS: ChatModelOption[] = [
  {
    id: 'gemini-4-hydrogen',
    label: 'Standard',
    description: 'Ausgewogene Antworten für Empfehlungen, Fragen & Erklärungen',
  },
  {
    id: 'gemini-3.1-flash-lite',
    label: 'Schnell',
    description: 'Besonders geringe Latenz für kurze Rückfragen beim Lesen',
  },
];

export const CHAT_PERSONAS: ChatPersonaOption[] = [
  {
    id: 'manga-guide',
    label: 'Manga-Berater',
    subtitle: 'Empfehlungen, Genres & Lese-Tipps',
    systemInstruction:
      'Du bist der sachkundige Manga- und Webtoon-Berater in der MangaReaderApp. Antworte auf Deutsch, prägnant, strukturiert und ohne Spoiler (außer der Nutzer bittet ausdrücklich darum). Gib passende Serien-Empfehlungen nach Stimmung, Genre, Zeichenstil oder Erzähltempo und erkläre japanische/koreanische Manga-Begriffe verständlich.',
    starterPrompts: [
      'Empfiehl mir 3 düstere Seinen-Mangas mit starker Atmosphäre wie Kurogane.',
      'Was unterscheidet den Webtoon-Vertikalmodus vom klassischen Manga-Blättermodus?',
      'Welche Sci-Fi-Webtoons lohnen sich für kurze Lesepausen?',
    ],
  },
  {
    id: 'reader-tech',
    label: 'Reader- & CDN-Hilfe',
    subtitle: 'HTTP-Diagnose, Cache-Limit & Einstellungen',
    systemInstruction:
      'Du bist der technische Support-Assistent der MangaReaderApp. Antworte klar und praxisnah auf Deutsch. Erkläre Funktionen der App wie den AMOLED-Nachtfilter (Software-Dimmer & Sepia), Autoscroll, Pre-Fetching, das konfigurierbare Bild-Cache-Limit (Standard 4 GB, bis 32 GB, manuelles Löschen ohne 31-Tage-Auto-Ablauf) sowie HTTP-Bildfehler (404 fehlende Datei, 403/HTML-Sicherheitsabfrage mit Sitzungserneuerung, 429 Rate-Limit mit Exponential Backoff).',
    starterPrompts: [
      'Warum zeigt eine einzelne Manga-Seite HTTP 403 (HTML-Sicherheitsabfrage) an?',
      'Wie wähle ich das optimale Bild-Cache-Limit zwischen 4 GB und 32 GB?',
      'Wann startet das automatische Pre-Fetching für das nächste Kapitel?',
    ],
  },
  {
    id: 'story-analyst',
    label: 'Kapitel-Analyst',
    subtitle: 'Handlung, Figuren & Zusammenfassungen',
    systemInstruction:
      'Du bist ein aufmerksamer Literatur- und Manga-Analyst in der MangaReaderApp. Antworte auf Deutsch in klar gegliederten Absätzen. Hilf dem Leser dabei, Handlungsstränge, Figurenbeziehungen, Symbolik und Panel-Aufbau zu verstehen, und halte Antworten kompakt (maximal ~200 Wörter, sofern keine ausführliche Analyse gewünscht wird).',
    starterPrompts: [
      'Fasse typische visuelle Stilmittel von Samurai-Seinen-Kapiteln zusammen.',
      'Wie behalte ich bei langen Webtoons den Überblick über viele Charaktere?',
      'Erkläre mir den Unterschied zwischen Rechts-nach-Links- und Links-nach-Rechts-Panelführung.',
    ],
  },
];

const ALLOWED_MODELS = new Set<ChatModelId>(['gemini-4-hydrogen', 'gemini-3.1-flash-lite']);
const MAX_HISTORY_TURNS = 30;
const MAX_MESSAGE_CHARS = 4000;

export function resolveAllowedModel(rawModel: unknown): ChatModelId {
  if (typeof rawModel === 'string' && ALLOWED_MODELS.has(rawModel as ChatModelId)) {
    return rawModel as ChatModelId;
  }
  return 'gemini-4-hydrogen';
}

export function resolvePersona(rawPersonaId: unknown): ChatPersonaOption {
  if (typeof rawPersonaId === 'string') {
    const match = CHAT_PERSONAS.find((p) => p.id === rawPersonaId);
    if (match) return match;
  }
  return CHAT_PERSONAS[0];
}

export function buildSystemInstruction(
  personaId: unknown,
  context?: ChatContextSummary
): string {
  const persona = resolvePersona(personaId);
  const parts: string[] = [persona.systemInstruction];

  if (context) {
    const ctxBits: string[] = [];
    if (typeof context.seriesTitle === 'string' && context.seriesTitle.trim()) {
      ctxBits.push(`Aktuelle Serie: ${context.seriesTitle.trim().slice(0, 120)}`);
    }
    if (typeof context.chapterTitle === 'string' && context.chapterTitle.trim()) {
      ctxBits.push(`Aktuelles Kapitel: ${context.chapterTitle.trim().slice(0, 120)}`);
    }
    if (
      typeof context.currentPage === 'number' &&
      typeof context.totalPages === 'number' &&
      Number.isFinite(context.currentPage) &&
      Number.isFinite(context.totalPages) &&
      context.totalPages > 0
    ) {
      ctxBits.push(`Leseposition: Seite ${context.currentPage} von ${context.totalPages}`);
    }
    if (typeof context.cacheLimitLabel === 'string' && context.cacheLimitLabel.trim()) {
      ctxBits.push(`Konfiguriertes Bild-Cache-Limit: ${context.cacheLimitLabel.trim().slice(0, 40)}`);
    }
    if (ctxBits.length > 0) {
      parts.push(`Aktueller App-Kontext (nur nutzen, wenn relevant): ${ctxBits.join(' | ')}.`);
    }
  }

  return parts.join('\n\n');
}

export function sanitizeChatHistory(rawHistory: unknown): SanitizedHistoryTurn[] {
  if (!Array.isArray(rawHistory)) return [];
  const sanitized: SanitizedHistoryTurn[] = [];

  for (const item of rawHistory) {
    if (!item || typeof item !== 'object') continue;
    const role = (item as { role?: unknown }).role;
    const text = (item as { text?: unknown }).text;
    if ((role !== 'user' && role !== 'model') || typeof text !== 'string') continue;
    const trimmed = text.trim();
    if (!trimmed) continue;
    sanitized.push({
      role,
      text: trimmed.slice(0, MAX_MESSAGE_CHARS),
    });
  }

  return sanitized.slice(-MAX_HISTORY_TURNS);
}

export function sanitizeUserMessage(rawMessage: unknown): string | null {
  if (typeof rawMessage !== 'string') return null;
  const trimmed = rawMessage.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, MAX_MESSAGE_CHARS);
}
