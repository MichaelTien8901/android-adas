package com.adasedge.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.adasedge.app.core.Prefs
import com.adasedge.app.databinding.ActivityDisclaimerBinding
import com.adasedge.app.util.Permissions

/**
 * Launcher screen. Enforces the one-time safety disclaimer acknowledgement
 * (driver-alert-hmi: "First-run acknowledgement") before driving mode can start,
 * and drives the runtime-permission flow. If the disclaimer was already accepted,
 * it acts as a simple start screen.
 */
class DisclaimerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisclaimerBinding
    private lateinit var prefs: Prefs

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateStartEnabled()
            if (Permissions.allGranted(this)) launchDriving()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityDisclaimerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.acceptCheckbox.isChecked = prefs.disclaimerAccepted

        binding.acceptCheckbox.setOnCheckedChangeListener { _, checked ->
            prefs.disclaimerAccepted = checked
            updateStartEnabled()
        }

        binding.startButton.setOnClickListener {
            if (!prefs.disclaimerAccepted) return@setOnClickListener
            if (Permissions.allGranted(this)) {
                launchDriving()
            } else {
                permissionLauncher.launch(Permissions.required)
            }
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStartEnabled()
    }

    private fun updateStartEnabled() {
        binding.startButton.isEnabled = prefs.disclaimerAccepted
    }

    private fun launchDriving() {
        startActivity(Intent(this, DrivingActivity::class.java))
    }
}
