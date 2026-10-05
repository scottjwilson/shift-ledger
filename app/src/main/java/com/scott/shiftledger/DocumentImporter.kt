package com.scott.shiftledger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

data class TextFragment(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Rebuild visual rows so a table's columns don't turn into unrelated payment rows. */
object DocumentRows {
    fun arrange(fragments: List<TextFragment>): String {
        val rows = mutableListOf<MutableList<TextFragment>>()
        fragments.filter { it.text.isNotBlank() && it.bottom > it.top }.sortedBy { (it.top + it.bottom) / 2 }.forEach { fragment ->
            val center = (fragment.top + fragment.bottom) / 2
            val row = rows.lastOrNull()
            val fits = row != null && abs(center - row.map { (it.top + it.bottom) / 2 }.average()) <=
                min(fragment.bottom - fragment.top, row.map { it.bottom - it.top }.average().toFloat()) * 0.55f
            if (fits) row!!.add(fragment) else rows.add(mutableListOf(fragment))
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
    }
}

data class ExtractedDocument(val text: String, val description: String, val usesOcr: Boolean)

class DocumentImporter(private val context: Context) {
    suspend fun extract(uri: Uri, progress: (String) -> Unit): ExtractedDocument = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a document using Files or Android's Share button." }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val temporary = File.createTempFile("earnings-", ".import", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var size = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 40 * 1024 * 1024) { "Choose a file smaller than 40 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not open the selected file.")
            val pdf = temporary.inputStream().use { input -> val header = ByteArray(5); input.read(header) == 5 && header.toString(Charsets.US_ASCII) == "%PDF-" }
            if (mime.startsWith("text/") && !pdf) {
                require(temporary.length() <= 2_000_000) { "Use a smaller text export." }
                return@withContext ExtractedDocument(temporary.readText().removePrefix("\uFEFF"), "Text file loaded. Check the payout year before previewing.", false)
            }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                suspend fun recognize(bitmap: Bitmap): String = withContext(NonCancellable) {
                    // Let the native task finish before recycling its bitmap, including on cancellation.
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                    val fragments = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
                        element.boundingBox?.let { box -> TextFragment(element.text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()) }
                    }
                    DocumentRows.arrange(fragments)
                }
                val text: String
                val description: String
                if (pdf) {
                    PdfRenderer(ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                        require(renderer.pageCount in 1..25) { "Import up to 25 PDF pages at a time." }
                        val pages = mutableListOf<String>()
                        for (index in 0 until renderer.pageCount) {
                            coroutineContext.ensureActive()
                            progress("Reading PDF page ${index + 1} of ${renderer.pageCount}…")
                            renderer.openPage(index).use { page ->
                                require(page.width > 0 && page.height > 0) { "This PDF has an invalid page." }
                                val scale = minOf(3.0, 2400.0 / page.width, 4800.0 / page.height)
                                val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    pages += recognize(bitmap)
                                } finally { bitmap.recycle() }
                            }
                        }
                        text = pages.joinToString("\n\n")
                        description = "Read ${renderer.pageCount} PDF pages on this phone. Check all amounts, dates, and order IDs before saving; text recognition can make mistakes."
                    }
                } else {
                    progress("Reading screenshot…")
                    val bitmap = decodeImage(temporary)
                    try { text = recognize(bitmap) } finally { bitmap.recycle() }
                    description = "Screenshot read on this phone. Check all amounts, dates, and order IDs before saving; cropped or blurry rows can be missed."
                }
                require(text.isNotBlank()) { "No text could be read. Try a clearer screenshot or paste the earnings text." }
                require(text.length <= 2_000_000) { "Use a smaller date range." }
                ExtractedDocument(text, description, true)
            } finally { recognizer.close() }
        } finally { temporary.delete() }
    }

    private fun decodeImage(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Select a PDF, screenshot/image, or text file." }
        options.inSampleSize = 1
        while ((options.outWidth.toLong() / options.inSampleSize) * (options.outHeight / options.inSampleSize) > 8_000_000 ||
            max(options.outWidth, options.outHeight) / options.inSampleSize > 8192) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val original = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "This image could not be opened." }
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val matrix = Matrix()
        if (exif?.isFlipped == true) matrix.postScale(-1f, 1f)
        exif?.rotationDegrees?.takeIf { it != 0 }?.let { matrix.postRotate(it.toFloat()) }
        if (matrix.isIdentity) return original
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also {
            if (it !== original) original.recycle()
        }
    }
}
