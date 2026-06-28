package com.adasedge.app.recording

import android.content.Context
import android.util.Log
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Retention policy over the MediaStore-backed clip library (dashcam-recording spec:
 * "Size-capped circular retention", "Clips are retrievable"). Clips live on shared,
 * MTP/USB-visible storage in a date-organized hierarchy under `Movies/ADASEdge/<YYYY>/<MM>/
 * <YYYY-MM-DD>/`; [ClipRepository] owns the MediaStore I/O, this class owns the cap math.
 *
 * The eviction decision is a pure function ([planEviction]) so it stays unit-testable without
 * Android; the instance methods do the MediaStore I/O around it. Eviction candidates are
 * finalized clips only (the in-progress clip is still pending and excluded by the repository),
 * and oldest-first ordering means the active (newest) clip is never the one deleted.
 */
class RecordingStore(context: Context, private val maxStorageMb: Int) {

    private val repo = ClipRepository(context)

    /** CameraX output target for the next segment (dated folder + datetime name). */
    fun newClipOutput(nowMillis: Long): MediaStoreOutputOptions = repo.newClipOutput(nowMillis)

    /** Free space available on the shared volume, in bytes. */
    fun freeSpaceBytes(): Long = repo.freeSpaceBytes()

    /**
     * Enforce the storage cap: delete oldest clips first across the whole hierarchy until the
     * total is within the low-water mark, never deleting [activeId]. Best-effort.
     * @return number of clips deleted.
     */
    fun enforce(activeId: String?): Int {
        val capBytes = maxStorageMb.toLong() * 1024 * 1024
        val lowWater = (capBytes * LOW_WATER_PCT / 100)
        val toDelete = planEviction(repo.clipInfos(), capBytes, lowWater, activeId)
        if (toDelete.isEmpty()) return 0
        val n = repo.deleteIds(toDelete)
        if (n > 0) Log.i(TAG, "retention: deleted $n oldest clip(s) to stay under ${maxStorageMb}MB")
        return n
    }

    /** Retention model for a clip. [id] is the content-URI string (stable, deletable). */
    data class ClipInfo(val id: String, val sizeBytes: Long, val captureMillis: Long)

    companion object {
        private const val TAG = "RecordingStore"
        const val EXT = ".mp4"
        const val PREFIX = "dashcam_"
        /** Delete down to this % of the cap (hysteresis) so we don't evict every segment. */
        private const val LOW_WATER_PCT = 90L

        private val NAME_FMT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        private val PATH_FMT = SimpleDateFormat("yyyy/MM/yyyy-MM-dd", Locale.US)
        private val DAY_RE = Regex("""(\d{4}-\d{2}-\d{2})""")
        private val TS_RE = Regex("""(\d{4}-\d{2}-\d{2}_\d{6})""")

        /** MediaStore RELATIVE_PATH for the capture day, e.g. `Movies/ADASEdge/2026/06/2026-06-22/`. */
        fun relativePath(nowMillis: Long): String =
            "${ClipRepository.ROOT}/${PATH_FMT.format(Date(nowMillis))}/"

        /** `dashcam_yyyy-MM-dd_HHmmss.mp4`, with a `_N` suffix if the name already exists in the
         *  target folder (keeps the datetime parseable instead of letting MediaStore rename). */
        fun newClipName(nowMillis: Long, existing: Set<String>): String {
            val base = "$PREFIX${NAME_FMT.format(Date(nowMillis))}"
            var name = "$base$EXT"
            var i = 2
            while (name in existing) { name = "${base}_$i$EXT"; i++ }
            return name
        }

        /** "YYYY-MM-DD" parsed from a clip name, or null. */
        fun dayFromName(name: String): String? = DAY_RE.find(name)?.groupValues?.get(1)

        /** Capture time in millis parsed from a clip name's datetime, or null. */
        fun captureMillisFromName(name: String): Long? =
            TS_RE.find(name)?.groupValues?.get(1)?.let { runCatching { NAME_FMT.parse(it)?.time }.getOrNull() }

        /**
         * Pure eviction planner. Returns the ids to delete, **oldest first**, so that total size
         * drops to at most [lowWaterBytes] — but only when total exceeds [capBytes]. [activeId] is
         * never returned. Ordering is by capture time (tiebreak id).
         */
        fun planEviction(
            clips: List<ClipInfo>,
            capBytes: Long,
            lowWaterBytes: Long,
            activeId: String?,
        ): List<String> {
            var total = clips.sumOf { it.sizeBytes }
            if (total <= capBytes) return emptyList()
            val oldestFirst = clips.sortedWith(compareBy({ it.captureMillis }, { it.id }))
            val deletions = ArrayList<String>()
            for (c in oldestFirst) {
                if (total <= lowWaterBytes) break
                if (c.id == activeId) continue
                deletions += c.id
                total -= c.sizeBytes
            }
            return deletions
        }
    }
}
