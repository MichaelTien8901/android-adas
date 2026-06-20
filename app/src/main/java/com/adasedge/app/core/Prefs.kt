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

    companion object {
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_AUDIBLE = "audible"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_HUD = "hud"
        private const val KEY_FCW = "fcw"
        private const val KEY_LDW = "ldw"
        private const val KEY_HEADWAY = "headway"
        private const val KEY_TSR = "tsr"
        private const val KEY_REPLAY = "replay_mode"
        private const val KEY_REPLAY_SPEED = "replay_speed_kmh"
    }
}
