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
        ScheduledTaskEntity::class,
        com.example.kucp.storage.KucpConnectorEntity::class,
        com.example.kucp.storage.KucpToolEntity::class,
        com.example.kucp.storage.KucpPluginEntity::class,
        com.example.kucp.storage.KucpWebhookEntity::class,
        com.example.kucp.storage.KucpWorkflowEntity::class,
        com.example.kucp.storage.KucpWorkflowRunEntity::class,
        com.example.kucp.storage.KucpAuditEventEntity::class
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
    abstract fun kucpDao(): com.example.kucp.storage.KucpDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`key` TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "importance INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "lastUsedAt INTEGER NOT NULL, " +
                        "sourceConversation TEXT NOT NULL, " +
                        "confidence REAL NOT NULL, " +
                        "userConfirmed INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS automation_failures (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "targetApp TEXT NOT NULL, " +
                        "targetAction TEXT NOT NULL, " +
                        "failureType TEXT NOT NULL, " +
                        "screenSummary TEXT NOT NULL, " +
                        "successfulAlternative TEXT, " +
                        "timestamp INTEGER NOT NULL)"
                )
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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_connectors (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL, protocol TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL, version TEXT NOT NULL, enabled INTEGER NOT NULL, definitionJson TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_tools (toolId TEXT NOT NULL PRIMARY KEY, connectorId TEXT NOT NULL, name TEXT NOT NULL, protocol TEXT NOT NULL, riskLevel TEXT NOT NULL, descriptorJson TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_plugins (pluginId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL, sha256 TEXT NOT NULL, enabled INTEGER NOT NULL, manifestJson TEXT NOT NULL, previousManifestJson TEXT NOT NULL, installedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_webhooks (id TEXT NOT NULL PRIMARY KEY, connectorId TEXT NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, subscriptionJson TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_workflows (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL, definitionJson TEXT NOT NULL, updatedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_workflow_runs (runId TEXT NOT NULL PRIMARY KEY, workflowId TEXT NOT NULL, status TEXT NOT NULL, recordJson TEXT NOT NULL, startedAtMs INTEGER NOT NULL, finishedAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS kucp_audit_events (id TEXT NOT NULL PRIMARY KEY, connectorId TEXT NOT NULL, toolId TEXT NOT NULL, action TEXT NOT NULL, outcome TEXT NOT NULL, riskLevel TEXT NOT NULL, detailRedacted TEXT NOT NULL, timestampMs INTEGER NOT NULL)")
            }
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
