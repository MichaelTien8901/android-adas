package com.adasedge.app.core

import android.content.Context
import androidx.core.content.edit

/**
 * Thin typed wrapper over SharedPreferences for user settings and the one-time
 * safety-disclaimer acknowledgement.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("adas_edge", Context.MODE_PRIVATE)

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(v) = sp.edit { putBoolean(KEY_DISCLAIMER, v) }

    var audibleAlerts: Boolean
        get() = sp.getBoolean(KEY_AUDIBLE, true)
        set(v) = sp.edit { putBoolean(KEY_AUDIBLE, v) }

    var hapticAlerts: Boolean
        get() = sp.getBoolean(KEY_HAPTIC, true)
        set(v) = sp.edit { putBoolean(KEY_HAPTIC, v) }

    /** Spoken (text-to-speech) warnings, e.g. "Collision warning", "Over speed limit". */
    var voiceAlerts: Boolean
        get() = sp.getBoolean(KEY_VOICE, false)
        set(v) = sp.edit { putBoolean(KEY_VOICE, v) }

    /** Experimental: snap detected lane points onto bright road markings before fitting
        (hybrid refinement). Costs a few ms/frame; can mis-snap on glary scenes. */
    var laneMarkingSnap: Boolean
        get() = sp.getBoolean(KEY_MARKING_SNAP, false)
        set(v) = sp.edit { putBoolean(KEY_MARKING_SNAP, v) }

    /** Experimental: fit lanes in a bird's-eye (top-down) view where they're straight &
        parallel — more robust to dashed-line outliers; the solid boundary anchors the
        dashed one. Uses the horizon/hood/centre calibration for the warp. */
    var birdEyeLaneFit: Boolean
        get() = sp.getBoolean(KEY_BEV_FIT, false)
        set(v) = sp.edit { putBoolean(KEY_BEV_FIT, v) }

    /** Experimental: temporally track the ego-lane curve coefficients through a Kalman
        filter with outlier gating and bounded gap prediction (openpilot-inspired
        recurrence surrogate). Smooths zig-zag and rejects adjacent-lane mixing; lanes
        become a tracked curve rather than per-frame points. */
    var laneStabilityTracker: Boolean
        get() = sp.getBoolean(KEY_LANE_TRACKER, false)
        set(v) = sp.edit { putBoolean(KEY_LANE_TRACKER, v) }

    /** Lane-detector selector: "twinlite" (default — drivable-area + lane segmentation,
        ego boundaries read directly from the lane-line mask; beat UFLDv2 on the replay
        paint-deviation benchmark and runs on the S26 NPU) | "ufldv2" (legacy fallback). */
    var laneModel: String
        get() = sp.getString(KEY_LANE_MODEL, "twinlite") ?: "twinlite"
        set(v) = sp.edit { putString(KEY_LANE_MODEL, v) }

    var hudMirror: Boolean
        get() = sp.getBoolean(KEY_HUD, false)
        set(v) = sp.edit { putBoolean(KEY_HUD, v) }

    var fcwEnabled: Boolean
        get() = sp.getBoolean(KEY_FCW, true)
        set(v) = sp.edit { putBoolean(KEY_FCW, v) }

    var ldwEnabled: Boolean
        get() = sp.getBoolean(KEY_LDW, true)
        set(v) = sp.edit { putBoolean(KEY_LDW, v) }

    var headwayEnabled: Boolean
        get() = sp.getBoolean(KEY_HEADWAY, true)
        set(v) = sp.edit { putBoolean(KEY_HEADWAY, v) }

    var tsrEnabled: Boolean
        get() = sp.getBoolean(KEY_TSR, true)
        set(v) = sp.edit { putBoolean(KEY_TSR, v) }

    /** Debug: feed a replay clip instead of the live camera (validation, task 9.6). */
    var replayMode: Boolean
        get() = sp.getBoolean(KEY_REPLAY, false)
        set(v) = sp.edit { putBoolean(KEY_REPLAY, v) }

    /** Synthetic ego speed injected during replay (above all warning speed gates). */
    var replaySpeedKmh: Int
        get() = sp.getInt(KEY_REPLAY_SPEED, 70)
        set(v) = sp.edit { putInt(KEY_REPLAY_SPEED, v) }

    /** Calibrated horizon row (0..1) from the guided calibration; drives the lane
        crop + distance geometry. Defaults to the rough built-in value. */
    var horizonRatio: Float
        get() = sp.getFloat(KEY_HORIZON, Calibration.DEFAULT.horizonRatio)
        set(v) = sp.edit { putFloat(KEY_HORIZON, v.coerceIn(0.05f, 0.95f)) }

    /** Calibrated road-bottom / hood-top row (0..1); the lane band ends here so lanes
        aren't drawn onto the car hood. 1.0 = no hood crop. */
    var roadBottomRatio: Float
        get() = sp.getFloat(KEY_ROAD_BOTTOM, Calibration.DEFAULT.roadBottomRatio)
        set(v) = sp.edit { putFloat(KEY_ROAD_BOTTOM, v.coerceIn(0.5f, 1f)) }

    /** Calibrated straight-ahead column (0..1) for an off-centre / angled camera; the
        ego reference for lane-departure and the lead in-path band. 0.5 = centred. */
    var centerRatio: Float
        get() = sp.getFloat(KEY_CENTER, Calibration.DEFAULT.centerRatio)
        set(v) = sp.edit { putFloat(KEY_CENTER, v.coerceIn(0.2f, 0.8f)) }

    companion object {
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_AUDIBLE = "audible"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_VOICE = "voice"
        private const val KEY_MARKING_SNAP = "lane_marking_snap"
        private const val KEY_BEV_FIT = "bird_eye_lane_fit"
        private const val KEY_LANE_TRACKER = "lane_stability_tracker"
        private const val KEY_LANE_MODEL = "lane_model"
        private const val KEY_HUD = "hud"
        private const val KEY_FCW = "fcw"
        private const val KEY_LDW = "ldw"
        private const val KEY_HEADWAY = "headway"
        private const val KEY_TSR = "tsr"
        private const val KEY_REPLAY = "replay_mode"
        private const val KEY_REPLAY_SPEED = "replay_speed_kmh"
        private const val KEY_HORIZON = "horizon_ratio"
        private const val KEY_ROAD_BOTTOM = "road_bottom_ratio"
        private const val KEY_CENTER = "center_ratio"
    }
}
