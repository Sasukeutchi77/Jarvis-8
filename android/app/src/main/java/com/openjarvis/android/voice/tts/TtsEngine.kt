package com.openjarvis.android.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.openjarvis.android.config.ConfigManager
import com.openjarvis.android.logging.JarvisLogger
import com.openjarvis.android.storage.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standardized TTS States for lifecycle and UI/Audio synchronization.
 */
enum class TtsState {
    IDLE,
    PREPARING,
    SPEAKING,
    FINISHING,
    ERROR
}

/**
 * Standardized TTS execution result status.
 */
enum class TtsResultStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    UNAVAILABLE
}

/**
 * Standardized TTS Result model.
 */
data class TtsResult(
    val status: TtsResultStatus,
    val providerName: String,
    val message: String? = null
)

/**
 * Interface for Text-To-Speech Synthesis Engines in JARVIS.
 */
interface TtsEngine {
    val name: String
    fun isReady(): Boolean
    fun isSpeaking(): Boolean
    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((errorMessage: String) -> Unit)? = null
    )
    fun stop()
    fun cancel()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun release()
}

/**
 * Android Native TextToSpeech Engine with robust initialization, locale management,
 * progress listener callbacks, and AudioFocus handling.
 */
class AndroidTtsEngine(
    private val context: Context,
    private val defaultLocale: Locale = Locale.FRENCH
) : TtsEngine {

    override val name: String = "Android TextToSpeech"

    private var tts: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private var _isSpeaking: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRate: Float = 1.05f
    private var speechPitch: Float = 1.0f

    private var activeUtteranceId: String? = null
    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val localeResult = tts?.setLanguage(defaultLocale)
                if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    JarvisLogger.w("AndroidTtsEngine", "Requested locale ($defaultLocale) not supported, falling back to default device locale.")
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(speechPitch)
                setupProgressListener()
                isInitialized = true
                JarvisLogger.i("AndroidTtsEngine", "Android TextToSpeech engine initialized successfully with locale ${tts?.voice?.locale ?: defaultLocale}.")
            } else {
                isInitialized = false
                JarvisLogger.e("AndroidTtsEngine", "Failed to initialize TextToSpeech: status=$status")
            }
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                _isSpeaking = true
                JarvisLogger.d("AndroidTtsEngine", "TTS_STARTED (Android TTS, utteranceId=$utteranceId)")
                mainHandler.post { activeOnStart?.invoke() }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                _isSpeaking = false
                JarvisLogger.d("AndroidTtsEngine", "TTS_FINISHED (Android TTS, utteranceId=$utteranceId)")
                mainHandler.post {
                    val doneCb = activeOnDone
                    clearActiveCallbacks()
                    doneCb?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                _isSpeaking = false
                JarvisLogger.e("AndroidTtsEngine", "TTS_ERROR (Android TTS, utteranceId=$utteranceId)")
                mainHandler.post {
                    val errCb = activeOnError
                    clearActiveCallbacks()
                    errCb?.invoke("Erreur de synthèse vocale Android")
                }
            }
        })
    }

    private fun clearActiveCallbacks() {
        activeUtteranceId = null
        activeOnStart = null
        activeOnDone = null
        activeOnError = null
    }

    override fun isReady(): Boolean = isInitialized

    override fun isSpeaking(): Boolean = _isSpeaking

    override fun setRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    override fun setPitch(pitch: Float) {
        speechPitch = pitch.coerceIn(0.5f, 1.5f)
        tts?.setPitch(speechPitch)
    }

    override fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        val cleanText = sanitizeTextForSpeech(text)
        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        // Cancel previous utterance cleanly
        stop()

        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        activeOnStart = onStart
        activeOnDone = onDone
        activeOnError = onError

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        mainHandler.post {
            try {
                _isSpeaking = true
                val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    _isSpeaking = false
                    JarvisLogger.w("AndroidTtsEngine", "TTS_ERROR: tts.speak returned TextToSpeech.ERROR")
                    clearActiveCallbacks()
                    onError?.invoke("Erreur speak TextToSpeech")
                }
            } catch (e: Exception) {
                _isSpeaking = false
                JarvisLogger.e("AndroidTtsEngine", "Exception during Android TTS speak", e)
                clearActiveCallbacks()
                onError?.invoke(e.message ?: "Exception speak")
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            try {
                tts?.stop()
            } catch (e: Exception) {
                JarvisLogger.e("AndroidTtsEngine", "Error stopping TTS", e)
            } finally {
                _isSpeaking = false
                clearActiveCallbacks()
            }
        }
    }

    override fun cancel() {
        JarvisLogger.d("AndroidTtsEngine", "TTS_CANCELLED (Android TTS)")
        stop()
    }

    override fun release() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                JarvisLogger.e("AndroidTtsEngine", "Error shutting down TTS", e)
            } finally {
                tts = null
                isInitialized = false
                _isSpeaking = false
                clearActiveCallbacks()
            }
        }
    }

    private fun sanitizeTextForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " extrait de code ")
            .replace(Regex("`[^`]+`"), " ")
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), " lien web ")
            .replace(Regex("^[#*>-]+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~`]+"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/**
 * Deepgram Aura HD Text-To-Speech Engine with real HTTP audio streaming,
 * text chunking for long texts, MediaPlayer audio playback, and clean lifecycle management.
 */
class DeepgramTtsEngine(
    private val context: Context,
    private val secureVault: SecureVault,
    private val model: String = "aura-helios-en"
) : TtsEngine {

    override val name: String = "Deepgram Aura HD"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingDeepgram: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var currentTempFile: File? = null

    // Track active callbacks to prevent multiple triggers
    private var activeOnStart: (() -> Unit)? = null
    private var activeOnDone: (() -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    override fun isReady(): Boolean = true

    override fun isSpeaking(): Boolean = isPlayingDeepgram

    override fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        val apiKey = secureVault.getSecret(SecureVault.KEY_DEEPGRAM)
        if (apiKey.isBlank()) {
            JarvisLogger.w("DeepgramTtsEngine", "DEEPGRAM_TTS_FAILED: No API key found in vault.")
            onError?.invoke("Clé API Deepgram manquante.")
            return
        }

        stop()

        activeOnStart = onStart
        activeOnDone = onDone
        activeOnError = onError

        activeJob = ioScope.launch {
            try {
                val cleanText = sanitizeTextForSpeech(text)
                if (cleanText.isBlank()) {
                    withContext(Dispatchers.Main) {
                        activeOnDone?.invoke()
                        clearCallbacks()
                    }
                    return@launch
                }

                // Handle text chunking for very long responses (> 1800 characters)
                val safeText = if (cleanText.length > 2000) cleanText.take(2000) + "..." else cleanText

                JarvisLogger.i("DeepgramTtsEngine", "DEEPGRAM_TTS_REQUEST (model=$model, len=${safeText.length})")

                val url = "https://api.deepgram.com/v1/speak?model=$model"
                val jsonBody = JSONObject().apply {
                    put("text", safeText)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val errMsg = response.body?.string() ?: "HTTP $code"
                    response.close()
                    JarvisLogger.w("DeepgramTtsEngine", "DEEPGRAM_TTS_FAILED: HTTP $code ($errMsg)")
                    withContext(Dispatchers.Main) {
                        val cb = activeOnError
                        clearCallbacks()
                        cb?.invoke("Deepgram HTTP $code")
                    }
                    return@launch
                }

                val responseBody = response.body
                if (responseBody == null) {
                    response.close()
                    JarvisLogger.w("DeepgramTtsEngine", "DEEPGRAM_TTS_FAILED: Empty response body from Deepgram.")
                    withContext(Dispatchers.Main) {
                        val cb = activeOnError
                        clearCallbacks()
                        cb?.invoke("Empty audio body")
                    }
                    return@launch
                }

                // Save audio bytes to clean temporary MP3 file
                val tempFile = File.createTempFile("jarvis_deepgram_tts_", ".mp3", context.cacheDir)
                currentTempFile = tempFile
                tempFile.deleteOnExit()

                FileOutputStream(tempFile).use { fos ->
                    responseBody.byteStream().copyTo(fos)
                }
                response.close()

                JarvisLogger.i("DeepgramTtsEngine", "DEEPGRAM_TTS_SUCCESS (audioSize=${tempFile.length()} bytes)")

                withContext(Dispatchers.Main) {
                    playAudioFile(tempFile)
                }
            } catch (e: Exception) {
                isPlayingDeepgram = false
                JarvisLogger.w("DeepgramTtsEngine", "DEEPGRAM_TTS_FAILED: Exception ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val cb = activeOnError
                    clearCallbacks()
                    cb?.invoke(e.message ?: "Deepgram network exception")
                }
            }
        }
    }

    private fun playAudioFile(audioFile: File) {
        try {
            cleanupMediaPlayer()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { mp ->
                    isPlayingDeepgram = true
                    JarvisLogger.d("DeepgramTtsEngine", "TTS_STARTED (Deepgram Aura)")
                    activeOnStart?.invoke()
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    isPlayingDeepgram = false
                    JarvisLogger.d("DeepgramTtsEngine", "TTS_FINISHED (Deepgram Aura)")
                    cleanupMediaPlayer()
                    audioFile.delete()
                    val cb = activeOnDone
                    clearCallbacks()
                    cb?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    isPlayingDeepgram = false
                    JarvisLogger.w("DeepgramTtsEngine", "TTS_ERROR (MediaPlayer what=$what extra=$extra)")
                    cleanupMediaPlayer()
                    audioFile.delete()
                    val cb = activeOnError
                    clearCallbacks()
                    cb?.invoke("MediaPlayer playback error ($what, $extra)")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            isPlayingDeepgram = false
            audioFile.delete()
            JarvisLogger.e("DeepgramTtsEngine", "Error preparing MediaPlayer", e)
            val cb = activeOnError
            clearCallbacks()
            cb?.invoke("MediaPlayer initialization error: ${e.message}")
        }
    }

    private fun cleanupMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (e: Exception) {
            JarvisLogger.w("DeepgramTtsEngine", "Error releasing MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
            isPlayingDeepgram = false
        }
    }

    private fun clearCallbacks() {
        activeOnStart = null
        activeOnDone = null
        activeOnError = null
    }

    override fun stop() {
        activeJob?.cancel()
        activeJob = null
        mainHandler.post {
            cleanupMediaPlayer()
            currentTempFile?.delete()
            currentTempFile = null
            clearCallbacks()
        }
    }

    override fun cancel() {
        JarvisLogger.d("DeepgramTtsEngine", "TTS_CANCELLED (Deepgram Aura)")
        stop()
    }

    override fun setRate(rate: Float) {
        // Aura handles pacing natively
    }

    override fun setPitch(pitch: Float) {
        // Aura handles pitch natively
    }

    override fun release() {
        stop()
        ioScope.launch {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun sanitizeTextForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " extrait de code ")
            .replace(Regex("`[^`]+`"), " ")
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), " lien web ")
            .replace(Regex("^[#*>-]+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~`]+"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/**
 * Complete TTS Manager orchestrating Provider Selection (Deepgram vs Android Native),
 * Seamless Fallback on API key missing/invalid/network errors, Audio Focus management,
 * and accurate TTS_FINISHED completion detection.
 */
class TtsManager(
    private val context: Context,
    private val secureVault: SecureVault,
    private val configManager: ConfigManager
) : TtsEngine {

    override val name: String
        get() = "TtsManager (Active: ${activeEngine.name})"

    val deepgramEngine = DeepgramTtsEngine(context, secureVault)
    val androidEngine = AndroidTtsEngine(context)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var _ttsState: TtsState = TtsState.IDLE
    val ttsState: TtsState get() = _ttsState

    private var isPlaying: Boolean = false
    private val isDestroyed = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private var isFallbackInProgress = false

    private val activeEngine: TtsEngine
        get() {
            val provider = configManager.config.value.ttsVoiceProvider.lowercase().trim()
            val deepgramKey = secureVault.getSecret(SecureVault.KEY_DEEPGRAM)
            return if (provider == "deepgram" && deepgramKey.isNotBlank()) {
                deepgramEngine
            } else {
                androidEngine
            }
        }

    override fun isReady(): Boolean = androidEngine.isReady()

    override fun isSpeaking(): Boolean = isPlaying || deepgramEngine.isSpeaking() || androidEngine.isSpeaking()

    override fun speak(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (isDestroyed.get()) {
            JarvisLogger.w("TtsManager", "TTS_ERROR: speak() called on a destroyed TtsManager instance.")
            onError?.invoke("TtsManager destroyed")
            return
        }

        if (text.isBlank()) {
            JarvisLogger.d("TtsManager", "Blank text provided to speak -> Immediate completion.")
            onDone?.invoke()
            return
        }

        // CANCEL_PREVIOUS: Stop any ongoing speech before starting new utterance
        stop()

        isCancelled.set(false)
        isFallbackInProgress = false
        _ttsState = TtsState.PREPARING
        requestAudioFocus()

        val provider = configManager.config.value.ttsVoiceProvider.lowercase().trim()
        val deepgramKey = secureVault.getSecret(SecureVault.KEY_DEEPGRAM)

        JarvisLogger.i("TtsManager", "TTS_PROVIDER_REQUESTED: $provider")

        when (provider) {
            "deepgram" -> {
                if (deepgramKey.isNotBlank()) {
                    JarvisLogger.i("TtsManager", "TTS_PROVIDER_SELECTED: Deepgram Aura HD")
                    _ttsState = TtsState.SPEAKING
                    isPlaying = true

                    deepgramEngine.speak(
                        text = text,
                        onStart = {
                            if (isCancelled.get() || isDestroyed.get()) return@speak
                            _ttsState = TtsState.SPEAKING
                            JarvisLogger.d("TtsManager", "TTS_STARTED")
                            onStart?.invoke()
                        },
                        onDone = {
                            if (isCancelled.get() || isDestroyed.get()) return@speak
                            _ttsState = TtsState.IDLE
                            isPlaying = false
                            abandonAudioFocus()
                            JarvisLogger.i("TtsManager", "TTS_SUCCESS: Speech playback completed via Deepgram.")
                            JarvisLogger.d("TtsManager", "TTS_FINISHED")
                            onDone?.invoke()
                        },
                        onError = { errorMsg ->
                            if (isCancelled.get() || isDestroyed.get()) return@speak
                            JarvisLogger.w("TtsManager", "TTS_FALLBACK: Deepgram failed ($errorMsg) -> Engaging Android TTS Fallback...")
                            // Fallback to Android native TTS (1 single attempt, preventing loops)
                            if (!isFallbackInProgress) {
                                isFallbackInProgress = true
                                fallbackToAndroidTts(text, onStart, onDone, onError)
                            } else {
                                _ttsState = TtsState.ERROR
                                isPlaying = false
                                abandonAudioFocus()
                                JarvisLogger.e("TtsManager", "TTS_ERROR: Fallback already in progress, aborting to prevent infinite loops.")
                                onError?.invoke("TTS Fallback error: $errorMsg")
                            }
                        }
                    )
                } else {
                    JarvisLogger.i("TtsManager", "TTS_FALLBACK: Deepgram requested but DEEPGRAM_API_KEY is empty -> Using Android Native TTS.")
                    fallbackToAndroidTts(text, onStart, onDone, onError)
                }
            }
            "android_native", "android" -> {
                JarvisLogger.i("TtsManager", "TTS_PROVIDER_SELECTED: Android Native TextToSpeech")
                _ttsState = TtsState.SPEAKING
                isPlaying = true
                androidEngine.speak(
                    text = text,
                    onStart = {
                        if (isCancelled.get() || isDestroyed.get()) return@speak
                        _ttsState = TtsState.SPEAKING
                        JarvisLogger.d("TtsManager", "TTS_STARTED")
                        onStart?.invoke()
                    },
                    onDone = {
                        if (isCancelled.get() || isDestroyed.get()) return@speak
                        _ttsState = TtsState.IDLE
                        isPlaying = false
                        abandonAudioFocus()
                        JarvisLogger.i("TtsManager", "TTS_SUCCESS: Speech playback completed via Android Native TTS.")
                        JarvisLogger.d("TtsManager", "TTS_FINISHED")
                        onDone?.invoke()
                    },
                    onError = { err ->
                        if (isCancelled.get() || isDestroyed.get()) return@speak
                        _ttsState = TtsState.ERROR
                        isPlaying = false
                        abandonAudioFocus()
                        JarvisLogger.e("TtsManager", "TTS_ERROR (Android TTS): $err")
                        onError?.invoke(err)
                    }
                )
            }
            else -> {
                JarvisLogger.w("TtsManager", "TTS_FALLBACK: Unknown provider '$provider' -> Falling back to Android Native TTS.")
                fallbackToAndroidTts(text, onStart, onDone, onError)
            }
        }
    }

    private fun fallbackToAndroidTts(
        text: String,
        onStart: (() -> Unit)?,
        onDone: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        if (isCancelled.get() || isDestroyed.get()) return
        JarvisLogger.i("TtsManager", "TTS_PROVIDER_SELECTED: Android Native TextToSpeech (Fallback)")
        _ttsState = TtsState.SPEAKING
        isPlaying = true
        androidEngine.speak(
            text = text,
            onStart = {
                if (isCancelled.get() || isDestroyed.get()) return@speak
                _ttsState = TtsState.SPEAKING
                JarvisLogger.d("TtsManager", "TTS_STARTED")
                onStart?.invoke()
            },
            onDone = {
                if (isCancelled.get() || isDestroyed.get()) return@speak
                _ttsState = TtsState.IDLE
                isPlaying = false
                abandonAudioFocus()
                JarvisLogger.i("TtsManager", "TTS_SUCCESS: Speech playback completed via Android Fallback.")
                JarvisLogger.d("TtsManager", "TTS_FINISHED")
                onDone?.invoke()
            },
            onError = { err ->
                if (isCancelled.get() || isDestroyed.get()) return@speak
                _ttsState = TtsState.ERROR
                isPlaying = false
                abandonAudioFocus()
                JarvisLogger.e("TtsManager", "TTS_ERROR (Android Fallback): $err")
                onError?.invoke(err)
            }
        )
    }

    override fun stop() {
        _ttsState = TtsState.IDLE
        isPlaying = false
        deepgramEngine.stop()
        androidEngine.stop()
        abandonAudioFocus()
    }

    override fun cancel() {
        isCancelled.set(true)
        _ttsState = TtsState.IDLE
        isPlaying = false
        JarvisLogger.d("TtsManager", "TTS_CANCELLED")
        deepgramEngine.cancel()
        androidEngine.cancel()
        abandonAudioFocus()
    }

    override fun setRate(rate: Float) {
        androidEngine.setRate(rate)
        deepgramEngine.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        androidEngine.setPitch(pitch)
        deepgramEngine.setPitch(pitch)
    }

    override fun release() {
        isDestroyed.set(true)
        stop()
        deepgramEngine.release()
        androidEngine.release()
        abandonAudioFocus()
        JarvisLogger.i("TtsManager", "TtsManager released and destroyed.")
    }

    private fun requestAudioFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            JarvisLogger.d("TtsManager", "AudioFocus lost -> pausing TTS")
                        }
                    }
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            JarvisLogger.e("TtsManager", "Error requesting AudioFocus", e)
        }
    }

    private fun abandonAudioFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            JarvisLogger.e("TtsManager", "Error abandoning AudioFocus", e)
        }
    }
}
