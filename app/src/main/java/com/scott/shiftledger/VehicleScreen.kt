package com.scott.shiftledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.UUID

@Composable internal fun VehicleScreen(
    state: LedgerState, busy: Boolean,
    onFuel: (FuelStop, () -> Unit) -> Unit, onDeleteFuel: (String, () -> Unit) -> Unit,
    onReading: (FuelReading, () -> Unit) -> Unit, onDeleteReading: (String, () -> Unit) -> Unit,
    onVehicle: (VehicleSettings, () -> Unit) -> Unit,
    onService: (MaintenanceRecord, () -> Unit) -> Unit, onDeleteService: (String, () -> Unit) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf("Fuel") }
    var period by rememberSaveable { mutableStateOf("All history") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val gauge = remember(state.fuelStops, state.fuelReadings, today) { FuelTracking.latestGauge(state, today) }
    val from = when (period) { "This month" -> today.withDayOfMonth(1); "This week" -> WeeklyGoals.monday(today); else -> null }
    val stops = remember(state.fuelStops, today) { FuelTracking.ordered(state.fuelStops).filter { LocalDate.parse(it.date) <= today } }
    val selected = stops.filter { from == null || LocalDate.parse(it.date) >= from }
    val cycles = remember(stops, from) { FuelTracking.cycles(stops).filter { from == null || LocalDate.parse(it.end.date) >= from } }
    val miles = cycles.sumOf { it.miles }
    val gallons = cycles.sumOf { it.gallons }
    val cost = selected.sumOf { it.amountCents }
    fun open(kind: String, id: String? = null) { editingId = id; dialog = kind }
    fun delete(kind: String, id: String) { deletingKind = kind; deletingId = id }
    LazyColumn(Modifier.fillMaxSize().testTag("vehicle_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Your van", "Fuel, oil changes, and the cost of keeping moving.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Fuel", "Maintenance").forEach { label -> FilterChip(section == label, onClick = { section = label }, label = { Text(label) }) }
            Spacer(Modifier.weight(1f)); TextButton(onClick = { open("vehicle") }) { Text("Vehicle") }
        } }
        if (section == "Fuel") {
            item { Panel {
                Text(state.vehicle.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text(gauge?.let { "${it.bars} / 9 bars" } ?: "Gauge not recorded", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${decimal(state.vehicle.tankGallons)} US gal tank", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text("FUEL", style = MaterialTheme.typography.labelLarge)
                }
                FuelGauge(gauge?.bars)
                gauge?.let {
                    Text("Last reported ${it.date}${it.odometer?.let { odo -> " · ${decimal(odo)} mi" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Text("Estimated fuel remaining: about ${decimal(state.vehicle.tankGallons * it.bars / 9.0)} gal", style = MaterialTheme.typography.bodyMedium)
                    if (it.bars <= 2) Hint("Low reported fuel. Check your dashboard before your next trip.")
                }
                if (gauge != null && gauge.bars > 0 && gallons > 0) Text("Estimated range at this reading: about ${kotlin.math.round(state.vehicle.tankGallons * gauge.bars / 9.0 * miles / gallons).toInt()} mi", style = MaterialTheme.typography.bodySmall)
                Text("Manual dashboard reading. Gallons and range are coarse estimates because dashboard bars may not be equal.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open("fuel") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("+ Fill-up") }
                    OutlinedButton(onClick = { open("reading") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Update bars") }
                }
            } }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("This week", "This month", "All history").forEach { label -> FilterChip(period == label, onClick = { period = label }, label = { Text(label) }) }
            } }
            item { Panel {
                Text("Fuel analysis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row {
                    VehicleMetric("Fuel purchased", "${decimal(selected.sumOf { it.gallons })} gal", Modifier.weight(1f))
                    VehicleMetric("Total paid", money(cost), Modifier.weight(1f))
                }
                Row {
                    VehicleMetric("Average pump price", if (selected.isNotEmpty()) "${fuelUnitPrice(cost, selected.sumOf { it.gallons })}/gal" else "—", Modifier.weight(1f))
                    VehicleMetric("Weighted MPG", if (gallons > 0) decimal(miles / gallons) else "—", Modifier.weight(1f))
                }
                Row {
                    VehicleMetric("Fuel cost / mile", if (miles > 0) "${fuelUnitPrice(cycles.sumOf { it.costCents }, miles)}/mi" else "—", Modifier.weight(1f))
                    VehicleMetric("Measured distance", "${decimal(miles)} mi", Modifier.weight(1f))
                }
                Text("MPG uses complete full-to-full intervals, with partial fills included. It measures all vehicle miles, including personal driving. Periods select intervals by their ending fill-up date, so measured distance and cost may begin before the selected period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (cycles.isEmpty()) Hint("Record a full tank as your starting point, then your next full fill-up to see measured MPG. Log every partial fill in between. Mark missed fill-ups to avoid overstating MPG.")
                Text("${money(selected.filter { it.countAsExpense }.sumOf { it.amountCents })} linked to Costs. Fuel records and their expenses are counted once. Existing fuel expenses stay separate; remove any duplicate you already entered.", style = MaterialTheme.typography.bodySmall)
            } }
            if (cycles.isNotEmpty()) item { Panel {
                Text("Recent tank efficiency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val recent = cycles.takeLast(6)
                val max = recent.maxOf { it.mpg }.coerceAtLeast(1.0)
                recent.forEach { cycle ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(cycle.end.date, style = MaterialTheme.typography.bodySmall); Text("${decimal(cycle.mpg)} MPG", fontWeight = FontWeight.SemiBold) }
                    LinearProgressIndicator(progress = { (cycle.mpg / max).toFloat().coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                    Text("${decimal(cycle.miles)} mi · ${decimal(cycle.gallons)} gal · ${cycle.stops.size} fill${if (cycle.stops.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            } }
            item { Text("Fill-up history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (selected.isEmpty()) item { Text("No fill-ups in this period. Add your next stop at the pump.") }
            items(selected.asReversed(), key = { "fill-${it.id}" }) { stop -> Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stop.date, fontWeight = FontWeight.Bold); Text(money(stop.amountCents), fontWeight = FontWeight.Bold) }
                Text("${stop.station.ifBlank { "Station not recorded" }} · ${decimal(stop.gallons)} gal · ${fuelUnitPrice(stop.amountCents, stop.gallons)}/gal")
                Text("${decimal(stop.odometer)} mi · ${if (stop.fullTank) "Filled to full" else "Partial fill"}")
                if (stop.barsBefore != null || stop.barsAfter != null) Text("Bars: ${stop.barsBefore ?: "—"} → ${if (stop.fullTank) 9 else stop.barsAfter ?: "—"}", style = MaterialTheme.typography.bodySmall)
                if (stop.missedFill) Hint("Missing fill-ups before this stop. This interval is excluded from MPG.")
                cycles.firstOrNull { it.end.id == stop.id }?.let { Text("${decimal(it.mpg)} MPG since ${it.start.date}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                if (stop.note.isNotBlank()) Text(stop.note)
                Row { TextButton(onClick = { open("fuel", stop.id) }, enabled = !busy) { Text("Edit fill-up") }; TextButton(onClick = { delete("fuel", stop.id) }, enabled = !busy) { Text("Delete fill-up") } }
            } }
            if (state.fuelReadings.isNotEmpty()) item { Text("Gauge check-ins", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(state.fuelReadings.filter { from == null || LocalDate.parse(it.date) >= from }, key = { "reading-${it.id}" }) { reading -> Panel {
                Text("${reading.date} · ${reading.bars}/9 bars", fontWeight = FontWeight.SemiBold)
                reading.odometer?.let { Text("${decimal(it)} mi") }
                Row { TextButton(onClick = { open("reading", reading.id) }, enabled = !busy) { Text("Edit reading") }; TextButton(onClick = { delete("reading", reading.id) }, enabled = !busy) { Text("Delete reading") } }
            } }
        } else {
            item { Panel {
                Text("Service log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Record oil changes, tires, brakes, and repairs. Set the next service date or mileage from your shop's recommendation.")
                Button(onClick = { open("service") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("+ Add service") }
            } }
            if (state.maintenance.isEmpty()) item { Hint("Add your last oil change to start a service history.") }
            val latestServices = state.maintenance.groupBy { it.service.trim().lowercase(java.util.Locale.US) }.mapValues { (_, rows) -> rows.maxWith(compareBy<MaintenanceRecord> { it.date }.thenBy { it.odometer }) }
            val knownOdometer = (state.fuelStops.map { it.odometer } + state.fuelReadings.mapNotNull { it.odometer } + state.maintenance.map { it.odometer }).maxOrNull()
            items(state.maintenance, key = { "service-${it.id}" }) { record -> Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(record.service, fontWeight = FontWeight.Bold); Text(money(record.amountCents)) }
                Text("${record.date} · ${decimal(record.odometer)} mi")
                if (record.shop.isNotBlank()) Text(record.shop)
                if (record.note.isNotBlank()) Text(record.note)
                record.nextOdometer?.let { Text("Next at ${decimal(it)} mi", style = MaterialTheme.typography.bodySmall) }
                record.nextDate?.let { Text("Next by $it", style = MaterialTheme.typography.bodySmall) }
                if (latestServices[record.service.trim().lowercase(java.util.Locale.US)]?.id == record.id &&
                    ((record.nextDate != null && LocalDate.parse(record.nextDate) <= today) || (record.nextOdometer != null && knownOdometer != null && knownOdometer >= record.nextOdometer))) Hint("Service due based on your saved schedule and readings.")
                Row { TextButton(onClick = { open("service", record.id) }, enabled = !busy) { Text("Edit service") }; TextButton(onClick = { delete("service", record.id) }, enabled = !busy) { Text("Delete service") } }
            } }
        }
    }
    val close = { dialog = null; editingId = null }
    when (dialog) {
        "fuel" -> FuelForm(state.fuelStops.firstOrNull { it.id == editingId }, busy, close, onFuel)
        "reading" -> ReadingForm(state.fuelReadings.firstOrNull { it.id == editingId }, gauge?.bars, busy, close, onReading)
        "vehicle" -> VehicleForm(state.vehicle, busy, close, onVehicle)
        "service" -> ServiceForm(state.maintenance.firstOrNull { it.id == editingId }, busy, close, onService)
    }
    deletingId?.let { id ->
        val done = { deletingId = null; deletingKind = null }
        val explanation = when (deletingKind) {
            "fuel" -> "Delete this fill-up and its linked expense? MPG will be recalculated. The next recorded fill-up will be marked as having missing fuel so this gap does not inflate MPG. You can clear that flag if you are removing a duplicate."
            "service" -> "Delete this service record and its linked expense?"
            else -> "Delete this gauge reading? The previous reported level will be shown."
        }
        AlertDialog(onDismissRequest = { if (!busy) done() }, title = { Text("Delete record?") }, text = { Text(explanation) },
            confirmButton = { TextButton(enabled = !busy, onClick = { when (deletingKind) { "fuel" -> onDeleteFuel(id, done); "service" -> onDeleteService(id, done); else -> onDeleteReading(id, done) } }) { Text("Delete") } },
            dismissButton = { TextButton(enabled = !busy, onClick = done) { Text("Keep") } })
    }
}

@Composable internal fun FuelGauge(bars: Int?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val lit = if (bars != null && bars <= 2) colors.error else if (bars == 3) colors.tertiary else colors.primary
    Column(modifier.semantics { contentDescription = bars?.let { "Fuel gauge: $it of 9 bars" } ?: "Fuel gauge: no reading" }) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val unit = size.width / 15f
            val pumpX = unit * .25f
            val pumpY = size.height * .29f
            val stroke = Stroke(width = 2.5.dp.toPx())
            drawRoundRect(colors.onSurface, Offset(pumpX, pumpY), Size(unit * 1.2f, size.height * .44f), CornerRadius(3.dp.toPx()), style = stroke)
            drawRect(colors.onSurface, Offset(pumpX + unit * .2f, pumpY + size.height * .07f), Size(unit * .8f, size.height * .14f), style = stroke)
            drawLine(colors.onSurface, Offset(pumpX - unit * .2f, pumpY + size.height * .48f), Offset(pumpX + unit * 1.4f, pumpY + size.height * .48f), 2.5.dp.toPx())
            val hose = Path().apply { moveTo(pumpX + unit * 1.2f, pumpY + size.height * .17f); cubicTo(pumpX + unit * 2.2f, pumpY + size.height * .05f, pumpX + unit * 1.6f, pumpY + size.height * .6f, pumpX + unit * 2f, pumpY + size.height * .4f); lineTo(pumpX + unit * 2f, pumpY); lineTo(pumpX + unit * 1.6f, pumpY - size.height * .08f) }
            drawPath(hose, colors.onSurface, style = stroke)
            for (i in 0..8) {
                val x = unit * (3.5f + i * 1.2f)
                val height = size.height * (.35f + i * .052f)
                val y = size.height * .87f - height
                val path = Path().apply { moveTo(x, y + size.height * .04f); lineTo(x + unit * .87f, y); lineTo(x + unit * .87f, size.height * .87f); lineTo(x, size.height * .87f); close() }
                drawPath(path, if (bars != null && i < bars) lit else colors.outlineVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 75.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("E", fontWeight = FontWeight.Bold); Text("½", color = colors.secondary); Text("F", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun VehicleMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun VehicleField(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false) {
    OutlinedTextField(value, onValueChange = change, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun VehicleCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, change, Modifier.semantics { contentDescription = label }); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
}
@Composable private fun VehicleDialog(title: String, busy: Boolean, dismiss: () -> Unit, valid: Boolean, error: String?, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 560.dp).heightIn(max = maxHeight), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                    if (!valid && error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") }
                    TextButton(enabled = valid && !busy, onClick = save) { Text("Save") }
                }
            }
        }
    }
}

@Composable private fun FuelForm(existing: FuelStop?, busy: Boolean, dismiss: () -> Unit, save: (FuelStop, () -> Unit) -> Unit) {
    val key = existing?.id ?: "new"
    var date by rememberSaveable(key) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var odo by rememberSaveable(key) { mutableStateOf(existing?.odometer?.toString() ?: "") }
    var gallons by rememberSaveable(key) { mutableStateOf(existing?.gallons?.toString() ?: "") }
    var amount by rememberSaveable(key) { mutableStateOf(existing?.amountCents?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var station by rememberSaveable(key) { mutableStateOf(existing?.station ?: "") }
    var note by rememberSaveable(key) { mutableStateOf(existing?.note ?: "") }
    var before by rememberSaveable(key) { mutableStateOf(existing?.barsBefore?.toString() ?: "") }
    var after by rememberSaveable(key) { mutableStateOf(existing?.barsAfter?.toString() ?: "") }
    var full by rememberSaveable(key) { mutableStateOf(existing?.fullTank ?: false) }
    var missed by rememberSaveable(key) { mutableStateOf(existing?.missedFill ?: false) }
    var expense by rememberSaveable(key) { mutableStateOf(existing?.countAsExpense ?: true) }
    val id = rememberSaveable(key) { existing?.id ?: UUID.randomUUID().toString() }
    val timestamp = rememberSaveable(key) { existing?.createdAt ?: System.currentTimeMillis() }
    val candidate = runCatching {
        val row = FuelStop(id, date, odo.toDouble(), gallons.toDouble(), cents(amount), full, missed, station.trim(), note.trim(), before.takeIf { it.isNotBlank() }?.toInt(), if (full) 9 else after.takeIf { it.isNotBlank() }?.toInt(), expense, timestamp)
        FuelTracking.validate(row); require(LocalDate.parse(date) <= LocalDate.now()) { "Choose today or an earlier date." }; row
    }
    VehicleDialog(if (existing == null) "Record fill-up" else "Edit fill-up", busy, dismiss, candidate.isSuccess,
        if (odo.isNotBlank() && gallons.isNotBlank() && amount.isNotBlank()) candidate.exceptionOrNull()?.localizedMessage ?: "Check all fields." else null,
        save = { candidate.getOrNull()?.let { save(it, dismiss) } }) {
        VehicleField("Date (YYYY-MM-DD)", date, { date = it.take(10) })
        VehicleField("Odometer (mi)", odo, { odo = it.take(14) }, true)
        VehicleField("Gallons added (US)", gallons, { gallons = it.take(12) }, true)
        VehicleField("Total paid ($)", amount, { amount = it.take(14) }, true)
        val g = gallons.toDoubleOrNull(); val paid = runCatching { cents(amount) }.getOrNull()
        if (g != null && g.isFinite() && g > 0 && paid != null && paid > 0) Text("Pump price: ${fuelUnitPrice(paid, g)}/gal", color = MaterialTheme.colorScheme.primary)
        VehicleCheck("Filled to full (pump shut off)", full, { full = it })
        VehicleField("Bars before (0–9, optional)", before, { before = it.take(1) }, true)
        if (!full) VehicleField("Bars after (0–9, optional)", after, { after = it.take(1) }, true)
        VehicleField("Station (optional)", station, { station = it.take(120) })
        VehicleCheck("I missed logging a fill-up before this stop", missed, { missed = it })
        VehicleCheck("Include this purchase in Costs", expense, { expense = it })
        Text("Saving also updates the linked expense. If you already entered this purchase in Costs, delete the old expense to avoid a duplicate.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
    }
}

@Composable private fun ReadingForm(existing: FuelReading?, lastBars: Int?, busy: Boolean, dismiss: () -> Unit, save: (FuelReading, () -> Unit) -> Unit) {
    val key = existing?.id ?: "new"
    var bars by rememberSaveable(key) { mutableIntStateOf(existing?.bars ?: lastBars ?: 9) }
    var date by rememberSaveable(key) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var odo by rememberSaveable(key) { mutableStateOf(existing?.odometer?.toString() ?: "") }
    val id = rememberSaveable(key) { existing?.id ?: UUID.randomUUID().toString() }
    val timestamp = rememberSaveable(key) { existing?.createdAt ?: System.currentTimeMillis() }
    val candidate = runCatching { FuelReading(id, date, bars, odo.takeIf { it.isNotBlank() }?.toDouble(), timestamp).also { FuelTracking.validate(it); require(LocalDate.parse(date) <= LocalDate.now()) { "Choose today or an earlier date." } } }
    VehicleDialog("Update fuel bars", busy, dismiss, candidate.isSuccess, candidate.exceptionOrNull()?.localizedMessage,
        save = { candidate.getOrNull()?.let { save(it, dismiss) } }) {
        FuelGauge(bars)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { bars-- }, enabled = bars > 0, modifier = Modifier.semantics { contentDescription = "Fewer fuel bars" }) { Text("−") }
            Text("$bars / 9 bars", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { bars++ }, enabled = bars < 9, modifier = Modifier.semantics { contentDescription = "More fuel bars" }) { Text("+") }
        }
        Text("Match the bars on your dashboard. This does not record a fuel purchase.", style = MaterialTheme.typography.bodySmall)
        VehicleField("Date (YYYY-MM-DD)", date, { date = it.take(10) })
        VehicleField("Odometer (mi, optional)", odo, { odo = it.take(14) }, true)
    }
}

@Composable private fun VehicleForm(existing: VehicleSettings, busy: Boolean, dismiss: () -> Unit, save: (VehicleSettings, () -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing.name) }
    var capacity by rememberSaveable { mutableStateOf(existing.tankGallons.toString()) }
    val candidate = runCatching { existing.copy(name = name.trim(), tankGallons = capacity.toDouble()).also(FuelTracking::validate) }
    VehicleDialog("Vehicle settings", busy, dismiss, candidate.isSuccess, if (capacity.isNotBlank()) candidate.exceptionOrNull()?.localizedMessage else null,
        save = { candidate.getOrNull()?.let { save(it, dismiss) } }) {
        VehicleField("Vehicle name", name, { name = it.take(100) })
        VehicleField("Tank capacity (US gal)", capacity, { capacity = it.take(10) }, true)
        Text("The 2023 Ram ProMaster owner's manual lists approximately 24 US gallons (90 L). Change this if your vehicle has a different tank. Capacity changes affect gauge estimates, not measured MPG.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ServiceForm(existing: MaintenanceRecord?, busy: Boolean, dismiss: () -> Unit, save: (MaintenanceRecord, () -> Unit) -> Unit) {
    val key = existing?.id ?: "new"
    var service by rememberSaveable(key) { mutableStateOf(existing?.service ?: "Oil change") }
    var date by rememberSaveable(key) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var odo by rememberSaveable(key) { mutableStateOf(existing?.odometer?.toString() ?: "") }
    var amount by rememberSaveable(key) { mutableStateOf(existing?.amountCents?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var shop by rememberSaveable(key) { mutableStateOf(existing?.shop ?: "") }
    var note by rememberSaveable(key) { mutableStateOf(existing?.note ?: "") }
    var nextOdo by rememberSaveable(key) { mutableStateOf(existing?.nextOdometer?.toString() ?: "") }
    var nextDate by rememberSaveable(key) { mutableStateOf(existing?.nextDate ?: "") }
    var expense by rememberSaveable(key) { mutableStateOf(existing?.countAsExpense ?: true) }
    val id = rememberSaveable(key) { existing?.id ?: UUID.randomUUID().toString() }
    val candidate = runCatching {
        MaintenanceRecord(id, date, service.trim(), odo.toDouble(), if (amount.isBlank()) 0 else cents(amount), shop.trim(), note.trim(), nextOdo.takeIf { it.isNotBlank() }?.toDouble(), nextDate.takeIf { it.isNotBlank() }, expense).also { FuelTracking.validate(it); require(LocalDate.parse(date) <= LocalDate.now()) { "Choose today or an earlier date." } }
    }
    VehicleDialog(if (existing == null) "Record service" else "Edit service", busy, dismiss, candidate.isSuccess, if (odo.isNotBlank()) candidate.exceptionOrNull()?.localizedMessage else null,
        save = { candidate.getOrNull()?.let { save(it, dismiss) } }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Oil change", "Tires", "Brakes", "Repair").forEach { FilterChip(service == it, onClick = { service = it }, label = { Text(it) }) } }
        VehicleField("Service", service, { service = it.take(100) })
        VehicleField("Date (YYYY-MM-DD)", date, { date = it.take(10) })
        VehicleField("Odometer (mi)", odo, { odo = it.take(14) }, true)
        VehicleField("Total paid ($, optional)", amount, { amount = it.take(14) }, true)
        VehicleField("Shop (optional)", shop, { shop = it.take(120) })
        VehicleField("Next service odometer (optional)", nextOdo, { nextOdo = it.take(14) }, true)
        VehicleField("Next service date (optional)", nextDate, { nextDate = it.take(10) })
        VehicleCheck("Include this service in Costs", expense, { expense = it })
        OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Notes / oil type / parts") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        Text("Due status follows the schedule you enter. Check your manual or service shop for the right interval.", style = MaterialTheme.typography.bodySmall)
    }
}
