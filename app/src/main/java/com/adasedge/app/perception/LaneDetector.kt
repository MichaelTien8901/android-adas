package com.adasedge.app.perception

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
) {
    fun detect(prepared: Preprocess.Prepared): LaneGeometry? {
        val outs = runner.run(prepared.input)
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
                val y = r / (numRow - 1f)
                pts += floatArrayOf(x, y)
                totalConf += sigmoidish(bestVal); counted++
            }
            if (pts.size >= 4) lanes += pts
        }
        if (lanes.isEmpty()) return null

        // Ego lanes: the lane whose bottom point is just left of center, and the
        // one just right of center.
        val withBottomX = lanes.map { it to (it.maxByOrNull { p -> p[1] }?.get(0) ?: 0.5f) }
        val left = withBottomX.filter { it.second <= 0.5f }.maxByOrNull { it.second }?.first
        val right = withBottomX.filter { it.second > 0.5f }.minByOrNull { it.second }?.first
        if (left == null && right == null) return null

        val conf = if (counted > 0) (totalConf / counted).coerceIn(0f, 1f) else 0f
        return LaneGeometry(left ?: emptyList(), right ?: emptyList(), conf)
    }

    private fun sigmoidish(v: Float): Float = 1f / (1f + abs(v).let { kotlin.math.exp(-it) })
}
