package com.adasedge.app.model

import android.graphics.RectF

/** Object classes the perception layer cares about (subset of COCO). */
enum class ObjectClass {
    CAR, TRUCK, BUS, PERSON, BICYCLE, MOTORCYCLE,
    TRAFFIC_LIGHT, STOP_SIGN, SPEED_LIMIT_SIGN, OTHER;

    val isVehicle: Boolean get() = this == CAR || this == TRUCK || this == BUS || this == MOTORCYCLE
    val isVulnerable: Boolean get() = this == PERSON || this == BICYCLE
}

/**
 * A single detection in normalized image coordinates (0..1), origin top-left.
 * [box] is normalized so it is independent of input resolution.
 */
data class Detection(
    val cls: ObjectClass,
    val box: RectF,
    val score: Float,
    /** Optional sub-label (e.g. recognized speed-limit value, light state). */
    val attribute: String? = null,
)

/** Ego-lane boundaries as normalized polylines (bottom-of-frame to vanishing point). */
data class LaneGeometry(
    val left: List<FloatArray>,   // each point = [x, y] normalized
    val right: List<FloatArray>,
    val confidence: Float,
)

/** Distance + TTC estimate for the lead vehicle in the ego path. */
data class LeadEstimate(
    val detection: Detection,
    val distanceMeters: Float,
    /** Time-to-collision in seconds; Float.POSITIVE_INFINITY when not closing. */
    val ttcSeconds: Float,
)

/**
 * Immutable per-frame perception output — the single contract every warning
 * evaluator consumes (adas-perception: "Perception output contract").
 */
data class PerceptionResult(
    val frameTimestampNanos: Long,
    val detections: List<Detection>,
    /** Null when lane geometry could not be reliably extracted this frame. */
    val lanes: LaneGeometry?,
    /** Null when no lead vehicle is present in the ego path. */
    val lead: LeadEstimate?,
    /** Source resolution the boxes were computed at, for overlay scaling. */
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    companion object {
        fun empty(tsNanos: Long, w: Int, h: Int) =
            PerceptionResult(tsNanos, emptyList(), null, null, w, h)
    }
}
