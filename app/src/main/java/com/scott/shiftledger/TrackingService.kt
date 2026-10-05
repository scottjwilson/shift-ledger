package com.scott.shiftledger

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.IBinder
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackingService : Service() {
    companion object {
        val running = MutableStateFlow(false)
        val problem = MutableStateFlow<String?>(null)
        val environment = MutableStateFlow(GpsEnvironment())
        var gapRecorded = false
            private set
        private const val CHANNEL = "shift_tracking"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val accumulator = LocationAccumulator()
    private val repository get() = (application as LedgerApplication).repository
    private lateinit var client: FusedLocationProviderClient
    private var requested = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            scope.launch {
                mutex.withLock {
                    for (location in result.locations) {
                        val elapsed = location.elapsedRealtimeNanos / 1_000_000
                        if (SystemClock.elapsedRealtime() - elapsed !in 0..30_000) continue
                        val step = accumulator.add(Fix(location.latitude, location.longitude, location.accuracy, elapsed))
                        if (step.accepted) {
                            environment.value = environment.value.copy(accuracyMeters = location.accuracy)
                            repository.recordDistance(step.meters, step.gap && !gapRecorded, System.currentTimeMillis())
                            gapRecorded = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Shift mileage", NotificationManager.IMPORTANCE_LOW))
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (requested) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fail("Precise location is needed for mileage. You can still enter a shift manually.")
            return START_NOT_STICKY
        }
        val notification = notification("Finding GPS", "Waiting for a reliable location before counting miles.")
        try {
            ServiceCompat.startForeground(this, 7, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            requested = true
            running.value = true
            problem.value = null
            gapRecorded = false
            environment.value = GpsEnvironment()
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
                .setMinUpdateIntervalMillis(3_000).setMaxUpdateDelayMillis(10_000).setWaitForAccurateLocation(true).build()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { fail("Location tracking could not start: ${it.localizedMessage}") }
            monitorHealth()
        } catch (e: Exception) { fail("Tracking paused: ${e.localizedMessage ?: "location access unavailable"}") }
        return START_NOT_STICKY
    }

    private fun notification(title: String, detail: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle("Shift Ledger · $title").setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun monitorHealth() = scope.launch {
        var lastWidgetUpdate = 0L
        while (isActive && running.value) {
            val now = System.currentTimeMillis()
            environment.value = environment.value.copy(
                locationEnabled = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java)),
                precisePermission = ContextCompat.checkSelfPermission(this@TrackingService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            val shift = repository.dao.activeShift()
            val health = gpsHealth(shift, running.value, environment.value, now)
            if (health.phase in setOf(GpsPhase.LOST, GpsPhase.DISABLED, GpsPhase.PERMISSION) && !gapRecorded) {
                repository.markGap()
                gapRecorded = true
            }
            if (now - lastWidgetUpdate >= 15_000) {
                getSystemService(NotificationManager::class.java).notify(7, notification(health.title, health.detail))
                runCatching { LedgerWidget.updateAll(this@TrackingService) }
                lastWidgetUpdate = now
            }
            delay(5_000)
        }
    }

    private fun fail(message: String) {
        problem.value = message
        running.value = false
        scope.launch {
            repository.markGap()
            repository.pauseShift()
            stopSelf()
        }
    }
    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        running.value = false
        scope.cancel()
        (application as LedgerApplication).refreshWidgets()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
