package com.adasedge.app.perception

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Temporal lane-coefficient tracker — the on-device classical surrogate for openpilot's
 * recurrent GRU state (see openspec change `openpilot-inspired-lane-stability`,
 * `research/01-openpilot-reuse-analysis.md`, design D1–D7).
 *
 * Each ego boundary's quadratic `x = a·y² + b·y + c` is tracked through a Kalman filter
 * in coefficient space. With state-transition `F = I`, measurement `H = I`, and DIAGONAL
 * process/measurement noise, the filter decouples into three independent scalar Kalmans
 * on `(a, b, c)` (so no matrix inverse is needed); their summed normalized innovation is
 * the chi-square outlier gate (design D5). A rejected or missing measurement makes the
 * track COAST (predict-only) for up to [TRACK_COAST_MAX] frames before going stale, so
 * dashed gaps and brief dropouts are bridged ("virtual lane lines") without inventing
 * geometry forever (design D6). A left/right lane-width + no-crossing guard rejects the
 * adjacent-lane-mixing measurement before it can corrupt the track (design D7).
 *
 * The tracker is frame-agnostic: it consumes whatever per-frame geometry the caller
 * produced (image-space smoothed points, or bird's-eye resampled points) and emits a
 * temporally stable curve resampled over the draw band [yTop, yBot]. It is core to both
 * lane paths now: TwinLite always tracks, and the legacy UFLDv2 fallback tracks too.
 */
class LaneTracker(
    private val yTop: Float,
    private val yBot: Float,
    private val resampleCount: Int = RESAMPLE_N,
) {
    private val left = Boundary()
    private val right = Boundary()

    /**
     * Advance both boundary tracks by one frame and return the tracked geometry.
     *
     * @param leftPts per-frame left-boundary points `[x, y]` (may be empty → coast)
     * @param rightPts per-frame right-boundary points `[x, y]` (may be empty → coast)
     * @param confidence frame-level lane confidence (UFLDv2 existence mean), used to
     *   scale measurement noise — a low-confidence frame updates the track only weakly.
     * @return tracked `(left, right)` polylines `[x, y]`; a side is empty when its track
     *   is unavailable (never measured yet, or coasted past the budget → stale).
     */
    fun update(
        leftPts: List<FloatArray>,
        rightPts: List<FloatArray>,
        confidence: Float,
    ): Pair<List<FloatArray>, List<FloatArray>> {
        var mL = fitQuad(leftPts)
        var mR = fitQuad(rightPts)

        // Predict step first so the gates compare against the propagated state.
        left.predict()
        right.predict()

        // D7 — left/right consistency guard. If both sides have a measurement but they
        // imply an implausible lane (crossing, or width outside bounds / changing too
        // fast across the band — the adjacent-lane-mixing signature), drop the LESS
        // trustworthy side (higher fit residual) so the good side still updates.
        if (mL != null && mR != null && !laneConsistent(mL.coeffs, mR.coeffs)) {
            if (mL.residual <= mR.residual) mR = null else mL = null
        }

        applyMeasurement(left, mL, confidence)
        applyMeasurement(right, mR, confidence)

        return resample(left) to resample(right)
    }

    /** Reset both tracks (e.g. on a long lane-unavailable stretch or mode change). */
    fun reset() { left.reset(); right.reset() }

    /** Gate a measurement and either correct the track or coast it. */
    private fun applyMeasurement(b: Boundary, m: Measurement?, confidence: Float) {
        if (m == null) { b.coastStep(); return }
        // Seed on first ever measurement OR after the track has gone stale (coast budget
        // exhausted) — a stale track must re-acquire a persistent new lane rather than
        // gate it forever against dead coefficients (design D6).
        if (!b.valid) { b.seed(m.coeffs); return }

        // D5 — outlier gates: joint chi-square in coefficient space AND a lateral-jump
        // bound at the bottom anchor (the most safety-relevant point for LDW).
        val r = measurementNoise(m, confidence)
        val chi2 = b.chiSquare(m.coeffs, r)
        val jump = abs(eval(m.coeffs, yBot) - eval(b.x, yBot))
        if (chi2 > CHI2_GATE || jump > JUMP_MAX) { b.coastStep(); return }

        b.correct(m.coeffs, r)
    }

    /** Measurement covariance per coefficient: inflated by fit residual and by low
     *  confidence (mirrors openpilot's per-point uncertainty, design D4). */
    private fun measurementNoise(m: Measurement, confidence: Float): DoubleArray {
        val scale = (1.0 + m.residual / RES_SCALE) / max(confidence.toDouble(), CONF_FLOOR)
        return doubleArrayOf(R_BASE_A * scale, R_BASE_B * scale, R_BASE_C * scale)
    }

    /** Plausible lane: no crossing across the band, bottom width in range, and width not
     *  changing implausibly between top and bottom (space-agnostic: holds in both
     *  perspective and bird's-eye, where the absolute ratio differs but stays bounded). */
    private fun laneConsistent(l: DoubleArray, r: DoubleArray): Boolean {
        val wBot = eval(r, yBot) - eval(l, yBot)
        val wTop = eval(r, yTop) - eval(l, yTop)
        if (wTop <= 0.0 || wBot <= 0.0) return false                 // crossed / inverted
        if (wBot < WIDTH_MIN || wBot > WIDTH_MAX) return false
        val ratio = max(wBot, wTop) / min(wBot, wTop)
        return ratio <= WIDTH_RATIO_MAX
    }

    private fun resample(b: Boundary): List<FloatArray> {
        if (!b.valid) return emptyList()
        val out = ArrayList<FloatArray>(resampleCount)
        for (k in 0 until resampleCount) {
            val y = yTop + (yBot - yTop) * (k / (resampleCount - 1f))
            val x = eval(b.x, y.toDouble()).toFloat().coerceIn(0f, 1f)
            out += floatArrayOf(x, y)
        }
        return out
    }

    private fun eval(c: DoubleArray, y: Float): Double = eval(c, y.toDouble())
    private fun eval(c: DoubleArray, y: Double): Double = c[0] * y * y + c[1] * y + c[2]

    /** A per-frame measurement: quadratic coefficients + median |residual| (fit quality). */
    private class Measurement(val coeffs: DoubleArray, val residual: Double)

    /** Ordinary least-squares quadratic fit `x = a·y² + b·y + c` over `[x, y]` points;
     *  null when too few points or the normal matrix is singular. */
    private fun fitQuad(pts: List<FloatArray>): Measurement? {
        val n = pts.size
        if (n < MIN_FIT_POINTS) return null
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in pts) {
            val y = p[1].toDouble(); val x = p[0].toDouble(); val y2 = y * y
            s0 += 1.0; s1 += y; s2 += y2; s3 += y2 * y; s4 += y2 * y2
            t0 += x; t1 += x * y; t2 += x * y2
        }
        val det = s4 * (s2 * s0 - s1 * s1) - s3 * (s3 * s0 - s1 * s2) + s2 * (s3 * s1 - s2 * s2)
        if (abs(det) < 1e-12) return null
        val a = (t2 * (s2 * s0 - s1 * s1) - s3 * (t1 * s0 - s1 * t0) + s2 * (t1 * s1 - s2 * t0)) / det
        val bb = (s4 * (t1 * s0 - t0 * s1) - t2 * (s3 * s0 - s1 * s2) + s2 * (s3 * t0 - t1 * s2)) / det
        val cc = (s4 * (s2 * t0 - t1 * s1) - s3 * (s3 * t0 - t1 * s2) + t2 * (s3 * s1 - s2 * s2)) / det
        val coeffs = doubleArrayOf(a, bb, cc)
        val resid = ArrayList<Double>(n)
        for (p in pts) resid += abs(p[0] - eval(coeffs, p[1]))
        resid.sort()
        return Measurement(coeffs, resid[resid.size / 2])
    }

    /**
     * One ego boundary: a diagonal 3-state Kalman over `[a, b, c]` plus coast state.
     * Because F = H = I and Q, R are diagonal, P stays diagonal — three scalar filters.
     */
    private class Boundary {
        val x = doubleArrayOf(0.0, 0.0, 0.0)        // state [a, b, c]
        private val p = doubleArrayOf(P0, P0, P0)   // diagonal covariance
        var initialized = false; private set
        private var coast = 0                        // consecutive predict-only frames

        /** Valid output exists once seeded and while within the coast budget. */
        val valid: Boolean get() = initialized && coast <= TRACK_COAST_MAX

        fun seed(z: DoubleArray) {
            z.copyInto(x); p[0] = P0; p[1] = P0; p[2] = P0
            initialized = true; coast = 0
        }

        fun predict() { for (i in 0..2) p[i] += Q[i] }

        /** Joint normalized innovation yᵀS⁻¹y with diagonal S = P + R. */
        fun chiSquare(z: DoubleArray, r: DoubleArray): Double {
            var s = 0.0
            for (i in 0..2) { val inn = z[i] - x[i]; s += inn * inn / (p[i] + r[i]) }
            return s
        }

        fun correct(z: DoubleArray, r: DoubleArray) {
            for (i in 0..2) {
                val k = p[i] / (p[i] + r[i])
                x[i] += k * (z[i] - x[i])
                p[i] *= (1.0 - k)
            }
            coast = 0
        }

        /** Coast one frame (no usable measurement); growing covariance is the predict()
         *  the caller already applied, so a stale track can't read as confident. */
        fun coastStep() { coast++ }

        fun reset() {
            x[0] = 0.0; x[1] = 0.0; x[2] = 0.0
            p[0] = P0; p[1] = P0; p[2] = P0
            initialized = false; coast = 0
        }
    }

    private companion object {
        const val RESAMPLE_N = 24            // output polyline points across the draw band
        const val MIN_FIT_POINTS = 4         // measurement needs at least this many points
        const val TRACK_COAST_MAX = 12       // max predict-only frames before a track goes stale
                                             // (~2.4s at 5 fps; lets the right boundary survive a
                                             // dashed/occluded gap on transitions instead of vanishing)

        // Initial / process / measurement (co)variances — diagonal, in normalized-x² units.
        const val P0 = 1e-2                  // initial coefficient variance (loose seed)
        // Process noise: lane shape barely changes frame-to-frame, but the offset (c) may
        // drift faster than the curvature (a) during real maneuvers, so Q_c > Q_a.
        val Q = doubleArrayOf(2e-5, 5e-5, 2e-4)        // [a, b, c]
        val R_BASE_A = 4e-3; val R_BASE_B = 4e-3; val R_BASE_C = 2e-3

        const val CHI2_GATE = 16.0           // ~chi-square(3 dof) far-tail; reject beyond
        const val JUMP_MAX = 0.12            // max plausible per-frame lateral jump at yBot (norm-x)
        const val RES_SCALE = 0.03           // fit residual (norm-x) that doubles measurement noise
        const val CONF_FLOOR = 0.1           // floor on confidence used to inflate R

        // Lane-consistency bounds (normalized-x width between the two boundaries).
        const val WIDTH_MIN = 0.04
        const val WIDTH_MAX = 1.30
        const val WIDTH_RATIO_MAX = 6.0      // top/bottom width ratio cap (catches mixing)
    }
}
