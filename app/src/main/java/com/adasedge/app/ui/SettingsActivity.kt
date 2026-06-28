package com.adasedge.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.adasedge.app.core.Prefs
import com.adasedge.app.databinding.ActivitySettingsBinding

/**
 * User settings: alert modalities, HUD mirror, and per-function toggles (each
 * warning independently toggleable). Also keeps the safety disclaimer accessible
 * (driver-alert-hmi: "Disclaimer accessible later").
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        with(binding) {
            audible.bind(prefs.audibleAlerts) { prefs.audibleAlerts = it }
            haptic.bind(prefs.hapticAlerts) { prefs.hapticAlerts = it }
            voice.bind(prefs.voiceAlerts) { prefs.voiceAlerts = it }
            hud.bind(prefs.hudMirror) { prefs.hudMirror = it }
            fcw.bind(prefs.fcwEnabled) { prefs.fcwEnabled = it }
            ldw.bind(prefs.ldwEnabled) { prefs.ldwEnabled = it }
            headway.bind(prefs.headwayEnabled) { prefs.headwayEnabled = it }
            tsr.bind(prefs.tsrEnabled) { prefs.tsrEnabled = it }
            dashcam.bind(prefs.dashcamEnabled) { prefs.dashcamEnabled = it }
            dashcamAutoStart.bind(prefs.dashcamAutoStart) { prefs.dashcamAutoStart = it }
            dashcamSegMin.bindInt(prefs.dashcamSegmentMinutes) { prefs.dashcamSegmentMinutes = it }
            dashcamMaxMb.bindInt(prefs.dashcamMaxStorageMb) { prefs.dashcamMaxStorageMb = it }
            replay.bind(prefs.replayMode) { prefs.replayMode = it }
            calibrateButton.setOnClickListener {
                startActivity(Intent(this@SettingsActivity, CalibrationActivity::class.java))
            }
            clipsButton.setOnClickListener {
                startActivity(Intent(this@SettingsActivity, ClipLibraryActivity::class.java))
            }
        }
    }

    private fun CompoundButton.bind(initial: Boolean, onChange: (Boolean) -> Unit) {
        isChecked = initial
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    /** Numeric setting: show [initial], commit the parsed value on focus loss / Done.
     *  Blank or unparseable input is ignored (keeps the prior value). */
    private fun EditText.bindInt(initial: Int, onChange: (Int) -> Unit) {
        setText(initial.toString())
        val commit = {
            text.toString().trim().toIntOrNull()?.let(onChange)
        }
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        setOnEditorActionListener { _, _, _ -> commit(); false }
    }
}
