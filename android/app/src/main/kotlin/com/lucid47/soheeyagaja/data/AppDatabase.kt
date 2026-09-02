package com.lucid47.soheeyagaja.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CustomerListEntity::class,
        CustomerEntity::class,
        CustomerCustomFieldEntity::class,
        ContactLogEntity::class,
        VisitLogEntity::class,
        VisitScheduleEntity::class,
        VisitScheduleItemEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun customerListDao(): CustomerListDao
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "soheeya-gaja.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN birthDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE customers ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN'")
                db.execSQL(
                    "ALTER TABLE customers ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE customers SET updatedAtEpochMillis = createdAtEpochMillis",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS customer_custom_fields (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        customerId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        value TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_customer_custom_fields_customerId " +
                        "ON customer_custom_fields (customerId)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS contact_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        listId INTEGER NOT NULL,
                        customerId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        result TEXT NOT NULL,
                        messageBody TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_logs_customerId ON contact_logs (customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_logs_listId ON contact_logs (listId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_contact_logs_createdAtEpochMillis " +
                        "ON contact_logs (createdAtEpochMillis)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS visit_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        listId INTEGER NOT NULL,
                        customerId INTEGER NOT NULL,
                        visitedAtEpochMillis INTEGER NOT NULL,
                        result TEXT NOT NULL,
                        memo TEXT,
                        kind TEXT NOT NULL,
                        locationAddress TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_visit_logs_customerId ON visit_logs (customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_visit_logs_listId ON visit_logs (listId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_visit_logs_visitedAtEpochMillis " +
                        "ON visit_logs (visitedAtEpochMillis)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS visit_schedules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        listId INTEGER NOT NULL,
                        dateKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(listId) REFERENCES customer_lists(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_visit_schedules_listId_dateKey " +
                        "ON visit_schedules (listId, dateKey)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS visit_schedule_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scheduleId INTEGER NOT NULL,
                        listId INTEGER NOT NULL,
                        customerId INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        completedAtEpochMillis INTEGER,
                        FOREIGN KEY(scheduleId) REFERENCES visit_schedules(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(customerId) REFERENCES customers(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_visit_schedule_items_scheduleId " +
                        "ON visit_schedule_items (scheduleId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_visit_schedule_items_customerId " +
                        "ON visit_schedule_items (customerId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_visit_schedule_items_listId " +
                        "ON visit_schedule_items (listId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_visit_schedule_items_scheduleId_customerId " +
                        "ON visit_schedule_items (scheduleId, customerId)",
                )
            }
        }
    }
}
