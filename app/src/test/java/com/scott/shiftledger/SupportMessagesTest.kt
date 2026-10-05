package com.scott.shiftledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportMessagesTest {
    @Test fun `order placeholder fills everywhere and cannot be copied blank`() {
        assertFalse(SupportMessages.canCopy(SupportMessages.SUNRUN_BODY, ""))
        assertFalse(SupportMessages.canCopy(SupportMessages.SUNRUN_BODY, "wrong"))
        assertTrue(SupportMessages.canCopy(SupportMessages.SUNRUN_BODY, "#2813447"))
        assertTrue(SupportMessages.render(SupportMessages.SUNRUN_BODY, " #2813447 ").contains("order #2813447"))
        assertEquals("2813447 / 2813447", SupportMessages.render("{order} / {order}", "2813447"))
        assertTrue(SupportMessages.canCopy("Thank you for your help.", ""))
    }
    @Test fun `saved edits and deletes survive reopening the actual database`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "message-persistence.db"
        fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addCallback(LedgerDatabase.SEED_MESSAGES).build()
        var db = open()
        try {
            val repository = LedgerRepository(db)
            val starter = db.dao().templates().single()
            repository.saveTemplate("custom", "Waiting", "Waiting on order #{order}.\nPlease advise.", 1234)
            repository.saveTemplate("custom", "Waiting updated", "Still waiting on order #{order}.", 2345)
            db.dao().deleteTemplate(starter.id)
            db.close(); db = open()
            assertEquals("Waiting updated", db.dao().templates().single().title)
            assertFalse(db.dao().templates().any(SupportMessages::isStarter))
            val backup = BackupCodec.decode(BackupCodec.encode(LedgerRepository(db).snapshot()))
            assertEquals(db.dao().templates(), backup.messageTemplates)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `restore keeps edited messages but replaces untouched starter with backed up wording`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).addCallback(LedgerDatabase.SEED_MESSAGES).build()
        try {
            val repository = LedgerRepository(db)
            val starter = db.dao().templates().single()
            repository.saveTemplate("mine", "Current", "My current message", 1000)
            val saved = listOf(starter.copy(body = "My edited Sunrun wording"), MessageTemplate("mine", "Old", "Old wording", 900), MessageTemplate("new", "New", "New message", 1200))
            val backup = BackupCodec.decode(BackupCodec.encode(LedgerState(messageTemplates = saved)))
            val report = repository.restore(backup)
            val restored = db.dao().templates().associateBy { it.id }
            assertEquals("My edited Sunrun wording", restored.getValue(starter.id).body)
            assertEquals("My current message", restored.getValue("mine").body)
            assertEquals("New message", restored.getValue("new").body)
            assertTrue(report, report.contains("1 conflicting"))
        } finally { db.close() }
    }
    @Test fun `older backups decode without templates and invalid new templates fail`() {
        for (version in 1..2) {
            val old = """{"format":"shift-ledger","version":$version,"createdAt":5000,"entries":[],"shifts":[],"expenses":[],"sources":[]}"""
            assertTrue(BackupCodec.decode(old).messageTemplates.isEmpty())
        }
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(LedgerState(messageTemplates = listOf(MessageTemplate("m", "", "body", 1000))))) }.isFailure)
    }
}
