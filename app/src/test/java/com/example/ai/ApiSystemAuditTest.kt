package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecureStorage
import com.example.utils.AppPreferences
import com.example.utils.CustomApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ApiSystemAuditTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear test preferences and secrets
        AppPreferences.clearCustomApiKey(context)
        AppPreferences.clearOpenRouterApiKey(context)
        AppPreferences.clearHuggingFaceApiKey(context)
        SecureStorage.clearSecret(context, "gemini_api_key")
        SecureStorage.clearSecret(context, "openrouter_api_key")
        SecureStorage.clearSecret(context, "huggingface_api_key")
    }

    @Test
    fun testSecureStorageDualTierEncryptionAndFallback() {
        val testSecret = "AIzaSyTestApiKey_1234567890_abcdef"
        val keyName = "test_audit_key"

        val saveSuccess = SecureStorage.saveSecret(context, keyName, testSecret)
        assertTrue("Save secret should succeed via Primary AES-GCM or Software Fallback", saveSuccess)

        val retrievedSecret = SecureStorage.getSecret(context, keyName)
        assertEquals("Retrieved secret must match saved secret", testSecret, retrievedSecret)

        SecureStorage.clearSecret(context, keyName)
        assertEquals("Cleared secret must return empty string", "", SecureStorage.getSecret(context, keyName))
    }

    @Test
    fun testProviderKeyIndependenceAndActiveLookup() {
        val geminiKey = "AIzaSyGeminiKey_11111"
        val openRouterKey = "sk-or-v1-OpenRouterKey_22222"
        val hfKey = "hf_HuggingFaceKey_33333"

        assertTrue(AppPreferences.setCustomApiKey(context, geminiKey))
        assertTrue(AppPreferences.setOpenRouterApiKey(context, openRouterKey))
        assertTrue(AppPreferences.setHuggingFaceApiKey(context, hfKey))

        // Check independent lookup
        assertEquals(geminiKey, AppPreferences.getApiKeyForProvider(context, "GEMINI"))
        assertEquals(openRouterKey, AppPreferences.getApiKeyForProvider(context, "OPENROUTER"))
        assertEquals(hfKey, AppPreferences.getApiKeyForProvider(context, "HUGGINGFACE"))

        // Test switching active provider
        AppPreferences.setAiProvider(context, "GEMINI")
        assertEquals(geminiKey, AppPreferences.getActiveApiKey(context))
        assertTrue("GEMINI active provider should have configured key", AppPreferences.hasConfiguredApiKey(context))

        AppPreferences.setAiProvider(context, "OPENROUTER")
        assertEquals(openRouterKey, AppPreferences.getActiveApiKey(context))
        assertTrue("OPENROUTER active provider should have configured key", AppPreferences.hasConfiguredApiKey(context))

        AppPreferences.setAiProvider(context, "HUGGINGFACE")
        assertEquals(hfKey, AppPreferences.getActiveApiKey(context))
        assertTrue("HUGGINGFACE active provider should have configured key", AppPreferences.hasConfiguredApiKey(context))

        AppPreferences.setAiProvider(context, "GEMMA_OFFLINE")
        assertEquals("", AppPreferences.getActiveApiKey(context))
        assertTrue("GEMMA_OFFLINE provider is self-contained and needs no API key", AppPreferences.hasConfiguredApiKey(context))
    }

    @Test
    fun testAccurateErrorClassificationNoFalseQuotaExhaustion() {
        // HTTP 429 rate limit (not quota)
        val rateLimitError = AiErrorClassifier.classifyHttpResponse(
            httpStatus = 429,
            errorBody = "{\"error\":{\"code\":429,\"message\":\"Rate limit reached. Please wait 5 seconds.\"}}"
        )
        assertEquals(AiErrorCategory.RATE_LIMIT, rateLimitError.category)
        assertTrue(rateLimitError.isRetryable)

        // HTTP 429 hard quota exhaustion
        val quotaError = AiErrorClassifier.classifyHttpResponse(
            httpStatus = 429,
            errorBody = "{\"error\":{\"code\":429,\"message\":\"You have exceeded your current quota. Check your plan and billing details.\",\"status\":\"RESOURCE_EXHAUSTED\"}}"
        )
        assertEquals(AiErrorCategory.QUOTA_EXHAUSTED, quotaError.category)
        assertFalse(quotaError.isRetryable)

        // Throwable with generic word "quota" should NOT false trigger QUOTA_EXHAUSTED
        val genericThrowable = java.io.IOException("Quota manager internal update state")
        val classifiedGeneric = AiErrorClassifier.classifyThrowable(genericThrowable)
        assertEquals("Generic exception containing 'quota' should not map to QUOTA_EXHAUSTED", AiErrorCategory.NETWORK_UNAVAILABLE, classifiedGeneric.category)

        // Throwable with explicit billing quota text SHOULD trigger QUOTA_EXHAUSTED
        val quotaThrowable = java.io.IOException("insufficient_quota: You have exceeded your billing limit")
        val classifiedQuota = AiErrorClassifier.classifyThrowable(quotaThrowable)
        assertEquals("Exception with explicit insufficient_quota must map to QUOTA_EXHAUSTED", AiErrorCategory.QUOTA_EXHAUSTED, classifiedQuota.category)
    }

    @Test
    fun testSecretRedactionInLogs() {
        val rawLog = "GET https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSySecretKey123 HTTP/1.1 Bearer sk-or-v1-987654"
        val redacted = SecretRedactor.redact(rawLog)

        assertFalse("Redacted text should not expose raw Gemini key", redacted.contains("AIzaSySecretKey123"))
        assertFalse("Redacted text should not expose raw Bearer token", redacted.contains("sk-or-v1-987654"))
        assertTrue("Redacted text should contain [REDACTED]", redacted.contains("[REDACTED]"))
    }
}
