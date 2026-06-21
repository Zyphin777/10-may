package com.example.offlineroutingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.offlineroutingapp.data.dao.ChatDao
import com.example.offlineroutingapp.data.dao.MessageDao
import com.example.offlineroutingapp.data.dao.UserDao
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.data.entities.UserEntity

@Database(
    entities  = [UserEntity::class, ChatEntity::class, MessageEntity::class],
    version   = 2,          // bumped from 1 → 2 for location columns
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 1 → 2
         *
         * Adds four new nullable columns to the `messages` table:
         *   isLocation    INTEGER  NOT NULL  DEFAULT 0
         *   locationLat   REAL     (nullable)
         *   locationLng   REAL     (nullable)
         *   locationLabel TEXT     (nullable)
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isLocation    INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN locationLat   REAL")
                db.execSQL("ALTER TABLE messages ADD COLUMN locationLng   REAL")
                db.execSQL("ALTER TABLE messages ADD COLUMN locationLabel TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offline_chat_database"
                )
                    .addMigrations(MIGRATION_1_2)           // safe migration — no data loss
                    .fallbackToDestructiveMigration()       // last resort for dev builds
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
