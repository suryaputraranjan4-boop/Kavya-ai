package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureStorageTest {

    @Test
    fun credentialsAreNeverPersistedAsPlaintextWhenKeystoreIsUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("kavya_encrypted_vault", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        val secret = "test-api-key-value"
        val saved = SecureStorage.saveSecret(context, "test_key", secret)
        val stored = preferences.getString("test_key", null)

        if (saved) {
            assertNotNull(stored)
            assertFalse(stored!!.startsWith("OBF:"))
            assertFalse(stored.contains(secret))
            assertEquals(secret, SecureStorage.getSecret(context, "test_key"))
        } else {
            assertNull(stored)
            assertEquals("", SecureStorage.getSecret(context, "test_key"))
        }
    }

    @Test
    fun insecureLegacyObfCredentialIsRemovedAndNotReturned() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("kavya_encrypted_vault", Context.MODE_PRIVATE)
        preferences.edit().putString("legacy_key", "OBF:dGVzdC1zZWNyZXQ=").commit()

        assertEquals("", SecureStorage.getSecret(context, "legacy_key"))
        assertFalse(preferences.contains("legacy_key"))
    }

    @Test
    fun blankCredentialClearsStoredValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("kavya_encrypted_vault", Context.MODE_PRIVATE)
        preferences.edit().putString("blank_key", "old-value").commit()

        assertTrue(SecureStorage.saveSecret(context, "blank_key", "   "))
        assertFalse(preferences.contains("blank_key"))
    }
}
