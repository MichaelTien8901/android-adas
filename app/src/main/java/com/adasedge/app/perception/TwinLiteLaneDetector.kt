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
) {
    fun detect(input: FloatArray): LaneGeometry? {
        val outs = runner.run(input)
        val ll = pick(outs, "ll", 0) ?: return null
        val da = pick(outs, "da", 1)
        val plane = segW * segH
        if (ll.data.size < 2 * plane) return null

        fun isLane(x: Int, y: Int): Boolean {
            val o = y * segW + x; return ll.data[plane + o] > ll.data[o]
        }
        fun isDriv(x: Int, y: Int): Boolean {
            if (da == null || da.data.size < 2 * plane) return false
            val o = y * segW + x; return da.data[plane + o] > da.data[o]
        }

        val yTop = (horizonRatio * segH).toInt().coerceIn(0, segH - 1)
        val yBot = (roadBottomRatio * segH).toInt().coerceIn(0, segH - 1)
        val step = max(1, (yBot - yTop) / SAMPLE_ROWS)
        val cx0 = (centerRatio * segW).toInt().coerceIn(0, segW - 1)

        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        var y = yBot
        while (y > yTop) {
            // Nearest lane-line pixel each side of centre = ego boundary.
            var lx = -1
            run { var x = cx0; while (x >= 0) { if (isLane(x, y)) { lx = x; break }; x-- } }
            var rx = -1
            run { var x = cx0; while (x < segW) { if (isLane(x, y)) { rx = x; break }; x++ } }
            // Fallback to the drivable-corridor edge when the lane head is blank here.
            if (lx < 0 && isDriv(cx0, y)) { var x = cx0; while (x > 0 && isDriv(x - 1, y)) x--; lx = x }
            if (rx < 0 && isDriv(cx0, y)) { var x = cx0; while (x < segW - 1 && isDriv(x + 1, y)) x++; rx = x }

            val yn = y / segH.toFloat()
            if (lx in 0 until cx0) left += floatArrayOf(lx / segW.toFloat(), yn)
            if (rx in (cx0 + 1) until segW) right += floatArrayOf(rx / segW.toFloat(), yn)
            y -= step
        }
        if (left.size < MIN_PTS && right.size < MIN_PTS) return null
        // Order far→near (top→bottom) to match the UFLDv2 output convention.
        left.reverse(); right.reverse()
        return LaneGeometry(left, right, 1f)
    }

    /** Pick an output by name, else by positional fallback index. */
    private fun pick(outs: List<TensorOut>, name: String, idx: Int): TensorOut? =
        outs.firstOrNull { it.name == name } ?: outs.getOrNull(idx)

    private companion object {
        const val SAMPLE_ROWS = 28   // rows sampled across the road band
        const val MIN_PTS = 4
    }
}
