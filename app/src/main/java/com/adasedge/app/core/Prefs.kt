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

    companion object {
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_AUDIBLE = "audible"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_VOICE = "voice"
        private const val KEY_HUD = "hud"
        private const val KEY_FCW = "fcw"
        private const val KEY_LDW = "ldw"
        private const val KEY_HEADWAY = "headway"
        private const val KEY_TSR = "tsr"
        private const val KEY_REPLAY = "replay_mode"
        private const val KEY_REPLAY_SPEED = "replay_speed_kmh"
        private const val KEY_HORIZON = "horizon_ratio"
        private const val KEY_ROAD_BOTTOM = "road_bottom_ratio"
    }
}
