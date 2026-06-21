package com.adasedge.app.perception

import com.adasedge.app.core.Calibration
import com.adasedge.app.core.Config
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.model.LaneGeometry
import kotlin.math.abs

/**
 * Ultra-Fast-Lane-Detection v2 decoder (design D5: NPU lane model in v1).
 * UFLDv2 predicts, per lane and per row-anchor, a distribution over griding
 * columns plus an existence flag. We argmax the column distribution, gate by
 * existence, then select the ego-left and ego-right lanes as those nearest the
 * bottom-center.
 *
 * NOTE: the griding / row-anchor counts MUST match the exported model's training
 * config; defaults below follow the CULane UFLDv2 config and should be adjusted
 * if you retrain. When output shapes don't match, [detect] returns null so the
 * pipeline reports lanes unavailable (adas-perception: "Lanes not detectable").
 */
class LaneDetector(
    private val runner: ModelRunner,
    private val numLanes: Int = 4,
    private val numRow: Int = 56,
    private val griding: Int = 100,
    private val minConfidence: Float = 0.35f,
    private val cropRatio: Float = Config.LANE_CROP_RATIO,
    private val horizonRatio: Float = Calibration.DEFAULT.horizonRatio,
    // UFLDv2 TuSimple row anchors span only [0.42, 1.0] of the model input
    // (deploy: row_anchor = linspace(0.42, 1, num_row)), NOT the full input height.
    private val rowAnchorMin: Float = 0.42f,
    private val rowAnchorMax: Float = 1.0f,
) {
    // Top of the lane-input region in full-frame normalized-y (sky crop + UFLDv2's
    // internal bottom-crop). Lane row-anchor y in [0,1] maps to [yTop, 1] of the frame.
    private val yTop = horizonRatio + (1f - cropRatio) * (1f - horizonRatio)

    // Smoothed ego-lane centre; the left/right split point tracks the lane across
    // frames so a boundary crossing image-centre keeps its side (see [detect]).
    private var laneCenter = 0.5f

    fun detect(input: FloatArray): LaneGeometry? {
        val outs = runner.run(input)
        // Expect a location tensor [.. numLanes*numRow*griding ..] and existence.
        val loc = outs.firstOrNull { it.data.size >= numLanes * numRow * griding } ?: return null
        val exist = outs.firstOrNull { it !== loc && it.data.size >= numLanes * numRow }

        val lanes = ArrayList<List<FloatArray>>()
        var totalConf = 0f; var counted = 0
        for (l in 0 until numLanes) {
            val pts = ArrayList<FloatArray>()
            for (r in 0 until numRow) {
                val base = (l * numRow + r) * griding
                if (base + griding > loc.data.size) continue
                var bestCol = -1; var bestVal = Float.NEGATIVE_INFINITY
                for (c in 0 until griding) {
                    val v = loc.data[base + c]
                    if (v > bestVal) { bestVal = v; bestCol = c }
                }
                val present = exist?.let { it.data[l * numRow + r] > 0.5f } ?: (bestVal > minConfidence)
                if (!present || bestCol < 0) continue
                val x = bestCol / (griding - 1f)
                // Map the row-anchor back to the full frame. The anchors only cover
                // [rowAnchorMin, rowAnchorMax] of the model input — placing them at
                // their true input fraction first (instead of spreading 0..1 across
                // [yTop,1]) fixes the lane top being drawn far too high (alignment).
                val anchorFrac = rowAnchorMin + (r / (numRow - 1f)) * (rowAnchorMax - rowAnchorMin)
                val y = yTop + anchorFrac * (1f - yTop)
                pts += floatArrayOf(x, y)
                totalConf += sigmoidish(bestVal); counted++
            }
            if (pts.size >= 4) lanes += pts
        }
        if (lanes.isEmpty()) { laneCenter = 0.5f; return null }

        // Ego lanes: the boundaries just left / right of the lane centre. The split
        // point is a SMOOTHED lane centre (not a fixed 0.5), so that as the car
        // departs and a boundary slides across image-centre it stays on its own side
        // instead of snapping into the other bucket — which otherwise makes the
        // overlay's far line jump and hides the departure on that side.
        val withBottomX = lanes.map { it to (it.maxByOrNull { p -> p[1] }?.get(0) ?: 0.5f) }
        val leftPair = withBottomX.filter { it.second <= laneCenter }.maxByOrNull { it.second }
        val rightPair = withBottomX.filter { it.second > laneCenter }.minByOrNull { it.second }
        if (leftPair == null && rightPair == null) { laneCenter = 0.5f; return null }

        // Re-centre the split from the chosen pair (clamped so noise / a full lane
        // change can't run it away; it re-seeds to 0.5 whenever lanes are lost).
        if (leftPair != null && rightPair != null) {
            val mid = (leftPair.second + rightPair.second) / 2f
            laneCenter = (laneCenter + Config.LANE_CENTER_SMOOTH * (mid - laneCenter)).coerceIn(0.35f, 0.65f)
        }

        val conf = if (counted > 0) (totalConf / counted).coerceIn(0f, 1f) else 0f
        return LaneGeometry(leftPair?.first ?: emptyList(), rightPair?.first ?: emptyList(), conf)
    }

    private fun sigmoidish(v: Float): Float = 1f / (1f + abs(v).let { kotlin.math.exp(-it) })
}
