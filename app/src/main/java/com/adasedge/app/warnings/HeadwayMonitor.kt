package com.adasedge.app.warnings

import com.adasedge.app.core.Config
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType

/**
 * Tailgating / headway monitor (headway-monitoring spec). THW = distance / speed,
 * warned only when it stays below the safe threshold for a dwell period, with
 * hysteresis on clear and low-speed gating.
 */
class HeadwayMonitor : WarningEvaluator {

    private var belowSinceNanos = 0L
    private var warning = false

    override fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        if (!speed.usable || speed.kmh < Config.HEADWAY_MIN_SPEED_KMH) { reset(); return emptyList() }
        val lead = result.lead ?: run { reset(); return emptyList() }
        if (speed.metersPerSecond <= 0.1f) { reset(); return emptyList() }

        val thw = lead.distanceMeters / speed.metersPerSecond
        val now = result.frameTimestampNanos

        if (!warning) {
            if (thw < Config.HEADWAY_SAFE_S) {
                if (belowSinceNanos == 0L) belowSinceNanos = now
                val dwellMs = (now - belowSinceNanos) / 1_000_000L
                if (dwellMs >= Config.HEADWAY_DWELL_MS) warning = true
            } else belowSinceNanos = 0L
        } else {
            // Clear with hysteresis margin.
            if (thw > Config.HEADWAY_SAFE_S + Config.HEADWAY_HYSTERESIS_S) { reset() }
        }

        return if (warning)
            listOf(Warning(WarningType.HEADWAY, WarningLevel.ADVISORY, message = "Too close: %.1fs".format(thw)))
        else emptyList()
    }

    private fun reset() { belowSinceNanos = 0L; warning = false }
}
