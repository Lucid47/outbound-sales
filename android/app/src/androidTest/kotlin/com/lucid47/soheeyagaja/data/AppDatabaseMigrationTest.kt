package com.lucid47.soheeyagaja.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @Test
    fun migrationOneToEightPreservesDataAndAddsMapAndMediaSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TEST_DATABASE)
        val legacy = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(LegacySchemaCallback())
                .build(),
        )
        legacy.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO customer_lists (id, name, sourceName, createdAtEpochMillis)
                VALUES (1, '기존 리스트', 'old.csv', 12345)
                """.trimIndent(),
            )
        }
        legacy.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
            )
            .build()
        migrated.openHelper.writableDatabase

        val cursor = migrated.openHelper.readableDatabase.query(
            "SELECT name, createdAtEpochMillis, updatedAtEpochMillis FROM customer_lists WHERE id=1",
        )
        cursor.use {
            check(it.moveToFirst())
            assertEquals("기존 리스트", it.getString(0))
            assertEquals(12345L, it.getLong(1))
            assertEquals(12345L, it.getLong(2))
        }
        val customerColumns = migrated.openHelper.readableDatabase.query("PRAGMA table_info(customers)")
        val columnNames = buildSet {
            customerColumns.use {
                val nameIndex = it.getColumnIndexOrThrow("name")
                while (it.moveToNext()) add(it.getString(nameIndex))
            }
        }
        assertTrue("birthDate" in columnNames)
        assertTrue("status" in columnNames)
        assertTrue("updatedAtEpochMillis" in columnNames)
        assertTrue("dashboardStatusId" in columnNames)
        assertTrue("latitude" in columnNames)
        assertTrue("longitude" in columnNames)
        assertTrue("geocodedAtEpochMillis" in columnNames)
        val customFieldsTable = migrated.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='customer_custom_fields'",
        )
        customFieldsTable.use { assertTrue(it.moveToFirst()) }
        listOf("contact_logs", "visit_logs", "visit_schedules", "visit_schedule_items").forEach { table ->
            val tableCursor = migrated.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'",
            )
            tableCursor.use { assertTrue("$table 테이블이 필요합니다.", it.moveToFirst()) }
        }
        listOf("dashboard_statuses", "dashboard_settings", "process_status_logs").forEach { table ->
            val tableCursor = migrated.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'",
            )
            tableCursor.use { assertTrue("$table 테이블이 필요합니다.", it.moveToFirst()) }
        }
        listOf("photo_memos", "audio_memos").forEach { table ->
            val tableCursor = migrated.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'",
            )
            tableCursor.use { assertTrue("$table 테이블이 필요합니다.", it.moveToFirst()) }
        }
        val audioColumns = migrated.openHelper.readableDatabase.query("PRAGMA table_info(audio_memos)")
        val audioColumnNames = buildSet {
            audioColumns.use {
                val nameIndex = it.getColumnIndexOrThrow("name")
                while (it.moveToNext()) add(it.getString(nameIndex))
            }
        }
        assertTrue("sourceType" in audioColumnNames)
        assertTrue("transcriptWordsJson" in audioColumnNames)
        assertTrue("transcriptionState" in audioColumnNames)
        val statuses = migrated.openHelper.readableDatabase.query("SELECT COUNT(*) FROM dashboard_statuses")
        statuses.use {
            assertTrue(it.moveToFirst())
            assertEquals(5, it.getInt(0))
        }
        migrated.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    private class LegacySchemaCallback : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS customer_lists (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    sourceName TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS customers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    listId INTEGER NOT NULL,
                    sourceRow INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    normalizedPhone TEXT NOT NULL,
                    address TEXT NOT NULL,
                    ownedAddress TEXT NOT NULL,
                    parcelAddress TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    duplicateKey TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(listId) REFERENCES customer_lists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customers_listId ON customers (listId)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_customers_listId_duplicateKey " +
                    "ON customers (listId, duplicateKey)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_customers_normalizedPhone ON customers (normalizedPhone)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '084c21a7ce16ee8ebe17b76c5f833af8')",
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val TEST_DATABASE = "contact-migration-test"
    }
}
