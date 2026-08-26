package com.deepak.umber.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepak.umber.ml.Classifier
import com.deepak.umber.ml.ModelStats
import com.deepak.umber.remote.SyncStatus
import com.deepak.umber.ui.SettingsUiState
import com.deepak.umber.ui.TaskState

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modelStats: ModelStats?,
    task: TaskState,
    smsGranted: Boolean,
    locationGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onBackfill: (Int) -> Unit,
    onRetrain: () -> Unit,
    onReparse: () -> Unit,
    onRebuild: () -> Unit,
    onImportStatement: () -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    versionName: String,
    onCheckUpdates: () -> Unit,
    canPinWidget: Boolean,
    onAddWidget: () -> Unit,
    /**
     * Null on the privacy flavour (see `AppContainer.remoteSync`) — the whole cloud sync section is
     * gated on this being non-null rather than on a separate build-flavour check.
     */
    syncStatus: SyncStatus? = null,
    syncEnableError: String? = null,
    onEnableSync: (String) -> Unit = {},
    onDisableSync: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    isDebugBuild: Boolean = false,
    syncBaseUrlOverride: String? = null,
    onSyncBaseUrlOverrideChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (!smsGranted || !locationGranted) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (!smsGranted) {
                        Text(
                            "SMS access is required — it's the only source of transaction data.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!locationGranted) {
                        Text(
                            "Location is optional. Without it, transactions simply have no place attached.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermissions) { Text("Grant") }
                }
            }
        }

        StatCard(
            title = "Data",
            rows = listOf(
                "Messages stored" to state.messageCount.toString(),
                "Merchants learned" to state.merchantCount.toString(),
                "Categories you've confirmed" to state.confirmedCount.toString(),
            ),
        )

        if (syncStatus != null) {
            CloudSyncSection(
                status = syncStatus,
                enableError = syncEnableError,
                onEnable = onEnableSync,
                onDisable = onDisableSync,
                onSyncNow = onSyncNow,
                isDebugBuild = isDebugBuild,
                baseUrlOverride = syncBaseUrlOverride,
                onBaseUrlOverrideChange = onSyncBaseUrlOverrideChange,
            )
        }

        StatCard(
            title = "Model",
            rows = listOf(
                "Training steps" to (modelStats?.trainedExamples?.toString() ?: "—"),
                "Status" to when {
                    modelStats == null -> "loading"
                    modelStats.isTrusted -> "in use"
                    else -> "warming up (needs ${Classifier.MIN_EXAMPLES_TO_TRUST} steps)"
                },
                "Size on disk" to (modelStats?.let { "${it.sizeBytes / 1024} KB" } ?: "—"),
            ),
        )

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("Import history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    // Worth stating plainly: this is safe to repeat, which is not obvious.
                    "Reads past bank SMS from your inbox. Safe to run more than once — already-imported messages are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onBackfill(30) },
                        enabled = smsGranted && task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("1 month") }
                    OutlinedButton(
                        onClick = { onBackfill(90) },
                        enabled = smsGranted && task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("3 months") }
                    OutlinedButton(
                        onClick = { onBackfill(365) },
                        enabled = smsGranted && task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("1 year") }
                }
            }
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("Bank statement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Import a statement as Excel (.xlsx), CSV or TSV. Transactions already captured " +
                        "from SMS are matched by reference number and won't be duplicated.\n\n" +
                        "Old .xls and PDF aren't supported — in Excel, 'Save as' .xlsx or CSV first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onImportStatement,
                    enabled = task !is TaskState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose statement file") }
            }
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("Export & backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Export writes your ledger as CSV — one row per transaction, amounts as plain " +
                        "numbers so they sum in a spreadsheet. Categories you've confirmed are " +
                        "preserved and re-taught to the model on import.\n\n" +
                        "Raw SMS text is never written to the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportCsv,
                        enabled = task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Export CSV") }
                    OutlinedButton(
                        onClick = onImportCsv,
                        enabled = task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Import CSV") }
                }
            }
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("Maintenance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Retrain rebuilds the model from every category you've confirmed. Re-scan runs the " +
                        "current parser over messages an older version skipped.\n\n" +
                        "Rebuild re-reads every stored message from scratch, fixing transactions that " +
                        "an older parser got wrong. Your confirmed categories are re-applied " +
                        "automatically, but imported statements and CSVs need importing again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRetrain,
                        enabled = task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Retrain") }
                    OutlinedButton(
                        onClick = onReparse,
                        enabled = task !is TaskState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text("Re-scan") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRebuild,
                    enabled = task !is TaskState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Rebuild ledger") }
            }
        }

        when (task) {
            is TaskState.Running -> {
                Column {
                    Text(task.detail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is TaskState.Done -> Text(task.detail, style = MaterialTheme.typography.bodySmall)
            TaskState.Idle -> Unit
        }

        if (state.rejects.isNotEmpty()) {
            StatCard(
                title = "Messages skipped",
                subtitle = "Why non-transaction messages were ignored. Useful when something is missing.",
                rows = state.rejects.map { it.category to it.txnCount.toString() },
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    // This card renders on both flavors from shared code — the claim must stay
                    // accurate for whichever one is actually running, not just describe the
                    // privacy build. Raw SMS/location text is true on both; "nothing leaves the
                    // device" is only true when sync isn't actually reaching a server.
                    if (syncStatus == null) {
                        "This app has no INTERNET permission. Messages, transactions, the model and any " +
                            "location data never leave this device — there is no server to send them to."
                    } else if (syncStatus.enabled) {
                        "Raw SMS text, account numbers and location never leave this device — only the " +
                            "parsed ledger (amount, merchant, category) syncs, because Sync is turned on " +
                            "below. Turn it off to stop sending anything."
                    } else {
                        "Raw SMS text, account numbers and location never leave this device. This build " +
                            "can sync the parsed ledger to your own server, but Sync is currently off " +
                            "below — nothing is sent unless you turn it on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("Home screen widget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Shows rolling 24 hour, 7 day and 30 day spend. Resize it for a sparkline and " +
                        "your top categories. Tapping it opens Umber.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAddWidget,
                    enabled = canPinWidget,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (canPinWidget) "Add widget to home screen" else "Add it from your launcher")
                }
                if (!canPinWidget) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // Some launchers don't implement the pin request, so say where to look
                        // rather than leaving a dead button.
                        "Your launcher doesn't support adding widgets from inside an app. " +
                            "Long-press the home screen → Widgets → Umber.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(versionName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    // Being upfront about why there's no automatic update prompt: the same missing
                    // permission that guarantees the privacy claim also rules one out.
                    "Umber can't check for updates on its own — it has no internet permission. " +
                        "This opens the releases page in your browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth()) {
                    Text("Check for updates")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Umber · built by deepaksilaych",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The `cloud` flavour's opt-in sync controls. Only ever rendered when [SettingsScreen] was handed a
 * non-null [SyncStatus] — the privacy build never builds this composable at all.
 *
 * Consent copy is inline rather than a separate dialog, matching how every other consequential
 * action in this screen (Rebuild ledger, Retrain) is presented: explanatory text right above the
 * control, no extra tap to acknowledge. The toggle itself is the opt-in — off by default, and
 * flipping it on is the only path that ever calls [onEnable].
 */
@Composable
private fun CloudSyncSection(
    status: SyncStatus,
    enableError: String?,
    onEnable: (String) -> Unit,
    onDisable: () -> Unit,
    onSyncNow: () -> Unit,
    isDebugBuild: Boolean,
    baseUrlOverride: String?,
    onBaseUrlOverrideChange: (String) -> Unit,
) {
    var setupKey by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = status.enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (setupKey.isNotBlank()) onEnable(setupKey)
                        } else {
                            onDisable()
                        }
                    },
                    // Off can't be flipped on without a key typed in below — there is nothing to
                    // register with yet.
                    enabled = status.enabled || setupKey.isNotBlank(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sends the parsed ledger only — amount, direction, merchant, category, account " +
                    "tail, reference number, timestamp — to your own server, for a web dashboard " +
                    "and smarter classification. Raw SMS text, account numbers and balances stay " +
                    "on this device; the server never sees them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!status.enabled) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = setupKey,
                    onValueChange = { setupKey = it },
                    label = { Text("Setup key") },
                    placeholder = { Text("Paste the key configured on your server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (enableError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Couldn't enable sync: $enableError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onSyncNow,
                    enabled = !status.syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (status.syncing) "Syncing…" else "Sync now") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                syncStatusLine(status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (isDebugBuild) {
                Spacer(Modifier.height(10.dp))
                var urlField by remember { mutableStateOf(baseUrlOverride.orEmpty()) }
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it; onBaseUrlOverrideChange(it) },
                    label = { Text("Debug: sync endpoint override") },
                    placeholder = { Text("http://10.0.2.2:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Debug builds only. Points sync at a local dev server instead of the default " +
                        "endpoint — leave blank to use the default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Synced 3 minutes ago" / "Sync error: … — will retry" / "Sync disabled: authentication failed,
 * check your setup key in Settings" — the three shapes Settings promises in its last-sync-status
 * line.
 */
private fun syncStatusLine(status: SyncStatus): String {
    status.lastError?.let { error ->
        return if (status.enabled) "Sync error: $error — will retry" else "Sync disabled: $error"
    }

    val lastSyncAt = status.lastSyncAt ?: return if (status.enabled) "Not synced yet" else "Sync is off"

    val minutes = ((System.currentTimeMillis() - lastSyncAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes minute${if (minutes == 1L) "" else "s"} ago"
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            "Synced $hours hour${if (hours == 1L) "" else "s"} ago"
        }
        else -> {
            val days = minutes / (60 * 24)
            "Synced $days day${if (days == 1L) "" else "s"} ago"
        }
    }
}

@Composable
private fun StatCard(title: String, rows: List<Pair<String, String>>, subtitle: String? = null) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
