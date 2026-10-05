package com.scott.shiftledger

const val GPS_STALE_MILLIS = 60_000L
enum class GpsPhase { IDLE, PAUSED, INTERRUPTED, PERMISSION, DISABLED, WAITING, LOST, TRACKING }
data class GpsHealth(val phase: GpsPhase, val title: String, val detail: String, val needsAttention: Boolean)
data class GpsEnvironment(val locationEnabled: Boolean = true, val precisePermission: Boolean = true, val accuracyMeters: Float? = null)

fun gpsHealth(shift: Shift?, serviceRunning: Boolean, environment: GpsEnvironment, now: Long): GpsHealth {
    if (shift == null) return GpsHealth(GpsPhase.IDLE, "Not tracking", "Start a shift to record mileage.", false)
    if (shift.runningSince == null) return GpsHealth(GpsPhase.PAUSED, "Paused", "Personal driving and paused time are excluded.", false)
    val last = shift.lastFixAt?.let { "Last reliable location ${ageText((now - it).coerceAtLeast(0))} ago." } ?: "No reliable location yet."
    return when {
        !serviceRunning -> GpsHealth(GpsPhase.INTERRUPTED, "Tracking interrupted", "$last Open the app and resume tracking.", true)
        !environment.precisePermission -> GpsHealth(GpsPhase.PERMISSION, "Location permission needed", "$last Allow precise location in app settings.", true)
        !environment.locationEnabled -> GpsHealth(GpsPhase.DISABLED, "Phone location is off", "$last Turn on Location to record miles.", true)
        now - (shift.lastFixAt ?: shift.runningSince) > GPS_STALE_MILLIS -> GpsHealth(GpsPhase.LOST, "GPS signal lost", "$last Missing miles may need correction.", true)
        shift.lastFixAt == null -> GpsHealth(GpsPhase.WAITING, "Finding GPS", "Waiting for a reliable location before counting miles.", false)
        else -> GpsHealth(GpsPhase.TRACKING, "Tracking", last + (environment.accuracyMeters?.let { " Accuracy about ${it.toInt()} m." } ?: ""), false)
    }
}

fun ageText(millis: Long): String = when {
    millis < 60_000 -> "${millis / 1000}s"
    millis < 3_600_000 -> "${millis / 60_000}m"
    else -> "${millis / 3_600_000}h ${(millis / 60_000) % 60}m"
}
