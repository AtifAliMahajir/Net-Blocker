package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppBlockRule::class], version = 1, exportSchema = false)
abstract class AppBlockDatabase : RoomDatabase() {
    abstract val appBlockRuleDao: AppBlockRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppBlockDatabase? = null

        fun getInstance(context: Context): AppBlockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppBlockDatabase::class.java,
                    "firewall_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
