package com.scott.shiftledger

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** In-process fixture provider in the test APK, never registered with Android. */
class FixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun draw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
        canvas.drawText("Dispatch Detailed Earnings", 30f, 60f, paint)
        fun row(y: Float, money: String, date: String, time: String, distance: String, order: String = "") {
            listOf(30f to money, 220f to date, 850f to time, 1080f to distance, 1270f to order).forEach { (x, text) -> canvas.drawText(text, x, y, paint) }
        }
        row(150f, "$99.50", "Oct 2, 2026, 2:41PM", "1hr 6mins", "35.01mi", "Order 12345")
        row(260f, "$98.95", "Payout · Oct 2 · Paid", "1hr 6mins", "35mi")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val name = uri.lastPathSegment
        require(name in setOf("sample.pdf", "sample.png"))
        val file = File(requireNotNull(context).cacheDir, requireNotNull(name))
        if (name.endsWith("pdf")) {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(1600, 600, 1).create())
                draw(page.canvas); document.finishPage(page)
                file.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
        } else {
            val bitmap = Bitmap.createBitmap(1600, 600, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = if (uri.lastPathSegment?.endsWith("pdf") == true) "application/pdf" else "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DocumentImportDeviceTest {
    @Test fun readsScreenshotOnDevice() = runBlocking { checkFile("sample.png") }
    @Test fun readsPdfOnDevice() = runBlocking { checkFile("sample.pdf") }
    private suspend fun checkFile(name: String) {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = FixtureProvider().apply { attachInfo(base, android.content.pm.ProviderInfo().apply { authority = "com.scott.shiftledger.test.documents" }) }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) { override fun getContentResolver(): ContentResolver = resolver }
        val document = DocumentImporter(context).extract(Uri.parse("content://com.scott.shiftledger.test.documents/$name")) {}
        var parsed = DispatchParser.parse(document.text, 2026)
        // This font can be recognized as 'Thr'. Never silently turn 1hr 6mins into 6mins.
        // Exercise the same explicit text correction available in the import screen.
        if (document.text.contains("Thr")) {
            assertFalse(document.text, parsed.entries.any { it.kind == "ORDER" })
            assertTrue(parsed.warnings.toString(), parsed.warnings.any { it.contains("Unreadable duration") })
            parsed = DispatchParser.parse(document.text.replace("Thr", "1hr"), 2026)
        }
        assertTrue(document.description, document.usesOcr)
        assertEquals(document.text, 2, parsed.entries.size)
        assertEquals(9950L, parsed.entries.single { it.kind == "ORDER" }.amountCents)
        assertEquals(9895L, parsed.entries.single { it.kind == "PAYOUT" }.amountCents)
        assertEquals(35.01, parsed.entries.single { it.kind == "ORDER" }.engagedMiles!!, 0.01)
        assertEquals(66, parsed.entries.single { it.kind == "ORDER" }.engagedMinutes)
    }
}
