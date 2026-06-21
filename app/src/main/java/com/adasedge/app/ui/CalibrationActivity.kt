package com.adasedge.app.ui

import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adasedge.app.R
import com.adasedge.app.camera.CameraController
import com.adasedge.app.core.Prefs
import com.adasedge.app.databinding.ActivityCalibrationBinding

/**
 * Guided horizon calibration (design Open Question: refine Calibration.horizonRatio).
 * Shows the live camera and a draggable horizon line; the saved normalized row is
 * persisted and used by the lane crop + distance geometry on the next session.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var prefs: Prefs
    private var camera: CameraController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.horizon.normalizedY = prefs.horizonRatio
        binding.horizon.hoodY = prefs.roadBottomRatio
        binding.saveButton.setOnClickListener {
            prefs.horizonRatio = binding.horizon.normalizedY
            prefs.roadBottomRatio = binding.horizon.hoodY
            Toast.makeText(this, getString(R.string.calib_saved, (binding.horizon.normalizedY * 100).toInt()),
                Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.cancelButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        // Fresh controller each time (its analysis executor is shut down on stop).
        camera = CameraController(this).also {
            it.start(this, binding.previewView.surfaceProvider, Size(1280, 720)) { bmp, _ -> bmp.recycle() }
        }
    }

    override fun onStop() {
        super.onStop()
        camera?.stop(); camera = null
    }
}
