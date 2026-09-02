package com.lucid47.soheeyagaja.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CustomerListEntity::class, CustomerEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun customerListDao(): CustomerListDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "soheeya-gaja.db",
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE customer_lists ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE customer_lists SET updatedAtEpochMillis = createdAtEpochMillis",
                )
                db.execSQL("ALTER TABLE customers ADD COLUMN contactIdentifier TEXT")
                db.execSQL("ALTER TABLE customers ADD COLUMN contactRegisteredName TEXT")
            }
        }
    }
}
