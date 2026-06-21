package com.adasedge.app.perception

import com.adasedge.app.core.Calibration
import com.adasedge.app.core.Config
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.LaneGeometry
import kotlin.math.abs
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
    // Draw band (normalized full-frame y): keep lane points in [horizonRatio,
    // roadBottomRatio] only. Above the horizon the far anchors are sky (the model's
    // existence flag is unreliable there → a wide blob); below the hood is the car.
    private val horizonRatio: Float = Calibration.DEFAULT.horizonRatio,
    private val roadBottomRatio: Float = Calibration.DEFAULT.roadBottomRatio,
    // Row-anchor y-positions in the FULL frame. The exported model is TuSimple, whose
    // anchors are linspace(160, 710, num_row)/720 of the original image — NOT the
    // CULane [0.42, 1.0]. The model input is the full frame's bottom crop_ratio
    // (Preprocess.toLaneInput feeds exactly that), so a row anchor maps straight to
    // this full-frame y — no horizon/crop band, which is what mis-positioned the lanes.
    private val rowAnchorMin: Float = 160f / 720f,   // 0.2222
    private val rowAnchorMax: Float = 710f / 720f,   // 0.9861
    // Temporal low-pass factor on each row's column (1.0 = no smoothing).
    private val temporalAlpha: Float = 0.5f,
) {
    // Per-row temporal EMA of each ego lane's column (normalized x); NaN = unset.
    private val smoothLeft = FloatArray(numRow) { Float.NaN }
    private val smoothRight = FloatArray(numRow) { Float.NaN }

    /**
     * @param gray optional full-frame grayscale (row-major, 0..255, [grayW]x[grayH])
     *   for the marking-snap refinement (hybrid path): each NN lane point is nudged
     *   onto the brightest lane-paint pixel in a small horizontal window, which
     *   tightens onto real markings and bridges dashed gaps. Null = NN-only.
     */
    fun detect(input: FloatArray, gray: ByteArray? = null, grayW: Int = 0, grayH: Int = 0): LaneGeometry? {
        val outs = runner.run(input)
        val loc = outs.firstOrNull { it.data.size >= numLanes * numRow * griding } ?: return null
        val exist = outs.firstOrNull { it !== loc && it.data.size >= numLanes * numRow }

        val left = decodeLane(loc, exist, EGO_LEFT_LANE, smoothLeft, gray, grayW, grayH)
        val right = decodeLane(loc, exist, EGO_RIGHT_LANE, smoothRight, gray, grayW, grayH)
        if (left.isEmpty() && right.isEmpty()) return null

        val conf = existenceConfidence(exist)
        return LaneGeometry(left, right, conf)
    }

    /** Decode one ego lane: soft-argmax per row, existence-gated, temporally smoothed,
     *  optionally snapped to the nearest bright marking pixel. */
    private fun decodeLane(
        loc: TensorOut, exist: TensorOut?, lane: Int, smooth: FloatArray,
        gray: ByteArray?, grayW: Int, grayH: Int,
    ): List<FloatArray> {
        val pts = ArrayList<FloatArray>(numRow)
        for (r in 0 until numRow) {
            val base = (lane * numRow + r) * griding
            if (base + griding > loc.data.size) { smooth[r] = Float.NaN; continue }

            var bestCol = 0; var bestVal = loc.data[base]
            for (c in 1 until griding) {
                val v = loc.data[base + c]
                if (v > bestVal) { bestVal = v; bestCol = c }
            }
            // Row anchor's full-frame y; keep only points inside the road draw band.
            val y = rowAnchorMin + (r / (numRow - 1f)) * (rowAnchorMax - rowAnchorMin)
            val present = (y in horizonRatio..roadBottomRatio) &&
                (exist?.let { it.data[lane * numRow + r] > EXIST_THRESHOLD } ?: (bestVal > minConfidence))
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

            // Hybrid: snap the NN x onto the nearest bright marking pixel (refines onto
            // real paint, bridges dashed gaps); the EMA state stays NN-only so the
            // search stays centred on a stable estimate.
            val xFinal = if (gray != null) snapToMarking(xs, y, gray, grayW, grayH) else xs
            pts += floatArrayOf(xFinal, y)
        }
        if (pts.size < MIN_LANE_POINTS) return emptyList()
        return polyfitSmooth(pts)
    }

    /** Nudge x onto the brightest pixel in a small horizontal window at row y — but
     *  only if that peak is clearly brighter than the local road (a real marking);
     *  otherwise keep the NN x. Markings (white/yellow) are bright in grayscale. */
    private fun snapToMarking(x: Float, y: Float, gray: ByteArray, gw: Int, gh: Int): Float {
        if (gw <= 0 || gh <= 0) return x
        val row = (y * gh).toInt().coerceIn(0, gh - 1)
        val cx = (x * gw).toInt()
        val half = max(2, (SNAP_WINDOW_NORM * gw).toInt())
        val lo = max(0, cx - half); val hi = min(gw - 1, cx + half)
        var bestCol = cx; var bestVal = -1; var sum = 0; var n = 0
        for (c in lo..hi) {
            val v = gray[row * gw + c].toInt() and 0xFF
            sum += v; n++
            if (v > bestVal) { bestVal = v; bestCol = c }
        }
        val avg = if (n > 0) sum / n else 0
        return if (bestVal - avg >= SNAP_MIN_CONTRAST) bestCol / gw.toFloat() else x
    }

    /**
     * Replace the raw per-row points with a quadratic least-squares fit x = a·y² +
     * b·y + c (one outlier-rejection pass), resampled at the same y's. A lane is a
     * smooth curve, so this removes the spatial zig-zag (e.g. from dashed markings)
     * that per-row argmax leaves behind. Falls back to the raw points if the fit is
     * degenerate.
     */
    private fun polyfitSmooth(pts: List<FloatArray>): List<FloatArray> {
        var c = fitQuadratic(pts) ?: return pts
        // Drop points far from the fit, then refit once for robustness to outliers.
        val res = pts.map { abs(it[0] - evalQuad(c, it[1])) }
        val thr = max(0.03f, 2.5f * res.sorted()[res.size / 2])
        val inliers = pts.filterIndexed { i, _ -> res[i] <= thr }
        if (inliers.size in MIN_LANE_POINTS until pts.size) c = fitQuadratic(inliers) ?: c
        val cc = c
        return pts.map { floatArrayOf(evalQuad(cc, it[1]).coerceIn(0f, 1f), it[1]) }
    }

    private fun evalQuad(c: FloatArray, y: Float) = c[0] * y * y + c[1] * y + c[2]

    /** Least-squares quadratic fit of x vs y; null if the normal matrix is singular. */
    private fun fitQuadratic(pts: List<FloatArray>): FloatArray? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val y = p[1].toDouble(); val x = p[0].toDouble()
            val y2 = y * y
            s0 += 1.0; s1 += y; s2 += y2; s3 += y2 * y; s4 += y2 * y2
            t0 += x; t1 += x * y; t2 += x * y2
        }
        // Solve [[s4,s3,s2],[s3,s2,s1],[s2,s1,s0]] · [a,b,c] = [t2,t1,t0] (Cramer's rule).
        val det = s4 * (s2 * s0 - s1 * s1) - s3 * (s3 * s0 - s1 * s2) + s2 * (s3 * s1 - s2 * s2)
        if (abs(det) < 1e-12) return null
        val a = (t2 * (s2 * s0 - s1 * s1) - s3 * (t1 * s0 - s1 * t0) + s2 * (t1 * s1 - s2 * t0)) / det
        val b = (s4 * (t1 * s0 - t0 * s1) - t2 * (s3 * s0 - s1 * s2) + s2 * (s3 * t0 - t1 * s2)) / det
        val cc = (s4 * (s2 * t0 - t1 * s1) - s3 * (s3 * t0 - t1 * s2) + t2 * (s3 * s1 - s2 * s2)) / det
        return floatArrayOf(a.toFloat(), b.toFloat(), cc.toFloat())
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
        const val SNAP_WINDOW_NORM = 0.025f   // horizontal search half-width (frac of width)
        const val SNAP_MIN_CONTRAST = 28      // peak must beat the local-window mean by this (0..255)
    }
}
