package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kavya_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): AppDatabase = getDatabase(context)
    }
}

