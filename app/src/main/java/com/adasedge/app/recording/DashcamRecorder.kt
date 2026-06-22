package com.adasedge.app.recording

import android.content.Context
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Dashcam recorder (dashcam-recording spec). Wraps a CameraX [VideoCapture] (bound as a third
 * use case by CameraController) and drives time-segmented capture: each clip rolls over to a
 * new datetime-named file at [segmentMinutes], and [RecordingStore] retention runs on every
 * rollover so storage stays under the cap. Video-only (no audio → no mic permission).
 *
 * Failure-isolated: encoder/storage errors are logged and surfaced via [onError]; they never
 * throw into the camera/session pipeline. The in-progress clip is protected from retention.
 */
class DashcamRecorder(
    private val context: Context,
    private val store: RecordingStore,
    private val segmentMinutes: Int,
    private val onError: (String) -> Unit = {},
) {
    /** Bound to the camera lifecycle by CameraController. */
    val videoCapture: VideoCapture<Recorder> =
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .build()
        )

    @Volatile
    var isRecording: Boolean = false
        private set

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dashcam-recorder")
    }
    private var recording: Recording? = null
    private var activeName: String? = null
    private var rollover: ScheduledFuture<*>? = null
    @Volatile private var stopping = false

    /** Begin recording (first segment). Safe to call once per session. */
    fun start() {
        stopping = false
        exec.execute { startSegment() }
    }

    /** Stop recording and finalize the current clip. */
    fun stop() {
        stopping = true
        exec.execute {
            rollover?.cancel(false); rollover = null
            runCatching { recording?.stop() }
            recording = null
            isRecording = false
        }
    }

    fun shutdown() {
        stop()
        exec.shutdown()
    }

    private fun startSegment() {
        try {
            store.enforce(activeName)              // make room before opening the next file
            val file: File = store.newClipFile(System.currentTimeMillis())
            activeName = file.name
            val pending = videoCapture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
            // video-only: do NOT call withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) onSegmentFinalized(event)
            }
            isRecording = true
            // Schedule rollover to the next segment.
            rollover = exec.schedule({ rollSegment() }, segmentMinutes.toLong(), TimeUnit.MINUTES)
        } catch (t: Throwable) {
            isRecording = false
            Log.w(TAG, "recording start failed", t)
            onError("dashcam: recording failed to start (${t.message})")
        }
    }

    /** Finalize the current clip; the Finalize event starts the next segment. */
    private fun rollSegment() {
        runCatching { recording?.stop() }   // -> VideoRecordEvent.Finalize
        recording = null
    }

    private fun onSegmentFinalized(event: VideoRecordEvent.Finalize) {
        if (event.hasError()) {
            Log.w(TAG, "segment finalize error: ${event.error}")
            onError("dashcam: clip finalize error ${event.error}")
        }
        // The just-finished clip is now eligible for retention.
        exec.execute {
            runCatching { store.enforce(null) }
            // Low free space and not stopping -> if we still can't secure space, give up.
            if (!stopping) {
                if (store.freeSpaceBytes() < MIN_FREE_BYTES && store.enforce(null) == 0) {
                    Log.w(TAG, "low storage; stopping dashcam")
                    onError("dashcam: stopped (low storage)")
                    isRecording = false
                } else {
                    startSegment()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DashcamRecorder"
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024  // keep ~200MB headroom
    }
}
