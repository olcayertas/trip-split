package com.olcayertas.tripsplit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OpEntity::class, GroupEntity::class, DeviceEntity::class, ForkEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class TripSplitDatabase : RoomDatabase() {
    abstract fun ops(): OpDao
    abstract fun groups(): GroupDao
    abstract fun device(): DeviceDao
    abstract fun forks(): ForkDao

    companion object {
        const val FILE = "tripsplit.db"

        fun open(context: Context): TripSplitDatabase =
            Room.databaseBuilder(context, TripSplitDatabase::class.java, FILE).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        /** The mailbox epoch of each group (SPEC §10.4.4 step 4). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN relay_epoch TEXT")
            }
        }

        /** Phase 10: the relay position of each group (SPEC §10.4.4). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN relay_cursor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN relay_vector TEXT NOT NULL DEFAULT '{}'")
            }
        }
    }
}
