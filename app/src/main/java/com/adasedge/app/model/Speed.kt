package com.adasedge.app.model

/**
 * Validity of the ego-speed estimate. Every speed-dependent warning MUST read
 * this and never the raw GPS number (speed-context: "Speed validity contract").
 */
enum class SpeedValidity {
    /** Fresh GPS-derived speed. */
    VALID,

    /** Bridged by IMU dead-reckoning during a short GPS dropout. */
    DEAD_RECKONED,

    /** No usable speed — dropout exceeded the dead-reckoning window. */
    INVALID,
}

/**
 * One published ego-speed sample. Consumers gate on [validity] and may widen
 * safety margins when [stale] is true (speed-context: "Latency-aware output").
 */
data class SpeedSample(
    val metersPerSecond: Float,
    val validity: SpeedValidity,
    /** Source GPS horizontal-speed accuracy in m/s, if reported. */
    val accuracyMps: Float,
    /** True when the latest valid fix is older than the freshness threshold. */
    val stale: Boolean,
    val timestampNanos: Long,
) {
    val kmh: Float get() = metersPerSecond * 3.6f
    val usable: Boolean get() = validity != SpeedValidity.INVALID

    companion object {
        fun invalid(tsNanos: Long) =
            SpeedSample(0f, SpeedValidity.INVALID, Float.NaN, stale = true, timestampNanos = tsNanos)
    }
}
