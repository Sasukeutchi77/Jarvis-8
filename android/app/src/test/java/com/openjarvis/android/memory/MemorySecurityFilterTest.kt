package com.openjarvis.android.memory

import com.openjarvis.android.memory.policy.MemorySecurityFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySecurityFilterTest {

    @Test
    fun testSafeContentAllowed() {
        val safeFacts = listOf(
            "Mon plat préféré est les lasagnes",
            "J'habite à Lyon",
            "Mon chat s'appelle Pixel",
            "J'aime courir le dimanche matin",
            "Mon anniversaire est le 15 août"
        )

        for (fact in safeFacts) {
            val result = MemorySecurityFilter.evaluate(fact)
            assertTrue("Fact should be allowed: $fact", result.isAllowed)
        }
    }

    @Test
    fun testApiKeysRejected() {
        val dangerousInputs = listOf(
            "Voici ma clé API : sk-proj-1234567890abcdef1234567890",
            "Mon token GitHub : ghp_1234567890abcdef1234567890abcdef1234",
            "GEMINI_API_KEY = AIzaSyD1234567890abcdef1234567890abcde",
            "DEEPGRAM_API_KEY : 098f6bcd4621d373cade4e832627b4f6",
            "Authorization: Bearer my_secret_token_12345"
        )

        for (input in dangerousInputs) {
            val result = MemorySecurityFilter.evaluate(input)
            assertFalse("Secret input must be rejected: $input", result.isAllowed)
        }
    }

    @Test
    fun testPasswordsAndPinRejected() {
        val sensitiveInputs = listOf(
            "Mon mot de passe est MonSuperPass123!",
            "Password = secretP@ssw0rd",
            "Code PIN : 1234",
            "Code secret : 987654"
        )

        for (input in sensitiveInputs) {
            val result = MemorySecurityFilter.evaluate(input)
            assertFalse("Password/PIN must be rejected: $input", result.isAllowed)
        }
    }

    @Test
    fun testBankingInfoRejected() {
        val bankingInputs = listOf(
            "Mon numéro de carte : 4532 0150 1234 5678",
            "Ma carte bancaire : 5123 4567 8901 2345",
            "Mon IBAN : FR7630006000011234567890189",
            "CVV = 789"
        )

        for (input in bankingInputs) {
            val result = MemorySecurityFilter.evaluate(input)
            assertFalse("Banking credential must be rejected: $input", result.isAllowed)
        }
    }

    @Test
    fun testEmptyContentRejected() {
        val result = MemorySecurityFilter.evaluate("   ")
        assertFalse(result.isAllowed)
    }
}
