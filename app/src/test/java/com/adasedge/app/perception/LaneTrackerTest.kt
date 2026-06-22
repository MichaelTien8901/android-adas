package com.adasedge.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [LaneTracker] — the openpilot-inspired temporal lane-coefficient
 * tracker. No Android/OpenCV deps; exercises Kalman smoothing, outlier gating, bounded
 * gap prediction, and the left/right consistency guard
 * (openspec change openpilot-inspired-lane-stability, design D2–D7).
 */
class LaneTrackerTest {

    private val yTop = 0.4f
    private val yBot = 1.0f

    /** A straight near-vertical boundary at normalized x = [x], optionally jittered. */
    private fun line(x: Float, jitter: Float = 0f): List<FloatArray> =
        (0 until 12).map { i ->
            val y = yTop + (yBot - yTop) * (i / 11f)
            val dx = if (jitter == 0f) 0f else ((i % 2) * 2 - 1) * jitter   // ±jitter, deterministic
            floatArrayOf(x + dx, y)
        }

    private fun tracker() = LaneTracker(yTop, yBot)

    /** x of the tracked boundary at the bottom anchor (the LDW-relevant point). */
    private fun List<FloatArray>.xAtBottom(): Float = last()[0]

    @Test
    fun seeds_and_outputs_after_first_frame() {
        val t = tracker()
        val (l, r) = t.update(line(0.30f), line(0.70f), 0.9f)
        assertTrue("left track produced", l.isNotEmpty())
        assertTrue("right track produced", r.isNotEmpty())
        assertEquals(0.30f, l.xAtBottom(), 0.02f)
        assertEquals(0.70f, r.xAtBottom(), 0.02f)
    }

    @Test
    fun suppresses_per_frame_jitter() {
        val t = tracker()
        var last = floatArrayOf()
        repeat(8) {
            val (l, _) = t.update(line(0.30f, jitter = 0.03f), line(0.70f, jitter = 0.03f), 0.9f)
            last = floatArrayOf(l.xAtBottom())
        }
        // Despite ±0.03 per-point jitter on every frame, the tracked bottom x stays put.
        assertEquals(0.30f, last[0], 0.015f)
    }

    @Test
    fun rejects_adjacent_lane_jump() {
        val t = tracker()
        repeat(5) { t.update(line(0.30f), line(0.70f), 0.9f) }   // establish a confident track
        // Right boundary snaps onto the next lane's marking (0.70 -> 0.95, a 0.25 jump).
        val (_, r) = t.update(line(0.30f), line(0.95f), 0.9f)
        // The jump exceeds the gate, so the track coasts near 0.70 rather than following.
        assertEquals(0.70f, r.xAtBottom(), 0.05f)
    }

    @Test
    fun coasts_through_gaps_then_goes_stale() {
        // Mirrors LaneTracker.TRACK_COAST_MAX (12 frames, ~2.4s at 5 fps).
        val coastMax = 12
        val t = tracker()
        repeat(5) { t.update(line(0.30f), line(0.70f), 0.9f) }
        // Empty frames (dashed gap / dropout): the track predicts through the budget.
        var lastNonEmpty = 0
        for (frame in 1..coastMax) {
            val (l, _) = t.update(emptyList(), emptyList(), 0.5f)
            if (l.isNotEmpty()) lastNonEmpty = frame
        }
        assertEquals("coasts through the full budget", coastMax, lastNonEmpty)
        // One frame past the budget → stale → unavailable.
        val (l7, r7) = t.update(emptyList(), emptyList(), 0.5f)
        assertTrue("left stale", l7.isEmpty())
        assertTrue("right stale", r7.isEmpty())
    }

    @Test
    fun reacquires_after_going_stale() {
        val t = tracker()
        repeat(3) { t.update(line(0.30f), line(0.70f), 0.9f) }
        repeat(14) { t.update(emptyList(), emptyList(), 0.5f) }  // drive past TRACK_COAST_MAX (12) → stale
        // A fresh, very different lane appears; the tracker re-seeds rather than gating
        // it forever (bounded coast lets a persistent new geometry be re-acquired).
        val (l, r) = t.update(line(0.45f), line(0.80f), 0.9f)
        assertEquals(0.45f, l.xAtBottom(), 0.03f)
        assertEquals(0.80f, r.xAtBottom(), 0.03f)
    }

    @Test
    fun guard_keeps_boundaries_from_crossing() {
        val t = tracker()
        repeat(5) { t.update(line(0.30f), line(0.70f), 0.9f) }
        // A noisy "mixed" left measurement crosses past the right boundary.
        val (l, r) = t.update(line(0.74f, jitter = 0.04f), line(0.70f), 0.9f)
        // Output must not cross: left stays left of right (mixed measurement gated out).
        assertTrue("left stays left of right", l.xAtBottom() < r.xAtBottom())
        assertEquals("left holds its track", 0.30f, l.xAtBottom(), 0.06f)
    }
}
