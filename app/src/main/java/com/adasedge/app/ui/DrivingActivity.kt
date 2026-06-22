package com.adasedge.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adasedge.app.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.adasedge.app.core.Prefs
import com.adasedge.app.databinding.ActivityDrivingBinding
import com.adasedge.app.service.DrivingService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The active driving screen: camera preview + transparent perception overlay.
 * Hosts the foreground [DrivingService], hands it the preview surface, and
 * renders the published perception/warning/status flows. Keeps the screen on.
 */
class DrivingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrivingBinding
    private lateinit var prefs: Prefs
    private var service: DrivingService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val svc = (b as DrivingService.LocalBinder).service
            service = svc
            svc.attachPreview(binding.previewView.surfaceProvider)
            setupRecordButton(svc)
            observe(svc)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityDrivingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.overlay.hudMirror = prefs.hudMirror
        binding.stopButton.setOnClickListener { stopDriving() }

        // Toggle the camera/backdrop feed off to save the per-frame display cost
        // (overlay boxes/lanes/warnings stay). Helps FPS, esp. in replay.
        binding.feedButton.setOnClickListener {
            feedOn = !feedOn
            binding.feedButton.setText(if (feedOn) R.string.feed_on else R.string.feed_off)
            binding.previewView.visibility = if (feedOn) View.VISIBLE else View.GONE
            service?.showFeed = feedOn
            if (!feedOn) binding.overlay.submitBackground(null)
        }
    }

    private var feedOn = true

    /** Live record on/off control. Hidden unless dashcam recording is available this session
     *  (the Settings master toggle is on and the camera bound a VideoCapture). */
    private fun setupRecordButton(svc: DrivingService) {
        if (!svc.recordingAvailable()) {
            binding.recordButton.visibility = View.GONE
            return
        }
        binding.recordButton.visibility = View.VISIBLE
        refreshRecordLabel(svc)
        binding.recordButton.setOnClickListener {
            svc.setRecording(!svc.isRecording())
            refreshRecordLabel(svc)
        }
    }

    private fun refreshRecordLabel(svc: DrivingService) {
        binding.recordButton.setText(if (svc.isRecording()) R.string.record_on else R.string.record_off)
    }

    override fun onStart() {
        super.onStart()
        binding.overlay.hudMirror = prefs.hudMirror
        val intent = Intent(this, DrivingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
        service = null
    }

    private fun observe(svc: DrivingService) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(svc.perceptionState, svc.warningState, svc.runtimeStatus) { p, w, s -> Triple(p, w, s) }
                        .collect { (p, w, s) -> binding.overlay.submit(p, w, s) }
                }
                launch {
                    svc.error.collect { msg -> if (msg != null) Toast.makeText(this@DrivingActivity, msg, Toast.LENGTH_LONG).show() }
                }
                launch {
                    svc.replayFrame.collect { binding.overlay.submitBackground(it) }
                }
            }
        }
    }

    private fun stopDriving() {
        // Tell the service to tear itself down (stopForeground + stopSelf) BEFORE we
        // unbind — a bound + started foreground service won't die on stopService()
        // alone, which left it running in the background.
        service?.requestStop()
        runCatching { unbindService(connection) }
        stopService(Intent(this, DrivingService::class.java))
        service = null
        finish()
    }
}
