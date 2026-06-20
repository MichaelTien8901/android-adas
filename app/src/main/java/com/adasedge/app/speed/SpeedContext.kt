package com.adasedge.app.speed

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.adasedge.app.core.Config
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.SpeedValidity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single gating service for ego speed (speed-context capability). Acquires
 * GPS speed via the fused provider (a phone app cannot read vehicle/CAN speed),
 * low-pass smooths it, clamps standstill jitter, bridges dropouts with IMU
 * dead-reckoning, and publishes a [SpeedSample] carrying the
 * VALID/DEAD_RECKONED/INVALID validity + freshness. Consumers read [sample] and
 * never the raw GPS value.
 */
class SpeedContext(context: Context) {

    private val appCtx = context.applicationContext
    private val fused = LocationServices.getFusedLocationProviderClient(appCtx)
    private val imu = ImuDeadReckoner(appCtx)

    private val _sample = MutableStateFlow(SpeedSample.invalid(System.nanoTime()))
    val sample: StateFlow<SpeedSample> = _sample.asStateFlow()

    private var smoothed = 0f
    private var lastValidFixNanos = 0L
    private var lastUpdateNanos = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val now = System.nanoTime()
            if (loc.hasSpeed()) {
                val raw = loc.speed                                   // m/s
                smoothed += Config.SPEED_SMOOTHING_ALPHA * (raw - smoothed)
                lastValidFixNanos = now
                publish(now, SpeedValidity.VALID,
                    accuracy = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else Float.NaN)
            }
        }
    }

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION
    fun start() {
        imu.start()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    fun stop() {
        fused.removeLocationUpdates(callback)
        imu.stop()
    }

    /**
     * Re-evaluate validity for the current instant. Call this at frame time so
     * dropouts transition VALID → DEAD_RECKONED → INVALID even between GPS
     * callbacks. Returns the freshest sample.
     */
    fun tick(): SpeedSample {
        val now = System.nanoTime()
        val sinceFixMs = (now - lastValidFixNanos) / 1_000_000L
        when {
            lastValidFixNanos == 0L -> publish(now, SpeedValidity.INVALID, Float.NaN)
            sinceFixMs <= Config.SPEED_FRESHNESS_MS ->
                publish(now, SpeedValidity.VALID, _sample.value.accuracyMps)
            sinceFixMs <= Config.DEAD_RECKON_WINDOW_MS -> {
                val dt = (now - lastUpdateNanos).coerceAtLeast(0) / 1e9f
                smoothed = imu.advance(smoothed, dt)
                publish(now, SpeedValidity.DEAD_RECKONED, _sample.value.accuracyMps)
            }
            else -> publish(now, SpeedValidity.INVALID, Float.NaN)
        }
        return _sample.value
    }

    private fun publish(now: Long, validity: SpeedValidity, accuracy: Float) {
        val clamped = if (smoothed < Config.STANDSTILL_FLOOR_MPS) 0f else smoothed
        val stale = (now - lastValidFixNanos) / 1_000_000L > Config.SPEED_FRESHNESS_MS
        lastUpdateNanos = now
        _sample.value = SpeedSample(
            metersPerSecond = if (validity == SpeedValidity.INVALID) 0f else clamped,
            validity = validity,
            accuracyMps = accuracy,
            stale = stale && validity != SpeedValidity.INVALID,
            timestampNanos = now,
        )
    }
}
