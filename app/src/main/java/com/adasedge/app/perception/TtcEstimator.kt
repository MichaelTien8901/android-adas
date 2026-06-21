package com.adasedge.app.perception

/**
 * Time-To-Collision from the growth rate of the lead vehicle's bounding-box
 * scale (adas-perception). Using the looming cue: if the box height h grows, the
 * object approaches; TTC ≈ h / (dh/dt). A small Kalman-like smoother stabilizes
 * the scale and its rate so single-frame noise does not produce spurious TTC.
 */
class TtcEstimator {
    private var smoothedHeight = 0f
    private var lastHeight = 0f
    private var lastTsNanos = 0L
    private var smoothedRate = 0f
    private val heightAlpha = 0.5f   // low-pass the raw bbox height (detection jitter)
    private val rateAlpha = 0.35f    // then low-pass its growth rate

    /** @return TTC seconds; POSITIVE_INFINITY when not closing or not enough data. */
    fun update(boxHeightNorm: Float, tsNanos: Long): Float {
        if (lastTsNanos == 0L || boxHeightNorm <= 0f) {
            smoothedHeight = boxHeightNorm; lastHeight = boxHeightNorm; lastTsNanos = tsNanos
            return Float.POSITIVE_INFINITY
        }
        // Smooth the height first so frame-to-frame box jitter doesn't masquerade as
        // a fast approach (the main source of false FCW alarms on real footage).
        smoothedHeight += heightAlpha * (boxHeightNorm - smoothedHeight)
        val dt = (tsNanos - lastTsNanos) / 1e9f
        if (dt <= 0f) return ttcFrom(smoothedHeight)
        val rate = (smoothedHeight - lastHeight) / dt
        smoothedRate += rateAlpha * (rate - smoothedRate)
        lastHeight = smoothedHeight; lastTsNanos = tsNanos
        return ttcFrom(smoothedHeight)
    }

    private fun ttcFrom(h: Float): Float =
        if (smoothedRate > 1e-4f) h / smoothedRate else Float.POSITIVE_INFINITY

    fun reset() { smoothedHeight = 0f; lastHeight = 0f; lastTsNanos = 0L; smoothedRate = 0f }
}
