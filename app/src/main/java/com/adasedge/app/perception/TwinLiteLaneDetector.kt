package com.adasedge.app.perception

import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.LaneGeometry
import kotlin.math.max

/**
 * TwinLiteNet decoder (lane-detector-bakeoff, branch eval/twinlitenet-drivable-area).
 *
 * TwinLiteNet is a lightweight multi-task SEGMENTATION model (input 1×3×360×640) with
 * two heads, each 2-class argmax over the full frame:
 *   - `da` [1,2,360,640] — drivable-area mask
 *   - `ll` [1,2,360,640] — lane-line mask
 *
 * Unlike UFLDv2 (which classifies "which of 4 line-slots is the ego boundary" and gets
 * the right slot wrong on multi-lane roads), here the ego boundaries are read directly
 * from the dense lane-line mask: for each row, the nearest lane-line pixel on each side
 * of the ego centre column IS the ego-left / ego-right boundary. No slot assignment, so
 * the failure mode we measured in UFLDv2 cannot occur. If `ll` is empty on a row we fall
 * back to the drivable-area mask edge (the corridor boundary) for that side.
 *
 * Output is the same `LaneGeometry` contract the warnings/overlay consume.
 */
class TwinLiteLaneDetector(
    private val runner: ModelRunner,
    private val segW: Int = 640,
    private val segH: Int = 360,
    private val centerRatio: Float = 0.5f,
    private val horizonRatio: Float = 0.45f,
    private val roadBottomRatio: Float = 1.0f,
    useTracker: Boolean = true,
) {
    // Same Kalman lane-coefficient tracker used for UFLDv2: smooths the lane curve over
    // time and gates per-frame outliers (the right boundary's near-field jumps) via the
    // chi-square + lateral-jump test. On by default for TwinLiteNet — the seg lane head
    // is accurate but un-smoothed and occasionally noisy on the dashed right boundary.
    private val tracker: LaneTracker? = if (useTracker) LaneTracker(horizonRatio, roadBottomRatio) else null

    fun detect(input: FloatArray): LaneGeometry? {
        val outs = runner.run(input)
        // NOTE: positional fallback = 1. The ORT path finds "ll" by name; the QNN
        // path returns unnamed out0/out1 in graph order [da, ll], so ll is index 1
        // (index 0 = da — decoding it as lane-lines makes every centre pixel "road"
        // and yields avail=0%).
        val ll = pick(outs, "ll", 1) ?: return null
        val plane = segW * segH
        if (ll.data.size < 2 * plane) return null

        // Vertical-window lane test: true if ANY row within ±DILATE_R at this column is
        // a lane pixel. Bridges dashed-marking gaps (and INT8-thinned dashes) along the
        // line's near-vertical direction WITHOUT shifting the boundary x — so the right
        // boundary stays measured on more rows and the tracker coasts (and disappears)
        // far less, while a genuine right drift is still detected (right-side LDW intact).
        fun isLane(x: Int, y: Int): Boolean {
            val y0 = max(0, y - DILATE_R); val y1 = kotlin.math.min(segH - 1, y + DILATE_R)
            var yy = y0
            while (yy <= y1) {
                val o = yy * segW + x
                if (ll.data[plane + o] > ll.data[o]) return true
                yy++
            }
            return false
        }

        val yTop = (horizonRatio * segH).toInt().coerceIn(0, segH - 1)
        val yBot = (roadBottomRatio * segH).toInt().coerceIn(0, segH - 1)
        val step = max(1, (yBot - yTop) / SAMPLE_ROWS)
        val cx0 = (centerRatio * segW).toInt().coerceIn(0, segW - 1)
        // Cap the search to a plausible half-lane so the right boundary can't snap to a
        // far lane line / the road edge (the R→1.0 outliers); ll-only (no wide da edge).
        val half = (MAX_HALF * segW).toInt()
        val loX = max(0, cx0 - half)
        val hiX = kotlin.math.min(segW - 1, cx0 + half)

        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        var y = yBot
        while (y > yTop) {
            // Nearest lane-line pixel each side of centre, within the half-lane cap.
            var lx = -1
            run { var x = cx0; while (x >= loX) { if (isLane(x, y)) { lx = x; break }; x-- } }
            var rx = -1
            run { var x = cx0; while (x <= hiX) { if (isLane(x, y)) { rx = x; break }; x++ } }

            val yn = y / segH.toFloat()
            if (lx in loX until cx0) left += floatArrayOf(lx / segW.toFloat(), yn)
            if (rx in (cx0 + 1)..hiX) right += floatArrayOf(rx / segW.toFloat(), yn)
            y -= step
        }
        if (left.size < MIN_PTS && right.size < MIN_PTS) return null
        // Order far→near (top→bottom) to match the UFLDv2 output convention.
        left.reverse(); right.reverse()

        if (tracker != null) {
            val (tl, tr) = tracker.update(left, right, 0.9f)
            if (tl.isEmpty() && tr.isEmpty()) return null
            return LaneGeometry(tl, tr, 1f)
        }
        return LaneGeometry(left, right, 1f)
    }

    /** Pick an output by name, else by positional fallback index. */
    private fun pick(outs: List<TensorOut>, name: String, idx: Int): TensorOut? =
        outs.firstOrNull { it.name == name } ?: outs.getOrNull(idx)

    private companion object {
        const val SAMPLE_ROWS = 28   // rows sampled across the road band
        const val MIN_PTS = 4
        const val MAX_HALF = 0.34f   // max |boundary − centre| (frac of width); caps the right snap
        const val DILATE_R = 4       // vertical dilation radius (rows) to bridge dashed-line gaps
    }
}
