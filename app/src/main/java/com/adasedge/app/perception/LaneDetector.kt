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
    private val centerRatio: Float = Calibration.DEFAULT.centerRatio,
    // Fit lanes in a bird's-eye (top-down) view where they're straight & parallel.
    private val birdEyeFit: Boolean = false,
) {
    // Per-row temporal EMA of each ego lane's column (normalized x); NaN = unset.
    private val smoothLeft = FloatArray(numRow) { Float.NaN }
    private val smoothRight = FloatArray(numRow) { Float.NaN }

    // Self-aligning bird's-eye homography. The source trapezoid is the EGO LANE itself
    // (its two boundary lines at trapTopY/trapBotY), estimated from the detected lanes
    // and temporally smoothed — so the warp tracks the real road on any mount instead
    // of a fixed calibration shape, and the lanes warp to ~vertical by construction.
    private val trapTopY = horizonRatio
    private val trapBotY = roadBottomRatio
    // [topLeftX, topRightX, botLeftX, botRightX]; seeded from calibration, then refined.
    private val corners = floatArrayOf(
        centerRatio - BEV_TOP_HALF.toFloat(), centerRatio + BEV_TOP_HALF.toFloat(),
        centerRatio - BEV_BOT_HALF.toFloat(), centerRatio + BEV_BOT_HALF.toFloat(),
    )
    private var bevH: DoubleArray? = if (birdEyeFit) buildH(false) else null
    private var bevHinv: DoubleArray? = if (birdEyeFit) buildH(true) else null

    private fun buildH(inverse: Boolean): DoubleArray {
        val src = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(corners[0].toDouble(), trapTopY.toDouble()),   // top-left
            org.opencv.core.Point(corners[1].toDouble(), trapTopY.toDouble()),   // top-right
            org.opencv.core.Point(corners[3].toDouble(), trapBotY.toDouble()),   // bottom-right
            org.opencv.core.Point(corners[2].toDouble(), trapBotY.toDouble()),   // bottom-left
        )
        val dst = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(BEV_X0, 0.0), org.opencv.core.Point(BEV_X1, 0.0),
            org.opencv.core.Point(BEV_X1, 1.0), org.opencv.core.Point(BEV_X0, 1.0),
        )
        val m = if (inverse) org.opencv.imgproc.Imgproc.getPerspectiveTransform(dst, src)
                else org.opencv.imgproc.Imgproc.getPerspectiveTransform(src, dst)
        val arr = DoubleArray(9); m.get(0, 0, arr)
        m.release(); src.release(); dst.release()
        return arr
    }

    /** Re-estimate the source trapezoid from the detected lanes (their boundary lines
     *  at trapTopY/trapBotY) and EMA it, then rebuild the homography. Falls back to the
     *  last/seed trapezoid when a frame's lanes are missing or implausible. */
    private fun updateHomography(left: List<FloatArray>, right: List<FloatArray>) {
        if (left.size >= MIN_LANE_POINTS && right.size >= MIN_LANE_POINTS) {
            val l = fitLine(left); val r = fitLine(right)
            val tlx = l[0] * trapTopY + l[1]; val blx = l[0] * trapBotY + l[1]
            val trx = r[0] * trapTopY + r[1]; val brx = r[0] * trapBotY + r[1]
            // sanity: left of right, plausible widths, on-frame-ish
            if (trx - tlx > 0.005f && brx - blx > 0.04f && tlx > -0.3f && brx < 1.3f) {
                corners[0] += CORNER_EMA * (tlx - corners[0])
                corners[1] += CORNER_EMA * (trx - corners[1])
                corners[2] += CORNER_EMA * (blx - corners[2])
                corners[3] += CORNER_EMA * (brx - corners[3])
                bevH = buildH(false); bevHinv = buildH(true)
                return
            }
        }
        if (bevH == null) { bevH = buildH(false); bevHinv = buildH(true) }   // ensure seeded
    }

    /** Least-squares line x = m·y + c over the points (m≈0 for a vertical lane). */
    private fun fitLine(pts: List<FloatArray>): FloatArray {
        var sy = 0.0; var sy2 = 0.0; var sx = 0.0; var sxy = 0.0
        for (p in pts) { val y = p[1].toDouble(); val x = p[0].toDouble(); sy += y; sy2 += y * y; sx += x; sxy += x * y }
        val n = pts.size
        val den = n * sy2 - sy * sy
        if (kotlin.math.abs(den) < 1e-9) return floatArrayOf(0f, (sx / n).toFloat())
        val m = (n * sxy - sy * sx) / den
        return floatArrayOf(m.toFloat(), ((sx - m * sy) / n).toFloat())
    }

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

        val leftRaw = decodeLane(loc, exist, EGO_LEFT_LANE, smoothLeft, gray, grayW, grayH)
        val rightRaw = decodeLane(loc, exist, EGO_RIGHT_LANE, smoothRight, gray, grayW, grayH)
        if (leftRaw.isEmpty() && rightRaw.isEmpty()) return null

        val (left, right) = if (birdEyeFit) {
            updateHomography(leftRaw, rightRaw)   // self-align the top-down warp to the lanes
            bevFit(leftRaw, rightRaw)
        } else {
            (if (leftRaw.isEmpty()) emptyList() else smoothLane(leftRaw)) to
                (if (rightRaw.isEmpty()) emptyList() else smoothLane(rightRaw))
        }
        return LaneGeometry(left, right, existenceConfidence(exist))
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
            val exr = exist?.data?.get(lane * numRow + r) ?: 1f
            val present = (y in horizonRatio..roadBottomRatio) &&
                (if (exist != null) exr > EXIST_THRESHOLD else bestVal > minConfidence)
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
            pts += floatArrayOf(xFinal, y, exr.coerceIn(0.1f, 1f))   // [x, y, confidence weight]
        }
        return if (pts.size < MIN_LANE_POINTS) emptyList() else pts   // raw [x,y,weight]; smoothed/fit by caller
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
     * LOCAL smoothing of the per-row lane points (keeps them on the actual markings,
     * unlike a global parabola which is too rigid and drifts off the dashed line):
     *  1. a small median filter kills single-row outliers (e.g. a far-field column
     *     jump) WITHOUT moving the good points, then
     *  2. a short moving average removes the residual zig-zag.
     * Points come in row order (y ascending), so the windows are along the lane.
     */
    private fun smoothLane(pts: List<FloatArray>): List<FloatArray> {
        val n = pts.size
        if (n < 3) return pts
        val x = FloatArray(n) { pts[it][0] }
        val med = FloatArray(n)
        val win = FloatArray(2 * MED_RADIUS + 1)
        for (i in 0 until n) {
            val lo = max(0, i - MED_RADIUS); val hi = min(n - 1, i + MED_RADIUS)
            var k = 0
            for (j in lo..hi) win[k++] = x[j]
            java.util.Arrays.sort(win, 0, k)
            med[i] = win[k / 2]
        }
        val out = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val lo = max(0, i - AVG_RADIUS); val hi = min(n - 1, i + AVG_RADIUS)
            var s = 0f
            for (j in lo..hi) s += med[j]
            out += floatArrayOf((s / (hi - lo + 1)).coerceIn(0f, 1f), pts[i][1])
        }
        return out
    }

    /**
     * Bird's-eye fit: warp the points to the top-down view (where lanes are straight
     * & parallel), robustly fit each lane there (a quadratic now MATCHES the geometry,
     * unlike in perspective), then COUPLE them — the cleaner lane (lower residual,
     * usually the solid one) lends its curvature (a,b) to both, and each solves only
     * its own offset c. So the noisy dashed boundary inherits the solid line's shape.
     * Finally warp the fitted points back to perspective for drawing.
     */
    private fun bevFit(leftRaw: List<FloatArray>, rightRaw: List<FloatArray>): Pair<List<FloatArray>, List<FloatArray>> {
        val h = bevH!!; val hinv = bevHinv!!
        val lb = leftRaw.map { warp(it, h) }
        val rb = rightRaw.map { warp(it, h) }
        var fL = if (lb.size >= 3) robustQuad(lb) else null
        var fR = if (rb.size >= 3) robustQuad(rb) else null
        if (fL != null && fR != null) {
            val a: Float; val b: Float
            if (fL.second <= fR.second) { a = fL.first[0]; b = fL.first[1] } else { a = fR.first[0]; b = fR.first[1] }
            fL = floatArrayOf(a, b, offsetC(lb, a, b)) to fL.second
            fR = floatArrayOf(a, b, offsetC(rb, a, b)) to fR.second
        }
        val left = fL?.let { resampleBack(lb, it.first, hinv) } ?: emptyList()
        val right = fR?.let { resampleBack(rb, it.first, hinv) } ?: emptyList()
        return left to right
    }

    /** Apply a 3x3 homography to a normalized point; keeps the weight (3rd element). */
    private fun warp(p: FloatArray, m: DoubleArray): FloatArray {
        val x = p[0].toDouble(); val y = p[1].toDouble()
        val w = m[6] * x + m[7] * y + m[8]
        return floatArrayOf(((m[0] * x + m[1] * y + m[2]) / w).toFloat(),
            ((m[3] * x + m[4] * y + m[5]) / w).toFloat(), if (p.size > 2) p[2] else 1f)
    }

    /** Weighted mean offset c given shared curvature (a,b): c = mean(x - a·y² - b·y). */
    private fun offsetC(pts: List<FloatArray>, a: Float, b: Float): Float {
        var s = 0f; var ws = 0f
        for (p in pts) { val w = p[2]; s += w * (p[0] - a * p[1] * p[1] - b * p[1]); ws += w }
        return if (ws > 0f) s / ws else 0f
    }

    private fun resampleBack(pts: List<FloatArray>, c: FloatArray, hinv: DoubleArray): List<FloatArray> =
        pts.map {
            val yb = it[1]; val xb = c[0] * yb * yb + c[1] * yb + c[2]
            val p = warp(floatArrayOf(xb, yb), hinv)
            floatArrayOf(p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f))
        }

    /** IRLS robust quadratic fit x = a·y² + b·y + c; returns coeffs + median |residual|. */
    private fun robustQuad(pts: List<FloatArray>): Pair<FloatArray, Float> {
        val n = pts.size
        val w = FloatArray(n) { pts[it][2] }
        var c = fitWeighted(pts, w) ?: floatArrayOf(0f, 0f, pts[n / 2][0])
        repeat(IRLS_ITERS) {
            for (i in 0 until n) {
                val res = (pts[i][0] - (c[0] * pts[i][1] * pts[i][1] + c[1] * pts[i][1] + c[2])) / IRLS_SCALE
                w[i] = pts[i][2] / (1f + res * res)
            }
            c = fitWeighted(pts, w) ?: c
        }
        val resids = pts.map { kotlin.math.abs(it[0] - (c[0] * it[1] * it[1] + c[1] * it[1] + c[2])) }.sorted()
        return c to resids[resids.size / 2]
    }

    /** Weighted least-squares quadratic fit of x vs y; null if the matrix is singular. */
    private fun fitWeighted(pts: List<FloatArray>, w: FloatArray): FloatArray? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (i in pts.indices) {
            val y = pts[i][1].toDouble(); val x = pts[i][0].toDouble(); val wi = w[i].toDouble()
            val y2 = y * y
            s0 += wi; s1 += wi * y; s2 += wi * y2; s3 += wi * y2 * y; s4 += wi * y2 * y2
            t0 += wi * x; t1 += wi * x * y; t2 += wi * x * y2
        }
        val det = s4 * (s2 * s0 - s1 * s1) - s3 * (s3 * s0 - s1 * s2) + s2 * (s3 * s1 - s2 * s2)
        if (kotlin.math.abs(det) < 1e-12) return null
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
        const val MED_RADIUS = 2              // median-filter window radius (kills single-row outliers)
        const val AVG_RADIUS = 2              // moving-average window radius (de-zigzag)
        const val IRLS_ITERS = 3             // robust reweighting passes (bird's-eye fit)
        const val IRLS_SCALE = 0.05f         // BEV residual (norm) at which a point's weight halves
        // Bird's-eye source trapezoid SEED half-widths (used until the lanes refine it).
        const val BEV_TOP_HALF = 0.06        // near the horizon (lanes nearly meet)
        const val BEV_BOT_HALF = 0.42        // near the camera (lanes wide)
        const val BEV_X0 = 0.25              // destination rectangle left/right
        const val BEV_X1 = 0.75
        const val CORNER_EMA = 0.1f          // how fast the self-aligning trapezoid tracks the lanes
    }
}
