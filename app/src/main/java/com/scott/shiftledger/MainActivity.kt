package com.scott.shiftledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val ledgerModel: LedgerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) ledgerModel.handleIntent(intent)
        setContent {
            val vm: LedgerViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val palette by vm.colorPalette.collectAsStateWithLifecycle()
            val font by vm.fontStyle.collectAsStateWithLifecycle()
            val dark = palette == ColorPalette.BLACK || themeMode.isDark()
            LaunchedEffect(dark, palette) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(palette.colors(false).background.toArgb(), palette.colors(true).background.toArgb()) { dark },
                )
            }
            LedgerTheme(dark, palette, font) {
                LedgerApp(this, vm, themeMode)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ledgerModel.handleIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerApp(context: Context, vm: LedgerViewModel, themeMode: ThemeMode) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val restore by vm.restorePreview.collectAsStateWithLifecycle()
    val tracking by TrackingService.running.collectAsStateWithLifecycle()
    val trackingProblem by TrackingService.problem.collectAsStateWithLifecycle()
    val gpsEnvironment by TrackingService.environment.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val importNotice by vm.importNotice.collectAsStateWithLifecycle()
    val requestedTab by vm.requestedTab.collectAsStateWithLifecycle()
    val widgetRequest by vm.widgetRequest.collectAsStateWithLifecycle()
    val importText = vm.importText
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var requestImport by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    var messageOrder by rememberSaveable { mutableStateOf("") }
    BackHandler(tool != null) { tool = null }
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf<Shift?>(null) }
    var selectedEntry by remember { mutableStateOf<Entry?>(null) }
    var selectedSource by remember { mutableStateOf<ImportSource?>(null) }
    var confirmUndo by remember { mutableStateOf<ImportSource?>(null) }
    var confirmDelete by remember { mutableStateOf<Expense?>(null) }
    var endConfirm by remember { mutableStateOf(false) }
    var widgetConfirm by remember { mutableStateOf<String?>(null) }
    var startOdometer by remember { mutableStateOf<Double?>(null) }
    var resumeRequested by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(requestedTab) { requestedTab?.let { tab = it; tool = null; vm.requestedTab.value = null } }
    LaunchedEffect(widgetRequest, busy) {
        if (!busy && widgetRequest != null) {
            val action = widgetRequest
            vm.widgetRequest.value = null
            val active = vm.repository.dao.activeShift()
            when {
                action == "start" && active == null -> form = "start"
                active == null -> vm.message.value = "No shift is currently open. Start one from Home."
                action == "end" -> endConfirm = true
                action == "pause" || action == "resume" -> widgetConfirm = action
            }
        }
    }

    fun locationEnabled() = LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    fun beginTracking() {
        if (!locationEnabled()) vm.message.value = "Turn on Location in your phone settings, then try again."
        else if (resumeRequested) vm.resume() else vm.start(startOdometer)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) beginTracking()
        else vm.message.value = "Mileage tracking needs precise location. Allow it in Android app settings, or add a shift manually."
    }
    fun requestTracking(resume: Boolean, odometer: Double? = null) {
        resumeRequested = resume; startOdometer = odometer
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) beginTracking()
        else permissions.launch(buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.task {
            val snapshot = vm.repository.snapshot()
            withContext(Dispatchers.IO) { writeText(context, uri, BackupCodec.csv(snapshot)) }
            vm.message.value = "CSV exported. Earnings, payouts, expenses, and shifts have separate record types."
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.task {
            val snapshot = vm.repository.snapshot()
            withContext(Dispatchers.IO) { writeText(context, uri, BackupCodec.encode(snapshot)) }
            vm.message.value = "Backup saved. Keep a copy somewhere you can access if you lose this phone."
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.task { vm.restorePreview.value = withContext(Dispatchers.IO) { BackupCodec.decode(readText(context, uri, 20_000_000)) } }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDocument(uri)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(navigationIcon = {
                if (tool != null) IconButton(onClick = { (context as ComponentActivity).onBackPressedDispatcher.onBackPressed() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }, title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DateRange, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    Text(when (tool) {
                        "vehicle" -> "Fuel & maintenance"
                        "appearance" -> "Appearance"
                        "goals" -> "Weekly goal"
                        "messages" -> "Saved messages"
                        "rates" -> "Dollars per mile"
                        else -> "SHIFT LEDGER"
                    }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            val labels = listOf("Home", "Earnings", "Shifts", "Costs", "Data")
            val icons = listOf(Icons.Default.Home, Icons.AutoMirrored.Filled.List, Icons.Default.DateRange, Icons.Default.ShoppingCart, Icons.Default.Settings)
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; tool = null }, icon = { Icon(icons[i], label) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy || importProgress != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tool) {
                "vehicle" -> VehicleScreen(state, busy,
                    onFuel = { row, done -> vm.task { vm.repository.saveFuel(row); done() } },
                    onDeleteFuel = { id, done -> vm.task { vm.repository.deleteFuel(id); done() } },
                    onReading = { row, done -> vm.task { vm.repository.saveReading(row); done() } },
                    onDeleteReading = { id, done -> vm.task { vm.repository.dao.deleteReading(id); done() } },
                    onVehicle = { row, done -> vm.task { vm.repository.saveVehicle(row); done() } },
                    onService = { row, done -> vm.task { vm.repository.saveMaintenance(row); done() } },
                    onDeleteService = { id, done -> vm.task { vm.repository.deleteMaintenance(id); done() } })
                "appearance" -> AppearanceScreen(themeMode, vm::setThemeMode, vm::setColorPalette, vm::setFontStyle, vm::resetAppearance)
                "goals" -> GoalsScreen(state, java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), busy,
                    onSave = { amount, done -> vm.task { vm.repository.saveGoal(amount); done() } },
                    onExpense = { form = "expense" }, onImport = { tool = null; requestImport = true; tab = 1 })
                "messages" -> MessagesScreen(state.messageTemplates, busy, messageOrder,
                    onSave = { id, title, body, done -> vm.task { vm.repository.saveTemplate(id, title, body); done() } },
                    onDelete = { id, done -> vm.task { vm.repository.dao.deleteTemplate(id); done() } })
                "rates" -> RatesScreen(state.entries, onOrder = { selectedEntry = it },
                    onMessages = { messageOrder = it; tool = "messages" })
                else -> when (tab) {
                0 -> HomeScreen(state, now, tracking, gpsEnvironment, trackingProblem, busy,
                    onStart = { form = "start" }, onPause = vm::pause,
                    onResume = { requestTracking(true) }, onEnd = { endConfirm = true },
                    onImport = { requestImport = true; tab = 1 }, onExpense = { form = "expense" }, onReview = { tab = 2 },
                    onMessages = { messageOrder = ""; tool = "messages" }, onRates = { tool = "rates" }, onGoal = { tool = "goals" }, onVehicle = { tool = "vehicle" })
                1 -> EarningsScreen(state, preview, busy || importProgress != null, importText, importProgress, importNotice, onPreview = vm::parse,
                    onCancelPreview = { vm.preview.value = null }, onSave = vm::saveImport,
                    onOpenFile = { documentPicker.launch(arrayOf("application/pdf", "image/*", "text/plain")) },
                    onManual = { form = "earning" }, onEntry = { selectedEntry = it }, onRates = { tool = "rates" },
                    requestImport = requestImport, onImportHandled = { requestImport = false })
                2 -> ShiftsScreen(state, now, onReview = { reviewing = it }, onManual = { form = "shift" })
                3 -> ExpensesScreen(state, onAdd = { form = "expense" }, onVehicle = { tool = "vehicle" }, onDelete = { confirmDelete = it })
                4 -> DataScreen(state, busy, themeMode, onThemeChange = vm::setThemeMode,
                    onAppearance = { tool = "appearance" },
                    onMessages = { messageOrder = ""; tool = "messages" },
                    onAddWidget = { if (!LedgerWidget.pin(context)) vm.message.value = "Long-press an empty area of your home screen, choose Widgets, and select Shift Ledger." },
                    onCsv = { csvPicker.launch("shift-ledger-${LocalDate.now()}.csv") },
                    onBackup = { backupPicker.launch("shift-ledger-backup-${LocalDate.now()}.json") },
                    onRestore = { restorePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onSource = { selectedSource = it }, onUndo = { confirmUndo = it })
                }
            }
        }
    }
    message?.let { Notice("Shift Ledger", it, onDismiss = { vm.message.value = null }) }
    if (endConfirm) Confirm("End this shift?", "Stop mileage tracking and save the shift for review.", "End shift", { endConfirm = false }) { vm.end(); endConfirm = false; tab = 2 }
    widgetConfirm?.let { action -> Confirm(if (action == "pause") "Pause this shift?" else "Resume this shift?",
        if (action == "pause") "Stop recording time and mileage for a personal break." else "Continue recording work time and mileage.",
        action.replaceFirstChar { it.uppercase() }, { widgetConfirm = null }) {
            if (action == "pause") vm.pause() else requestTracking(true)
            widgetConfirm = null
        }
    }
    form?.let { which ->
        EntryForm(which, onDismiss = { form = null }) { date, amount, extra, note, category ->
            when (which) {
                "start" -> requestTracking(false, amount.takeIf { it.isNotBlank() }?.toDouble())
                "expense" -> vm.addExpense(date, amount, category, note)
                "earning" -> vm.addManualEarning(date, amount, note)
                "shift" -> vm.manualShift(date, amount, extra, note)
            }
            form = null
        }
    }
    reviewing?.let { shift -> ReviewDialog(shift, { reviewing = null }) { miles, note, timing, reset ->
        vm.task { vm.repository.reviewShift(shift.id, miles, note, timing, reset); vm.message.value = "Shift reviewed." }
        reviewing = null
    } }
    selectedEntry?.let { entry ->
        AlertDialog(onDismissRequest = { selectedEntry = null }, title = { Text("${entry.kind.lowercase().replaceFirstChar { it.uppercase() }} · ${money(entry.amountCents)}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${entry.date}${entry.orderId?.let { " · Order $it" } ?: ""}")
                if (entry.kind == "ORDER") {
                    val rate = orderRates(state.entries).firstOrNull { it.order.id == entry.id } ?: OrderRate(entry, emptyList())
                    Text("${rateText(rate.earningsCents, rate.miles)} per engaged mile", fontWeight = FontWeight.Bold)
                    Text("${money(rate.earningsCents)} order pay including linked adjustments · Dispatch estimated miles", style = MaterialTheme.typography.bodySmall)
                }
                if (!entry.orderId.isNullOrBlank()) TextButton(onClick = { messageOrder = entry.orderId; selectedEntry = null; tool = "messages" }) { Text("Support message for this order") }
                Text(entry.raw.ifBlank { "Manually entered earnings" })
                if (entry.kind == "MANUAL") Text("Remove a manual entry before importing the same earnings from Dispatch.")
            } }, confirmButton = { TextButton(onClick = { selectedEntry = null }) { Text("Close") } },
            dismissButton = { if (entry.kind == "MANUAL") TextButton(onClick = {
                vm.task { vm.repository.dao.deleteManual(entry.id) }; selectedEntry = null
            }) { Text("Delete manual entry") } })
    }
    selectedSource?.let { source -> Notice("Original earnings import", source.text, { selectedSource = null }) }
    confirmUndo?.let { source -> Confirm("Undo this import?", "Remove the earnings rows first added by this import. Shifts and expenses are kept. You can import the text again.", "Undo import", { confirmUndo = null }) {
        vm.task { vm.repository.undoImport(source.id) }; confirmUndo = null
    } }
    confirmDelete?.let { expense -> Confirm("Delete expense?", "${expense.date} · ${money(expense.amountCents)} · ${expense.category}", "Delete", { confirmDelete = null }) {
        vm.task { vm.repository.dao.deleteExpense(expense.id) }; confirmDelete = null
    } }
    restore?.let { backup -> Confirm("Restore this backup?", "Add ${backup.entries.size} earnings rows, ${backup.shifts.size} shifts, ${backup.expenses.size} expenses, ${backup.messageTemplates.size} saved messages, and ${backup.goalPlans.size} goal settings, ${backup.fuelStops.size} fill-ups, ${backup.fuelReadings.size} gauge readings, and ${backup.maintenance.size} service records. Existing IDs keep their current values. Open shifts from the backup will be closed and marked for review.", "Restore", { vm.restorePreview.value = null }) {
        vm.task { vm.message.value = vm.repository.restore(backup); vm.restorePreview.value = null }
    } }
}

@Composable private fun Notice(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
@Composable internal fun Confirm(title: String, text: String, button: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(button) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun EntryForm(kind: String, onDismiss: () -> Unit, onSave: (String, String, String, String, String) -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Fuel") }
    var error by remember { mutableStateOf<String?>(null) }
    val title = when (kind) { "start" -> "Start your shift"; "expense" -> "Add expense"; "shift" -> "Add a missed shift"; else -> "Manual earnings" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == "start") Text("Allow precise location to record mileage while you work. You can pause for personal driving.")
            else OutlinedTextField(date, { date = it }, label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(amount, { amount = it }, label = { Text(when (kind) { "start" -> "Starting odometer (optional)"; "shift" -> "Miles"; else -> "Amount ($)" }) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (kind == "shift") OutlinedTextField(extra, { extra = it }, label = { Text("Working hours · e.g. 2.5") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (kind == "expense") {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                listOf(listOf("Fuel", "Tolls", "Parking"), listOf("Maintenance", "Other")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { row.forEach { item -> FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item, fontSize = 12.sp) }) } }
                }
            }
            if (kind != "start") OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, maxLines = 3)
            if (kind == "earning") Hint("Manual entries cannot be matched to Dispatch orders automatically. Remove them before importing the same work.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            if (kind != "start") require(validDate(date)) { "Enter a date like 2026-10-04." }
            if (kind in listOf("start", "shift")) {
                if (kind == "shift" || amount.isNotBlank()) require(amount.toDouble().isFinite() && amount.toDouble() in 0.0..10_000_000.0) { "Enter a valid mileage number." }
                if (kind == "shift") require(extra.toDouble().isFinite() && extra.toDouble() > 0 && extra.toDouble() <= 24) { "Hours must be greater than zero and at most 24." }
            } else require(cents(amount) != 0L && (kind != "expense" || cents(amount) > 0)) { "Enter a valid amount." }
            onSave(date, amount, extra, note, category)
        }.onFailure { error = it.message ?: "Check the entered values." }
    }) { Text(if (kind == "start") "Start tracking" else "Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun ReviewDialog(shift: Shift, onDismiss: () -> Unit, onSave: (Double?, String, ShiftTiming?, Boolean) -> Unit) {
    var miles by rememberSaveable { mutableStateOf(shift.correctedMiles?.let(::decimal) ?: "") }
    var odometer by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf(shift.note) }
    var error by remember { mutableStateOf<String?>(null) }
    val initialStart = remember(shift.id) { ShiftTimes.display(shift.startTime()) }
    val initialEnd = remember(shift.id) { ShiftTimes.display(requireNotNull(shift.endTime())) }
    val initialBreaks = remember(shift.id) { ((requireNotNull(shift.endTime()) - shift.startTime() - shift.durationMillis(System.currentTimeMillis())).coerceAtLeast(0) / 60_000).toString() }
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var end by rememberSaveable { mutableStateOf(initialEnd) }
    var breaks by rememberSaveable { mutableStateOf(initialBreaks) }
    var reset by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Review ${shift.date}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPS recorded ${decimal(shift.gpsMeters / 1609.344)} mi. ${shift.gaps} gaps flagged.")
            Text("Shift times", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Local time · YYYY-MM-DD HH:mm (24-hour clock). Original recorded times are kept.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(start, { start = it; reset = false }, label = { Text("Start date and time") }, singleLine = true)
            OutlinedTextField(end, { end = it; reset = false }, label = { Text("End date and time") }, singleLine = true)
            OutlinedTextField(breaks, { breaks = it; reset = false }, label = { Text("Personal / unpaid break minutes") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = {
                start = ShiftTimes.display(shift.startedAt)
                end = ShiftTimes.display(requireNotNull(shift.endedAt))
                breaks = ((shift.endedAt - shift.startedAt - shift.workedMillis).coerceAtLeast(0) / 60_000).toString()
                reset = true
            }) { Text("Restore recorded times") }
            if (shift.odometerStart != null) {
                Text("Starting odometer: ${decimal(shift.odometerStart)}")
                OutlinedTextField(odometer, { odometer = it }, label = { Text("Ending odometer (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Odometer difference includes personal driving. Subtract those miles before using it.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val end = odometer.toDoubleOrNull()
                    if (end != null && end.isFinite() && end >= shift.odometerStart) miles = decimal(end - shift.odometerStart)
                    else error = "Ending odometer must be at least the starting reading."
                }) { Text("Use odometer difference") }
            }
            OutlinedTextField(miles, { miles = it }, label = { Text("Corrected work miles (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Leave blank to use GPS mileage. Check missing expenses and earnings before marking reviewed.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(note, { note = it }, label = { Text("Review note") }, maxLines = 4)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val corrected = miles.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (miles.isNotBlank() && (corrected == null || !corrected.isFinite() || corrected !in 0.0..100_000.0)) error = "Enter valid mileage."
        else runCatching {
            val timing = if (!reset && (start != initialStart || end != initialEnd || breaks != initialBreaks)) ShiftTimes.parse(start, end, breaks) else null
            onSave(corrected, note, timing, reset)
        }.onFailure { error = it.localizedMessage ?: "Check the date, time, and break minutes." }
    }) { Text("Mark reviewed") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun readText(context: Context, uri: Uri, maxChars: Int): String {
    return requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open that file." }.bufferedReader().use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= maxChars) { "File is too large. Choose a smaller export." }
            text.append(buffer, 0, count)
        }
        text.toString()
    }
}
private fun writeText(context: Context, uri: Uri, text: String) {
    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Could not write that file." }.bufferedWriter().use { it.write(text) }
}
