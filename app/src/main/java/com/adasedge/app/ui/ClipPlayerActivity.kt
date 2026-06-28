package com.adasedge.app.ui

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import com.adasedge.app.databinding.ActivityClipPlayerBinding

/**
 * In-app clip player (clip-library spec: "Play and stop a recorded clip"). Plays the selected
 * recording immediately; the attached [MediaController] provides play/pause/seek, and leaving the
 * screen (back) stops playback. Independent of the ADAS replay pipeline.
 */
class ClipPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClipPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val controller = MediaController(this).apply { setAnchorView(binding.video) }
        with(binding.video) {
            setMediaController(controller)
            setVideoURI(Uri.parse(uriStr))
            setOnPreparedListener { start() }          // play immediately
            setOnErrorListener { _, _, _ -> finish(); true }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.video.pause()
    }

    override fun onStop() {
        super.onStop()
        binding.video.stopPlayback()                   // stop on leave
    }

    companion object {
        const val EXTRA_URI = "clip_uri"
    }
}
