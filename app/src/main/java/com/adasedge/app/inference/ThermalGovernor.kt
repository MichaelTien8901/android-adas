package com.adasedge.app.inference

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.adasedge.app.core.Config

/**
 * Thermal-aware degradation (realtime-inference: "Thermal-aware sustained
 * operation"). Combines the OS thermal status with the measured FPS to decide a
 * processing tier: under throttle or below the FPS floor, perception drops to a
 * lower input cadence/resolution rather than dropping frames silently.
 */
class ThermalGovernor(context: Context) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    enum class Tier { FULL, REDUCED, MINIMAL }

    /** Either cause is active — drives cadence reduction. */
    @Volatile var throttled: Boolean = false
        private set
    /** Real OS thermal throttle (THERMAL_STATUS_SEVERE+). */
    @Volatile var thermalSevere: Boolean = false
        private set
    /** Processing is below the sustained-FPS floor (not necessarily thermal). */
    @Volatile var starved: Boolean = false
        private set

    /** Returns the current processing tier and updates the state flags. */
    fun tier(measuredFps: Float): Tier {
        thermalSevere = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) severeThermal() else false
        starved = measuredFps in 0.001f..Config.MIN_SUSTAINED_FPS
        throttled = thermalSevere || starved
        return when {
            thermalSevere && measuredFps < Config.MIN_SUSTAINED_FPS * 0.7f -> Tier.MINIMAL
            throttled -> Tier.REDUCED
            else -> Tier.FULL
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun severeThermal(): Boolean =
        power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    /** Inference cadence divider for a tier (process every Nth frame). */
    fun frameStride(tier: Tier): Int = when (tier) {
        Tier.FULL -> 1
        Tier.REDUCED -> 2
        Tier.MINIMAL -> 3
    }
}
