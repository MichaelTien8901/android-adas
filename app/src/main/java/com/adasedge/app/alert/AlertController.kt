package com.adasedge.app.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.adasedge.app.core.Prefs
import com.adasedge.app.model.Side
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType
import java.util.Locale

/**
 * Multi-modal alerts (driver-alert-hmi: "Multi-modal warning alerts"). Plays an
 * urgency-scaled audible tone, haptic cue, and optional spoken (text-to-speech)
 * warning for the highest-urgency active warning, honoring the user's mute
 * settings, and rate-limits so a sustained warning does not buzz every frame.
 */
class AlertController(context: Context, private val prefs: Prefs) {

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // Text-to-speech: init is async, so guard speech until onInit reports success.
    @Volatile private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::onTtsInit)

    private fun onTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val locale = Locale.getDefault().takeIf { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
        tts.language = locale
        // Navigation-guidance usage: spoken warnings duck music/maps and play OVER
        // them, rather than being treated as mutable background media (which Samsung's
        // audio-hardening can silence). Same rationale as turn-by-turn nav prompts.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ttsReady = true
    }

    private var lastLevel = WarningLevel.NONE
    private var lastFireMs = 0L
    private var stopWasVisible = false   // edge-trigger for the spoken stop-sign cue

    /** Drive cues from the current frame's warnings. */
    fun update(warnings: List<Warning>) {
        // INFO-cue announcements: spoken but never beep (they sit below the urgency
        // gate below, which would otherwise drop them).
        val stopNow = warnings.any { it.type == WarningType.STOP_SIGN }
        if (prefs.voiceAlerts && ttsReady) {
            // Speak each newly-detected limit once ("Speed limit 50") — TrafficSignWarning
            // emits SPEED_LIMIT exactly once per new value.
            warnings.firstOrNull { it.type == WarningType.SPEED_LIMIT }
                ?.let { tts.speak(it.message, TextToSpeech.QUEUE_ADD, null, "adas-limit") }
            // Stop sign persists every frame while visible, so announce only on its
            // rising edge (first appearance) to avoid repeating "Stop sign" each frame.
            if (stopNow && !stopWasVisible) tts.speak("Stop sign", TextToSpeech.QUEUE_ADD, null, "adas-stop")
        }
        stopWasVisible = stopNow

        val topWarning = warnings.maxByOrNull { it.level.ordinal }
        val top = topWarning?.level ?: WarningLevel.NONE
        if (top <= WarningLevel.INFO) { lastLevel = top; return }

        val now = System.currentTimeMillis()
        // Beep promptly on entry/escalation; while SUSTAINED, re-beep slowly for an
        // advisory (tailgating can persist for minutes — avoid nagging) but stay
        // urgent for an imminent collision.
        val cooldown = if (top == WarningLevel.IMMINENT) 700L else 6000L
        val escalated = top.ordinal > lastLevel.ordinal
        if (!escalated && now - lastFireMs < cooldown) return
        lastFireMs = now
        lastLevel = top

        // When voice is on it carries the message; keep only the urgent tone as an
        // attention earcon ahead of speech, and drop the plain advisory beep so the
        // two don't talk over each other. Voice off -> original tone behaviour.
        val speak = prefs.voiceAlerts && ttsReady
        if (prefs.audibleAlerts && (!speak || top == WarningLevel.IMMINENT)) playTone(top)
        if (speak && topWarning != null) speak(topWarning)
        if (prefs.hapticAlerts) vibrate(top)
    }

    private fun speak(w: Warning) {
        val phrase = when (w.type) {
            WarningType.FORWARD_COLLISION -> if (w.level == WarningLevel.IMMINENT) "Brake. Collision warning" else "Collision warning"
            WarningType.LANE_DEPARTURE -> when (w.side) {
                Side.LEFT -> "Lane departure left"
                Side.RIGHT -> "Lane departure right"
                Side.NONE -> "Lane departure"
            }
            WarningType.HEADWAY -> "Too close"
            WarningType.OVER_SPEED -> "Over speed limit"
            WarningType.SPEED_LIMIT -> w.message            // announced via the dedicated path above
            WarningType.STOP_SIGN -> "Stop sign"
            WarningType.TRAFFIC_LIGHT -> "Traffic light"
        }
        // QUEUE_FLUSH: a newer, higher-urgency warning interrupts an in-progress phrase.
        tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "adas-${w.type}")
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
        runCatching { tts.stop(); tts.shutdown() }
    }
}
