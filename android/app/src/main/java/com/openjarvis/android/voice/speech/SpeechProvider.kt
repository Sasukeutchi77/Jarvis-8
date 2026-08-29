package com.openjarvis.android.voice.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.storage.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Standard Error Codes for JARVIS Speech-To-Text Recognition.
 */
object SpeechErrorCodes {
    const val ERROR_NONE = 0
    const val ERROR_NO_API_KEY = -101
    const val ERROR_UNAUTHORIZED = 401
    const val ERROR_FORBIDDEN = 403
    const val ERROR_RATE_LIMITED = 429
    const val ERROR_SERVER = 500
    const val ERROR_NETWORK = -102
    const val ERROR_AUDIO_RECORD = -103
    const val ERROR_PERMISSION_DENIED = -104
    const val ERROR_TIMEOUT = -105
    const val ERROR_CANCELLED = -106
    const val ERROR_RECOGNITION_UNAVAILABLE = -107
    const val ERROR_RECOGNIZER_BUSY = -108
    const val ERROR_NO_MATCH = -109
}

/**
 * State enum for STT engine lifecycle.
 */
enum class SttState {
    IDLE,
    LISTENING,
    PROCESSING,
    STOPPING
}

/**
 * Result data class for parsed Deepgram transcript message.
 */
data class DeepgramTranscriptResult(
    val transcript: String,
    val isFinal: Boolean,
    val speechFinal: Boolean,
    val confidence: Double = 0.0
)

/**
 * Interface for Speech-To-Text Recognition Providers in JARVIS.
 */
interface SpeechProvider {
    val name: String
    val state: SttState
        get() = if (isListening()) SttState.LISTENING else SttState.IDLE

    fun isAvailable(): Boolean
    fun startListening(
        language: String = "fr-FR",
        onPartialResult: (transcript: String) -> Unit,
        onFinalResult: (transcript: String) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit,
        onRmsDbChanged: (rmsDb: Float) -> Unit = {}
    )
    fun stopListening()
    fun cancel() {}
    fun isListening(): Boolean
    fun release()
}

/**
 * Native Android SpeechRecognizer Provider (Offline & System Native Engine).
 * Acts as the rock-solid fallback STT engine when Cloud Deepgram is unavailable.
 */
class AndroidSpeechProvider(private val context: Context) : SpeechProvider {

    override val name: String = "Android SpeechRecognizer"

    private var speechRecognizer: SpeechRecognizer? = null
    private var _state: SttState = SttState.IDLE
    override val state: SttState
        get() = _state

    private var hasEmittedFinalResult: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    override fun isListening(): Boolean = _state == SttState.LISTENING || _state == SttState.PROCESSING

    override fun startListening(
        language: String,
        onPartialResult: (transcript: String) -> Unit,
        onFinalResult: (transcript: String) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit,
        onRmsDbChanged: (rmsDb: Float) -> Unit
    ) {
        // 1. Permission Guard
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state = SttState.IDLE
            JarvisLogger.e("AndroidSpeechProvider", "ANDROID_STT_ERROR: Permission microphone manquante (RECORD_AUDIO).")
            onError(SpeechErrorCodes.ERROR_PERMISSION_DENIED, "PERMISSION_REQUIRED: Permission microphone manquante.")
            return
        }

        // 2. Recognition Availability Guard
        if (!isAvailable()) {
            _state = SttState.IDLE
            JarvisLogger.w("AndroidSpeechProvider", "ANDROID_STT_ERROR: Service SpeechRecognizer non disponible sur cet appareil.")
            onError(SpeechErrorCodes.ERROR_RECOGNITION_UNAVAILABLE, "Service de reconnaissance vocale Android indisponible.")
            return
        }

        mainHandler.post {
            try {
                // Prevent duplicate concurrent sessions: destroy previous instance cleanly
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                    } catch (e: Exception) {
                        JarvisLogger.w("AndroidSpeechProvider", "Error cleaning previous recognizer: ${e.message}")
                    }
                    speechRecognizer = null
                }

