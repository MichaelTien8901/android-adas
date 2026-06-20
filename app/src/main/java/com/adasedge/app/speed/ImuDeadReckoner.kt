package com.adasedge.app.speed

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Short-horizon IMU dead-reckoning used to bridge GPS dropouts (speed-context:
 * "Dead-reckoning through GPS dropouts"). Without heading we cannot fully
 * integrate velocity, so we use linear acceleration to detect longitudinal
 * change — chiefly hard braking — and adjust the held speed, decaying gently
 * toward the last value. Accuracy degrades with time, hence the bounded window.
 */
class ImuDeadReckoner(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    @Volatile private var horizAccel = 0f      // m/s^2 magnitude in horizontal plane
    @Volatile private var lastTsNanos = 0L

    fun start() {
        linAccel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    /**
     * Advance the dead-reckoned speed estimate.
     * @return the new estimated speed (m/s), clamped to non-negative.
     */
    fun advance(lastSpeedMps: Float, dtSeconds: Float): Float {
        // Apply the magnitude of horizontal acceleration as a deceleration bias:
        // strong horizontal accel during a GPS gap most often means braking.
        val deltaV = horizAccel * dtSeconds
        val decel = (deltaV).coerceAtMost(lastSpeedMps)
        return (lastSpeedMps - 0.5f * decel).coerceAtLeast(0f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        horizAccel = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1])
        lastTsNanos = e.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
