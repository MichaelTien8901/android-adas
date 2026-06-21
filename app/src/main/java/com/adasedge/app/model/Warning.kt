package com.adasedge.app.model

enum class WarningType { FORWARD_COLLISION, LANE_DEPARTURE, HEADWAY, OVER_SPEED, SPEED_LIMIT, STOP_SIGN, TRAFFIC_LIGHT }

/** Urgency tiers; drives the HMI cue strength (advisory vs imminent). */
enum class WarningLevel { NONE, INFO, ADVISORY, IMMINENT }

/** Which side, for lane-departure. */
enum class Side { LEFT, RIGHT, NONE }

/**
 * An active warning emitted by an evaluator and consumed by the HMI /
 * AlertController. Equality intentionally ignores [message] so repeated frames
 * of the same warning at the same level are de-duplicated.
 */
data class Warning(
    val type: WarningType,
    val level: WarningLevel,
    val side: Side = Side.NONE,
    val message: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (other !is Warning) return false
        return type == other.type && level == other.level && side == other.side
    }

    override fun hashCode(): Int = (type.ordinal * 31 + level.ordinal) * 31 + side.ordinal
}