                hasEmittedFinalResult = false
                _state = SttState.LISTENING

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _state = SttState.LISTENING
                            JarvisLogger.i("AndroidSpeechProvider", "ANDROID_STT_STARTED (locale=$language)")
                        }

                        override fun onBeginningOfSpeech() {
                            _state = SttState.LISTENING
                            JarvisLogger.d("AndroidSpeechProvider", "Speech input detected.")
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            onRmsDbChanged(rmsdB)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _state = SttState.PROCESSING
                            JarvisLogger.d("AndroidSpeechProvider", "Speech ended, processing transcription...")
                        }

                        override fun onError(error: Int) {
                            _state = SttState.IDLE
                            val (mappedCode, errorMsg) = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> Pair(SpeechErrorCodes.ERROR_AUDIO_RECORD, "ERROR_AUDIO: Erreur capture microphone")
                                SpeechRecognizer.ERROR_CLIENT -> Pair(error, "ERROR_CLIENT: Erreur client SpeechRecognizer")
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Pair(SpeechErrorCodes.ERROR_PERMISSION_DENIED, "ERROR_INSUFFICIENT_PERMISSIONS: Permission requise")
                                SpeechRecognizer.ERROR_NETWORK -> Pair(SpeechErrorCodes.ERROR_NETWORK, "ERROR_NETWORK: Erreur de connexion réseau Google")
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Pair(SpeechErrorCodes.ERROR_TIMEOUT, "ERROR_NETWORK_TIMEOUT: Délai réseau dépassé")
                                SpeechRecognizer.ERROR_NO_MATCH -> Pair(SpeechErrorCodes.ERROR_NO_MATCH, "ERROR_NO_MATCH: Aucun mot reconnu")
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Pair(SpeechErrorCodes.ERROR_RECOGNIZER_BUSY, "ERROR_RECOGNIZER_BUSY: Service de reconnaissance occupé")
                                SpeechRecognizer.ERROR_SERVER -> Pair(SpeechErrorCodes.ERROR_SERVER, "ERROR_SERVER: Erreur serveur Google STT")
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Pair(SpeechErrorCodes.ERROR_TIMEOUT, "ERROR_SPEECH_TIMEOUT: Silence détecté (timeout)")
                                else -> Pair(error, "Erreur Android SpeechRecognizer ($error)")
                            }

                            JarvisLogger.w("AndroidSpeechProvider", "ANDROID_STT_ERROR: $errorMsg (code=$error)")

                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                try {
                                    speechRecognizer?.cancel()
                                    speechRecognizer?.destroy()
                                    speechRecognizer = null
                                } catch (e: Exception) {
                                    JarvisLogger.w("AndroidSpeechProvider", "Cleanup on busy error: ${e.message}")
                                }
                            }

                            onError(mappedCode, errorMsg)
                        }

                        override fun onResults(results: Bundle?) {
                            _state = SttState.IDLE
                            if (hasEmittedFinalResult) {
                                JarvisLogger.d("AndroidSpeechProvider", "Duplicate onResults event ignored.")
                                return
                            }
                            hasEmittedFinalResult = true

                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val finalTranscript = matches?.firstOrNull()?.trim() ?: ""

                            JarvisLogger.i("AndroidSpeechProvider", "ANDROID_STT_RESULT: '$finalTranscript'")
                            onFinalResult(finalTranscript)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (_state != SttState.STOPPING && !hasEmittedFinalResult) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val partial = matches?.firstOrNull()?.trim() ?: ""
                                if (partial.isNotBlank()) {
                                    JarvisLogger.d("AndroidSpeechProvider", "Partial transcript: '$partial'")
                                    onPartialResult(partial)
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _state = SttState.IDLE
                JarvisLogger.e("AndroidSpeechProvider", "ANDROID_STT_ERROR: Exception starting recognition", e)
                onError(-1, e.message ?: "Failed to start speech recognizer")
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            try {
                if (_state == SttState.LISTENING) {
                    _state = SttState.STOPPING
                    speechRecognizer?.stopListening()
                    JarvisLogger.i("AndroidSpeechProvider", "ANDROID_STT_STOPPED")
                }
            } catch (e: Exception) {
                JarvisLogger.e("AndroidSpeechProvider", "Error stopping recognizer", e)
            }
        }
    }

    override fun cancel() {
        mainHandler.post {
            try {
                hasEmittedFinalResult = true
                _state = SttState.IDLE
                speechRecognizer?.cancel()
                JarvisLogger.i("AndroidSpeechProvider", "ANDROID_STT_CANCELLED")
            } catch (e: Exception) {
                JarvisLogger.e("AndroidSpeechProvider", "Error canceling recognizer", e)
            }
        }
    }

    override fun release() {
        mainHandler.post {
            try {
                hasEmittedFinalResult = true
                _state = SttState.IDLE
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                JarvisLogger.e("AndroidSpeechProvider", "Error destroying speech recognizer", e)
            } finally {
                speechRecognizer = null
            }
        }
    }
}

