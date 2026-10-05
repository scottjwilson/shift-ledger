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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
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
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

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
                    Text("SHIFT LEDGER", fontSize = 15.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            val labels = listOf("Home", "Earnings", "Shifts", "Costs", "Data")
            val icons = listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.DateRange, Icons.Default.ShoppingCart, Icons.Default.Settings)
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
                    onExpense = { form = "expense" }, onImport = { tool = null; tab = 1 })
                "messages" -> MessagesScreen(state.messageTemplates, busy, messageOrder,
                    onSave = { id, title, body, done -> vm.task { vm.repository.saveTemplate(id, title, body); done() } },
                    onDelete = { id, done -> vm.task { vm.repository.dao.deleteTemplate(id); done() } })
                "rates" -> RatesScreen(state.entries, onOrder = { selectedEntry = it },
                    onMessages = { messageOrder = it; tool = "messages" })
                else -> when (tab) {
                0 -> HomeScreen(state, now, tracking, gpsEnvironment, trackingProblem, busy,
                    onStart = { form = "start" }, onPause = vm::pause,
                    onResume = { requestTracking(true) }, onEnd = { endConfirm = true },
                    onImport = { tab = 1 }, onExpense = { form = "expense" }, onReview = { tab = 2 },
                    onMessages = { messageOrder = ""; tool = "messages" }, onRates = { tool = "rates" }, onGoal = { tool = "goals" }, onVehicle = { tool = "vehicle" })
                1 -> EarningsScreen(state, preview, busy || importProgress != null, importText, importProgress, importNotice, onPreview = vm::parse,
                    onCancelPreview = { vm.preview.value = null }, onSave = vm::saveImport,
                    onOpenFile = { documentPicker.launch(arrayOf("application/pdf", "image/*", "text/plain")) },
                    onManual = { form = "earning" }, onEntry = { selectedEntry = it }, onRates = { tool = "rates" })
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

@Composable internal fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}
@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable internal fun Hint(text: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun HomeScreen(state: LedgerState, now: Long, tracking: Boolean, environment: GpsEnvironment, problem: String?, busy: Boolean,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onEnd: () -> Unit,
    onImport: () -> Unit, onExpense: () -> Unit, onReview: () -> Unit, onMessages: () -> Unit, onRates: () -> Unit, onGoal: () -> Unit, onVehicle: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val goal = remember(state.entries, state.expenses, state.goalPlans, today) { WeeklyGoals.dashboard(state, today).current }
    val from = when (period) { 0 -> today; 1 -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); 2 -> today.withDayOfMonth(1); 3 -> today.withDayOfYear(1); else -> null }
    val total = totals(state, from, today, now)
    val orderSummary = remember(state.entries, from, today) { summarizeRates(orderRates(state.entries).filter { row ->
        LocalDate.parse(row.order.date).let { it <= today && (from == null || it >= from) }
    }) }
    val shift = state.activeShift
    val accents = LocalLedgerAccents.current
    LazyColumn(Modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading("Your workday", "Earnings, miles, and time in one place.") }
        item { Panel {
            Text("Fuel & maintenance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val gauge = FuelTracking.latestGauge(state, today)
            Text(gauge?.let { "${it.bars}/9 fuel bars · last reported ${it.date}" } ?: "Track fill-ups, MPG, oil changes, and service.")
            OutlinedButton(onClick = onVehicle, modifier = Modifier.fillMaxWidth().testTag("open_vehicle")) { Text("Open fuel & maintenance") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onMessages, modifier = Modifier.weight(1f)) { Text("Saved messages") }
            OutlinedButton(onClick = onRates, modifier = Modifier.weight(1f)) { Text("Dollars per mile") }
        } }
        item { Panel {
            Text(if (shift == null) "Ready when you are" else if (tracking) "Shift in progress" else if (shift.runningSince == null) "Shift paused" else "Tracking interrupted", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (shift == null) {
                Text("Start before your first delivery. Mileage stays with your shift while you use Dispatch.")
                Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start shift") }
            } else {
                Text("${decimal(shift.miles())} mi  ·  ${durationText(shift.durationMillis(now))}", style = MaterialTheme.typography.headlineSmall)
                val health = gpsHealth(shift, tracking, environment, now)
                if (health.needsAttention) Hint("${health.title}\n${health.detail}")
                else {
                    Text(health.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(health.detail, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = if (tracking) onPause else onResume, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (tracking) "Pause" else "Resume") }
                    Button(onClick = onEnd, enabled = !busy, modifier = Modifier.weight(1f)) { Text("End shift") }
                }
                Text("Pause for personal driving. Paused time and mileage are excluded.", style = MaterialTheme.typography.bodySmall)
            }
            problem?.let { Hint(it) }
        } }
        item { WeeklyGoalCard(goal, onGoal) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today", "This week", "This month", "This year", "All history").forEachIndexed { i, label -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(label) }) }
        } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = accents.earnings), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RECORDED EARNINGS", color = accents.earningsDetail, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                    Text(money(total.earned), color = accents.onEarnings, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    Text("Orders + adjustments + manual entries", color = accents.earningsDetail, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = accents.onEarnings.copy(alpha = 0.2f))
                    Text("Paid out  ${money(total.paid)}", color = accents.onEarnings, style = MaterialTheme.typography.titleMedium)
                    Text("Payouts are separate cash movements.", color = accents.earningsDetail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Panel {
            Metric("Earned / order mile", rateText(orderSummary.ratedEarningsCents, orderSummary.miles))
            Text("${money(orderSummary.ratedEarningsCents)} order pay ÷ ${decimal(orderSummary.miles)} Dispatch engaged mi", style = MaterialTheme.typography.bodyMedium)
            Text("Includes linked adjustments on the order's date. ${orderSummary.ratedCount} of ${orderSummary.orderCount} orders have usable miles. Payouts and manual earnings are excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            TextButton(onClick = onRates) { Text("See orders and period totals") }
        } }
        item { Panel {
            Row { Metric("Shift miles", decimal(total.miles), Modifier.weight(1f)); Metric("Shift hours", decimal(total.hours), Modifier.weight(1f)) }
            HorizontalDivider()
            Row {
                Metric("Earned / shift mile", rateText(total.earned, total.miles), Modifier.weight(1f))
                Metric("Earned / shift hour", if (total.hours > 0) money((total.earned / total.hours).toLong()) else "—", Modifier.weight(1f))
            }
            Text("Rates use all recorded earnings and shifts in this period. Import earnings and review shifts to complete the picture.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import earnings") }
            OutlinedButton(onClick = onExpense, modifier = Modifier.weight(1f)) { Text("Add expense") }
        } }
        item { Panel {
            Metric("Recorded expenses", money(total.expenses))
            Metric("Earnings after recorded expenses", money(total.earned - total.expenses))
            Text("Includes only costs you entered. Reimbursements may already be included in Dispatch earnings.", style = MaterialTheme.typography.bodySmall)
        } }
        val reviews = state.shifts.count { it.endedAt != null && !it.reviewed }
        if (reviews > 0) item { TextButton(onClick = onReview) { Text("Review $reviews completed ${if (reviews == 1) "shift" else "shifts"}") } }
        if (state.entries.isEmpty()) item { Hint("Your ledger starts empty. Import Dispatch history or add earnings to begin.") }
    }
}

