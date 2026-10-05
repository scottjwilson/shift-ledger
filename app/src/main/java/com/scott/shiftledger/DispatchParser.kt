package com.scott.shiftledger

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedImport(val entries: List<Entry>, val warnings: List<String>, val source: ImportSource)
data class ImportPreview(val parsed: ParsedImport, val newEntries: List<Entry>, val duplicates: Int, val conflicts: List<Entry>)

object DispatchParser {
    private val amount = Regex("(?<![\\w.])(?:-\\s*)?\\$\\s*(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}")
    private val dateTime = Regex("([A-Z][a-z]{2} \\d{1,2}, \\d{4}, \\d{1,2}:\\d{2}\\s*[AP]M)")
    private val payoutDate = Regex("Payout\\s*[·•∙|]?\\s*([A-Z][a-z]{2} \\d{1,2})(?:, (\\d{4}))?\\s*[·•∙|]?\\s*Paid\\b")
    private val order = Regex("Order\\s+(\\d+)")
    private val hours = Regex("(\\d+)\\s*hrs?\\b")
    private val minutes = Regex("(\\d+)\\s*mins?\\b")
    private val distance = Regex("(\\d+(?:\\.\\d+)?)\\s*mi\\b")
    private val durationTokens = Regex("\\b(\\S+?)\\s*(hrs?|mins?)\\b")
    private val timestampFormat = DateTimeFormatter.ofPattern("MMM d, uuuu, h:mma", Locale.US)
    private val dayFormat = DateTimeFormatter.ofPattern("MMM d uuuu", Locale.US)

    fun parse(text: String, payoutYear: Int, now: Long = System.currentTimeMillis()): ParsedImport {
        require(text.length <= 2_000_000) { "Import is too large. Use a smaller date range." }
        require(payoutYear in 2000..2100) { "Enter a year between 2000 and 2100." }
        val source = ImportSource(hash("$payoutYear|$text"), now, payoutYear, text)
        val normalized = text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ")
        val markers = amount.findAll(normalized).toList()
        val entries = mutableListOf<Entry>()
        val warnings = mutableListOf<String>()
        markers.forEachIndexed { index, match ->
            val raw = normalized.substring(match.range.first, markers.getOrNull(index + 1)?.range?.first ?: normalized.length).trim()
            val orderMatch = order.find(raw)
            val payout = payoutDate.find(raw)
            if (orderMatch == null && payout == null) {
                if (raw.contains("Payout") || dateTime.containsMatchIn(raw) || raw.contains("Order "))
                    warnings += "Could not read a row: ${raw.take(140)}"
                return@forEachIndexed
            }
            runCatching {
                require(order.findAll(raw).count() <= 1) { "Multiple order IDs in one row; check the extracted layout" }
                val value = cents(match.value.replace(" ", ""))
                require(value in -1_000_000_000_000..1_000_000_000_000) { "Amount outside supported range" }
                require(durationTokens.findAll(raw).all { it.groupValues[1].all(Char::isDigit) }) {
                    "Unreadable duration; correct the extracted text before saving"
                }
                val h = hours.find(raw)?.groupValues?.get(1)?.toInt()
                val m = minutes.find(raw)?.groupValues?.get(1)?.toInt()
                val time = if (h == null && m == null) null else ((h ?: 0) * 60 + (m ?: 0))
                val miles = distance.find(raw)?.groupValues?.get(1)?.toDouble()
                if (payout != null) {
                    val explicitYear = payout.groupValues[2].toIntOrNull() ?: payoutYear
                    val day = LocalDate.parse("${payout.groupValues[1]} $explicitYear", dayFormat).toString()
                    Entry("payout:" + hash("$day|$value|$time|$miles"), "PAYOUT", day, value,
                        engagedMinutes = time, engagedMiles = miles, sourceId = source.id, raw = raw)
                } else {
                    val whenText = requireNotNull(dateTime.find(raw)) { "Missing order date" }.value.replace(Regex("\\s+([AP]M)"), "$1")
                    val whenAt = LocalDateTime.parse(whenText, timestampFormat)
                    val id = requireNotNull(orderMatch).groupValues[1]
                    require((time == null) == (miles == null)) { "Incomplete time or mileage" }
                    val adjustment = time == null
                    if (adjustment && !raw.contains("Adjustment"))
                        warnings += "Order $id: treated ${money(value)} as an adjustment because no time or mileage is present. Check the preview."
                    val kind = if (adjustment) "ADJUSTMENT" else "ORDER"
                    val key = if (adjustment) "adjustment:$id:$whenAt" else "order:$id"
                    Entry(key, kind, whenAt.toLocalDate().toString(), value, id, whenAt.toString(), time, miles, source.id, raw)
                }
            }.onSuccess { entries += it }.onFailure {
                warnings += "Skipped row (${it.message}): ${raw.take(140)}"
            }
        }
        val years = entries.filter { it.kind != "PAYOUT" }.map { it.date.take(4).toInt() }.toSet()
        if (years.size > 1 || years.any { it != payoutYear }) {
            warnings += "Order years differ from the payout year. Payouts were excluded. Import one calendar year at a time and check the year field."
            entries.removeAll { it.kind == "PAYOUT" }
        }
        val repeated = entries.groupBy { it.id }.filterValues { it.size > 1 }
        val conflictingIds = repeated.filterValues { rows -> rows.drop(1).any { !sameRecord(rows.first(), it) } }.keys
        if (repeated.isNotEmpty()) warnings += "Repeated rows detected. Identical rows are counted once; conflicting rows are excluded. Identical payouts have no unique Dispatch ID and need checking."
        if (entries.isEmpty()) warnings += "No readable order or payout rows found. Paste the Detailed Earnings table with amounts and dates."
        return ParsedImport(entries.filterNot { it.id in conflictingIds }.distinctBy { it.id }, warnings.distinct(), source)
    }

    fun preview(parsed: ParsedImport, existing: List<Entry>): ImportPreview {
        val byId = existing.associateBy { it.id }
        val fresh = parsed.entries.filter { it.id !in byId }
        val conflicts = parsed.entries.filter { it.id in byId && !sameRecord(it, byId.getValue(it.id)) }
        return ImportPreview(parsed, fresh, parsed.entries.size - fresh.size - conflicts.size, conflicts)
    }

    fun sameRecord(a: Entry, b: Entry) = a.copy(sourceId = "", raw = "") == b.copy(sourceId = "", raw = "")
}
