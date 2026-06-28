package com.adasedge.app.recording

import com.adasedge.app.recording.RecordingStore.ClipInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the size-capped circular retention planner + clip naming / date paths. */
class RecordingStoreTest {

    private val MB = 1024L * 1024

    /** id encodes order so assertions read clearly; capture time drives oldest-first eviction. */
    private fun clip(id: String, mb: Long, captureMillis: Long) = ClipInfo(id, mb * MB, captureMillis)

    @Test
    fun under_cap_deletes_nothing() {
        val clips = listOf(clip("a", 100, 0), clip("b", 100, 1))
        val del = RecordingStore.planEviction(clips, capBytes = 500 * MB, lowWaterBytes = 450 * MB, activeId = null)
        assertTrue("nothing deleted under cap", del.isEmpty())
    }

    @Test
    fun over_cap_deletes_oldest_first_down_to_low_water() {
        // 5 x 100MB = 500MB, cap 400MB, low-water 360MB -> must drop to <=360 -> delete 2 oldest (down to 300).
        val clips = (0..4).map { clip("id$it", 100, captureMillis = it.toLong()) }
        val del = RecordingStore.planEviction(clips, capBytes = 400 * MB, lowWaterBytes = 360 * MB, activeId = null)
        assertEquals(listOf("id0", "id1"), del)
    }

    @Test
    fun active_clip_is_never_deleted() {
        // Oldest is the active clip; eviction must skip it and take the next-oldest instead.
        val clips = (0..4).map { clip("id$it", 100, captureMillis = it.toLong()) }
        val del = RecordingStore.planEviction(clips, capBytes = 400 * MB, lowWaterBytes = 360 * MB, activeId = "id0")
        assertTrue("active clip protected", "id0" !in del)
        assertEquals(listOf("id1", "id2"), del)
    }

    @Test
    fun name_encodes_datetime_and_dedupes_on_collision() {
        val ts = 0L // 1970-01-01 00:00:00 UTC; formatted in local tz but stable within a run
        val first = RecordingStore.newClipName(ts, emptySet())
        assertTrue(first.startsWith(RecordingStore.PREFIX) && first.endsWith(RecordingStore.EXT))
        val second = RecordingStore.newClipName(ts, setOf(first))
        assertTrue("collision gets a suffix", second != first && second.endsWith("_2${RecordingStore.EXT}"))
    }

    @Test
    fun relative_path_and_name_round_trip_to_the_same_day() {
        val name = RecordingStore.newClipName(0L, emptySet())
        val day = RecordingStore.dayFromName(name)               // e.g. 1970-01-01 (local tz)
        assertTrue("name carries a day", day != null)
        // The dated folder for the same instant must contain that day.
        assertTrue("path nests the day", RecordingStore.relativePath(0L).contains("$day/"))
        // Capture time parses back from the name (within the minute granularity of the format).
        assertTrue("capture time recovered", RecordingStore.captureMillisFromName(name) != null)
        assertNull("non-clip name has no day", RecordingStore.dayFromName("not-a-clip.mp4"))
    }
}