@Composable private fun EarningsScreen(state: LedgerState, preview: ImportPreview?, busy: Boolean, importText: MutableState<String>, importProgress: String?, importNotice: String?,
    onPreview: (String, Int) -> Unit, onCancelPreview: () -> Unit, onSave: () -> Unit, onOpenFile: () -> Unit,
    onManual: () -> Unit, onEntry: (Entry) -> Unit, onRates: () -> Unit) {
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    var filter by rememberSaveable { mutableStateOf("Earnings") }
    val rates = remember(state.entries) { orderRates(state.entries).associateBy { it.order.id } }
    val listState = rememberLazyListState()
    LaunchedEffect(preview?.parsed?.source?.id) {
        if (preview != null) listState.animateScrollToItem(3)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Earnings", "Import your work. Keep payouts separate.") }
        item { OutlinedButton(onClick = onRates, modifier = Modifier.fillMaxWidth()) { Text("Dollars per mile · orders & periods") } }
        item { Panel {
            Text("Import Dispatch history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Open a Dispatch PDF, screenshot, or text file. You can also share a file to Shift Ledger or paste the Detailed Earnings table. Import one calendar year at a time.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open PDF / screenshot / text") }
            importProgress?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            importNotice?.let { Hint(it) }
            OutlinedTextField(value = importText.value, enabled = importProgress == null, onValueChange = { importText.value = it.take(2_000_000); onCancelPreview() },
                label = { Text("Paste earnings history") }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp), maxLines = 8)
            OutlinedTextField(value = year, onValueChange = { year = it.take(4); onCancelPreview() }, label = { Text("Year for payouts without a year") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onPreview(importText.value, year.toInt()) }, enabled = !busy && importText.value.isNotBlank() && year.toIntOrNull() in 2000..2100) { Text("Preview import") }
            }
        } }
        if (preview != null) {
            item { Panel {
                Text("Check before saving", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${preview.newEntries.size} new · ${preview.duplicates} already saved · ${preview.conflicts.size} conflicts")
                Text("New earnings: ${money(preview.newEntries.filter { it.kind != "PAYOUT" }.sumOf { it.amountCents })}")
                Text("New payouts: ${money(preview.newEntries.filter { it.kind == "PAYOUT" }.sumOf { it.amountCents })}")
                preview.parsed.warnings.forEach { Hint(it) }
                if (preview.conflicts.isNotEmpty()) Hint("Conflicting rows will not be saved. Review their original imports in Data; undo an old import before replacing its records.")
                if (state.entries.any { it.kind == "MANUAL" }) Hint("Manual earnings exist. Remove any that represent the same work before importing, to avoid counting it twice.")
                Button(onClick = onSave, enabled = !busy && preview.newEntries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save ${preview.newEntries.size} new rows") }
                TextButton(onClick = onCancelPreview) { Text("Cancel preview") }
            } }
            items(preview.parsed.entries, key = { "preview-${it.id}" }) { entry ->
                EntryCard(entry, when { preview.newEntries.any { it.id == entry.id } -> "NEW"; preview.conflicts.any { it.id == entry.id } -> "CONFLICT · EXCLUDED"; else -> "ALREADY SAVED" }, onEntry)
            }
        } else {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onManual) { Text("+ Manual earnings") }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Earnings", "Payouts").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
            } }
            val rows = state.entries.filter { if (filter == "Payouts") it.kind == "PAYOUT" else it.kind != "PAYOUT" }
            if (rows.isEmpty()) item { Text("No ${filter.lowercase()} saved yet.", color = MaterialTheme.colorScheme.secondary) }
            items(rows, key = { it.id }) { EntryCard(it, null, onEntry, rates[it.id]) }
        }
    }
}

