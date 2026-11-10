package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        AutomationFailureEntity::class,
        com.example.evolution.EvolutionHistoryEntity::class,
        com.example.evolution.EvolutionReportEntity::class,
        com.example.evolution.EvolutionUpgradeEntity::class,
        DurableTaskEntity::class,
        DurableTaskStepEntity::class,
        ScheduledTaskEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun automationFailureDao(): AutomationFailureDao
    abstract fun evolutionDao(): com.example.evolution.EvolutionDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memories (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL, category TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS automation_failures (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, appPackage TEXT NOT NULL, targetText TEXT NOT NULL, actionType TEXT NOT NULL, failureReason TEXT NOT NULL, screenStateDump TEXT NOT NULL, resolved INTEGER NOT NULL)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS evolution_history (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, projectName TEXT NOT NULL, capability TEXT NOT NULL, securityResult TEXT NOT NULL, action TEXT NOT NULL, status TEXT NOT NULL, version TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS evolution_reports (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, scanTime TEXT NOT NULL, discovered INTEGER NOT NULL, analyzed INTEGER NOT NULL, passed INTEGER NOT NULL, quarantined INTEGER NOT NULL, rejected INTEGER NOT NULL, newCaps INTEGER NOT NULL, candidates INTEGER NOT NULL, applied INTEGER NOT NULL, pending INTEGER NOT NULL, rollbacks INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS evolution_upgrades (id TEXT NOT NULL PRIMARY KEY, projectName TEXT NOT NULL, projectUrl TEXT NOT NULL, commitSha TEXT NOT NULL, license TEXT NOT NULL, licenseCompatible INTEGER NOT NULL, capabilityImproved TEXT NOT NULL, reason TEXT NOT NULL, securityResult TEXT NOT NULL, riskLevel TEXT NOT NULL, upgradeLevel TEXT NOT NULL, status TEXT NOT NULL, beforeSnippet TEXT NOT NULL, afterSnippet TEXT NOT NULL, filesAffected TEXT NOT NULL, testResultsSummary TEXT NOT NULL, performanceBenchmark TEXT NOT NULL, date TEXT NOT NULL, version TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS durable_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, intentCategory TEXT NOT NULL, status TEXT NOT NULL, totalSteps INTEGER NOT NULL, currentStepIndex INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, resultSummary TEXT, lastError TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS durable_task_steps (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, stepIndex INTEGER NOT NULL, title TEXT NOT NULL, actionType TEXT NOT NULL, targetApp TEXT, param TEXT, status TEXT NOT NULL, expectedState TEXT, actualState TEXT, result TEXT, failureReason TEXT, timestamp INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_durable_task_steps_taskId ON durable_task_steps (taskId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, promptOrAction TEXT NOT NULL, scheduleType TEXT NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, dayOfWeek INTEGER NOT NULL, isActive INTEGER NOT NULL, lastRunTimestamp INTEGER NOT NULL, nextRunTimestamp INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }


        /**
         * Rebuild legacy memory tables to match the current Room entities.
         *
         * Earlier app versions created memories(id TEXT, content, category, timestamp)
         * and automation_failures with obsolete column names. This migration preserves
         * the usable legacy fields instead of dropping user data.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateMemoriesTable(db)
                migrateAutomationFailuresTable(db)
            }
        }

        private fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            return db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    if (nameIndex >= 0) {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
            }
        }

        private fun migrateMemoriesTable(db: SupportSQLiteDatabase) {
            val existing = tableColumns(db, "memories")
            val required = setOf(
                "id", "key", "content", "category", "importance", "createdAt",
                "lastUsedAt", "sourceConversation", "confidence", "userConfirmed"
            )
            if (existing.containsAll(required)) return

            db.execSQL("DROP TABLE IF EXISTS memories_new")
            db.execSQL(
                "CREATE TABLE memories_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`key` TEXT NOT NULL, content TEXT NOT NULL, category TEXT NOT NULL, " +
                    "importance INTEGER NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, " +
                    "sourceConversation TEXT NOT NULL, confidence REAL NOT NULL, userConfirmed INTEGER NOT NULL)"
            )

            if (existing.isNotEmpty()) {
                val keyExpr = when {
                    "key" in existing -> "COALESCE(NULLIF(`key`, ''), 'Legacy memory')"
                    "id" in existing -> "COALESCE(NULLIF(CAST(id AS TEXT), ''), 'Legacy memory')"
                    else -> "'Legacy memory'"
                }
                val createdExpr = when {
                    "createdAt" in existing -> "COALESCE(createdAt, 0)"
                    "timestamp" in existing -> "COALESCE(timestamp, 0)"
                    else -> "0"
                }
                val lastUsedExpr = if ("lastUsedAt" in existing) "COALESCE(lastUsedAt, 0)" else createdExpr
                val contentExpr = if ("content" in existing) "COALESCE(content, '')" else "''"
                val categoryExpr = if ("category" in existing) "COALESCE(category, 'PREFERENCE')" else "'PREFERENCE'"
                val importanceExpr = if ("importance" in existing) "COALESCE(importance, 3)" else "3"
                val sourceExpr = if ("sourceConversation" in existing) "COALESCE(sourceConversation, '')" else "''"
                val confidenceExpr = if ("confidence" in existing) "COALESCE(confidence, 1.0)" else "1.0"
                val confirmedExpr = if ("userConfirmed" in existing) "COALESCE(userConfirmed, 1)" else "1"

                db.execSQL(
                    "INSERT INTO memories_new (`key`, content, category, importance, createdAt, lastUsedAt, " +
                        "sourceConversation, confidence, userConfirmed) " +
                        "SELECT $keyExpr, $contentExpr, $categoryExpr, $importanceExpr, $createdExpr, " +
                        "$lastUsedExpr, $sourceExpr, $confidenceExpr, $confirmedExpr FROM memories"
                )
                db.execSQL("DROP TABLE memories")
            }
            db.execSQL("ALTER TABLE memories_new RENAME TO memories")
        }

        private fun migrateAutomationFailuresTable(db: SupportSQLiteDatabase) {
            val existing = tableColumns(db, "automation_failures")
            val required = setOf(
                "id", "targetApp", "targetAction", "failureType", "screenSummary",
                "successfulAlternative", "timestamp"
            )
            if (existing.containsAll(required)) return

            db.execSQL("DROP TABLE IF EXISTS automation_failures_new")
            db.execSQL(
                "CREATE TABLE automation_failures_new (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "targetApp TEXT NOT NULL, targetAction TEXT NOT NULL, failureType TEXT NOT NULL, " +
                    "screenSummary TEXT NOT NULL, successfulAlternative TEXT, timestamp INTEGER NOT NULL)"
            )

            if (existing.isNotEmpty()) {
                val appExpr = when {
                    "targetApp" in existing -> "COALESCE(targetApp, '')"
                    "appPackage" in existing -> "COALESCE(appPackage, '')"
                    else -> "''"
                }
                val actionExpr = when {
                    "targetAction" in existing -> "COALESCE(targetAction, 'Unknown action')"
                    "targetText" in existing -> "COALESCE(targetText, 'Unknown action')"
                    "actionType" in existing -> "COALESCE(actionType, 'Unknown action')"
                    else -> "'Unknown action'"
                }
                val failureExpr = when {
                    "failureType" in existing -> "COALESCE(failureType, 'LEGACY')"
                    "actionType" in existing && "failureReason" in existing ->
                        "CASE WHEN COALESCE(actionType, '') <> '' AND COALESCE(failureReason, '') <> '' " +
                            "THEN actionType || ': ' || failureReason " +
                            "WHEN COALESCE(failureReason, '') <> '' THEN failureReason " +
                            "WHEN COALESCE(actionType, '') <> '' THEN actionType ELSE 'LEGACY' END"
                    "failureReason" in existing -> "COALESCE(failureReason, 'LEGACY')"
                    "actionType" in existing -> "COALESCE(actionType, 'LEGACY')"
                    else -> "'LEGACY'"
                }
                val screenExpr = when {
                    "screenSummary" in existing -> "COALESCE(screenSummary, '')"
                    "screenStateDump" in existing -> "COALESCE(screenStateDump, '')"
                    else -> "''"
                }
                val alternativeExpr = when {
                    "successfulAlternative" in existing -> "successfulAlternative"
                    "resolved" in existing ->
                        "CASE WHEN COALESCE(resolved, 0) <> 0 THEN 'Legacy record marked as resolved' ELSE NULL END"
                    else -> "NULL"
                }
                val timestampExpr = if ("timestamp" in existing) "COALESCE(timestamp, 0)" else "0"

                db.execSQL(
                    "INSERT INTO automation_failures_new " +
                        "(targetApp, targetAction, failureType, screenSummary, successfulAlternative, timestamp) " +
                        "SELECT $appExpr, $actionExpr, $failureExpr, $screenExpr, $alternativeExpr, $timestampExpr " +
                        "FROM automation_failures"
                )
                db.execSQL("DROP TABLE automation_failures")
            }
            db.execSQL("ALTER TABLE automation_failures_new RENAME TO automation_failures")
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kavya_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): AppDatabase = getDatabase(context)
    }
}
