package com.adasedge.app.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.adasedge.app.camera.CameraController
import java.io.File

/**
 * Debug validation source (tasks 9.6 / 9.2): replays a video file through the
 * same frame sink the camera uses, so each warning's activation/clear can be
 * exercised deterministically against known road footage — no driving required.
 *
 * The clip is read from the app's external files dir; push one with:
 *   adb push road.mp4 /sdcard/Android/data/com.adasedge.app/files/replay.mp4
 *
 * Frames are decoded at a fixed cadence and stamped with a synthetic, monotonic
 * timestamp advancing by the same step, so TTC / headway temporal math is
 * consistent regardless of how fast the device actually decodes. The clip loops.
 */
class ReplaySource(context: Context) {

    private val appCtx = context.applicationContext
    @Volatile private var running = false
    private var thread: Thread? = null

    /** The replay clip, if present. */
    fun file(): File = File(appCtx.getExternalFilesDir(null), FILE_NAME)
    fun available(): Boolean = file().let { it.exists() && it.length() > 0 }

    /**
     * Start feeding decoded frames to [sink]. Returns false if no clip is present.
     * @param fps nominal sampling/playback rate (also the synthetic-timestamp step).
     */
    fun start(fps: Int = 15, sink: CameraController.FrameSink): Boolean {
        if (!available()) return false
        running = true
        thread = Thread({ loop(fps, sink) }, "replay-source").apply { start() }
        return true
    }

    private fun loop(fps: Int, sink: CameraController.FrameSink) {
        val stepUs = 1_000_000L / fps
        val stepNanos = 1_000_000_000L / fps
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file().absolutePath)
            val durationUs =
                (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            Log.i(TAG, "replay start: ${file().name} duration=${durationUs / 1000}ms @${fps}fps")
            var posUs = 0L
            var tsNanos = 0L
            while (running) {
                val t0 = System.nanoTime()
                val frame = mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
                if (frame == null) { posUs = 0L; continue }            // decode miss -> restart clip
                tsNanos += stepNanos
                sink.onFrame(frame, tsNanos)                            // ownership passes; pipeline recycles
                posUs += stepUs
                if (durationUs > 0 && posUs >= durationUs) posUs = 0L   // loop
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                val sleep = stepUs / 1000L - elapsedMs                  // pace to ~fps if decode was fast
                if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "replay failed", t)
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    companion object {
        private const val TAG = "ReplaySource"
        const val FILE_NAME = "replay.mp4"
    }
}
