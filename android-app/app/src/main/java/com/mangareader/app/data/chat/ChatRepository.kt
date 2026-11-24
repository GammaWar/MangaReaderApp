package com.mangareader.app.data.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatInitialState(
    val messages: List<ChatMessage>,
    val persona: ChatPersonaId,
    val model: ChatModelId,
    val apiKey: String
)

class ChatRepository(private val context: Context) {

    private val chatFile = File(context.filesDir, "assistant_chat_history.json")
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val writeMutex = Mutex()

    suspend fun loadInitialState(): ChatInitialState = withContext(Dispatchers.IO) {
        ChatInitialState(
            messages = loadMessagesSync(),
            persona = loadPersonaSync(),
            model = loadModelSync(),
            apiKey = loadApiKeySync()
        )
    }

    suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        saveApiKeySync(apiKey)
    }

    suspend fun savePersona(personaId: ChatPersonaId) = withContext(Dispatchers.IO) {
        savePersonaSync(personaId)
    }

    suspend fun saveModel(modelId: ChatModelId) = withContext(Dispatchers.IO) {
        saveModelSync(modelId)
    }

    fun loadApiKeySync(): String = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty().trim()

    fun saveApiKeySync(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun clearApiKeySync() {
        prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    fun loadPersonaSync(): ChatPersonaId =
        ChatPersonaId.fromId(prefs.getString(KEY_PERSONA, ChatPersonaId.MANGA_GUIDE.id))

    fun savePersonaSync(personaId: ChatPersonaId) {
        prefs.edit().putString(KEY_PERSONA, personaId.id).apply()
    }

    fun loadModelSync(): ChatModelId =
        ChatModelId.fromId(prefs.getString(KEY_MODEL, ChatModelId.STANDARD.id))

    fun saveModelSync(modelId: ChatModelId) {
        prefs.edit().putString(KEY_MODEL, modelId.id).apply()
    }

    fun loadMessagesSync(): List<ChatMessage> {
        return try {
            if (!chatFile.exists()) return emptyList()
            val text = chatFile.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            val result = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = obj.optString("role", "")
                val body = obj.optString("text", "")
                if ((role == "user" || role == "model") && body.isNotBlank()) {
                    result.add(
                        ChatMessage(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            role = role,
                            text = body,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            modelId = obj.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
                            isError = obj.optBoolean("isError", false)
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveMessages(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val arr = JSONArray()
                messages.takeLast(60).forEach { msg ->
                    val obj = JSONObject().apply {
                        put("id", msg.id)
                        put("role", msg.role)
                        put("text", msg.text)
                        put("createdAt", msg.createdAt)
                        msg.modelId?.let { put("modelId", it) }
                        put("isError", msg.isError)
                    }
                    arr.put(obj)
                }

                val tempFile = File(context.filesDir, "assistant_chat_history.tmp")
                tempFile.writeText(arr.toString())
                if (chatFile.exists()) {
                    chatFile.delete()
                }
                if (!tempFile.renameTo(chatFile)) {
                    chatFile.writeText(arr.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "mangareader_assistant_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_PERSONA = "selected_persona"
        private const val KEY_MODEL = "selected_model"
    }
}
