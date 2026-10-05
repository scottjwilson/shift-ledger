package com.scott.shiftledger

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class ShiftTiming(val start: Long, val end: Long, val workedMillis: Long)

object ShiftTimes {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    fun display(time: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(time).atZone(zone).format(format)
    fun parse(start: String, end: String, breaks: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ShiftTiming {
        fun instant(value: String): Long {
            val local = LocalDateTime.parse(value.trim(), format)
            val offsets = zone.rules.getValidOffsets(local)
            require(offsets.size == 1) { "That time falls in a daylight-saving clock change. Choose an unambiguous time." }
            return local.toInstant(offsets.single()).toEpochMilli()
        }
        val from = instant(start)
        val to = instant(end)
        val breakMinutes = breaks.trim().ifBlank { "0" }.toLongOrNull()
        require(from > 0 && to > from && to <= now) { "End must be after start and not in the future." }
        require(to - from <= 7 * 86_400_000L) { "A shift cannot exceed seven days." }
        require(breakMinutes != null && breakMinutes in 0..((to - from) / 60_000)) { "Break minutes must fit within the shift." }
        return ShiftTiming(from, to, to - from - breakMinutes * 60_000)
    }
}