@Composable private fun EntryCard(entry: Entry, status: String?, onEntry: (Entry) -> Unit, rate: OrderRate? = null) {
    Card(onClick = { onEntry(entry) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.orderId?.let { "Order $it" } ?: if (entry.kind == "MANUAL") "Manual earnings" else "Payout", fontWeight = FontWeight.SemiBold)
                Text(money(entry.amountCents), fontWeight = FontWeight.Bold)
            }
            Text("${entry.date} · ${entry.kind.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (entry.kind == "ORDER") Text("${decimal(entry.engagedMiles ?: 0.0)} engaged mi · ${durationText((entry.engagedMinutes ?: 0) * 60_000L)}", style = MaterialTheme.typography.bodySmall)
            if (entry.kind == "ORDER") Text("${rateText(rate?.earningsCents ?: entry.amountCents, entry.engagedMiles)} / engaged mi${if (rate?.adjustments?.isNotEmpty() == true) " · includes ${money(rate.adjustmentCents)} adjustments" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun ShiftsScreen(state: LedgerState, now: Long, onReview: (Shift) -> Unit, onManual: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Shifts", "Whole-shift mileage and time, with room to correct.") }
        item { OutlinedButton(onClick = onManual) { Text("+ Add a missed shift") } }
        if (state.shifts.isEmpty()) item { Panel { Text("No shifts yet", style = MaterialTheme.typography.titleLarge); Text("Start tracking from Home, or add a past shift using your own mileage record.") } }
        items(state.shifts, key = { it.id }) { shift -> Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shift.date, fontWeight = FontWeight.Bold)
                Text(if (shift.endedAt == null) "OPEN" else if (shift.reviewed) "REVIEWED" else "NEEDS REVIEW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text("${decimal(shift.miles())} mi · ${durationText(shift.durationMillis(now))}", style = MaterialTheme.typography.titleLarge)
            Text("${ShiftTimes.display(shift.startTime())} → ${shift.endTime()?.let { ShiftTimes.display(it) } ?: "Open"}", style = MaterialTheme.typography.bodySmall)
            if (shift.correctedWorkedMillis != null) Text("Time corrected · originally recorded ${durationText(shift.workedMillis)}", style = MaterialTheme.typography.bodySmall)
            Text(if (shift.correctedMiles != null) "Manual / corrected distance · GPS recorded ${decimal(shift.gpsMeters / 1609.344)} mi" else "GPS distance estimate", style = MaterialTheme.typography.bodySmall)
            if (shift.gaps > 0) Hint("${shift.gaps} tracking ${if (shift.gaps == 1) "gap" else "gaps"} flagged. Check mileage against your records.")
            if (shift.note.isNotBlank()) Text(shift.note)
            if (shift.endedAt != null) TextButton(onClick = { onReview(shift) }) { Text(if (shift.reviewed) "Edit times & mileage" else "Review times & mileage") }
        } }
    }
}

