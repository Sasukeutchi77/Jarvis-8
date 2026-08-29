package com.openjarvis.android.voice

import com.openjarvis.android.voice.speech.DeepgramSpeechProvider
import com.openjarvis.android.voice.speech.SpeechErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Tests for DeepgramSpeechProvider & Deepgram Live WebSocket Protocol Parsing.
 */
class DeepgramSpeechProviderTest {

    @Test
    fun testAudioFormatConstants() {
        assertEquals(16000, DeepgramSpeechProvider.SAMPLE_RATE)
        assertEquals(2048, DeepgramSpeechProvider.CHUNK_SIZE_BYTES)
    }

    @Test
    fun testParseInterimDeepgramResponse() {
        val json = """
            {
              "type": "Results",
              "channel_index": [0, 1],
              "duration": 0.8,
              "start": 0.0,
              "is_final": false,
              "speech_final": false,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "bonjour jarvis",
                    "confidence": 0.92
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = DeepgramSpeechProvider.parseDeepgramResponse(json)
        assertNotNull(parsed)
        assertEquals("bonjour jarvis", parsed?.transcript)
        assertFalse(parsed!!.isFinal)
        assertFalse(parsed.speechFinal)
        assertEquals(0.92, parsed.confidence, 0.001)
    }

    @Test
    fun testParseFinalDeepgramChunk() {
        val json = """
            {
              "type": "Results",
              "is_final": true,
              "speech_final": false,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "allume les lumières du salon",
                    "confidence": 0.98
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = DeepgramSpeechProvider.parseDeepgramResponse(json)
        assertNotNull(parsed)
        assertEquals("allume les lumières du salon", parsed?.transcript)
        assertTrue(parsed!!.isFinal)
        assertFalse(parsed.speechFinal)
    }

    @Test
    fun testParseSpeechFinalEndpointing() {
        val json = """
            {
              "type": "Results",
              "is_final": true,
              "speech_final": true,
              "channel": {
                "alternatives": [
                  {
                    "transcript": "quelle heure est-il",
                    "confidence": 0.99
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = DeepgramSpeechProvider.parseDeepgramResponse(json)
        assertNotNull(parsed)
        assertEquals("quelle heure est-il", parsed?.transcript)
        assertTrue(parsed!!.isFinal)
        assertTrue(parsed.speechFinal)
    }

    @Test
    fun testParseEmptyOrMalformedResponse() {
        val malformedJson = "{ invalid json"
        assertNull(DeepgramSpeechProvider.parseDeepgramResponse(malformedJson))

        val nonResultJson = """{"type": "Metadata", "request_id": "12345"}"""
        assertNull(DeepgramSpeechProvider.parseDeepgramResponse(nonResultJson))

        val emptyAltJson = """{"type": "Results", "channel": {"alternatives": []}}"""
        val parsedEmpty = DeepgramSpeechProvider.parseDeepgramResponse(emptyAltJson)
        assertNotNull(parsedEmpty)
        assertEquals("", parsedEmpty?.transcript)
    }

    @Test
    fun testSpeechErrorCodes() {
        assertEquals(401, SpeechErrorCodes.ERROR_UNAUTHORIZED)
        assertEquals(403, SpeechErrorCodes.ERROR_FORBIDDEN)
        assertEquals(429, SpeechErrorCodes.ERROR_RATE_LIMITED)
        assertEquals(500, SpeechErrorCodes.ERROR_SERVER)
        assertEquals(-101, SpeechErrorCodes.ERROR_NO_API_KEY)
        assertEquals(-102, SpeechErrorCodes.ERROR_NETWORK)
        assertEquals(-103, SpeechErrorCodes.ERROR_AUDIO_RECORD)
        assertEquals(-104, SpeechErrorCodes.ERROR_PERMISSION_DENIED)
    }
}
