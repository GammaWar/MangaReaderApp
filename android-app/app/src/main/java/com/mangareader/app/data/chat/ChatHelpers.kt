package com.mangareader.app.data.chat

enum class ChatModelId(
    val id: String,
    val apiModelId: String,
    val label: String,
    val description: String
) {
    STANDARD(
        id = "gemini-4-hydrogen",
        apiModelId = "gemini-2.5-flash",
        label = "Standard",
        description = "Ausgewogene Antworten für Empfehlungen, Fragen & Erklärungen"
    ),
    FAST(
        id = "gemini-3.1-flash-lite",
        apiModelId = "gemini-2.5-flash-lite",
        label = "Schnell",
        description = "Besonders geringe Latenz für kurze Rückfragen beim Lesen"
    );

    companion object {
        fun fromId(raw: String?): ChatModelId {
            return entries.firstOrNull { it.id == raw } ?: STANDARD
        }
    }
}

enum class ChatPersonaId(
    val id: String,
    val label: String,
    val subtitle: String,
    val systemInstruction: String,
    val starterPrompts: List<String>
) {
    MANGA_GUIDE(
        id = "manga-guide",
        label = "Manga-Berater",
        subtitle = "Empfehlungen, Genres & Lese-Tipps",
        systemInstruction = "Du bist der sachkundige Manga- und Webtoon-Berater in der MangaReaderApp. Antworte auf Deutsch, prägnant, strukturiert und ohne Spoiler (außer der Nutzer bittet ausdrücklich darum). Gib passende Serien-Empfehlungen nach Stimmung, Genre, Zeichenstil oder Erzähltempo und erkläre japanische/koreanische Manga-Begriffe verständlich.",
        starterPrompts = listOf(
            "Empfiehl mir 3 düstere Seinen-Mangas mit starker Atmosphäre.",
            "Was unterscheidet den Webtoon-Vertikalmodus vom klassischen Manga-Blättermodus?",
            "Welche Sci-Fi-Webtoons lohnen sich für kurze Lesepausen?"
        )
    ),
    READER_TECH(
        id = "reader-tech",
        label = "Reader- & CDN-Hilfe",
        subtitle = "HTTP-Diagnose, Cache-Limit & Einstellungen",
        systemInstruction = "Du bist der technische Support-Assistent der MangaReaderApp. Antworte klar und praxisnah auf Deutsch. Erkläre Funktionen der App wie den AMOLED-Nachtfilter (Software-Dimmer & Sepia), Autoscroll, Pre-Fetching, das konfigurierbare Bild-Cache-Limit (Standard 4 GB, bis 32 GB, manuelles Löschen ohne 31-Tage-Auto-Ablauf) sowie HTTP-Bildfehler (404 fehlende Datei, 403/HTML- bzw. XML-Sicherheitsabfrage mit Sitzungserneuerung, 429 Rate-Limit mit Exponential Backoff, Null-Bitmap-Cache-Bypass).",
        starterPrompts = listOf(
            "Warum zeigt eine einzelne Manga-Seite HTTP 403 (HTML-Sicherheitsabfrage) an?",
            "Wie wähle ich das optimale Bild-Cache-Limit zwischen 4 GB und 32 GB?",
            "Wann startet das automatische Pre-Fetching für das nächste Kapitel?"
        )
    ),
    STORY_ANALYST(
        id = "story-analyst",
        label = "Kapitel-Analyst",
        subtitle = "Handlung, Figuren & Zusammenfassungen",
        systemInstruction = "Du bist ein aufmerksamer Literatur- und Manga-Analyst in der MangaReaderApp. Antworte auf Deutsch in klar gegliederten Absätzen. Hilf dem Leser dabei, Handlungsstränge, Figurenbeziehungen, Symbolik und Panel-Aufbau zu verstehen, und halte Antworten kompakt (maximal ~200 Wörter, sofern keine ausführliche Analyse gewünscht wird).",
        starterPrompts = listOf(
            "Fasse typische visuelle Stilmittel von Samurai-Seinen-Kapiteln zusammen.",
            "Wie behalte ich bei langen Webtoons den Überblick über viele Charaktere?",
            "Erkläre mir den Unterschied zwischen Rechts-nach-Links- und Links-nach-Rechts-Panelführung."
        )
    );

    companion object {
        fun fromId(raw: String?): ChatPersonaId {
            return entries.firstOrNull { it.id == raw } ?: MANGA_GUIDE
        }
    }
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" or "model"
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modelId: String? = null,
    val isError: Boolean = false
)

data class ChatContextSummary(
    val seriesTitle: String? = null,
    val chapterTitle: String? = null,
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val cacheLimitLabel: String? = null
)

data class SanitizedHistoryTurn(
    val role: String,
    val text: String
)

object ChatHelpers {
    const val MAX_HISTORY_TURNS = 30
    const val MAX_MESSAGE_CHARS = 4000

    fun resolveAllowedModel(rawModelId: String?): ChatModelId =
        ChatModelId.fromId(rawModelId)

    fun resolvePersona(rawPersonaId: String?): ChatPersonaId =
        ChatPersonaId.fromId(rawPersonaId)

    fun buildSystemInstruction(
        personaId: ChatPersonaId,
        context: ChatContextSummary? = null
    ): String {
        val parts = mutableListOf(personaId.systemInstruction)
        if (context != null) {
            val ctxBits = mutableListOf<String>()
            val series = context.seriesTitle?.trim().orEmpty()
            if (series.isNotEmpty()) {
                ctxBits.add("Aktuelle Serie: ${series.take(120)}")
            }
            val chapter = context.chapterTitle?.trim().orEmpty()
            if (chapter.isNotEmpty()) {
                ctxBits.add("Aktuelles Kapitel: ${chapter.take(120)}")
            }
            if (context.currentPage != null && context.totalPages != null && context.totalPages > 0) {
                ctxBits.add("Leseposition: Seite ${context.currentPage} von ${context.totalPages}")
            }
            val cacheLabel = context.cacheLimitLabel?.trim().orEmpty()
            if (cacheLabel.isNotEmpty()) {
                ctxBits.add("Konfiguriertes Bild-Cache-Limit: ${cacheLabel.take(40)}")
            }
            if (ctxBits.isNotEmpty()) {
                parts.add("Aktueller App-Kontext (nur nutzen, wenn relevant): ${ctxBits.joinToString(" | ")}.")
            }
        }
        return parts.joinToString("\n\n")
    }

    fun sanitizeChatHistory(rawMessages: List<ChatMessage>): List<SanitizedHistoryTurn> {
        val sanitized = mutableListOf<SanitizedHistoryTurn>()
        for (item in rawMessages) {
            if (item.isError) continue
            if (item.role != "user" && item.role != "model") continue
            val trimmed = item.text.trim()
            if (trimmed.isEmpty()) continue
            sanitized.add(
                SanitizedHistoryTurn(
                    role = item.role,
                    text = trimmed.take(MAX_MESSAGE_CHARS)
                )
            )
        }
        return if (sanitized.size > MAX_HISTORY_TURNS) {
            sanitized.takeLast(MAX_HISTORY_TURNS)
        } else {
            sanitized
        }
    }

    fun sanitizeUserMessage(rawMessage: String?): String? {
        if (rawMessage == null) return null
        val trimmed = rawMessage.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_MESSAGE_CHARS)
    }
}
