package com.deepak.umber

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepak.umber.ui.UmberViewModel
import com.deepak.umber.ui.TaskState
import com.deepak.umber.ui.history.HistoryScreen
import com.deepak.umber.ui.home.HomeScreen
import com.deepak.umber.ui.review.ReviewScreen
import com.deepak.umber.ui.settings.SettingsScreen
import com.deepak.umber.ui.theme.UmberTheme

private enum class Tab(val label: String) {
    HOME("Home"),
    HISTORY("History"),
    REVIEW("Review"),
    SETTINGS("Settings"),
}

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as UmberApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UmberTheme {
                AppScaffold(container)
            }
        }
    }

    // Location tracking is deliberately tied to the foreground lifecycle — see LocationCache for
    // why this app never asks for background location.
    override fun onStart() {
        super.onStart()
        container.locationCache.startTracking()
    }

    override fun onStop() {
        super.onStop()
        container.locationCache.stopTracking()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(container: AppContainer) {
    val vm: UmberViewModel = viewModel(factory = UmberViewModel.factory(container))

    val home by vm.home.collectAsState()
    val history by vm.history.collectAsState()
    val search by vm.search.collectAsState()
    val review by vm.review.collectAsState()
    val settings by vm.settings.collectAsState()
    val task by vm.task.collectAsState()
    val modelStats by vm.modelStats.collectAsState()

    var tab by remember { mutableStateOf(Tab.HOME) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var locationGranted by remember { mutableStateOf(container.locationCache.hasPermission()) }

    val resolver = context.contentResolver

    // SAF: the user picks the exact file, so no storage permission is needed and the app never
    // gains broader filesystem access than the single document it was handed.
    val statementPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importStatement(it, resolver) } }

    val ledgerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importLedger(it, resolver) } }

    val ledgerExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { vm.exportLedger(it, resolver) } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        smsGranted = result[Manifest.permission.READ_SMS] ?: smsGranted
        locationGranted = container.locationCache.hasPermission()
        if (locationGranted) container.locationCache.startTracking()
    }

    // Ask on first launch rather than burying it in Settings — without SMS the app has nothing
    // to show, so a cold start with no data and no prompt just looks broken.
    LaunchedEffect(Unit) {
        if (!smsGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            val icon = when (entry) {
                                Tab.HOME -> Icons.Filled.Home
                                Tab.HISTORY -> Icons.Filled.ReceiptLong
                                Tab.REVIEW -> Icons.Filled.Rule
                                Tab.SETTINGS -> Icons.Filled.Settings
                            }
                            if (entry == Tab.REVIEW && home.reviewCount > 0) {
                                BadgedBox(badge = { Badge { Text(home.reviewCount.toString()) } }) {
                                    Icon(icon, contentDescription = entry.label)
                                }
                            } else {
                                Icon(icon, contentDescription = entry.label)
                            }
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.HOME -> HomeScreen(
                state = home,
                onOpenReview = { tab = Tab.REVIEW },
                modifier = Modifier.padding(padding),
            )

            Tab.HISTORY -> HistoryScreen(
                items = history,
                query = search,
                onQueryChange = vm::setSearch,
                onCategoryChange = vm::changeCategory,
                modifier = Modifier.padding(padding),
            )

            Tab.REVIEW -> ReviewScreen(
                items = review,
                onConfirm = { txn, category -> vm.confirm(txn, category) },
                modifier = Modifier.padding(padding),
            )

            Tab.SETTINGS -> SettingsScreen(
                state = settings,
                modelStats = modelStats,
                task = task,
                smsGranted = smsGranted,
                locationGranted = locationGranted,
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
                onBackfill = { days -> vm.runBackfill(days) },
                onRetrain = { vm.retrain() },
                onReparse = { vm.reparseRejected() },
                onRebuild = { vm.rebuildLedger() },
                // Deliberately unfiltered.
                //
                // The importer identifies the format from magic bytes precisely because MIME is
                // unreliable here: banks serve a CSV named .xls, providers disagree about
                // spreadsheet types, and a real SBI .xlsx export arrives as
                // "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" — a type an
                // earlier hand-written allowlist omitted, which silently greyed out the one file
                // the user was trying to pick.
                //
                // A filter that hides the target file is a dead end. Showing a few extra files is
                // a mild annoyance that the format sniffer already reports clearly.
                onImportStatement = { statementPicker.launch(arrayOf("*/*")) },
                onExportCsv = { ledgerExporter.launch("ledger-export.csv") },
                onImportCsv = { ledgerPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    // Clear a finished task message when the user navigates away, so a stale "imported 412"
    // doesn't sit on the screen forever.
    LaunchedEffect(tab) {
        if (tab != Tab.SETTINGS && task is TaskState.Done) vm.clearTask()
    }
}
