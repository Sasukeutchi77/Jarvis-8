package com.openjarvis.android.core.engine.cloud

import com.openjarvis.android.core.engine.EngineMessage
import com.openjarvis.android.core.engine.InferenceEngine
import com.openjarvis.android.core.engine.ProviderType
import com.openjarvis.android.core.engine.StreamChunk
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.storage.SecureVault
import com.openjarvis.android.storage.database.entity.DocumentEntity
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Cloud Engine implementation for Anthropic Claude API (v1/messages) with SSE streaming.
 */
class AnthropicCloudEngine(
    private val secureVault: SecureVault,
    override val modelName: String = "claude-3-5-sonnet-20241022"
) : InferenceEngine {

    override val providerType: ProviderType = ProviderType.CLOUD_ANTHROPIC

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(): Boolean {
        val key = secureVault.getSecret(SecureVault.KEY_ANTHROPIC)
        return key.isNotBlank()
    }

    override fun generateStream(
        messages: List<EngineMessage>,
        contextMemory: List<DocumentEntity>,
        tools: List<String>
    ): Flow<StreamChunk> = flow {
        val apiKey = secureVault.getSecret(SecureVault.KEY_ANTHROPIC)
        if (apiKey.isBlank()) {
            emit(StreamChunk.Error("Anthropic API Key manquante dans le SecureVault."))
            return@flow
        }

        val url = "https://api.anthropic.com/v1/messages"

        // Build messages array (Anthropic accepts 'user' and 'assistant')
        val msgsArray = JSONArray()
        messages.forEach { m ->
            msgsArray.put(JSONObject().apply {
                put("role", if (m.role == "assistant") "assistant" else "user")
                put("content", m.content)
            })
        }

        // System prompt with memory injection
        val systemPrompt = buildString {
            append("Tu es JARVIS, l'intelligence artificielle personnelle de l'utilisateur sur Android. ")
            append("Sois élégant, bienveillant, concis et efficace.\n")
            if (contextMemory.isNotEmpty()) {
                append("\n[MÉMOIRE ET CONTEXTE UTILISATEUR] :\n")
                contextMemory.forEach { doc ->
                    append("- [${doc.source}] ${doc.content}\n")
                }
            }
        }

        val requestJson = JSONObject().apply {
            put("model", modelName)
            put("messages", msgsArray)
            put("system", systemPrompt)
            put("max_tokens", 2048)
            put("stream", true)
        }

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: "Code ${response.code}"
                    JarvisLogger.e("AnthropicEngine", "HTTP Error $errBody")
                    emit(StreamChunk.Error("Erreur Anthropic Cloud: HTTP ${response.code}"))
                    return@flow
                }

                val responseBody = response.body
                if (responseBody == null) {
                    emit(StreamChunk.Error("Réponse vide de l'API Anthropic."))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                var line: String?
                var totalTokens = 0

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.startsWith("data: ")) {
                        val dataJson = currentLine.removePrefix("data: ").trim()
                        if (dataJson.isEmpty()) continue

                        try {
                            val json = JSONObject(dataJson)
                            val eventType = json.optString("type", "")

                            if (eventType == "content_block_delta") {
                                val delta = json.optJSONObject("delta")
                                val text = delta?.optString("text", "") ?: ""
                                if (text.isNotEmpty()) {
                                    totalTokens += Math.max(1, text.length / 4)
                                    emit(StreamChunk.TextDelta(text))
                                }
                            }
                        } catch (e: Exception) {
                            JarvisLogger.w("AnthropicEngine", "Failed to parse SSE line: $currentLine", e)
                        }
                    }
                }
                reader.close()
                emit(StreamChunk.Completed(totalTokens = totalTokens, promptTokens = 0, completionTokens = totalTokens))
            }
        } catch (e: Exception) {
            JarvisLogger.e("AnthropicEngine", "Connection error to Anthropic", e)
            emit(StreamChunk.Error("Impossible de contacter le serveur Anthropic: ${e.message}", e))
        }
    }.flowOn(Dispatchers.IO)
}
