package com.adasedge.app.perception

import com.adasedge.app.core.Calibration
import com.adasedge.app.core.Config
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.LaneGeometry
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-Fast-Lane-Detection v2 decoder (design D5: NPU lane model in v1).
 *
 * UFLDv2 predicts, per lane and per row-anchor, a distribution over [griding]
 * columns plus an existence probability. Decoding follows the authors' own deploy
 * code (Ultra-Fast-Lane-Detection-v2/demo.py) to keep the output smooth:
 *
 *  - **Ego lanes only.** Only slots 1 (ego-left) and 2 (ego-right) are valid on the
 *    ROW-anchor branch; the outer lanes (0, 3) belong to a separate column branch
 *    we don't export, so decoding them on the row branch produced scattered noise.
 *    Slot 1 is the left boundary, slot 2 the right — a stable, semantic assignment
 *    that needs no centre-split heuristic and is correct even mid-departure.
 *  - **Soft-argmax.** Each column is a softmax-weighted expectation over a +/-1
 *    window around the argmax (sub-cell precision) instead of a raw argmax, which
 *    otherwise snaps to integer columns and stair-steps.
 *  - **Temporal EMA.** Each row's column is low-passed across frames for stability.
 *
 * The griding / row-anchor counts MUST match the exported model; on a shape
 * mismatch [detect] returns null so the pipeline reports lanes unavailable.
 */
class LaneDetector(
    private val runner: ModelRunner,
    private val numLanes: Int = 4,
    private val numRow: Int = 56,
    private val griding: Int = 100,
    private val minConfidence: Float = 0.35f,
    private val cropRatio: Float = Config.LANE_CROP_RATIO,
    private val horizonRatio: Float = Calibration.DEFAULT.horizonRatio,
    private val roadBottomRatio: Float = Calibration.DEFAULT.roadBottomRatio,
    // UFLDv2 TuSimple row anchors span only [0.42, 1.0] of the model input
    // (deploy: row_anchor = linspace(0.42, 1, num_row)), NOT the full input height.
    private val rowAnchorMin: Float = 0.42f,
    private val rowAnchorMax: Float = 1.0f,
    // Temporal low-pass factor on each row's column (1.0 = no smoothing).
    private val temporalAlpha: Float = 0.5f,
) {
    // Lane-input region in full-frame normalized-y: [yTop, yBottom]. yTop drops the
    // sky (horizon) + UFLDv2's internal top-crop; yBottom drops the car hood.
    private val yBottom = roadBottomRatio
    private val yTop = horizonRatio + (1f - cropRatio) * (roadBottomRatio - horizonRatio)

    // Per-row temporal EMA of each ego lane's column (normalized x); NaN = unset.
    private val smoothLeft = FloatArray(numRow) { Float.NaN }
    private val smoothRight = FloatArray(numRow) { Float.NaN }

    fun detect(input: FloatArray): LaneGeometry? {
        val outs = runner.run(input)
        val loc = outs.firstOrNull { it.data.size >= numLanes * numRow * griding } ?: return null
        val exist = outs.firstOrNull { it !== loc && it.data.size >= numLanes * numRow }

        val left = decodeLane(loc, exist, EGO_LEFT_LANE, smoothLeft)
        val right = decodeLane(loc, exist, EGO_RIGHT_LANE, smoothRight)
        if (left.isEmpty() && right.isEmpty()) return null

        val conf = existenceConfidence(exist)
        return LaneGeometry(left, right, conf)
    }

    /** Decode one ego lane: soft-argmax per row, existence-gated, temporally smoothed. */
    private fun decodeLane(loc: TensorOut, exist: TensorOut?, lane: Int, smooth: FloatArray): List<FloatArray> {
        val pts = ArrayList<FloatArray>(numRow)
        for (r in 0 until numRow) {
            val base = (lane * numRow + r) * griding
            if (base + griding > loc.data.size) { smooth[r] = Float.NaN; continue }

            var bestCol = 0; var bestVal = loc.data[base]
            for (c in 1 until griding) {
                val v = loc.data[base + c]
                if (v > bestVal) { bestVal = v; bestCol = c }
            }
            val present = exist?.let { it.data[lane * numRow + r] > EXIST_THRESHOLD } ?: (bestVal > minConfidence)
            if (!present) { smooth[r] = Float.NaN; continue }

            // Soft-argmax: softmax-weighted column over a +/-LOCAL_W window (sub-cell).
            val lo = max(0, bestCol - LOCAL_W); val hi = min(griding - 1, bestCol + LOCAL_W)
            var sumExp = 0f; var sumWeighted = 0f
            for (c in lo..hi) {
                val e = exp(loc.data[base + c] - bestVal)
                sumExp += e; sumWeighted += e * c
            }
            val refinedCol = if (sumExp > 0f) sumWeighted / sumExp + 0.5f else bestCol.toFloat()
            val xRaw = (refinedCol / (griding - 1f)).coerceIn(0f, 1f)

            val xs = if (smooth[r].isNaN()) xRaw else smooth[r] + temporalAlpha * (xRaw - smooth[r])
            smooth[r] = xs

            val anchorFrac = rowAnchorMin + (r / (numRow - 1f)) * (rowAnchorMax - rowAnchorMin)
            val y = yTop + anchorFrac * (yBottom - yTop)
            pts += floatArrayOf(xs, y)
        }
        return if (pts.size >= MIN_LANE_POINTS) pts else emptyList()
    }

    /** Mean existence probability over present ego-lane anchors (0.5 if none/no head). */
    private fun existenceConfidence(exist: TensorOut?): Float {
        if (exist == null) return 0.5f
        var sum = 0f; var n = 0
        for (lane in intArrayOf(EGO_LEFT_LANE, EGO_RIGHT_LANE)) {
            for (r in 0 until numRow) {
                val p = exist.data[lane * numRow + r]
                if (p > EXIST_THRESHOLD) { sum += p; n++ }
            }
        }
        return if (n > 0) (sum / n).coerceIn(0f, 1f) else 0.5f
    }

    private companion object {
        const val EGO_LEFT_LANE = 1     // UFLDv2 row-anchor ego lanes are slots [1, 2]
        const val EGO_RIGHT_LANE = 2
        const val LOCAL_W = 1           // soft-argmax window radius (demo.py local_width)
        const val EXIST_THRESHOLD = 0.5f
        const val MIN_LANE_POINTS = 6
    }
}
