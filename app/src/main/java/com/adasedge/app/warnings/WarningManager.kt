package com.adasedge.app.warnings

import com.adasedge.app.core.Prefs
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning

/**
 * Runs all enabled warning evaluators against the shared perception contract +
 * speed-context and aggregates their warnings (collision/lane/headway/TSR specs;
 * design D6). Each function is independently toggleable via [Prefs], which also
 * supports the "degrade to detection-only" rollback in design's Migration Plan.
 */
class WarningManager(private val prefs: Prefs) {

    val fcw = ForwardCollisionWarning()
    val ldw = LaneDepartureWarning(egoCenter = prefs.centerRatio)
    val headway = HeadwayMonitor()
    val tsr = TrafficSignWarning()

    fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        val out = ArrayList<Warning>()
        if (prefs.fcwEnabled) out += fcw.evaluate(result, speed)
        if (prefs.ldwEnabled) out += ldw.evaluate(result, speed)
        if (prefs.headwayEnabled) out += headway.evaluate(result, speed)
        if (prefs.tsrEnabled) out += tsr.evaluate(result, speed)
        return out
    }
}
