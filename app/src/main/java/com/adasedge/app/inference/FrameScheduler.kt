package com.adasedge.app.inference

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Realtime frame gate (realtime-inference: "Realtime frame scheduling"). The
 * camera analyzer already keeps only the latest frame; this adds an in-flight
 * guard so a slow inference cycle causes intermediate frames to be dropped rather
 * than queued, and measures a rolling FPS over the processed frames.
 */
class FrameScheduler {
    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastEndNanos = 0L
    @Volatile private var emaFps = 0f
    private val alpha = 0.2f

    /** @return true if this frame may be processed; false if one is already in flight. */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** Call when a processed frame finishes; updates the rolling FPS. */
    fun end() {
        val now = System.nanoTime()
        if (lastEndNanos != 0L) {
            val dt = (now - lastEndNanos) / 1e9f
            if (dt > 0f) {
                val inst = 1f / dt
                emaFps = if (emaFps == 0f) inst else emaFps + alpha * (inst - emaFps)
            }
        }
        lastEndNanos = now
        inFlight.set(false)
    }

    val fps: Float get() = emaFps

    val belowFloor: Boolean get() = emaFps in 0.001f..(com.adasedge.app.core.Config.MIN_SUSTAINED_FPS)
}
