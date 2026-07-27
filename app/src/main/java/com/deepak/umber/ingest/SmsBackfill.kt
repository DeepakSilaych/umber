package com.deepak.umber.ingest

import android.content.Context
import android.provider.Telephony
import com.deepak.umber.data.model.SourceType

data class BackfillResult(
    val scanned: Int,
    val inserted: Int,
    val duplicates: Int,
    val rejected: Int,
)

/**
 * One-shot import of historical SMS from the system inbox.
 *
 * This is what makes the app useful on day one instead of day thirty — and, more importantly, it's
 * what gives the classifier a real training set to learn from as soon as the user starts confirming
 * categories.
 *
 * Idempotent: the raw-message fingerprint makes re-running it a no-op, so the user can safely hit
 * "import again" after widening the window.
 */
class SmsBackfill(
    private val context: Context,
    private val ingest: IngestPipeline,
) {

    suspend fun run(days: Int = 180, onProgress: (scanned: Int) -> Unit = {}): BackfillResult {
        val since = System.currentTimeMillis() - days * MS_PER_DAY

        var scanned = 0
        var inserted = 0
        var duplicates = 0
        var rejected = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return BackfillResult(0, 0, 0, 0)

        cursor.use { c ->
            val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (c.moveToNext()) {
                val sender = c.getString(addressCol) ?: continue
                val body = c.getString(bodyCol) ?: continue
                val date = c.getLong(dateCol)

                scanned++
                when (ingest.ingest(SourceType.SMS, sender, body, date)) {
                    is IngestOutcome.Inserted -> inserted++
                    IngestOutcome.DuplicateMessage, IngestOutcome.DuplicateTransaction -> duplicates++
                    is IngestOutcome.Rejected -> rejected++
                }

                if (scanned % PROGRESS_STRIDE == 0) onProgress(scanned)
            }
        }

        onProgress(scanned)
        return BackfillResult(scanned, inserted, duplicates, rejected)
    }

    private companion object {
        const val MS_PER_DAY = 24 * 60 * 60 * 1000L
        const val PROGRESS_STRIDE = 25
    }
}
