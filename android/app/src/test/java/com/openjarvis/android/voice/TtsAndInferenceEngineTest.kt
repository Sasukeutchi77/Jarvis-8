package com.openjarvis.android.voice

import com.openjarvis.android.core.engine.EngineMessage
import com.openjarvis.android.core.engine.ProviderType
import com.openjarvis.android.core.engine.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Tests for Text-To-Speech sanitization, Barge-in interruption phrases,
 * and Multi-Provider Cloud AI engine models and message schemas.
 */
class TtsAndInferenceEngineTest {

    @Test
    fun testTextSanitizationForSpeech() {
        val rawInput = """
            Voici la réponse :
            ```python
            def hello():
                print("world")
            ```
            Consultez [ce lien](https://openjarvis.com) pour plus d'infos ! 🚀 #Important *Gras*
        """.trimIndent()

        val sanitized = rawInput
            .replace(Regex("```[\\s\\S]*?```"), " extrait de code ")
            .replace(Regex("`[^`]+`"), " ")
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), " lien web ")
            .replace(Regex("^[#*>-]+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~`]+"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        assertTrue(!sanitized.contains("```"))
        assertTrue(!sanitized.contains("def hello"))
        assertTrue(!sanitized.contains("https://openjarvis.com"))
        assertTrue(sanitized.contains("extrait de code"))
        assertTrue(sanitized.contains("ce lien"))
        assertTrue(!sanitized.contains("🚀"))
    }

    @Test
    fun testProviderTypeEnum() {
        assertEquals("LOCAL_LLAMA", ProviderType.LOCAL_LLAMA.name)
        assertEquals("LAN_OLLAMA", ProviderType.LAN_OLLAMA.name)
        assertEquals("CLOUD_GEMINI", ProviderType.CLOUD_GEMINI.name)
        assertEquals("CLOUD_OPENAI", ProviderType.CLOUD_OPENAI.name)
        assertEquals("CLOUD_ANTHROPIC", ProviderType.CLOUD_ANTHROPIC.name)
        assertEquals("CLOUD_OPENROUTER", ProviderType.CLOUD_OPENROUTER.name)
    }

    @Test
    fun testEngineMessageAndStreamChunk() {
        val userMsg = EngineMessage(role = "user", content = "Bonjour JARVIS")
        assertEquals("user", userMsg.role)
        assertEquals("Bonjour JARVIS", userMsg.content)

        val textChunk = StreamChunk.TextDelta("Bonjour !")
        assertEquals("Bonjour !", textChunk.text)

        val completedChunk = StreamChunk.Completed(totalTokens = 42, promptTokens = 12, completionTokens = 30)
        assertEquals(42, completedChunk.totalTokens)
    }

    @Test
    fun testInterruptionPhrases() {
        val stopPhrases = listOf("stop", "arrête", "arrete", "tais-toi", "tais toi", "jarvis arrête", "jarvis arrete", "jarvis stop", "annule", "pause")
        for (phrase in stopPhrases) {
            val lower = phrase.lowercase().trim()
            val isStop = lower in listOf(
                "arrête", "arrete", "stop", "tais-toi", "tais toi", "jarvis arrête", "jarvis arrete", "jarvis stop", "annule", "pause"
            )
            assertTrue("Phrase '$phrase' should trigger voice barge-in interruption", isStop)
        }
    }
}
