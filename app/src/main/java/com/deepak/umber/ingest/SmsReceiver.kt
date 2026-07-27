package com.deepak.umber.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.deepak.umber.UmberApp
import com.deepak.umber.data.model.SourceType
import kotlinx.coroutines.launch

/**
 * Live SMS ingestion.
 *
 * Registered with a high priority so we see the message promptly, but this is *not* an abort —
 * `SMS_RECEIVED_ACTION` is a non-ordered broadcast on modern Android and the user's messaging app
 * still gets every message untouched.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // A long SMS arrives as several PDUs. Concatenating by originating address reassembles the
        // original text — parsing the fragments individually would split the amount from the
        // merchant and yield nothing usable.
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (part in parts) {
            val sender = part.originatingAddress ?: part.displayOriginatingAddress ?: continue
            val text = part.displayMessageBody ?: part.messageBody ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(text)
        }
        if (bySender.isEmpty()) return

        val receivedAt = System.currentTimeMillis()
        val app = context.applicationContext as? UmberApp ?: return
        val container = app.container

        // goAsync keeps the process alive across the suspend boundary. The budget is ~10s; ingest
        // is a parse plus a couple of indexed queries, so this stays well inside it.
        val pending = goAsync()
        container.appScope.launch {
            try {
                var inserted = false
                for ((sender, body) in bySender) {
                    val outcome = container.ingest.ingest(
                        source = SourceType.SMS,
                        sender = sender,
                        body = body.toString(),
                        receivedAt = receivedAt,
                    )
                    if (outcome is IngestOutcome.Inserted) inserted = true
                }
                if (inserted) container.widgetUpdater.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "SMS ingest failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
