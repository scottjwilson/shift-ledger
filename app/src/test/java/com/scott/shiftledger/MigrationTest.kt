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
@Config(sdk = [35])
class MigrationTest {
    @Test fun `version three update preserves messages and earnings with default weekly goal`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v3.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.scott.shiftledger.LedgerDatabase/3.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO message_templates VALUES ('custom','My message','My own wording',1000)")
            old.execSQL("INSERT INTO expenses VALUES ('fuel','2026-10-02',2500,'Fuel','saved cost')")
            old.execSQL("INSERT INTO entries VALUES ('order','ORDER','2026-10-02',10000,'123','',30,20.0,'','saved order')")
            old.version = 3
        }
        val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).build()
        try {
            val repository = LedgerRepository(upgraded)
            val snapshot = repository.snapshot()
            assertEquals("My own wording", snapshot.messageTemplates.single().body)
            assertEquals(2500L, snapshot.expenses.single().amountCents)
            assertEquals(10000L, snapshot.entries.single().amountCents)
            assertTrue(snapshot.goalPlans.isEmpty())
            val goal = WeeklyGoals.dashboard(snapshot, java.time.LocalDate.parse("2026-10-04")).current
            assertEquals(150000L, goal.target)
            assertEquals(7500L, goal.net)
            repository.saveGoal(180000)
            assertEquals(180000L, repository.snapshot().goalPlans.single().targetCents)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun `version two update retains time corrections and seeds messages once`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.scott.shiftledger.LedgerDatabase/2.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO shifts (id,date,startedAt,endedAt,runningSince,workedMillis,gpsMeters,correctedMiles,odometerStart,lastFixAt,gaps,reviewed,note,correctedStartedAt,correctedEndedAt,correctedWorkedMillis) VALUES ('kept','2026-10-01',1000,5000,NULL,4000,123.5,NULL,NULL,4000,0,1,'corrected shift',2000,5000,2500)")
            old.version = 2
        }
        fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).addCallback(LedgerDatabase.SEED_MESSAGES).build()
        var upgraded = open()
        try {
            assertEquals(2500L, upgraded.dao().shifts().single().correctedWorkedMillis)
            val starter = upgraded.dao().templates().single()
            upgraded.dao().deleteTemplate(starter.id)
            upgraded.close(); upgraded = open()
            assertTrue(upgraded.dao().templates().isEmpty())
            assertEquals("corrected shift", upgraded.dao().shifts().single().note)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun `installed version one database migrates without losing rows`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-check.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(File("schemas/com.scott.shiftledger.LedgerDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO shifts (id,date,startedAt,endedAt,runningSince,workedMillis,gpsMeters,correctedMiles,odometerStart,lastFixAt,gaps,reviewed,note) VALUES ('kept','2026-10-01',1000,5000,NULL,4000,123.5,NULL,NULL,4000,0,1,'existing shift')")
            old.execSQL("INSERT INTO expenses (id,date,amountCents,category,note) VALUES ('fuel','2026-10-01',2050,'Fuel','existing expense')")
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).build()
        try {
            val shift = upgraded.dao().shifts().single()
            assertEquals("existing shift", shift.note)
            assertEquals(123.5, shift.gpsMeters, 0.0)
            assertNull(shift.correctedWorkedMillis)
            assertEquals(2050L, upgraded.dao().expenses().single().amountCents)
            assertEquals(SupportMessages.SUNRUN_BODY, upgraded.dao().templates().single().body)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
