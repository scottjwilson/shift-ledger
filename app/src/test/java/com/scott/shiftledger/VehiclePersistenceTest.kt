package com.scott.shiftledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VehiclePersistenceTest {
    private val fuel = FuelStop("f","2026-09-01",1000.0,20.0,8000,true,barsBefore=1,barsAfter=9,createdAt=1000)
    @Test fun `fuel edits and service costs remain linked after reopen backup restore and delete`() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="vehicle-persist.db"; context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        var db=open()
        try {
            var repo=LedgerRepository(db)
            repo.saveFuel(fuel); repo.saveFuel(fuel.copy(amountCents=8500,station="Shell"))
            val service=MaintenanceRecord("s","2026-09-01","Oil change",1000.0,12000,nextOdometer=6000.0)
            repo.saveMaintenance(service)
            repo.saveVehicle(VehicleSettings(tankGallons=22.0))
            repo.saveReading(FuelReading("r","2026-09-02",5,1200.0,2000))
            db.close(); db=open(); repo=LedgerRepository(db)
            val state=repo.snapshot()
            assertEquals(20500L,state.expenses.sumOf { it.amountCents })
            assertEquals(2,state.expenses.size)
            assertEquals(22.0,state.vehicle.tankGallons,0.0)
            val backup=BackupCodec.decode(BackupCodec.encode(state))
            assertEquals(state.fuelStops,backup.fuelStops)
            assertEquals(state.maintenance,backup.maintenance)
            assertEquals(state.fuelReadings,backup.fuelReadings)
            val targetDb=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
            try {
                val target=LedgerRepository(targetDb)
                target.restore(backup); target.restore(backup)
                assertEquals(2,target.snapshot().expenses.size)
                assertEquals(22.0,target.snapshot().vehicle.tankGallons,0.0)
                target.saveFuel(state.fuelStops.single().copy(countAsExpense=false))
                assertEquals(12000L,target.snapshot().expenses.sumOf { it.amountCents })
                target.deleteFuel("f"); target.deleteMaintenance("s")
                assertTrue(target.snapshot().expenses.isEmpty())
                assertTrue(target.snapshot().fuelStops.isEmpty())
            } finally { targetDb.close() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `restore conflicts preserve both local fuel and matching expense`() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            repo.saveFuel(fuel.copy(amountCents=9000))
            val state=LedgerState(fuelStops=listOf(fuel),expenses=listOf(FuelTracking.expense(fuel)))
            val report=repo.restore(BackupCodec.decode(BackupCodec.encode(state)))
            assertTrue(report,report.contains("conflicting"))
            assertEquals(9000L,repo.snapshot().fuelStops.single().amountCents)
            assertEquals(9000L,repo.snapshot().expenses.single().amountCents)
        } finally { db.close() }
    }
    @Test fun `deleting a real fill invalidates the next interval instead of inflating MPG`() = runTest {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LedgerDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db)
            repo.saveFuel(fuel)
            repo.saveFuel(fuel.copy(id="p",date="2026-09-02",odometer=1100.0,gallons=5.0,fullTank=false,barsAfter=4))
            repo.saveFuel(fuel.copy(id="end",date="2026-09-03",odometer=1300.0,gallons=15.0))
            assertEquals(15.0,FuelTracking.cycles(repo.snapshot().fuelStops).single().mpg,0.0)
            repo.deleteFuel("p")
            assertTrue(FuelTracking.cycles(repo.snapshot().fuelStops).isEmpty())
            assertEquals(2,repo.snapshot().expenses.size)
            assertTrue(repo.snapshot().fuelStops.first { it.id=="end" }.missedFill)
        } finally { db.close() }
    }
    @Test fun `deleting trailing fuel preserves gap through reopen backup and later fills`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-fuel-gap.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        var db = open()
        try {
            var repo = LedgerRepository(db)
            repo.saveFuel(fuel)
            repo.saveFuel(fuel.copy(id = "partial", date = "2026-09-03", odometer = 1100.0, gallons = 5.0, fullTank = false, barsAfter = 4))
            repo.deleteFuel("partial")
            db.close(); db = open(); repo = LedgerRepository(db)
            val backup = BackupCodec.decode(BackupCodec.encode(repo.snapshot()))
            assertEquals("2026-09-03", backup.vehicles.single().pendingFuelGapDate)
            val targetDb = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            try {
                val target = LedgerRepository(targetDb)
                target.restore(backup)
                // An earlier backfilled purchase must not consume the later missing-fill marker.
                target.saveFuel(fuel.copy(id = "earlier", date = "2026-09-02", odometer = 1050.0, fullTank = false, barsAfter = 4))
                assertNotNull(target.snapshot().vehicle.pendingFuelGapDate)
                target.saveFuel(fuel.copy(id = "end", date = "2026-09-04", odometer = 1300.0, gallons = 15.0))
                assertTrue(FuelTracking.cycles(target.snapshot().fuelStops).isEmpty())
                assertTrue(target.snapshot().fuelStops.first { it.id == "end" }.missedFill)
                assertNull(target.snapshot().vehicle.pendingFuelGapDate)
                target.saveFuel(fuel.copy(id = "next", date = "2026-09-05", odometer = 1500.0, gallons = 10.0))
                assertEquals(20.0, FuelTracking.cycles(target.snapshot().fuelStops).single().mpg, 0.0)
            } finally { targetDb.close() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `restore across a pending gap also excludes the incomplete interval`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db)
            repo.saveFuel(fuel)
            repo.saveFuel(fuel.copy(id = "partial", date = "2026-09-02", odometer = 1100.0, fullTank = false, barsAfter = 4))
            repo.deleteFuel("partial")
            val end = fuel.copy(id = "end", date = "2026-09-03", odometer = 1300.0)
            repo.restore(BackupCodec.decode(BackupCodec.encode(LedgerState(fuelStops = listOf(end), expenses = listOf(FuelTracking.expense(end))))))
            assertTrue(FuelTracking.cycles(repo.snapshot().fuelStops).isEmpty())
            assertNull(repo.snapshot().vehicle.pendingFuelGapDate)
        } finally { db.close() }
    }
    @Test fun `backup rejects orphaned and conflicting linked costs and supports old versions`() {
        for (version in 1..5) {
            val old="""{"format":"shift-ledger","version":$version,"createdAt":5000,"entries":[],"shifts":[],"expenses":[],"sources":[]}"""
            assertTrue(BackupCodec.decode(old).fuelStops.isEmpty())
        }
        val state=LedgerState(fuelStops=listOf(fuel),expenses=listOf(FuelTracking.expense(fuel)))
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state.copy(expenses=emptyList()))) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state.copy(fuelStops=emptyList()))) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state.copy(expenses=listOf(FuelTracking.expense(fuel).copy(amountCents=1))))) }.isFailure)
        val csv=BackupCodec.csv(state)
        assertTrue(csv.contains("FUEL_DETAIL"))
        assertTrue(csv.contains("expense counted in EXPENSE rows"))
    }
    @Test fun `version five upgrade preserves vehicle settings and fuel costs`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "vehicle-v5-migration.db"; context.deleteDatabase(name)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.scott.shiftledger.LedgerDatabase/5.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO vehicle_settings VALUES ('primary','My van',22.0)")
            old.execSQL("INSERT INTO shifts VALUES ('active','2026-09-01',1000,NULL,1000,0,123.0,NULL,NULL,NULL,0,0,'running',NULL,NULL,NULL)")
            old.version = 5
        }
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_5_6).build()
        try {
            val repo = LedgerRepository(db)
            assertEquals("My van", repo.snapshot().vehicle.name)
            assertEquals(22.0, repo.snapshot().vehicle.tankGallons, 0.0)
            assertNull(repo.snapshot().vehicle.pendingFuelGapDate)
            assertEquals("active", repo.snapshot().activeShift!!.id)
            assertEquals(1000L, repo.snapshot().activeShift!!.runningSince)
            repo.saveFuel(fuel)
            assertEquals(8000L, repo.snapshot().expenses.single().amountCents)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `version four upgrade keeps earnings expenses corrections templates and goals`() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="vehicle-migration.db"; context.deleteDatabase(name)
        val path=context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val schema=JSONObject(File("schemas/com.scott.shiftledger.LedgerDatabase/4.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO entries VALUES ('o','ORDER','2026-09-01',10000,'123','',30,20.0,'','existing order')")
            old.execSQL("INSERT INTO expenses VALUES ('gas','2026-09-01',2500,'Fuel','existing expense')")
            old.execSQL("INSERT INTO message_templates VALUES ('m','Template','Saved message',1000)")
            old.execSQL("INSERT INTO weekly_goal_plans VALUES ('2026-08-31',170000)")
            old.execSQL("INSERT INTO shifts VALUES ('s','2026-09-01',1000,5000,NULL,4000,123.0,NULL,NULL,NULL,0,1,'existing shift',2000,5000,2500)")
            old.version=4
        }
        val db=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).build()
        try {
            val repo=LedgerRepository(db); val state=repo.snapshot()
            assertEquals(10000L,state.entries.single().amountCents)
            assertEquals(2500L,state.expenses.single().amountCents)
            assertEquals("Saved message",state.messageTemplates.single().body)
            assertEquals(170000L,state.goalPlans.single().targetCents)
            assertEquals(2500L,state.shifts.single().correctedWorkedMillis)
            assertTrue(state.fuelStops.isEmpty()); assertEquals(24.0,state.vehicle.tankGallons,0.0)
            repo.saveFuel(fuel)
            assertEquals(2,repo.snapshot().expenses.size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
