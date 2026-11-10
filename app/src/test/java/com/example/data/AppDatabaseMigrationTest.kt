package com.example.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppDatabaseMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migration56PreservesLegacyMemoryAndAutomationFailureRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseFile = File(tempFolder.root, "legacy-v5.db")
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseFile.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE memories (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, " +
                            "category TEXT NOT NULL, timestamp INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO memories (id, content, category, timestamp) " +
                            "VALUES ('old-memory-1', 'Keep this preference', 'PREFERENCE', 1234)"
                    )
                    db.execSQL(
                        "CREATE TABLE automation_failures (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, " +
                            "appPackage TEXT NOT NULL, targetText TEXT NOT NULL, actionType TEXT NOT NULL, " +
                            "failureReason TEXT NOT NULL, screenStateDump TEXT NOT NULL, resolved INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO automation_failures VALUES " +
                            "('old-failure-1', 5678, 'com.example.app', 'Continue', 'CLICK', 'not found', 'screen dump', 1)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_5_6.migrate(db)

            val memoryColumns = db.query("PRAGMA table_info(memories)").use { cursor ->
                val index = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(index)) }
            }
            assertTrue(memoryColumns.containsAll(setOf(
                "id", "key", "content", "category", "importance", "createdAt",
                "lastUsedAt", "sourceConversation", "confidence", "userConfirmed"
            )))
            db.query("SELECT `key`, content, createdAt FROM memories").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("old-memory-1", cursor.getString(0))
                assertEquals("Keep this preference", cursor.getString(1))
                assertEquals(1234L, cursor.getLong(2))
            }

            val failureColumns = db.query("PRAGMA table_info(automation_failures)").use { cursor ->
                val index = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(index)) }
            }
            assertTrue(failureColumns.containsAll(setOf(
                "id", "targetApp", "targetAction", "failureType", "screenSummary",
                "successfulAlternative", "timestamp"
            )))
            db.query("SELECT targetApp, targetAction, failureType, screenSummary, successfulAlternative FROM automation_failures").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("com.example.app", cursor.getString(0))
                assertEquals("Continue", cursor.getString(1))
                assertTrue(cursor.getString(2).contains("CLICK"))
                assertEquals("screen dump", cursor.getString(3))
                assertEquals("Legacy record marked as resolved", cursor.getString(4))
            }
        } finally {
            helper.close()
        }
    }
}
