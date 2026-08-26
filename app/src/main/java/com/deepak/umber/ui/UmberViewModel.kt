package com.deepak.umber.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deepak.umber.AppContainer
import com.deepak.umber.data.db.AccountTotal
import com.deepak.umber.data.db.CategoryTotal
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.db.TxnWithSource
import com.deepak.umber.data.repo.SpendWindow
import com.deepak.umber.data.repo.WindowSummary
import com.deepak.umber.ingest.BackfillResult
import com.deepak.umber.io.ImportReport
import com.deepak.umber.ml.ModelStats
import com.deepak.umber.remote.RegisterOutcome
import com.deepak.umber.remote.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val summaries: List<WindowSummary> = emptyList(),
    val topCategories: List<CategoryTotal> = emptyList(),
    val daily: List<Long> = emptyList(),
    val accounts: List<AccountTotal> = emptyList(),
    val reviewCount: Int = 0,
    val totalCount: Int = 0,
)

data class SettingsUiState(
    val messageCount: Int = 0,
    val confirmedCount: Int = 0,
    val merchantCount: Int = 0,
    val rejects: List<CategoryTotal> = emptyList(),
)

sealed interface TaskState {
    data object Idle : TaskState
    data class Running(val detail: String) : TaskState
    data class Done(val detail: String) : TaskState
}

class UmberViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.repository

    /**
     * Rolling windows drift with wall-clock time, so the UI needs a heartbeat as well as a data
     * signal — otherwise "last 24 hours" would only update when a transaction happened to arrive.
     */
    private val minuteTicker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    /**
     * Room invalidates by table, so the transaction count re-emits on *any* write to `txn` —
     * including a category change that leaves the count identical. That makes it a sufficient
     * change signal without also observing the row list.
     */
    val home: StateFlow<HomeUiState> = combine(
        repository.transactionCount(),
        repository.needingReviewCount(),
        minuteTicker,
    ) { totalCount, reviewCount, now ->
        HomeUiState(
            loading = false,
            summaries = repository.allSummaries(now),
            topCategories = repository.topCategories(SpendWindow.THIS_MONTH, limit = 8, now = now),
            daily = repository.dailySeries(days = 30, now = now),
            accounts = repository.accountTotals(SpendWindow.THIS_MONTH, now = now),
            reviewCount = reviewCount,
            totalCount = totalCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    fun setSearch(query: String) {
        _search.value = query
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<TxnWithSource>> = _search
        .flatMapLatest { query -> repository.history(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun changeCategory(row: TxnWithSource, category: String) = confirm(row.txn, category)

    val review: StateFlow<List<TxnEntity>> = repository.needingReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<SettingsUiState> = combine(
        repository.messageCount(),
        repository.confirmedCount(),
        repository.knownMerchantCount(),
        repository.rejectBreakdown(),
    ) { messages, confirmed, merchants, rejects ->
        SettingsUiState(messages, confirmed, merchants, rejects)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // --------------------------------------------------------------------- sync
    //
    // container.remoteSync is null on the privacy flavour (see AppContainer), which is the single
    // gate Settings uses to decide whether to show the cloud section at all.

    val syncStatus: StateFlow<SyncStatus>? = container.remoteSync?.status

    /** Set when [enableSync] fails — e.g. a rejected setup key. Cleared on the next attempt. */
    private val _syncEnableError = MutableStateFlow<String?>(null)
    val syncEnableError: StateFlow<String?> = _syncEnableError.asStateFlow()

    fun enableSync(setupKey: String) {
        val remote = container.remoteSync ?: return
        viewModelScope.launch {
            when (val outcome = remote.enable(setupKey)) {
                RegisterOutcome.Success -> _syncEnableError.value = null
                is RegisterOutcome.Failed -> _syncEnableError.value = outcome.detail
            }
        }
    }

    fun disableSync() {
        container.remoteSync?.disable()
    }

    fun syncNow() {
        val remote = container.remoteSync ?: return
        viewModelScope.launch { remote.sync() }
    }

    /** Debug builds only — see Settings' endpoint override field. */
    fun setSyncBaseUrlOverride(url: String?) {
        container.remoteSync?.baseUrlOverride = url?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Current override, if any — read once to seed the debug text field. */
    fun syncBaseUrlOverride(): String? = container.remoteSync?.baseUrlOverride

    private val _task = MutableStateFlow<TaskState>(TaskState.Idle)
    val task: StateFlow<TaskState> = _task.asStateFlow()

    private val _modelStats = MutableStateFlow<ModelStats?>(null)
    val modelStats: StateFlow<ModelStats?> = _modelStats.asStateFlow()

    init {
        viewModelScope.launch { _modelStats.value = repository.modelStats() }
    }

    fun confirm(txn: TxnEntity, category: String, applyToAll: Boolean = true) {
        viewModelScope.launch {
            repository.confirmCategory(txn, category, applyToAll)
            _modelStats.value = repository.modelStats()
            container.widgetUpdater.refresh()
        }
    }

    fun runBackfill(days: Int = 180) {
        viewModelScope.launch {
            _task.value = TaskState.Running("Reading SMS history…")
            val result: BackfillResult = container.backfill.run(days) { scanned ->
                _task.value = TaskState.Running("Scanned $scanned messages…")
            }
            container.widgetUpdater.refresh()
            _modelStats.value = repository.modelStats()
            _task.value = TaskState.Done(
                "Scanned ${result.scanned} · imported ${result.inserted} · " +
                    "${result.duplicates} duplicates · ${result.rejected} not transactions",
            )
        }
    }

    fun retrain() {
        viewModelScope.launch {
            _task.value = TaskState.Running("Retraining…")
            val stats = repository.retrainModel()
            _modelStats.value = stats
            _task.value = TaskState.Done("Retrained on ${stats.trainedExamples} steps")
        }
    }

    // ------------------------------------------------------------- files (SAF)

    /**
     * All file work goes through the resolver the caller hands in, so nothing here needs storage
     * permissions — the Storage Access Framework grants access to exactly the file the user picked.
     */
    fun importStatement(uri: Uri, resolver: ContentResolver) {
        runFileTask("Reading statement…") {
            // Bytes, not text: the importer sniffs magic numbers to tell xlsx from CSV, since
            // banks mislabel both the extension and the MIME type.
            val bytes = resolver.readBytes(uri)
                ?: return@runFileTask "Couldn't open that file"
            val report = repository.importStatement(bytes, "statement:${uri.lastPathSegment.orEmpty()}")
            reportText(report, "statement")
        }
    }

    fun rebuildLedger() {
        runFileTask("Rebuilding from stored messages…") {
            "Rebuilt ${repository.rebuildLedger()} transactions"
        }
    }

    fun importLedger(uri: Uri, resolver: ContentResolver) {
        runFileTask("Reading CSV…") {
            val text = resolver.readText(uri)
                ?: return@runFileTask "Couldn't open that file"
            val report = repository.importLedgerCsv(text, "csv:${uri.lastPathSegment.orEmpty()}")
            reportText(report, "CSV")
        }
    }

    fun exportLedger(uri: Uri, resolver: ContentResolver) {
        runFileTask("Writing CSV…") {
            val csv = repository.exportLedgerCsv()
            val wrote = runCatching {
                resolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } != null
            }.getOrDefault(false)

            if (!wrote) "Couldn't write that file" else "Exported ${csv.lineSequence().count() - 1} transactions"
        }
    }

    private fun reportText(report: ImportReport, label: String): String = when {
        !report.ok -> "Couldn't read the $label: ${report.problem}"
        else -> "Imported ${report.imported} · ${report.duplicates} already known · " +
            "${report.skipped} skipped, from ${report.rows} rows"
    }

    private fun runFileTask(running: String, block: suspend () -> String) {
        viewModelScope.launch {
            _task.value = TaskState.Running(running)
            val message = runCatching { block() }
                .getOrElse { e -> "Failed: ${e.message ?: e::class.simpleName}" }
            container.widgetUpdater.refresh()
            _modelStats.value = repository.modelStats()
            _task.value = TaskState.Done(message)
        }
    }

    fun reparseRejected() {
        viewModelScope.launch {
            _task.value = TaskState.Running("Re-reading skipped messages…")
            val recovered = repository.reparseRejected()
            container.widgetUpdater.refresh()
            _task.value = TaskState.Done("Recovered $recovered transactions")
        }
    }

    fun clearTask() {
        _task.value = TaskState.Idle
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { UmberViewModel(container) }
        }
    }
}

/**
 * Reads a SAF document as text.
 *
 * Bank exports are not reliably UTF-8 — some are Windows-1252, and a few carry a BOM — so bytes are
 * decoded leniently rather than strictly, and the BOM is stripped. A malformed byte becoming U+FFFD
 * is harmless here; refusing to open the file is not.
 */
private fun ContentResolver.readText(uri: Uri): String? = runCatching {
    openInputStream(uri)?.use { stream ->
        String(stream.readBytes(), Charsets.UTF_8).removePrefix("﻿")
    }
}.getOrNull()

private fun ContentResolver.readBytes(uri: Uri): ByteArray? = runCatching {
    openInputStream(uri)?.use { it.readBytes() }
}.getOrNull()
