package com.adasedge.app.warnings

import com.adasedge.app.core.Config
import com.adasedge.app.model.ObjectClass
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType

/**
 * Traffic Sign Recognition + over-speed (traffic-sign-recognition spec).
 * Maintains the current applicable speed limit (set from SPEED_LIMIT_SIGN
 * detections that carry a recognized value via a dedicated TSR classifier),
 * expires it after a distance without confirmation, and warns when ego speed
 * exceeds it beyond tolerance. Stop signs / traffic lights are surfaced as
 * best-effort informational cues only.
 */
class TrafficSignWarning : WarningEvaluator {

    private var currentLimitKmh: Int? = null
    private var distanceSinceConfirmM = 0f
    private var lastTsNanos = 0L

    override fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        integrateDistanceAndExpire(result, speed)

        // Update the limit from any speed-limit sign carrying a recognized value.
        result.detections
            .firstOrNull { it.cls == ObjectClass.SPEED_LIMIT_SIGN && it.score >= Config.SIGN_CONF_THRESHOLD }
            ?.attribute?.toIntOrNull()
            ?.let { currentLimitKmh = it; distanceSinceConfirmM = 0f }

        val out = ArrayList<Warning>()

        // Over-speed warning (requires a known limit and valid speed).
        val limit = currentLimitKmh
        if (limit != null && speed.usable && speed.kmh > limit + Config.OVERSPEED_TOLERANCE_KMH) {
            out += Warning(WarningType.OVER_SPEED, WarningLevel.ADVISORY,
                message = "Over limit: ${speed.kmh.toInt()} / $limit")
        }

        // Best-effort informational cues.
        if (result.detections.any { it.cls == ObjectClass.STOP_SIGN && it.score >= Config.SIGN_CONF_THRESHOLD })
            out += Warning(WarningType.STOP_SIGN, WarningLevel.INFO, message = "Stop sign")
        if (result.detections.any { it.cls == ObjectClass.TRAFFIC_LIGHT && it.score >= Config.SIGN_CONF_THRESHOLD })
            out += Warning(WarningType.TRAFFIC_LIGHT, WarningLevel.INFO, message = "Traffic light")

        return out
    }

    private fun integrateDistanceAndExpire(result: PerceptionResult, speed: SpeedSample) {
        if (lastTsNanos != 0L && speed.usable) {
            val dt = (result.frameTimestampNanos - lastTsNanos) / 1e9f
            if (dt > 0f) distanceSinceConfirmM += speed.metersPerSecond * dt
        }
        lastTsNanos = result.frameTimestampNanos
        if (distanceSinceConfirmM > Config.SIGN_STALE_AFTER_M) currentLimitKmh = null
    }
}
