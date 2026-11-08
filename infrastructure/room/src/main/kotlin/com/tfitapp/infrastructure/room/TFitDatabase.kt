package com.tfitapp.infrastructure.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tfitapp.infrastructure.room.dao.ExerciseDao
import com.tfitapp.infrastructure.room.dao.FoodDao
import com.tfitapp.infrastructure.room.dao.FoodLogDao
import com.tfitapp.infrastructure.room.dao.MealTemplateDao
import com.tfitapp.infrastructure.room.dao.SessionDao
import com.tfitapp.infrastructure.room.dao.SettingsDao
import com.tfitapp.infrastructure.room.dao.TimerRunDao
import com.tfitapp.infrastructure.room.dao.WeightDao
import com.tfitapp.infrastructure.room.entity.BlockFeedbackEntity
import com.tfitapp.infrastructure.room.entity.ExerciseEntity
import com.tfitapp.infrastructure.room.entity.FoodEntity
import com.tfitapp.infrastructure.room.entity.FoodFtsEntity
import com.tfitapp.infrastructure.room.entity.FoodLogEntity
import com.tfitapp.infrastructure.room.entity.MealTemplateEntity
import com.tfitapp.infrastructure.room.entity.SessionEntity
import com.tfitapp.infrastructure.room.entity.SetLogEntity
import com.tfitapp.infrastructure.room.entity.SettingsEntity
import com.tfitapp.infrastructure.room.entity.TimerRunEntity
import com.tfitapp.infrastructure.room.entity.WeightEntity

@Database(
    version = 2,
    exportSchema = true,
    entities = [
        FoodEntity::class, FoodFtsEntity::class, FoodLogEntity::class, WeightEntity::class,
        SettingsEntity::class, ExerciseEntity::class, SessionEntity::class, SetLogEntity::class, TimerRunEntity::class,
        MealTemplateEntity::class, BlockFeedbackEntity::class,
    ],
)
abstract class TFitDatabase : RoomDatabase() {
    abstract fun foods(): FoodDao
    abstract fun foodLog(): FoodLogDao
    abstract fun weights(): WeightDao
    abstract fun settings(): SettingsDao
    abstract fun exercises(): ExerciseDao
    abstract fun sessions(): SessionDao
    abstract fun timerRuns(): TimerRunDao
    abstract fun mealTemplates(): MealTemplateDao

    companion object {
        const val NAME = "tfit.db"

        /** Saved meals, check-out text and per-block feedback. Every statement is guarded, so it can run against a half-migrated file. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meal_template` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                        "`itemsJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `archivedAtMs` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_template_nameKey` ON `meal_template` (`nameKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `block_feedback` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `blockId` TEXT NOT NULL, " +
                        "`exerciseId` TEXT NOT NULL, `feel` TEXT NOT NULL, `source` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_feedback_sessionId` ON `block_feedback` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_feedback_exerciseId` ON `block_feedback` (`exerciseId`)")
                if (!hasColumn(db, "session", "checkOutText")) db.execSQL("ALTER TABLE `session` ADD COLUMN `checkOutText` TEXT")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): TFitDatabase =
            Room.databaseBuilder(context, TFitDatabase::class.java, NAME).addMigrations(*MIGRATIONS).build()

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIndex = c.getColumnIndex("name")
                while (c.moveToNext()) if (c.getString(nameIndex) == column) return true
                false
            }
    }
}
