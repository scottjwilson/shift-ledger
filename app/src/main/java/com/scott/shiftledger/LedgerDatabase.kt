package com.scott.shiftledger

import android.app.Application
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.*
import java.time.LocalDate
import java.util.UUID

@Dao
interface LedgerDao {
    @Query("SELECT * FROM fuel_stops ORDER BY date DESC, odometer DESC, createdAt DESC") fun watchFuel(): Flow<List<FuelStop>>
    @Query("SELECT * FROM fuel_stops") suspend fun fuel(): List<FuelStop>
    @Upsert suspend fun saveFuel(stop: FuelStop)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFuel(stops: List<FuelStop>)
    @Query("DELETE FROM fuel_stops WHERE id = :id") suspend fun deleteFuel(id: String)
    @Query("SELECT * FROM fuel_readings ORDER BY date DESC, createdAt DESC") fun watchReadings(): Flow<List<FuelReading>>
    @Query("SELECT * FROM fuel_readings") suspend fun readings(): List<FuelReading>
    @Upsert suspend fun saveReading(reading: FuelReading)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReadings(readings: List<FuelReading>)
    @Query("DELETE FROM fuel_readings WHERE id = :id") suspend fun deleteReading(id: String)
    @Query("SELECT * FROM vehicle_settings") fun watchVehicles(): Flow<List<VehicleSettings>>
    @Query("SELECT * FROM vehicle_settings") suspend fun vehicles(): List<VehicleSettings>
    @Upsert suspend fun saveVehicle(vehicle: VehicleSettings)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertVehicles(vehicles: List<VehicleSettings>)
    @Query("SELECT * FROM maintenance ORDER BY date DESC, odometer DESC") fun watchMaintenance(): Flow<List<MaintenanceRecord>>
    @Query("SELECT * FROM maintenance") suspend fun maintenance(): List<MaintenanceRecord>
    @Upsert suspend fun saveMaintenance(record: MaintenanceRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMaintenance(records: List<MaintenanceRecord>)
    @Query("DELETE FROM maintenance WHERE id = :id") suspend fun deleteMaintenance(id: String)
    @Upsert suspend fun saveExpense(expense: Expense)
    @Query("SELECT * FROM entries ORDER BY date DESC, occurredAt DESC") fun watchEntries(): Flow<List<Entry>>
    @Query("SELECT * FROM shifts ORDER BY COALESCE(correctedStartedAt, startedAt) DESC") fun watchShifts(): Flow<List<Shift>>
    @Query("SELECT * FROM expenses ORDER BY date DESC") fun watchExpenses(): Flow<List<Expense>>
    @Query("SELECT * FROM imports ORDER BY createdAt DESC") fun watchSources(): Flow<List<ImportSource>>
    @Query("SELECT * FROM message_templates ORDER BY updatedAt DESC, title ASC") fun watchTemplates(): Flow<List<MessageTemplate>>
    @Query("SELECT * FROM message_templates ORDER BY updatedAt DESC, title ASC") suspend fun templates(): List<MessageTemplate>
    @Query("SELECT * FROM weekly_goal_plans ORDER BY startWeek DESC") fun watchGoals(): Flow<List<WeeklyGoalPlan>>
    @Query("SELECT * FROM weekly_goal_plans ORDER BY startWeek DESC") suspend fun goals(): List<WeeklyGoalPlan>
    @Upsert suspend fun saveGoal(plan: WeeklyGoalPlan)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertGoals(plans: List<WeeklyGoalPlan>)
    @Query("SELECT * FROM entries") suspend fun entries(): List<Entry>
    @Query("SELECT * FROM shifts") suspend fun shifts(): List<Shift>
    @Query("SELECT * FROM expenses") suspend fun expenses(): List<Expense>
    @Query("SELECT * FROM imports") suspend fun sources(): List<ImportSource>
    @Query("SELECT * FROM shifts WHERE endedAt IS NULL LIMIT 1") suspend fun activeShift(): Shift?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEntries(rows: List<Entry>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSources(rows: List<ImportSource>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertShifts(rows: List<Shift>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExpenses(rows: List<Expense>)
    @Upsert suspend fun saveShift(shift: Shift)
    @Upsert suspend fun saveTemplate(template: MessageTemplate)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTemplates(rows: List<MessageTemplate>)
    @Query("DELETE FROM message_templates WHERE id = :id") suspend fun deleteTemplate(id: String)
    @Query("DELETE FROM expenses WHERE id = :id") suspend fun deleteExpense(id: String)
    @Query("DELETE FROM entries WHERE sourceId = :id") suspend fun deleteSourceEntries(id: String)
    @Query("DELETE FROM imports WHERE id = :id") suspend fun deleteSource(id: String)
    @Query("DELETE FROM entries WHERE id = :id AND kind = 'MANUAL'") suspend fun deleteManual(id: String)
}

@Database(entities = [Entry::class, Expense::class, Shift::class, ImportSource::class, MessageTemplate::class, WeeklyGoalPlan::class, FuelStop::class, FuelReading::class, VehicleSettings::class, MaintenanceRecord::class], version = 5, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shifts ADD COLUMN correctedStartedAt INTEGER")
                db.execSQL("ALTER TABLE shifts ADD COLUMN correctedEndedAt INTEGER")
                db.execSQL("ALTER TABLE shifts ADD COLUMN correctedWorkedMillis INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS message_templates (id TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                SupportMessages.seed(db)
            }
        }
        val SEED_MESSAGES = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { SupportMessages.seed(db) }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS fuel_stops (id TEXT NOT NULL, date TEXT NOT NULL, odometer REAL NOT NULL, gallons REAL NOT NULL, amountCents INTEGER NOT NULL, fullTank INTEGER NOT NULL, missedFill INTEGER NOT NULL, station TEXT NOT NULL, note TEXT NOT NULL, barsBefore INTEGER, barsAfter INTEGER, countAsExpense INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS fuel_readings (id TEXT NOT NULL, date TEXT NOT NULL, bars INTEGER NOT NULL, odometer REAL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS vehicle_settings (id TEXT NOT NULL, name TEXT NOT NULL, tankGallons REAL NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS maintenance (id TEXT NOT NULL, date TEXT NOT NULL, service TEXT NOT NULL, odometer REAL NOT NULL, amountCents INTEGER NOT NULL, shop TEXT NOT NULL, note TEXT NOT NULL, nextOdometer REAL, nextDate TEXT, countAsExpense INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS weekly_goal_plans (startWeek TEXT NOT NULL, targetCents INTEGER NOT NULL, PRIMARY KEY(startWeek))")
            }
        }
    }
}

class LedgerRepository(val database: LedgerDatabase) {
    val dao = database.dao()
    val state = combine(dao.watchEntries(), dao.watchShifts(), dao.watchExpenses(), dao.watchSources(), dao.watchTemplates()) { e, s, x, i, t -> LedgerState(e, s, x, i, t) }
        .combine(dao.watchGoals()) { ledger, goals -> ledger.copy(goalPlans = goals) }
        .combine(dao.watchFuel()) { ledger, fuel -> ledger.copy(fuelStops = fuel) }
        .combine(dao.watchReadings()) { ledger, readings -> ledger.copy(fuelReadings = readings) }
        .combine(dao.watchVehicles()) { ledger, vehicles -> ledger.copy(vehicles = vehicles) }
        .combine(dao.watchMaintenance()) { ledger, maintenance -> ledger.copy(maintenance = maintenance) }

    suspend fun snapshot(): LedgerState = database.withTransaction { LedgerState(dao.entries(), dao.shifts(), dao.expenses(), dao.sources(), dao.templates(), dao.goals(), dao.fuel(), dao.readings(), dao.vehicles(), dao.maintenance()) }
    suspend fun saveFuel(stop: FuelStop) = database.withTransaction {
        FuelTracking.validate(stop)
        require(LocalDate.parse(stop.date) <= LocalDate.now()) { "Choose today or an earlier date." }
        FuelTracking.validateSequence(dao.fuel().filterNot { it.id == stop.id } + stop)
        dao.saveFuel(stop)
        if (stop.countAsExpense) dao.saveExpense(FuelTracking.expense(stop)) else dao.deleteExpense(stop.expenseId)
    }
    suspend fun deleteFuel(id: String) = database.withTransaction {
        val ordered = FuelTracking.ordered(dao.fuel())
        val index = ordered.indexOfFirst { it.id == id }
        if (index >= 0) ordered.getOrNull(index + 1)?.let { dao.saveFuel(it.copy(missedFill = true)) }
        dao.deleteFuel(id); dao.deleteExpense("fuel:$id")
    }
    suspend fun saveReading(reading: FuelReading) {
        FuelTracking.validate(reading)
        require(LocalDate.parse(reading.date) <= LocalDate.now()) { "Choose today or an earlier date." }
        dao.saveReading(reading)
    }
    suspend fun saveVehicle(vehicle: VehicleSettings) { FuelTracking.validate(vehicle); dao.saveVehicle(vehicle) }
    suspend fun saveMaintenance(record: MaintenanceRecord) = database.withTransaction {
        FuelTracking.validate(record)
        require(LocalDate.parse(record.date) <= LocalDate.now()) { "Choose today or an earlier date." }
        dao.saveMaintenance(record)
        if (record.countAsExpense && record.amountCents > 0) dao.saveExpense(FuelTracking.expense(record)) else dao.deleteExpense(record.expenseId)
    }
    suspend fun deleteMaintenance(id: String) = database.withTransaction { dao.deleteMaintenance(id); dao.deleteExpense("service:$id") }
    suspend fun saveGoal(amountCents: Long, today: LocalDate = LocalDate.now()) {
        val plan = WeeklyGoalPlan(WeeklyGoals.monday(today).toString(), amountCents)
        WeeklyGoals.validate(plan)
        dao.saveGoal(plan)
    }
    suspend fun saveTemplate(id: String, title: String, body: String, now: Long = System.currentTimeMillis()) {
        SupportMessages.validate(title, body)
        require(id.isNotBlank() && now > 0)
        dao.saveTemplate(MessageTemplate(id, title.trim(), body.trim(), now))
    }
    suspend fun commitImport(parsed: ParsedImport): Int = database.withTransaction {
        val preview = DispatchParser.preview(parsed, dao.entries())
        dao.insertSources(listOf(parsed.source))
        dao.insertEntries(preview.newEntries).count { it != -1L }
    }
    suspend fun undoImport(id: String) = database.withTransaction { dao.deleteSourceEntries(id); dao.deleteSource(id) }
    suspend fun startShift(odometer: Double?, now: Long = System.currentTimeMillis()): Shift = database.withTransaction {
        require(dao.activeShift() == null) { "A shift is already open." }
        Shift(UUID.randomUUID().toString(), LocalDate.now().toString(), now, runningSince = now, odometerStart = odometer)
            .also { dao.saveShift(it) }
    }
    suspend fun resumeShift(now: Long = System.currentTimeMillis()) = database.withTransaction {
        dao.activeShift()?.let { shift ->
            // A still-running row with no service means tracking was interrupted.
            dao.saveShift(shift.copy(workedMillis = shift.durationMillis(now), runningSince = now,
                gaps = shift.gaps + if (shift.runningSince != null) 1 else 0, lastFixAt = null))
        }
    }
    suspend fun pauseShift(now: Long = System.currentTimeMillis()) = database.withTransaction {
        dao.activeShift()?.let { dao.saveShift(it.copy(workedMillis = it.durationMillis(now), runningSince = null)) }
    }
    suspend fun endShift(now: Long = System.currentTimeMillis()) = database.withTransaction {
        dao.activeShift()?.let { dao.saveShift(it.copy(workedMillis = it.durationMillis(now), runningSince = null, endedAt = now)) }
    }
    suspend fun recordDistance(meters: Double, gap: Boolean, now: Long) = database.withTransaction {
        dao.activeShift()?.takeIf { it.runningSince != null }?.let {
            dao.saveShift(it.copy(gpsMeters = it.gpsMeters + meters, lastFixAt = now, gaps = it.gaps + if (gap) 1 else 0))
        }
    }
    suspend fun markGap() = database.withTransaction {
        dao.activeShift()?.let { dao.saveShift(it.copy(gaps = it.gaps + 1)) }
    }
    suspend fun reviewShift(id: String, miles: Double?, note: String, timing: ShiftTiming? = null, resetTimes: Boolean = false) = database.withTransaction {
        val shift = dao.shifts().first { it.id == id }
        require(shift.endedAt != null) { "End this shift before reviewing it." }
        require(miles == null || miles.isFinite() && miles in 0.0..100_000.0)
        if (timing != null) require(timing.start > 0 && timing.end > timing.start && timing.end <= System.currentTimeMillis() && timing.workedMillis in 0..(timing.end - timing.start)) { "Invalid shift times." }
        val start = if (resetTimes) null else timing?.start ?: shift.correctedStartedAt
        val end = if (resetTimes) null else timing?.end ?: shift.correctedEndedAt
        val worked = if (resetTimes) null else timing?.workedMillis ?: shift.correctedWorkedMillis
        dao.saveShift(shift.copy(correctedMiles = miles, reviewed = true, note = note,
            correctedStartedAt = start, correctedEndedAt = end, correctedWorkedMillis = worked,
            date = java.time.Instant.ofEpochMilli(start ?: shift.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()))
    }
    suspend fun restore(backup: LedgerBackup): String = database.withTransaction {
        require(dao.activeShift() == null) { "End your active shift before restoring a backup." }
        val existing = snapshot()
        val oldEntries = existing.entries.associateBy { it.id }
        val oldTemplates = existing.messageTemplates.associateBy { it.id }
        val vehicleConflicts = backup.vehicles.count { row -> existing.vehicles.any { it.id == row.id && it != row } }
        val fuelConflicts = backup.fuelStops.count { row -> existing.fuelStops.any { it.id == row.id && it != row } }
        val maintenanceConflicts = backup.maintenance.count { row -> existing.maintenance.any { it.id == row.id && it != row } }
        val readingConflicts = backup.fuelReadings.count { row -> existing.fuelReadings.any { it.id == row.id && it != row } }
        fun canAddExpense(id: String, expected: Expense?) = existing.expenses.none { it.id == id && it != expected }
        val fuelToAdd = backup.fuelStops.filter { row -> existing.fuelStops.none { it.id == row.id } && canAddExpense(row.expenseId, if (row.countAsExpense) FuelTracking.expense(row) else null) }
        val maintenanceToAdd = backup.maintenance.filter { row -> existing.maintenance.none { it.id == row.id } && canAddExpense(row.expenseId, if (row.countAsExpense && row.amountCents > 0) FuelTracking.expense(row) else null) }
        FuelTracking.validateSequence(existing.fuelStops + fuelToAdd)
        val conflicts = vehicleConflicts + fuelConflicts + maintenanceConflicts + readingConflicts + backup.entries.count { it.id in oldEntries && !DispatchParser.sameRecord(it, oldEntries.getValue(it.id)) } +
            backup.shifts.count { restored -> existing.shifts.any { it.id == restored.id && it != restored } } +
            backup.expenses.count { restored -> existing.expenses.any { it.id == restored.id && it != restored } } +
            backup.messageTemplates.count { restored -> oldTemplates[restored.id]?.let {
                !SupportMessages.isStarter(it) && (it.title != restored.title || it.body != restored.body)
            } == true } +
            backup.goalPlans.count { restored -> existing.goalPlans.any { it.startWeek == restored.startWeek && it != restored } }
        dao.insertSources(backup.sources)
        val added = dao.insertEntries(backup.entries).count { it != -1L }
        dao.insertShifts(backup.shifts.map { shift ->
            if (shift.endedAt == null) shift.copy(endedAt = backup.createdAt, runningSince = null,
                workedMillis = shift.durationMillis(backup.createdAt), gaps = shift.gaps + 1, reviewed = false)
            else shift
        })
        dao.insertExpenses(backup.expenses.filterNot { it.id.startsWith("fuel:") || it.id.startsWith("service:") })
        dao.insertFuel(fuelToAdd)
        fuelToAdd.filter { it.countAsExpense }.forEach { dao.saveExpense(FuelTracking.expense(it)) }
        dao.insertMaintenance(maintenanceToAdd)
        maintenanceToAdd.filter { it.countAsExpense && it.amountCents > 0 }.forEach { dao.saveExpense(FuelTracking.expense(it)) }
        dao.insertReadings(backup.fuelReadings)
        dao.insertVehicles(backup.vehicles)
        dao.insertGoals(backup.goalPlans)
        dao.insertTemplates(backup.messageTemplates)
        // A fresh installation's starter example must not hide the user's edited copy in a backup.
        val starterReplacements = backup.messageTemplates.filter { oldTemplates[it.id]?.let(SupportMessages::isStarter) == true }
        starterReplacements.forEach { dao.saveTemplate(it) }
        val newExpenses = dao.expenses().count { row -> existing.expenses.none { it.id == row.id } }
        "Restored $added new earnings rows, ${backup.shifts.count { b -> existing.shifts.none { it.id == b.id } }} shifts, ${newExpenses} expenses, ${backup.messageTemplates.count { it.id !in oldTemplates } + starterReplacements.size} messages, and ${backup.goalPlans.count { b -> existing.goalPlans.none { it.startWeek == b.startWeek } }} goal settings, ${fuelToAdd.size} fill-ups, and ${maintenanceToAdd.size} service records. $conflicts conflicting records kept their current values."
    }
}

class LedgerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun refreshWidgets() { applicationScope.launch { runCatching { LedgerWidget.updateAll(this@LedgerApplication) } } }
    val repository by lazy {
        LedgerRepository(Room.databaseBuilder(this, LedgerDatabase::class.java, "shift-ledger.db")
            .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5).addCallback(LedgerDatabase.SEED_MESSAGES).build())
    }
    override fun onCreate() { super.onCreate(); refreshWidgets() }
}
