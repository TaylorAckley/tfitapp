package com.tfitapp.infrastructure.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TFitDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun migrate1To2KeepsSessionsAndIsReRunnable() {
        helper.createDatabase(DB, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO session (id, plannedFor, slotId, candidateJson, alternativesJson, checkInText, jevConfidence, startedAtMs, finishedAtMs, createdAtMs) " +
                    "VALUES ('s1', '2026-09-18', 'slot.a', '{}', '[]', NULL, NULL, NULL, 1, 1)",
            )
        }

        val v2 = helper.runMigrationsAndValidate(DB, 2, true, TFitDatabase.MIGRATION_1_2)
        v2.query("SELECT id, checkOutText FROM session").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("s1", c.getString(0))
            assertTrue(c.isNull(1))
        }

        // Every statement is guarded: running the same migration again against the migrated file must be a no-op.
        TFitDatabase.MIGRATION_1_2.migrate(v2)
        v2.query("SELECT COUNT(*) FROM meal_template").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        v2.query("SELECT COUNT(*) FROM block_feedback").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        v2.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