@Composable private fun ExpensesScreen(state: LedgerState, onAdd: () -> Unit, onVehicle: () -> Unit, onDelete: (Expense) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Expenses", "A quick record of what work costs you.") }
        item { OutlinedButton(onClick = onVehicle, modifier = Modifier.fillMaxWidth()) { Text("Fuel & maintenance · fill-ups and service") } }
        item { Panel { Metric("All recorded expenses", money(state.expenses.sumOf { it.amountCents })); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add expense") } } }
        item { Text("Log the amount you paid. Dispatch reimbursements may already be included in your imported earnings; check before adding separate income.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        if (state.expenses.isEmpty()) item { Text("Fuel, tolls, parking, maintenance, and other costs will appear here.") }
        items(state.expenses, key = { it.id }) { expense -> Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(expense.category, fontWeight = FontWeight.Bold); Text(money(expense.amountCents), fontWeight = FontWeight.Bold) }
            Text(expense.date, style = MaterialTheme.typography.bodySmall)
            if (expense.note.isNotBlank()) Text(expense.note)
            if (state.fuelStops.any { it.expenseId == expense.id } || state.maintenance.any { it.expenseId == expense.id }) {
                Text("Linked vehicle cost. Edit or delete the source record to update this expense.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onVehicle) { Text("Manage vehicle record") }
            } else TextButton(onClick = { onDelete(expense) }) { Text("Delete") }
        } }
    }
}

@Composable private fun DataScreen(state: LedgerState, busy: Boolean, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit,
    onAppearance: () -> Unit, onMessages: () -> Unit, onAddWidget: () -> Unit, onCsv: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onSource: (ImportSource) -> Unit, onUndo: (ImportSource) -> Unit) {
    val appearance = LocalLedgerAppearance.current
    LazyColumn(Modifier.fillMaxSize().testTag("data_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Your records", "Saved on this phone. Ready to take with you.") }
        item { Panel {
            Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${appearance.palette.label} colors · ${appearance.font.label} text")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = themeMode == mode, onClick = { onThemeChange(mode) }, label = { Text(mode.label) })
                }
            }
            if (appearance.palette == ColorPalette.BLACK) Text("Pure Black keeps backgrounds black in every mode.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAppearance, modifier = Modifier.fillMaxWidth()) { Text("Choose colors & fonts") }
        } }
        item { Panel {
            Text("Backup & export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("A full backup includes shifts, expenses, earnings, saved messages, weekly goal settings, fuel and service records, vehicle settings, and original import text. Save a copy outside this phone.")
            Button(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save full backup") }
            OutlinedButton(onClick = onCsv, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export spreadsheet CSV") }
            TextButton(onClick = onRestore, enabled = !busy && state.activeShift == null) { Text("Restore from backup") }
            if (state.activeShift != null) Text("End your shift before restoring a backup.", style = MaterialTheme.typography.bodySmall)
            Text("Files contain your work records. Choose where to save them. CSV keeps payouts separate and is not a full backup.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("Saved support messages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.messageTemplates.size} reusable messages saved on this phone.")
            OutlinedButton(onClick = onMessages) { Text("Open saved messages") }
        } }
        item { Panel {
            Text("Home-screen widget", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("See today's earnings and shift miles. Open start, pause, resume, and end controls directly from your home screen.")
            Button(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) { Text("Add home-screen widget") }
            Text("The widget shows its last check time. Tap ↻ to refresh whenever you need an update.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("About Shift Ledger", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Personal companion · version ${BuildConfig.VERSION_NAME}")
            Text("No Dispatch sign-in or automatic account sync. PDFs and screenshots are read on this phone. Review extracted text and earnings rows before saving.")
            Text("GPS mileage is an estimate. Review gaps and use odometer readings when available. Shifts are grouped by their start date.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("Import history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.sources.isEmpty()) item { Text("Saved imports and their original text will appear here.") }
        items(state.sources, key = { it.id }) { source -> Panel {
            val date = java.time.Instant.ofEpochMilli(source.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Text("Imported $date", fontWeight = FontWeight.Bold)
            Text("${state.entries.count { it.sourceId == source.id }} saved rows · payout year ${source.payoutYear}")
            Row { TextButton(onClick = { onSource(source) }) { Text("View original") }; TextButton(onClick = { onUndo(source) }) { Text("Undo import") } }
        } }
    }
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
