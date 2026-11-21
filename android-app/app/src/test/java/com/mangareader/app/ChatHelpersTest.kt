package com.mangareader.app

import com.mangareader.app.data.chat.ChatContextSummary
import com.mangareader.app.data.chat.ChatHelpers
import com.mangareader.app.data.chat.ChatMessage
import com.mangareader.app.data.chat.ChatModelId
import com.mangareader.app.data.chat.ChatPersonaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHelpersTest {

    @Test
    fun `resolveAllowedModel and resolvePersona enforce valid enum fallbacks`() {
        assertEquals(ChatModelId.STANDARD, ChatHelpers.resolveAllowedModel("gemini-4-hydrogen"))
        assertEquals(ChatModelId.FAST, ChatHelpers.resolveAllowedModel("gemini-3.1-flash-lite"))
        assertEquals(ChatModelId.STANDARD, ChatHelpers.resolveAllowedModel("unknown-model"))
        assertEquals(ChatModelId.STANDARD, ChatHelpers.resolveAllowedModel(null))

        assertEquals(ChatPersonaId.READER_TECH, ChatHelpers.resolvePersona("reader-tech"))
        assertEquals(ChatPersonaId.MANGA_GUIDE, ChatHelpers.resolvePersona("invalid"))
    }

    @Test
    fun `buildSystemInstruction includes persona role and sanitized app context`() {
        val prompt = ChatHelpers.buildSystemInstruction(
            ChatPersonaId.READER_TECH,
            ChatContextSummary(
                seriesTitle = "Kurogane: Klingen im Regen",
                chapterTitle = "Kapitel 12",
                currentPage = 3,
                totalPages = 6,
                cacheLimitLabel = "8 GB"
            )
        )

        assertTrue(prompt.contains("technische Support-Assistent"))
        assertTrue(prompt.contains("Kurogane: Klingen im Regen"))
        assertTrue(prompt.contains("Seite 3 von 6"))
        assertTrue(prompt.contains("8 GB"))
    }

    @Test
    fun `sanitizeChatHistory filters invalid entries, trims whitespace, and enforces 30-turn window`() {
        val mixed = listOf(
            ChatMessage(role = "system", text = "should be ignored"),
            ChatMessage(role = "user", text = "   Hallo!   "),
            ChatMessage(role = "model", text = ""),
            ChatMessage(role = "model", text = "Fehler", isError = true),
            ChatMessage(role = "model", text = "Hallo zurück!")
        )
        val clean = ChatHelpers.sanitizeChatHistory(mixed)
        assertEquals(2, clean.size)
        assertEquals("user", clean[0].role)
        assertEquals("Hallo!", clean[0].text)
        assertEquals("model", clean[1].role)
        assertEquals("Hallo zurück!", clean[1].text)

        val longHistory = (0 until 40).map { i ->
            ChatMessage(role = if (i % 2 == 0) "user" else "model", text = "Turn $i")
        }
        val trimmed = ChatHelpers.sanitizeChatHistory(longHistory)
        assertEquals(30, trimmed.size)
        assertEquals("Turn 10", trimmed.first().text)
        assertEquals("Turn 39", trimmed.last().text)
    }

    @Test
    fun `sanitizeUserMessage rejects empty strings and caps excessive length`() {
        assertNull(ChatHelpers.sanitizeUserMessage("   "))
        assertNull(ChatHelpers.sanitizeUserMessage(null))
        assertEquals("Testfrage", ChatHelpers.sanitizeUserMessage("  Testfrage  "))
        val huge = "a".repeat(5000)
        assertEquals(4000, ChatHelpers.sanitizeUserMessage(huge)?.length)
    }
}
