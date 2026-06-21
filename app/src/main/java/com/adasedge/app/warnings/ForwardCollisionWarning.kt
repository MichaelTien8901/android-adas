package com.adasedge.app.warnings

import com.adasedge.app.core.Config
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType

/**
 * Forward Collision Warning (collision-warning spec). Two-stage TTC thresholds,
 * speed-gated with a latency margin, on the ego-path lead vehicle (ego-path
 * relevance is enforced upstream in PerceptionEngine.selectLead).
 *
 * The looming-TTC from monocular bbox growth is noisy frame-to-frame, so a raw
 * threshold cross produced frequent false "Brake" alarms on real footage. We gate
 * with hysteresis: TTC must stay below the advisory threshold for FCW_DWELL_S
 * before warning (a single noisy dip won't fire), and once active the warning only
 * clears after TTC stays above the threshold for FCW_CLEAR_HYST_S (so a one-frame
 * recovery doesn't drop a live warning).
 */
class ForwardCollisionWarning : WarningEvaluator {

    private var belowSinceNanos = -1L  // TTC continuously <= advisory since this ts (-1 = unset; arming)
    private var aboveSinceNanos = -1L  // TTC continuously >  advisory since this ts (-1 = unset; clearing)
    private var active = false

    override fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        // Speed gating: need usable speed above the minimum activation speed.
        if (!speed.usable || speed.kmh < Config.FCW_MIN_SPEED_KMH) return disarm()

        val lead = result.lead ?: return disarm()
        val ttc = lead.ttcSeconds
        if (ttc.isInfinite() || ttc <= 0f) return disarm()

        // Widen thresholds by the latency margin to absorb lagged GPS speed.
        val advisory = Config.FCW_TTC_ADVISORY_S + Config.FCW_LATENCY_MARGIN_S
        val imminent = Config.FCW_TTC_IMMINENT_S + Config.FCW_LATENCY_MARGIN_S
        val ts = result.frameTimestampNanos
        val dwellNanos = (Config.FCW_DWELL_S * 1e9f).toLong()
        val graceNanos = (Config.FCW_CLEAR_HYST_S * 1e9f).toLong()

        if (ttc <= advisory) {
            aboveSinceNanos = -1L
            if (belowSinceNanos < 0L) belowSinceNanos = ts
            if (ts - belowSinceNanos >= dwellNanos) active = true
        } else {
            belowSinceNanos = -1L
            if (aboveSinceNanos < 0L) aboveSinceNanos = ts
            if (ts - aboveSinceNanos >= graceNanos) active = false
        }
        if (!active) return emptyList()

        val level = if (ttc <= imminent) WarningLevel.IMMINENT else WarningLevel.ADVISORY
        return listOf(
            Warning(
                WarningType.FORWARD_COLLISION, level,
                message = "Collision risk: %.1fs / %.0fm".format(ttc, lead.distanceMeters),
            )
        )
    }

    private fun disarm(): List<Warning> {
        belowSinceNanos = -1L; aboveSinceNanos = -1L; active = false
        return emptyList()
    }
}
