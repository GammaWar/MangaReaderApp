package com.mangareader.app.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiChatClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun streamMessage(
        apiKey: String,
        model: ChatModelId,
        persona: ChatPersonaId,
        history: List<ChatMessage>,
        userMessage: String,
        contextSummary: ChatContextSummary
    ): Flow<String> = flow {
        val cleanedKey = apiKey.trim()
        if (cleanedKey.isEmpty()) {
            throw IOException(
                "Kein Gemini-API-Schlüssel hinterlegt. Tippe oben rechts auf das Schlüssel-Symbol, um einen Key einzutragen."
            )
        }

        val sanitizedHistory = ChatHelpers.sanitizeChatHistory(history)
        val systemPrompt = ChatHelpers.buildSystemInstruction(persona, contextSummary)

        val contentsArray = JSONArray()
        sanitizedHistory.forEach { turn ->
            contentsArray.put(
                JSONObject().apply {
                    put("role", turn.role)
                    put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                }
            )
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }
        )

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", contentsArray)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.apiModelId}:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cleanedKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                val friendly = when {
                    response.code == 400 || response.code == 401 || response.code == 403 ||
                        errBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                        errBody.contains("PERMISSION_DENIED", ignoreCase = true) -> {
                        "API-Zugriff abgelehnt (HTTP ${response.code}). Bitte prüfe deinen Gemini-API-Schlüssel."
                    }
                    response.code == 429 || errBody.contains("RESOURCE_EXHAUSTED", ignoreCase = true) -> {
                        "Anfragelimit erreicht (HTTP 429). Bitte warte kurz oder wechsle auf den schnellen Modus."
                    }
                    else -> {
                        val parsedMsg = runCatching {
                            JSONObject(errBody).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        parsedMsg?.takeIf { it.isNotBlank() }
                            ?: "Serverfehler (HTTP ${response.code}) beim Abruf der Antwort."
                    }
                }
                throw IOException(friendly)
            }

            val source = response.body?.source()
                ?: throw IOException("Streaming-Antwort konnte nicht gelesen werden.")

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val jsonPayload = trimmed.removePrefix("data:").trim()
                if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue

                val chunkText = extractChunkText(jsonPayload)
                if (!chunkText.isNullOrEmpty()) {
                    emit(chunkText)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun extractChunkText(jsonStr: String): String? {
        return runCatching {
            val root = JSONObject(jsonStr)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val first = candidates.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val t = part.optString("text", "")
                if (t.isNotEmpty()) sb.append(t)
            }
            sb.toString().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
