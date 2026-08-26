package com.deepak.umber.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.LocConfidence
import com.deepak.umber.data.model.SourceType

/**
 * The original message, kept forever.
 *
 * This is the source of truth, not [TxnEntity]. Keeping raw text means an improved parser can be
 * replayed over history — otherwise every extraction bug is permanent, because the SMS itself may
 * be long gone from the device.
 *
 * [fingerprint] is uniquely indexed so re-running a backfill is idempotent.
 */
@Entity(
    tableName = "raw_message",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["receivedAt"]),
    ],
)
data class RawMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: SourceType,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val fingerprint: String,
    val parsed: Boolean = false,
    val rejectReason: String? = null,
    val parserVersion: Int = 0,
)

/**
 * A transaction extracted from a raw message.
 *
 * Money is stored as integer paise. Never floats — a rounding drift of a hundredth of a rupee per
 * row silently corrupts every total the widget shows.
 *
 * [refNo] carries a unique index. SQLite permits repeated NULLs in a unique index, so this acts as
 * a free exact-duplicate guard for the messages that carry a reference number, while messages
 * without one fall back to the time-window dedupe in the ingest pipeline.
 */
@Entity(
    tableName = "txn",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["refNo"], unique = true),
        Index(value = ["merchantNorm"]),
        Index(value = ["needsReview"]),
        Index(value = ["rawMessageId"]),
        Index(value = ["clientId"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawMessageId: Long,

    /**
     * Stable identity across devices and reinstalls.
     *
     * [id] is a local autoincrement and would collide immediately between two installs, so it can
     * never be the sync key. Generated once at insert and never rewritten.
     */
    val clientId: String,

    val amountPaise: Long,
    val direction: Direction,
    val channel: Channel,

    val accountTail: String?,
    val merchantRaw: String?,
    val merchantNorm: String?,
    val vpaHandle: String?,
    val refNo: String?,
    val balancePaise: Long?,

    val occurredAt: Long,

    val category: String,
    val categorySource: CategorySource,
    val confidence: Float,
    val needsReview: Boolean,

    val lat: Double? = null,
    val lon: Double? = null,
    val locAccuracyM: Float? = null,
    val locAgeMs: Long? = null,
    val locConfidence: LocConfidence = LocConfidence.NONE,

    val parserVersion: Int,
    val createdAt: Long,

    /**
     * When this row last changed, in UTC milliseconds — set by whoever made the change. Drives sync
     * conflict resolution (see `docs/SYNC.md`'s "last write wins within a tier" rule) and is what
     * [syncedAt] is compared against to find rows a push hasn't caught up with yet.
     *
     * Bumped on creation and at every write path that changes `category`, `categorySource`,
     * `merchantRaw` or `needsReview` — see `UmberRepository.confirmCategory`.
     */
    val updatedAt: Long = createdAt,

    /**
     * `updatedAt` as of the last successful push to the sync server, or null if this row has never
     * been pushed.
     *
     * A row is "pending sync" exactly when `syncedAt` is null or older than `updatedAt` — cheap to
     * query (see `TxnDao.pendingSync`) without a separate dirty-flag table, and it self-heals: a row
     * edited again after a successful push simply becomes pending again the moment `updatedAt` moves
     * past `syncedAt`.
     *
     * Only meaningful in the `cloud` flavour. Always null on `privacy`, which never syncs.
     */
    val syncedAt: Long? = null,
)

/**
 * Merchant -> category, learned from user confirmations only.
 *
 * The single highest-value table in the app: spending is dominated by repeat merchants, so an exact
 * lookup here resolves most transactions with certainty and zero inference.
 */
@Entity(tableName = "merchant_memory")
data class MerchantMemoryEntity(
    @PrimaryKey val merchantNorm: String,
    val category: String,
    val confirmations: Int,
    val updatedAt: Long,
)

/** Single-row table holding the serialised classifier weights. */
@Entity(tableName = "model_state")
data class ModelStateEntity(
    @PrimaryKey val id: Int = 0,
    val labelsVersion: Int,
    val trainedExamples: Int,
    val weights: ByteArray,
    val updatedAt: Long,
) {
    // ByteArray needs structural equality written by hand; the generated data-class version
    // compares references and would report two identical models as different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModelStateEntity) return false
        return id == other.id &&
            labelsVersion == other.labelsVersion &&
            trainedExamples == other.trainedExamples &&
            updatedAt == other.updatedAt &&
            weights.contentEquals(other.weights)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + labelsVersion
        result = 31 * result + trainedExamples
        result = 31 * result + weights.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * Single-row cache of the most recent location fix.
 *
 * Written only while the app is in the foreground. The SMS receiver runs in the background, where
 * Android 10+ blocks location access without the background-location permission — reading this
 * cached row instead sidesteps that permission entirely, at the cost of the fix being older.
 */
@Entity(tableName = "last_location")
data class LastLocationEntity(
    @PrimaryKey val id: Int = 0,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val recordedAt: Long,
)
