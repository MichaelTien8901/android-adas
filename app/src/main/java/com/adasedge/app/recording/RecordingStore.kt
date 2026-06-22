package com.adasedge.app.recording

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the dashcam clip directory and enforces the size-capped circular buffer
 * (dashcam-recording spec: "Size-capped circular retention", "Clips are retrievable").
 *
 * Clips live under app-specific external storage (`Android/data/<pkg>/files/dashcam/`) so
 * no runtime storage permission is needed and they're pullable via adb / a file manager.
 * Filenames encode the capture datetime so they sort chronologically.
 *
 * The eviction decision is a pure function ([planEviction]) so it is unit-testable without
 * Android; the instance methods do the File I/O around it.
 */
class RecordingStore(context: Context, private val maxStorageMb: Int) {

    private val appContext = context.applicationContext

    /** The single directory all clips live in (created on demand). */
    fun dir(): File =
        File(appContext.getExternalFilesDir(null), DIR_NAME).apply { if (!exists()) mkdirs() }

    /** A new clip file with a datetime name unique within the directory. */
    fun newClipFile(nowMillis: Long): File {
        val d = dir()
        val existing = d.list()?.toSet() ?: emptySet()
        return File(d, newClipName(nowMillis, existing))
    }

    fun clips(): List<ClipInfo> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.map { ClipInfo(it.name, it.length(), it.lastModified()) }
            ?: emptyList()

    /** Free space available on the clip directory's volume, in bytes. */
    fun freeSpaceBytes(): Long = dir().usableSpace

    /**
     * Enforce the storage cap: delete oldest clips first until the directory is within the
     * low-water mark, never deleting [activeName]. Best-effort; logs and continues on error.
     * @return number of clips deleted.
     */
    fun enforce(activeName: String?): Int {
        val capBytes = maxStorageMb.toLong() * 1024 * 1024
        val lowWater = (capBytes * LOW_WATER_PCT / 100)
        val toDelete = planEviction(clips(), capBytes, lowWater, activeName)
        var n = 0
        val d = dir()
        for (name in toDelete) {
            if (runCatching { File(d, name).delete() }.getOrDefault(false)) n++
            else Log.w(TAG, "failed to delete clip $name")
        }
        if (n > 0) Log.i(TAG, "retention: deleted $n oldest clip(s) to stay under ${maxStorageMb}MB")
        return n
    }

    data class ClipInfo(val name: String, val sizeBytes: Long, val lastModified: Long)

    companion object {
        private const val TAG = "RecordingStore"
        private const val DIR_NAME = "dashcam"
        const val EXT = ".mp4"
        const val PREFIX = "dashcam_"
        /** Delete down to this % of the cap (hysteresis) so we don't evict every segment. */
        private const val LOW_WATER_PCT = 90L

        private val NAME_FMT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

        /** `dashcam_yyyy-MM-dd_HHmmss.mp4`, with a `_N` suffix if the name already exists. */
        fun newClipName(nowMillis: Long, existing: Set<String>): String {
            val base = "$PREFIX${NAME_FMT.format(Date(nowMillis))}"
            var name = "$base$EXT"
            var i = 2
            while (name in existing) { name = "${base}_$i$EXT"; i++ }
            return name
        }

        /**
         * Pure eviction planner. Returns the names to delete, oldest first, so that total
         * size drops to at most [lowWaterBytes] — but only when total exceeds [capBytes].
         * [activeName] is never returned. Ordering is by the datetime in the filename
         * (lexicographic on the `dashcam_<ts>` prefix), falling back to [ClipInfo.lastModified].
         */
        fun planEviction(
            clips: List<ClipInfo>,
            capBytes: Long,
            lowWaterBytes: Long,
            activeName: String?,
        ): List<String> {
            var total = clips.sumOf { it.sizeBytes }
            if (total <= capBytes) return emptyList()
            val oldestFirst = clips.sortedWith(compareBy({ it.name }, { it.lastModified }))
            val deletions = ArrayList<String>()
            for (c in oldestFirst) {
                if (total <= lowWaterBytes) break
                if (c.name == activeName) continue
                deletions += c.name
                total -= c.sizeBytes
            }
            return deletions
        }
    }
}
