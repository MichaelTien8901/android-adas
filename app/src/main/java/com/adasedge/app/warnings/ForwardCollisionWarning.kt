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
 */
class ForwardCollisionWarning : WarningEvaluator {

    override fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        // Speed gating: need usable speed above the minimum activation speed.
        if (!speed.usable) return emptyList()
        if (speed.kmh < Config.FCW_MIN_SPEED_KMH) return emptyList()

        val lead = result.lead ?: return emptyList()
        val ttc = lead.ttcSeconds
        if (ttc.isInfinite() || ttc <= 0f) return emptyList()

        // Widen thresholds by the latency margin to absorb lagged GPS speed.
        val advisory = Config.FCW_TTC_ADVISORY_S + Config.FCW_LATENCY_MARGIN_S
        val imminent = Config.FCW_TTC_IMMINENT_S + Config.FCW_LATENCY_MARGIN_S

        val level = when {
            ttc <= imminent -> WarningLevel.IMMINENT
            ttc <= advisory -> WarningLevel.ADVISORY
            else -> return emptyList()
        }
        return listOf(
            Warning(
                WarningType.FORWARD_COLLISION, level,
                message = "Collision risk: %.1fs / %.0fm".format(ttc, lead.distanceMeters),
            )
        )
    }
}
