package com.scott.shiftledger

import android.app.Application
import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as LedgerApplication).repository
    val state = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerState())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<ImportPreview?>(null)
    val restorePreview = MutableStateFlow<LedgerBackup?>(null)
    val importText = mutableStateOf("")
    val importProgress = MutableStateFlow<String?>(null)
    val importNotice = MutableStateFlow<String?>(null)
    val requestedTab = MutableStateFlow<Int?>(null)
    val widgetRequest = MutableStateFlow<String?>(null)
    private var importJob: Job? = null
    private var importedWithOcr = false
    private val settings = application.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selectedTheme = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == settings.getString("theme_mode", null) } ?: ThemeMode.SYSTEM
    )
    val themeMode = selectedTheme.asStateFlow()
    private val selectedPalette = MutableStateFlow(
        ColorPalette.entries.firstOrNull { it.name == settings.getString("color_palette", null) } ?: ColorPalette.FOREST
    )
    private val selectedFont = MutableStateFlow(
        LedgerFont.entries.firstOrNull { it.name == settings.getString("font_style", null) } ?: LedgerFont.MODERN
    )
    val colorPalette = selectedPalette.asStateFlow()
    val fontStyle = selectedFont.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        settings.edit().putString("theme_mode", mode.name).apply()
        selectedTheme.value = mode
    }
    fun setColorPalette(palette: ColorPalette) {
        settings.edit().putString("color_palette", palette.name).apply()
        selectedPalette.value = palette
    }
    fun setFontStyle(font: LedgerFont) {
        settings.edit().putString("font_style", font.name).apply()
        selectedFont.value = font
    }
    fun resetAppearance() {
        settings.edit().putString("theme_mode", ThemeMode.SYSTEM.name).putString("color_palette", ColorPalette.FOREST.name)
            .putString("font_style", LedgerFont.MODERN.name).apply()
        selectedTheme.value = ThemeMode.SYSTEM
        selectedPalette.value = ColorPalette.FOREST
        selectedFont.value = LedgerFont.MODERN
    }

    fun task(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.localizedMessage ?: "Could not complete that action." }
            finally { busy.value = false; getApplication<LedgerApplication>().refreshWidgets() }
        }
    }
    fun parse(text: String, year: Int) = task {
        val result = withContext(Dispatchers.Default) { DispatchParser.parse(text, year) }
        val parsed = if (importedWithOcr) result.copy(warnings = listOf("Read from a PDF or image. Compare each row with the original: recognition may omit rows or misread amounts, dates, and order IDs.") + result.warnings) else result
        preview.value = DispatchParser.preview(parsed, repository.dao.entries())
    }
    fun importDocument(uri: Uri) {
        if (importJob?.isActive == true) { message.value = "A file is already being read. Wait for it to finish."; return }
        requestedTab.value = 1
        importJob = viewModelScope.launch {
            importProgress.value = "Opening document…"
            try {
                val result = DocumentImporter(getApplication()).extract(uri) { importProgress.value = it }
                importText.value = result.text
                importNotice.value = result.description
                importedWithOcr = result.usesOcr
                preview.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = "Could not read that file. ${e.localizedMessage ?: "Try a PDF, screenshot, or text file."}" }
            finally { importProgress.value = null }
        }
    }
    fun handleIntent(intent: Intent) {
        if (intent.action == LedgerWidget.ACTION) {
            val action = intent.getStringExtra(LedgerWidget.EXTRA_ACTION)
            if (action in setOf("home", "start", "resume", "pause", "end", "import")) {
                requestedTab.value = if (action == "import") 1 else 0
                if (action !in setOf("home", "import")) widgetRequest.value = action
            }
        } else if (intent.action == Intent.ACTION_SEND) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) importDocument(uri)
            else intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                if (it.length > 2_000_000) { message.value = "Shared text is too large."; return }
                if (importJob?.isActive == true) { message.value = "Wait for the current file import to finish."; return }
                importText.value = it; preview.value = null; importedWithOcr = false
                importNotice.value = "Shared text loaded. Check the payout year before previewing."
                requestedTab.value = 1
            }
        }
    }
    fun saveImport() = task {
        preview.value?.let {
            val count = repository.commitImport(it.parsed)
            message.value = "Saved $count new rows. Payouts remain separate from earnings."
            preview.value = null
        }
    }
    fun start(odometer: Double?) = task {
        repository.startShift(odometer)
        launchTracking()
    }
    fun resume() = task {
        if (TrackingService.running.value) return@task
        repository.resumeShift()
        launchTracking()
    }
    private suspend fun launchTracking() {
        try { ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), TrackingService::class.java)) }
        catch (e: Exception) {
            repository.markGap(); repository.pauseShift()
            throw IllegalStateException("Tracking could not start. The shift is paused; try Resume. ${e.localizedMessage}")
        }
    }
    fun pause() = task {
        repository.pauseShift()
        getApplication<Application>().stopService(Intent(getApplication(), TrackingService::class.java))
    }
    fun end() = task {
        val shift = repository.dao.activeShift()
        if (shift?.runningSince != null && !TrackingService.gapRecorded && (!TrackingService.running.value || shift.lastFixAt == null || System.currentTimeMillis() - shift.lastFixAt > GPS_STALE_MILLIS)) repository.markGap()
        repository.endShift()
        getApplication<Application>().stopService(Intent(getApplication(), TrackingService::class.java))
        message.value = "Shift ended. Review mileage and any missing expenses in Shifts."
    }
    fun addExpense(date: String, amount: String, category: String, note: String) = task {
        require(validDate(date)) { "Use a date like 2026-10-04." }
        val value = cents(amount)
        require(value in 1..1_000_000_000_000) { "Enter an expense greater than zero." }
        repository.dao.insertExpenses(listOf(Expense(UUID.randomUUID().toString(), date, value, category, note)))
        message.value = "Expense saved."
    }
    fun addManualEarning(date: String, amount: String, note: String) = task {
        require(validDate(date)) { "Use a date like 2026-10-04." }
        val value = cents(amount)
        require(value in -1_000_000_000_000..1_000_000_000_000 && value != 0L) { "Enter a nonzero amount." }
        repository.dao.insertEntries(listOf(Entry(UUID.randomUUID().toString(), "MANUAL", date, value, raw = note)))
        message.value = "Manual earnings saved. Remove this row before importing the same earnings from Dispatch."
    }
    fun manualShift(date: String, miles: String, hours: String, note: String) = task {
        val day = LocalDate.parse(date)
        require(day <= LocalDate.now()) { "Choose today or an earlier date." }
        val distance = miles.toDouble()
        val time = hours.toDouble()
        require(distance.isFinite() && distance in 0.0..100_000.0 && time.isFinite() && time > 0 && time <= 24) { "Check miles and hours (up to 24)." }
        val midnight = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val duration = (time * 3_600_000).toLong()
        val end = minOf(day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), System.currentTimeMillis())
        require(end - duration >= midnight) { "Hours exceed the elapsed time on that date." }
        repository.dao.insertShifts(listOf(Shift(UUID.randomUUID().toString(), date, end - duration, endedAt = end,
            workedMillis = duration, correctedMiles = distance, reviewed = true, note = "Manual: $note")))
        message.value = "Manual shift saved."
    }
}
