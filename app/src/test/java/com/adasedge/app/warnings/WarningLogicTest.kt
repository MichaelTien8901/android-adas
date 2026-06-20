package com.adasedge.app.warnings

import android.graphics.RectF
import com.adasedge.app.model.Detection
import com.adasedge.app.model.LeadEstimate
import com.adasedge.app.model.ObjectClass
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.SpeedSample
import com.adasedge.app.model.SpeedValidity
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType
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
        val w = ForwardCollisionWarning().evaluate(resultWithLead(12f, 1.0f, 0L), speed(80f))
        assertEquals(1, w.size)
        assertEquals(WarningType.FORWARD_COLLISION, w[0].type)
        assertEquals(WarningLevel.IMMINENT, w[0].level)
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
}
