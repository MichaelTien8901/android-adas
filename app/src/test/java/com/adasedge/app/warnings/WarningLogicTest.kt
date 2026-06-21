package com.adasedge.app.warnings

import android.graphics.RectF
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.AccelPath
import com.adasedge.app.model.Detection
import com.adasedge.app.model.LeadEstimate
import com.adasedge.app.model.ObjectClass
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.Side
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.SpeedValidity
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType
import com.adasedge.app.perception.LaneDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the warning evaluators — exercise the speed gating, TTC
 * staging, and headway dwell without any device (android.jar stubs return
 * defaults; the geometry fields these tests touch are set explicitly).
 */
class WarningLogicTest {

    private fun speed(kmh: Float, validity: SpeedValidity = SpeedValidity.VALID) =
        SpeedSample(kmh / 3.6f, validity, accuracyMps = 0.5f, stale = false, timestampNanos = 0L)

    private fun resultWithLead(distM: Float, ttc: Float, tsNanos: Long): PerceptionResult {
        val det = Detection(ObjectClass.CAR, RectF(), 0.9f)
        return PerceptionResult(tsNanos, listOf(det), null, LeadEstimate(det, distM, ttc), 1280, 720)
    }

    @Test fun fcw_imminent_when_ttc_low_and_speed_ok() {
        val fcw = ForwardCollisionWarning()
        // Sustained low TTC: arm, then fire once the dwell (0.35 s) elapses.
        assertTrue(fcw.evaluate(resultWithLead(12f, 1.0f, 0L), speed(80f)).isEmpty())
        val w = fcw.evaluate(resultWithLead(12f, 1.0f, 500_000_000L), speed(80f))
        assertEquals(1, w.size)
        assertEquals(WarningType.FORWARD_COLLISION, w[0].type)
        assertEquals(WarningLevel.IMMINENT, w[0].level)
    }

    @Test fun fcw_no_false_alarm_on_transient_ttc_dip() {
        val fcw = ForwardCollisionWarning()
        // One noisy frame dips below threshold, next frame recovers — must NOT fire.
        assertTrue(fcw.evaluate(resultWithLead(12f, 1.0f, 0L), speed(80f)).isEmpty())
        assertTrue(fcw.evaluate(resultWithLead(40f, 6.0f, 100_000_000L), speed(80f)).isEmpty())
    }

    @Test fun fcw_suppressed_below_min_speed() {
        val w = ForwardCollisionWarning().evaluate(resultWithLead(12f, 1.0f, 0L), speed(3f))
        assertTrue(w.isEmpty())
    }

    @Test fun fcw_suppressed_when_speed_invalid() {
        val w = ForwardCollisionWarning()
            .evaluate(resultWithLead(12f, 1.0f, 0L), speed(80f, SpeedValidity.INVALID))
        assertTrue(w.isEmpty())
    }

    @Test fun headway_warns_after_dwell() {
        val mon = HeadwayMonitor()
        // 10 m at 20 m/s -> THW 0.5s (< 2.0s safe). Need dwell >= 1200ms.
        val s = speed(72f) // 20 m/s. Realistic (large) frame timestamps.
        assertTrue(mon.evaluate(resultWithLead(10f, Float.POSITIVE_INFINITY, 1_000_000_000L), s).isEmpty())
        val w = mon.evaluate(resultWithLead(10f, Float.POSITIVE_INFINITY, 3_000_000_000L), s)
        assertEquals(WarningType.HEADWAY, w.single().type)
    }

    @Test fun headway_suppressed_at_low_speed() {
        val w = HeadwayMonitor().evaluate(resultWithLead(10f, Float.POSITIVE_INFINITY, 0L), speed(10f))
        assertTrue(w.isEmpty())
    }

    // ---- LDW lane-boundary tracking (right-departure regression) ----

    private val numLanes = 4; private val numRow = 56; private val griding = 100

    /** Fake UFLDv2 outputs: each entry maps a lane index to a vertical line at x. */
    private fun laneOutputs(lanesX: Map<Int, Float>): List<TensorOut> {
        val loc = FloatArray(numLanes * numRow * griding)
        val exist = FloatArray(numLanes * numRow)
        for ((l, x) in lanesX) {
            val col = Math.round(x * (griding - 1)).coerceIn(0, griding - 1)
            for (r in 0 until numRow) {
                loc[(l * numRow + r) * griding + col] = 1f
                exist[l * numRow + r] = 1f
            }
        }
        return listOf(
            TensorOut("loc", loc, intArrayOf(1, numLanes, numRow, griding)),
            TensorOut("exist", exist, intArrayOf(1, numLanes, numRow)),
        )
    }

    private class FakeRunner : ModelRunner {
        var outputs: List<TensorOut> = emptyList()
        override val accelPath = AccelPath.CPU
        override fun run(input: FloatArray) = outputs
        override fun close() {}
    }

    /** UFLDv2 ego slot 2 is the right boundary regardless of where it sits, so a
     *  right boundary sliding across image centre (0.5) is still reported as `right`
     *  (no fixed-0.5 bucket to snap). temporalAlpha=1 disables EMA lag for the assert. */
    @Test fun right_boundary_tracks_across_center_on_right_departure() {
        val runner = FakeRunner()
        val det = LaneDetector(runner, temporalAlpha = 1f)
        var rightBottomX = Float.NaN
        // left = ego slot 1 (~0.30); right = ego slot 2 sweeping across 0.5 to 0.46.
        for (rx in listOf(0.65f, 0.60f, 0.55f, 0.52f, 0.49f, 0.46f)) {
            runner.outputs = laneOutputs(mapOf(1 to 0.30f, 2 to rx))
            val g = det.detect(FloatArray(1))!!
            rightBottomX = g.right.maxByOrNull { it[1] }?.get(0) ?: Float.NaN
        }
        assertTrue("right boundary lost when it crossed image centre", !rightBottomX.isNaN())
        assertEquals("right boundary should track to ~0.46, not snap away", 0.46f, rightBottomX, 0.03f)
    }

    /** End-to-end: the ego-slot geometry makes LDW fire on the RIGHT side. */
    @Test fun ldw_fires_right_on_right_departure() {
        val runner = FakeRunner()
        val det = LaneDetector(runner, temporalAlpha = 1f)
        val ldw = LaneDepartureWarning()
        var warns = emptyList<com.adasedge.app.model.Warning>()
        for (rx in listOf(0.65f, 0.58f, 0.52f, 0.49f)) {   // right boundary closing on ego
            runner.outputs = laneOutputs(mapOf(1 to 0.30f, 2 to rx))
            val lanes = det.detect(FloatArray(1))
            warns = ldw.evaluate(PerceptionResult(0L, emptyList(), lanes, null, 1280, 720), speed(80f))
        }
        assertEquals(WarningType.LANE_DEPARTURE, warns.single().type)
        assertEquals("a right departure must report Side.RIGHT", Side.RIGHT, warns.single().side)
    }
}
