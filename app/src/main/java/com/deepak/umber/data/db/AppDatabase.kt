package com.deepak.umber.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RawMessageEntity::class,
        TxnEntity::class,
        MerchantMemoryEntity::class,
        ModelStateEntity::class,
        LastLocationEntity::class,
    ],
    version = 2,
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
        /**
         * Adds `clientId` for cross-device sync.
         *
         * Existing rows are backfilled with `randomblob(16)` rather than left null, so every
         * transaction that predates sync still has a stable identity. A real migration, not a
         * destructive fallback: `raw_message` is the one table that cannot be reconstructed.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN clientId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE txn SET clientId = lower(hex(randomblob(16))) WHERE clientId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_txn_clientId ON txn (clientId)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "umber.db",
            )
                .addMigrations(MIGRATION_1_2)
                // Everything derived (transactions, model, merchant memory) can be rebuilt from
                // raw_message, but raw_message itself cannot be recovered — so no destructive
                // migration fallback. Add real migrations when the schema changes.
                .build()
                .also { instance = it }
        }
    }
}
