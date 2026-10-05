package com.scott.shiftledger

import kotlin.math.*

data class Fix(val latitude: Double, val longitude: Double, val accuracy: Float, val elapsedMillis: Long)
data class DistanceStep(val meters: Double = 0.0, val gap: Boolean = false, val accepted: Boolean = false)

/** Reject poor fixes and implausible jumps. Never bridge a long missing segment. */
class LocationAccumulator {
    private var anchor: Fix? = null
    private var lastSeen: Long? = null
    fun reset() { anchor = null; lastSeen = null }
    fun add(fix: Fix): DistanceStep {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            !fix.accuracy.isFinite() || fix.accuracy !in 0f..40f) return DistanceStep()
        val previous = anchor
        if (lastSeen != null && fix.elapsedMillis <= lastSeen!!) return DistanceStep()
        val gap = lastSeen?.let { fix.elapsedMillis - it > 120_000 } ?: false
        lastSeen = fix.elapsedMillis
        if (previous == null || gap) {
            anchor = fix
            return DistanceStep(gap = gap, accepted = true)
        }
        val meters = distance(previous, fix)
        val seconds = (fix.elapsedMillis - previous.elapsedMillis) / 1000.0
        if (seconds <= 0 || meters / seconds > 65) return DistanceStep()
        val noiseThreshold = max(8.0, (previous.accuracy + fix.accuracy) / 2.0)
        if (meters < noiseThreshold) return DistanceStep(accepted = true)
        anchor = fix
        return DistanceStep(meters, accepted = true)
    }
    private fun distance(a: Fix, b: Fix): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
