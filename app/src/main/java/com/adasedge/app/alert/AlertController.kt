package com.adasedge.app.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.adasedge.app.core.Prefs
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel

/**
 * Multi-modal alerts (driver-alert-hmi: "Multi-modal warning alerts"). Plays an
 * urgency-scaled audible tone and haptic cue for the highest-urgency active
 * warning, honoring the user's mute settings, and rate-limits so a sustained
 * warning does not buzz every frame.
 */
class AlertController(context: Context, private val prefs: Prefs) {

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastLevel = WarningLevel.NONE
    private var lastFireMs = 0L

    /** Drive cues from the current frame's warnings. */
    fun update(warnings: List<Warning>) {
        val top = warnings.maxByOrNull { it.level.ordinal }?.level ?: WarningLevel.NONE
        if (top <= WarningLevel.INFO) { lastLevel = top; return }

        val now = System.currentTimeMillis()
        val cooldown = if (top == WarningLevel.IMMINENT) 700L else 1800L
        val escalated = top.ordinal > lastLevel.ordinal
        if (!escalated && now - lastFireMs < cooldown) return
        lastFireMs = now
        lastLevel = top

        if (prefs.audibleAlerts) playTone(top)
        if (prefs.hapticAlerts) vibrate(top)
    }

    private fun playTone(level: WarningLevel) = when (level) {
        WarningLevel.IMMINENT -> tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
        else -> tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    private fun vibrate(level: WarningLevel) {
        val effect = if (level == WarningLevel.IMMINENT)
            VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
        else VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    fun release() {
        runCatching { tone.release() }
    }
}
