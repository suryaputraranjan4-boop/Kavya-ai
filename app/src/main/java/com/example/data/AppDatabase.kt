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
    version = 5,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kavya_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): AppDatabase = getDatabase(context)
    }
}
