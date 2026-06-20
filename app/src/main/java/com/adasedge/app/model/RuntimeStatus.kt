package com.adasedge.app.model

/** Which accelerator the inference runtime ended up using. */
enum class AccelPath { QNN_HTP, ORT_QNN, LITERT_GPU, CPU }

/**
 * Live runtime/health state surfaced to the HMI so the driver's expectation
 * matches actual capability (driver-alert-hmi: "Degraded-mode and status
 * indicators").
 */
data class RuntimeStatus(
    val accelPath: AccelPath = AccelPath.CPU,
    /** True once a thermal throttle has forced a resolution/cadence reduction. */
    val thermalThrottled: Boolean = false,
    val fps: Float = 0f,
    val lanesAvailable: Boolean = false,
    val speedValidity: SpeedValidity = SpeedValidity.INVALID,
    /** Current smoothed ego speed in km/h, for the HUD/status display. */
    val speedKmh: Float = 0f,
) {
    val reducedPerformance: Boolean
        get() = thermalThrottled || accelPath == AccelPath.LITERT_GPU || accelPath == AccelPath.CPU
}
