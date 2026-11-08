package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureStorageBackupRulesTest {

    @Test
    fun encryptedCredentialVaultIsExcludedFromAllBackupTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val fullBackupTargets = findVaultExclusions(context.resources.getXml(R.xml.backup_rules))
        assertTrue("The full-backup rules must exclude the encrypted credential vault", fullBackupTargets.contains("full-backup-content"))

        val extractionTargets = findVaultExclusions(context.resources.getXml(R.xml.data_extraction_rules))
        assertEquals(setOf("cloud-backup", "device-transfer"), extractionTargets)
    }

    private fun findVaultExclusions(parser: XmlPullParser): Set<String> {
        val excludedTargets = mutableSetOf<String>()
        var currentTarget = "full-backup-content"

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                            currentTarget = parser.name
                        } else if (
                            parser.name == "exclude" &&
                            parser.getAttributeValue(null, "domain") == "sharedpref" &&
                            parser.getAttributeValue(null, "path") == "kavya_encrypted_vault.xml"
                        ) {
                            excludedTargets.add(currentTarget)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                            currentTarget = "full-backup-content"
                        }
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }

        return excludedTargets
    }
}
