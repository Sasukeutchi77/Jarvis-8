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
 * Cloud Engine implementation for OpenAI Chat Completions API with SSE streaming.
 */
class OpenAICloudEngine(
    private val secureVault: SecureVault,
    override val modelName: String = "gpt-4o"
) : InferenceEngine {

    override val providerType: ProviderType = ProviderType.CLOUD_OPENAI

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(): Boolean {
        val key = secureVault.getSecret(SecureVault.KEY_OPENAI)
        return key.isNotBlank()
    }

    override fun generateStream(
        messages: List<EngineMessage>,
        contextMemory: List<DocumentEntity>,
        tools: List<String>
    ): Flow<StreamChunk> = flow {
        val apiKey = secureVault.getSecret(SecureVault.KEY_OPENAI)
        if (apiKey.isBlank()) {
            emit(StreamChunk.Error("OpenAI API Key manquante dans le SecureVault."))
            return@flow
        }

        val url = "https://api.openai.com/v1/chat/completions"

        // Build messages array
        val msgsArray = JSONArray()

        // System prompt with memory injection
        val systemPrompt = buildString {
            append("Tu es JARVIS, l'intelligence artificielle personnelle et bienveillante de l'utilisateur sur Android. ")
            append("Sois élégant, concis et réactif.\n")
            if (contextMemory.isNotEmpty()) {
                append("\n[CONTEXTE MÉMOIRE RÉCUPÉRÉ] :\n")
                contextMemory.forEach { doc ->
                    append("- [${doc.source}] ${doc.content}\n")
                }
            }
        }
        msgsArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        messages.forEach { m ->
            msgsArray.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            })
        }

        val requestJson = JSONObject().apply {
            put("model", modelName)
            put("messages", msgsArray)
            put("stream", true)
            put("temperature", 0.7)
        }

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: "Code ${response.code}"
                    JarvisLogger.e("OpenAIEngine", "HTTP Error $errBody")
                    emit(StreamChunk.Error("Erreur OpenAI Cloud: HTTP ${response.code}"))
                    return@flow
                }

                val responseBody = response.body
                if (responseBody == null) {
                    emit(StreamChunk.Error("Réponse vide de l'API OpenAI."))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                var line: String?
                var totalTokens = 0

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.startsWith("data: ")) {
                        val dataJson = currentLine.removePrefix("data: ").trim()
                        if (dataJson == "[DONE]" || dataJson.isEmpty()) continue

                        try {
                            val json = JSONObject(dataJson)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val choice = choices.getJSONObject(0)
                                val delta = choice.optJSONObject("delta")
                                val text = delta?.optString("content", "") ?: ""
                                if (text.isNotEmpty()) {
                                    totalTokens += Math.max(1, text.length / 4)
                                    emit(StreamChunk.TextDelta(text))
                                }
                            }
                        } catch (e: Exception) {
                            JarvisLogger.w("OpenAIEngine", "Failed to parse SSE line: $currentLine", e)
                        }
                    }
                }
                reader.close()
                emit(StreamChunk.Completed(totalTokens = totalTokens, promptTokens = 0, completionTokens = totalTokens))
            }
        } catch (e: Exception) {
            JarvisLogger.e("OpenAIEngine", "Connection error to OpenAI", e)
            emit(StreamChunk.Error("Impossible de contacter le serveur OpenAI: ${e.message}", e))
        }
    }.flowOn(Dispatchers.IO)
}
