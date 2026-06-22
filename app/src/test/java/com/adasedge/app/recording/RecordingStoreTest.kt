package com.adasedge.app.recording

import com.adasedge.app.recording.RecordingStore.ClipInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the size-capped circular retention planner + clip naming. */
class RecordingStoreTest {

    private val MB = 1024L * 1024

    private fun clip(name: String, mb: Long, mtime: Long = 0L) = ClipInfo(name, mb * MB, mtime)

    @Test
    fun under_cap_deletes_nothing() {
        val clips = listOf(clip("dashcam_2026-06-22_120000.mp4", 100), clip("dashcam_2026-06-22_120300.mp4", 100))
        val del = RecordingStore.planEviction(clips, capBytes = 500 * MB, lowWaterBytes = 450 * MB, activeName = null)
        assertTrue("nothing deleted under cap", del.isEmpty())
    }

    @Test
    fun over_cap_deletes_oldest_first_down_to_low_water() {
        // 5 x 100MB = 500MB, cap 400MB, low-water 360MB -> must drop to <=360 -> delete 2 oldest (down to 300).
        val clips = (0..4).map { clip("dashcam_2026-06-22_12%02d00.mp4".format(it), 100, mtime = it.toLong()) }
        val del = RecordingStore.planEviction(clips, capBytes = 400 * MB, lowWaterBytes = 360 * MB, activeName = null)
        assertEquals(listOf("dashcam_2026-06-22_120000.mp4", "dashcam_2026-06-22_120100.mp4"), del)
    }

    @Test
    fun active_clip_is_never_deleted() {
        // Oldest is the active clip; eviction must skip it and take the next-oldest instead.
        val clips = (0..4).map { clip("dashcam_2026-06-22_12%02d00.mp4".format(it), 100, mtime = it.toLong()) }
        val active = "dashcam_2026-06-22_120000.mp4"
        val del = RecordingStore.planEviction(clips, capBytes = 400 * MB, lowWaterBytes = 360 * MB, activeName = active)
        assertTrue("active clip protected", active !in del)
        assertEquals(listOf("dashcam_2026-06-22_120100.mp4", "dashcam_2026-06-22_120200.mp4"), del)
    }

    @Test
    fun name_encodes_datetime_and_dedupes_on_collision() {
        val ts = 0L // 1970-01-01 00:00:00 UTC; formatted in local tz but stable within a run
        val first = RecordingStore.newClipName(ts, emptySet())
        assertTrue(first.startsWith(RecordingStore.PREFIX) && first.endsWith(RecordingStore.EXT))
        val second = RecordingStore.newClipName(ts, setOf(first))
        assertTrue("collision gets a suffix", second != first && second.endsWith("_2${RecordingStore.EXT}"))
    }
}
