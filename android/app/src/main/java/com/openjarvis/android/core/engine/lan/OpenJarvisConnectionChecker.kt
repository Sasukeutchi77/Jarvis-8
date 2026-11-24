package com.openjarvis.android.core.engine.lan

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

data class ServerConnectionResult(val connected: Boolean, val message: String)

/** Checks OpenJarvis authentication first, then the standard OpenAI-compatible models endpoint. */
object OpenJarvisConnectionChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun check(serverUrl: String, apiKey: String): ServerConnectionResult = withContext(Dispatchers.IO) {
        val baseUrl = serverUrl.trim().trimEnd('/')
        val uri = Uri.parse(baseUrl)
        if (uri.host.isNullOrBlank() || (uri.scheme != "http" && uri.scheme != "https")) {
            return@withContext ServerConnectionResult(
                connected = false,
                message = "Adresse invalide. Utilisez une URL complète en http:// ou https://."
            )
        }

        try {
            val authResponse = request("$baseUrl/api/auth/status", apiKey)
            if (authResponse.code == 401 || authResponse.code == 403) {
                return@withContext ServerConnectionResult(false, "Accès refusé. Vérifiez la clé API ou le jeton de l’appareil.")
            }
            if (authResponse.code in 200..299) {
                val authenticated = runCatching { JSONObject(authResponse.body).optBoolean("authenticated") }.getOrDefault(false)
                return@withContext if (authenticated) {
                    ServerConnectionResult(true, "Serveur OpenJarvis connecté et authentification validée.")
                } else {
                    ServerConnectionResult(false, "Serveur OpenJarvis joignable, mais clé invalide ou non configurée côté serveur.")
                }
            }
            if (authResponse.code != 404) {
                return@withContext ServerConnectionResult(
                    false,
                    "Le serveur répond, mais le contrôle OpenJarvis a échoué (HTTP ${authResponse.code})."
                )
            }

            val modelsResponse = request("$baseUrl/v1/models", apiKey)
            when {
                modelsResponse.code in 200..299 -> ServerConnectionResult(
                    true,
                    "Serveur compatible OpenAI connecté. Le point d’accès OpenJarvis n’est pas présent ; la clé n’a pas pu être vérifiée."
                )
                modelsResponse.code == 401 || modelsResponse.code == 403 -> ServerConnectionResult(
                    false,
                    "Serveur joignable, mais la clé API est absente ou refusée."
                )
                else -> ServerConnectionResult(
                    false,
                    "Serveur joignable, mais les points d’accès /api/auth/status et /v1/models échouent (HTTP ${modelsResponse.code})."
                )
            }
        } catch (_: UnknownHostException) {
            ServerConnectionResult(false, "Nom d’hôte introuvable. Vérifiez l’adresse et le réseau Wi-Fi.")
        } catch (_: ConnectException) {
            ServerConnectionResult(false, "Connexion refusée. Vérifiez l’adresse IP, le port et que le serveur est démarré.")
        } catch (_: SocketTimeoutException) {
            ServerConnectionResult(false, "Délai dépassé. Le serveur ne répond pas assez vite.")
        } catch (_: SSLHandshakeException) {
            ServerConnectionResult(false, "Échec TLS. Vérifiez le certificat HTTPS du serveur.")
        } catch (e: IllegalArgumentException) {
            ServerConnectionResult(false, "URL invalide : ${e.localizedMessage ?: "format non reconnu"}.")
        } catch (e: IOException) {
            ServerConnectionResult(false, "Erreur réseau : ${e.localizedMessage ?: "connexion interrompue"}.")
        }
    }

    private data class ResponseSnapshot(val code: Int, val body: String)

    private fun request(url: String, apiKey: String): ResponseSnapshot {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
            }
            .build()
        return client.newCall(request).execute().use { response ->
            ResponseSnapshot(response.code, response.body?.string().orEmpty())
        }
    }
}