/**
 * Real Deepgram Nova-2 Live WebSocket Streaming Speech Recognition Provider.
 * Streams real-time 16kHz 16-bit Mono PCM audio directly to the Deepgram WebSocket API
 * and parses live interim and finalized transcripts.
 */
class DeepgramSpeechProvider(
    private val context: Context,
    private val secureVault: SecureVault
) : SpeechProvider {

    override val name: String = "Deepgram Nova-2 Live STT Provider"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var webSocket: WebSocket? = null
    private var _isListening: Boolean = false

    private val accumulatedTranscript = StringBuilder()
    private var hasEmittedFinal = false

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for persistent WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 2048 // 1024 shorts = 64ms @ 16kHz

        /**
         * Helper parser for Deepgram JSON WebSocket responses.
         */
        fun parseDeepgramResponse(jsonStr: String): DeepgramTranscriptResult? {
            return try {
                val json = JSONObject(jsonStr)
                val type = json.optString("type", "")
                if (type == "Results" || json.has("channel")) {
                    val channel = json.optJSONObject("channel")
                    val alternatives = channel?.optJSONArray("alternatives")
                    val firstAlt = alternatives?.optJSONObject(0)
                    val transcript = firstAlt?.optString("transcript", "")?.trim() ?: ""
                    val confidence = firstAlt?.optDouble("confidence", 0.0) ?: 0.0
                    val isFinal = json.optBoolean("is_final", false)
                    val speechFinal = json.optBoolean("speech_final", false)

                    DeepgramTranscriptResult(
                        transcript = transcript,
                        isFinal = isFinal,
                        speechFinal = speechFinal,
                        confidence = confidence
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun isAvailable(): Boolean {
        val apiKey = secureVault.getSecret(SecureVault.KEY_DEEPGRAM).trim()
        return apiKey.isNotBlank()
    }

    override fun isListening(): Boolean = _isListening

    override fun startListening(
        language: String,
        onPartialResult: (transcript: String) -> Unit,
        onFinalResult: (transcript: String) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit,
        onRmsDbChanged: (rmsDb: Float) -> Unit
    ) {
        // 1. Validate Microphone Permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            JarvisLogger.e("DeepgramSpeechProvider", "RECORD_AUDIO permission is not granted.")
            onError(SpeechErrorCodes.ERROR_PERMISSION_DENIED, "Permission microphone manquante.")
            return
        }

        // 2. Validate Deepgram API Key
        val apiKey = secureVault.getSecret(SecureVault.KEY_DEEPGRAM).trim()
        if (apiKey.isBlank()) {
            JarvisLogger.w("DeepgramSpeechProvider", "Deepgram API key is absent in vault.")
            onError(SpeechErrorCodes.ERROR_NO_API_KEY, "Clé API Deepgram manquante.")
            return
        }

        stopListeningInternal()
        accumulatedTranscript.clear()
        hasEmittedFinal = false

        // 3. Build Deepgram Live Streaming WebSocket URL
        val langParam = if (language.lowercase().startsWith("fr")) "fr" else "en"
        val wsUrl = "wss://api.deepgram.com/v1/listen?model=nova-2&language=$langParam&smart_format=true&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1&interim_results=true&endpointing=300&vad_events=true"

        JarvisLogger.i("DeepgramSpeechProvider", "Deepgram STT starting (model=nova-2, lang=$langParam)...")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                JarvisLogger.i("DeepgramSpeechProvider", "Deepgram WebSocket connected.")
                _isListening = true
                startAudioCapture(ws, onRmsDbChanged, onError)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val parsed = parseDeepgramResponse(text) ?: return
                if (parsed.transcript.isNotBlank()) {
                    if (parsed.isFinal) {
                        if (accumulatedTranscript.isNotEmpty()) {
                            accumulatedTranscript.append(" ")
                        }
                        accumulatedTranscript.append(parsed.transcript)
                        val fullTranscript = accumulatedTranscript.toString().trim()

                        JarvisLogger.i("DeepgramSpeechProvider", "Final chunk: '${parsed.transcript}' -> Full: '$fullTranscript'")
                        onPartialResult(fullTranscript)

                        if (parsed.speechFinal) {
                            JarvisLogger.i("DeepgramSpeechProvider", "Deepgram speech_final endpointing detected.")
                            hasEmittedFinal = true
                            onFinalResult(fullTranscript)
                        }
                    } else {
                        val interimText = if (accumulatedTranscript.isNotEmpty()) {
                            "${accumulatedTranscript} ${parsed.transcript}".trim()
                        } else {
                            parsed.transcript
                        }
                        JarvisLogger.d("DeepgramSpeechProvider", "Interim transcript: '$interimText'")
                        onPartialResult(interimText)
                    }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                JarvisLogger.d("DeepgramSpeechProvider", "Deepgram WebSocket closing: $code / $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                JarvisLogger.i("DeepgramSpeechProvider", "Deepgram WebSocket closed: $code")
                stopListeningInternal()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: -1
                val errorMsg = when (httpCode) {
                    401, 403 -> "Erreur d'authentification Deepgram (Clé API invalide: HTTP $httpCode)"
                    400 -> "Paramètres de requête Deepgram invalides (HTTP 400)"
                    404 -> "Service Deepgram introuvable (HTTP 404)"
                    408 -> "Délai de connexion Deepgram dépassé (HTTP 408)"
                    429 -> "Limite de requêtes Deepgram atteinte (HTTP 429)"
                    in 500..599 -> "Erreur serveur Deepgram (HTTP $httpCode)"
                    else -> "Erreur de connexion Deepgram: ${t.message ?: "Réseau indisponible"}"
                }
                JarvisLogger.w("DeepgramSpeechProvider", "Deepgram error: $errorMsg (code=$httpCode)")
                stopListeningInternal()
                onError(if (httpCode > 0) httpCode else SpeechErrorCodes.ERROR_NETWORK, errorMsg)
            }
        })
    }

    private fun startAudioCapture(
        ws: WebSocket,
        onRmsDbChanged: (rmsDb: Float) -> Unit,
        onError: (errorCode: Int, message: String) -> Unit
    ) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            onError(SpeechErrorCodes.ERROR_AUDIO_RECORD, "Erreur calcul buffer microphone: $minBuf")
            return
        }

        val bufferSize = minBuf * 2
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError(SpeechErrorCodes.ERROR_AUDIO_RECORD, "Microphone indisponible ou occupé.")
                releaseHardware()
                return
            }

            audioRecord?.startRecording()
            JarvisLogger.i("DeepgramSpeechProvider", "Microphone audio stream started @ $SAMPLE_RATE Hz Linear16.")

            captureJob = scope.launch {
                val rawBuffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && _isListening) {
                    val bytesRead = audioRecord?.read(rawBuffer, 0, rawBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        // Calculate RMS level for visualizer
                        var sumSquares = 0.0
                        val numShorts = bytesRead / 2
                        for (i in 0 until numShorts) {
                            val sample = ((rawBuffer[i * 2 + 1].toInt() shl 8) or (rawBuffer[i * 2].toInt() and 0xFF)).toShort()
                            sumSquares += sample * sample
                        }
                        val rms = if (numShorts > 0) sqrt(sumSquares / numShorts).toFloat() else 0f
                        val rmsDb = if (rms > 1e-4f) (20 * log10(rms.toDouble() / 32767.0) + 90.0).toFloat().coerceIn(0f, 90f) else 0f
                        onRmsDbChanged(rmsDb)

                        // Send audio chunk over WebSocket
                        val byteString = ByteString.of(rawBuffer, 0, bytesRead)
                        ws.send(byteString)
                    } else if (bytesRead < 0) {
                        JarvisLogger.w("DeepgramSpeechProvider", "AudioRecord read error code: $bytesRead")
                        delay(40)
                    }
                    delay(5)
                }
            }
        } catch (e: Exception) {
            JarvisLogger.e("DeepgramSpeechProvider", "Exception in AudioRecord setup", e)
            onError(SpeechErrorCodes.ERROR_AUDIO_RECORD, "Erreur microphone: ${e.message}")
        }
    }

    override fun stopListening() {
        if (_isListening) {
            try {
                webSocket?.send("{\"type\": \"CloseStream\"}")
                webSocket?.close(1000, "Listening stopped")
            } catch (e: Exception) {
                JarvisLogger.w("DeepgramSpeechProvider", "Error closing WebSocket: ${e.message}")
            }
            stopListeningInternal()
        }
    }

    private fun stopListeningInternal() {
        _isListening = false
        captureJob?.cancel()
        captureJob = null
        releaseHardware()
        webSocket = null
    }

    private fun releaseHardware() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            JarvisLogger.e("DeepgramSpeechProvider", "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }

    override fun release() {
        stopListeningInternal()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}

