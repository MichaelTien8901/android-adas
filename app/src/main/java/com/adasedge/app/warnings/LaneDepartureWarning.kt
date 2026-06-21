package com.adasedge.app.warnings

import com.adasedge.app.core.Config
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.Side
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType
import kotlin.math.abs

/**
 * Lane Departure Warning (lane-departure-warning spec). Activation-speed gating
 * with hysteresis, lane-availability gating, and best-effort intent suppression.
 * Departure is judged from the camera (ego) lateral position 0.5 relative to the
 * nearest lane boundary at the bottom of the frame.
 *
 * Intent suppression is best-effort: a phone app cannot read the turn signal, so
 * [intentToDepart] can be set by any future intent cue; when unknown it is false.
 */
class LaneDepartureWarning(
    /** Straight-ahead column (0..1) for an off-centre / angled camera; 0.5 = centred. */
    private val egoCenter: Float = 0.5f,
) : WarningEvaluator {

    @Volatile var intentToDepart: Side = Side.NONE   // hook for future intent cues
    private var active = false                         // hysteresis state on activation speed

    override fun evaluate(result: PerceptionResult, speed: SpeedSample): List<Warning> {
        if (!speed.usable) { active = false; return emptyList() }

        // Activation-speed hysteresis.
        val kmh = speed.kmh
        active = when {
            !active && kmh >= Config.LDW_ACTIVATION_KMH -> true
            active && kmh < Config.LDW_ACTIVATION_KMH - Config.LDW_HYSTERESIS_KMH -> false
            else -> active
        }
        if (!active) return emptyList()

        val lanes = result.lanes ?: return emptyList()      // lane-availability gate
        val ego = egoCenter

        // The boundaries are now ego-lane-stable (LaneDetector tracks them across the
        // image centre), so the nearest one is the line being crossed and its side is
        // correct even mid-departure.
        val leftX = lanes.left.minByOrNull { abs(it[1] - 1f) }?.get(0)
        val rightX = lanes.right.minByOrNull { abs(it[1] - 1f) }?.get(0)

        val side: Side
        val gap: Float
        when {
            leftX != null && (ego - leftX) < (rightX?.let { it - ego } ?: Float.MAX_VALUE) -> {
                side = Side.LEFT; gap = ego - leftX
            }
            rightX != null -> { side = Side.RIGHT; gap = rightX - ego }
            else -> return emptyList()
        }
        if (gap >= Config.LDW_DEPARTURE_DIST_NORM) return emptyList()
        if (intentToDepart == side) return emptyList()       // best-effort suppression

        return listOf(
            Warning(WarningType.LANE_DEPARTURE, WarningLevel.ADVISORY, side, "Lane departure ${side.name.lowercase()}")
        )
    }
}
