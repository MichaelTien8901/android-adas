package com.adasedge.app.perception

/**
 * Time-To-Collision from the growth rate of the lead vehicle's bounding-box
 * scale (adas-perception). Using the looming cue: if the box height h grows, the
 * object approaches; TTC ≈ h / (dh/dt). A small Kalman-like smoother stabilizes
 * the scale and its rate so single-frame noise does not produce spurious TTC.
 */
class TtcEstimator {
    private var lastHeight = 0f
    private var lastTsNanos = 0L
    private var smoothedRate = 0f
    private val rateAlpha = 0.4f

    /** @return TTC seconds; POSITIVE_INFINITY when not closing or not enough data. */
    fun update(boxHeightNorm: Float, tsNanos: Long): Float {
        if (lastTsNanos == 0L || boxHeightNorm <= 0f) {
            lastHeight = boxHeightNorm; lastTsNanos = tsNanos
            return Float.POSITIVE_INFINITY
        }
        val dt = (tsNanos - lastTsNanos) / 1e9f
        if (dt <= 0f) return ttcFrom(boxHeightNorm)
        val rate = (boxHeightNorm - lastHeight) / dt
        smoothedRate += rateAlpha * (rate - smoothedRate)
        lastHeight = boxHeightNorm; lastTsNanos = tsNanos
        return ttcFrom(boxHeightNorm)
    }

    private fun ttcFrom(h: Float): Float =
        if (smoothedRate > 1e-4f) h / smoothedRate else Float.POSITIVE_INFINITY

    fun reset() { lastHeight = 0f; lastTsNanos = 0L; smoothedRate = 0f }
}
