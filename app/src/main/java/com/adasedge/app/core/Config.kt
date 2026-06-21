package com.adasedge.app.core

/**
 * Central tunable parameters. Values are chosen from the research docs
 * (research/01 thresholds, research/03 FPS budget, research/05 speed gating)
 * and are intentionally conservative for a PoC.
 */
object Config {

    // ---- Inference / scheduling (realtime-inference) ----
    const val MODEL_INPUT_SIZE = 640          // YOLO11n square input
    const val LANE_INPUT_W = 800              // UFLDv2 input
    const val LANE_INPUT_H = 320
    /** UFLDv2 crop ratio: resize to W x (H/ratio) then keep the bottom H rows
        (drops sky), matching the model's training preprocessing. */
    const val LANE_CROP_RATIO = 0.8f
    const val DETECTION_CONF_THRESHOLD = 0.35f
    const val NMS_IOU_THRESHOLD = 0.45f
    const val MIN_SUSTAINED_FPS = 15f
    const val TARGET_FPS = 30f
    /** Quantization accuracy gate: max allowed mAP drop vs FP baseline. */
    const val MAX_QUANT_MAP_DROP = 0.03f

    // ---- Forward collision (collision-warning, research/01) ----
    const val FCW_TTC_ADVISORY_S = 2.7f
    const val FCW_TTC_IMMINENT_S = 1.4f
    const val FCW_MIN_SPEED_KMH = 8f
    const val FCW_LATENCY_MARGIN_S = 0.3f      // absorbs GPS speed lag

    // ---- Lane departure (lane-departure-warning, ISO 17361) ----
    const val LDW_ACTIVATION_KMH = 60f
    const val LDW_HYSTERESIS_KMH = 5f          // enable@+, disable@-
    const val LDW_DEPARTURE_DIST_NORM = 0.04f  // normalized distance-to-line

    // ---- Headway / tailgating (headway-monitoring, two-second rule) ----
    const val HEADWAY_SAFE_S = 2.0f
    const val HEADWAY_HYSTERESIS_S = 0.4f
    const val HEADWAY_MIN_SPEED_KMH = 25f
    const val HEADWAY_DWELL_MS = 1200L

    // ---- Traffic sign / over-speed (traffic-sign-recognition) ----
    const val OVERSPEED_TOLERANCE_KMH = 5f
    const val SIGN_STALE_AFTER_M = 3000f       // expire a limit after this distance
    const val SIGN_CONF_THRESHOLD = 0.5f
    const val SIGN_CONFIRM_HITS = 4            // frames a NEW limit must persist before it replaces the active one (debounce)

    // ---- Speed context (speed-context, research/05) ----
    const val SPEED_SMOOTHING_ALPHA = 0.35f    // low-pass factor on raw GPS speed
    const val STANDSTILL_FLOOR_MPS = 0.85f     // ~3 km/h clamp to zero
    const val DEAD_RECKON_WINDOW_MS = 8000L    // bridge GPS dropouts up to this long
    const val SPEED_FRESHNESS_MS = 1500L       // older valid sample -> stale flag
}
