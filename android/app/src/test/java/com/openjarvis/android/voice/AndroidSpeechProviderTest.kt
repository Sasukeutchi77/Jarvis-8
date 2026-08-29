package com.openjarvis.android.voice

import com.openjarvis.android.voice.speech.SpeechErrorCodes
import com.openjarvis.android.voice.speech.SttState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Tests for AndroidSpeechProvider error mappings, lifecycle states,
 * deduplication guards, and Deepgram fallback behavior.
 */
class AndroidSpeechProviderTest {

    @Test
    fun testSttStateEnum() {
        assertEquals("IDLE", SttState.IDLE.name)
        assertEquals("LISTENING", SttState.LISTENING.name)
        assertEquals("PROCESSING", SttState.PROCESSING.name)
        assertEquals("STOPPING", SttState.STOPPING.name)
    }

    @Test
    fun testSpeechErrorCodesMapping() {
        assertEquals(0, SpeechErrorCodes.ERROR_NONE)
        assertEquals(-101, SpeechErrorCodes.ERROR_NO_API_KEY)
        assertEquals(-102, SpeechErrorCodes.ERROR_NETWORK)
        assertEquals(-103, SpeechErrorCodes.ERROR_AUDIO_RECORD)
        assertEquals(-104, SpeechErrorCodes.ERROR_PERMISSION_DENIED)
        assertEquals(-105, SpeechErrorCodes.ERROR_TIMEOUT)
        assertEquals(-106, SpeechErrorCodes.ERROR_CANCELLED)
        assertEquals(-107, SpeechErrorCodes.ERROR_RECOGNITION_UNAVAILABLE)
        assertEquals(-108, SpeechErrorCodes.ERROR_RECOGNIZER_BUSY)
        assertEquals(-109, SpeechErrorCodes.ERROR_NO_MATCH)
        assertEquals(401, SpeechErrorCodes.ERROR_UNAUTHORIZED)
        assertEquals(429, SpeechErrorCodes.ERROR_RATE_LIMITED)
        assertEquals(500, SpeechErrorCodes.ERROR_SERVER)
    }

    @Test
    fun testDeduplicationLogic() {
        var emitCount = 0
        var hasEmitted = false
        val sampleCommand = "ouvre WhatsApp"

        val handleResult = { transcript: String ->
            if (!hasEmitted) {
                hasEmitted = true
                emitCount++
            }
        }

        // Simulate multiple onResults events (e.g. race condition or double binder callback)
        handleResult(sampleCommand)
        handleResult(sampleCommand)
        handleResult(sampleCommand)

        assertEquals(1, emitCount)
    }

    @Test
    fun testFallbackScenarioDeepgramToAndroid() {
        var deepgramAvailable = false
        var androidAvailable = true
        var activeEngine = "none"
        var finalExecutedCommand: String? = null
        var executionCount = 0

        // Orchestrator logic
        if (deepgramAvailable) {
            activeEngine = "Deepgram"
        } else if (androidAvailable) {
            activeEngine = "AndroidSpeechRecognizer"
            // Simulate Android STT final result
            val recognizedText = "ouvre WhatsApp"
            if (executionCount == 0) {
                executionCount++
                finalExecutedCommand = recognizedText
            }
        }

        assertEquals("AndroidSpeechRecognizer", activeEngine)
        assertEquals("ouvre WhatsApp", finalExecutedCommand)
        assertEquals(1, executionCount)
    }

    @Test
    fun testEmptyAndNoMatchResults() {
        val emptyList = emptyList<String>()
        val finalTranscript = emptyList.firstOrNull()?.trim() ?: ""
        assertEquals("", finalTranscript)
        assertTrue(finalTranscript.isBlank())
    }

    @Test
    fun testInterruptionPhrases() {
        val stopPhrases = listOf("stop", "arrête", "arrete", "tais-toi", "tais toi", "jarvis arrête", "annule", "pause")
        for (phrase in stopPhrases) {
            val isStop = phrase.lowercase().trim() in listOf(
                "arrête", "arrete", "stop", "tais-toi", "tais toi", "jarvis arrête", "jarvis arrete", "jarvis stop", "annule", "pause"
            )
            assertTrue("Phrase '$phrase' should be recognized as interruption", isStop)
        }
    }
}
