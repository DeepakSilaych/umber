package com.deepak.umber.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RawMessageEntity::class,
        TxnEntity::class,
        MerchantMemoryEntity::class,
        ModelStateEntity::class,
        LastLocationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rawMessages(): RawMessageDao
    abstract fun txns(): TxnDao
    abstract fun merchantMemory(): MerchantMemoryDao
    abstract fun modelState(): ModelStateDao
    abstract fun lastLocation(): LastLocationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "umber.db",
            )
                // Everything derived (transactions, model, merchant memory) can be rebuilt from
                // raw_message, but raw_message itself cannot be recovered — so no destructive
                // migration fallback. Add real migrations when the schema changes.
                .build()
                .also { instance = it }
        }
    }
}
