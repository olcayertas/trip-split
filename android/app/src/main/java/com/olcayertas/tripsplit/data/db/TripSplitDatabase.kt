package com.olcayertas.tripsplit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OpEntity::class, GroupEntity::class, DeviceEntity::class, ForkEntity::class],
    version = 1,
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
            Room.databaseBuilder(context, TripSplitDatabase::class.java, FILE).build()
    }
}
