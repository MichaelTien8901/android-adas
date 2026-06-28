package com.adasedge.app.recording

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.video.MediaStoreOutputOptions
import com.adasedge.app.replay.ReplaySource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaStore-backed clip store (clip-library + dashcam-recording specs). Clips live on shared,
 * MTP/USB-visible storage under `Movies/ADASEdge/<YYYY>/<MM>/<YYYY-MM-DD>/`, so a day's footage
 * is a single folder visible to a desktop file manager when the phone is mounted — no `adb` and
 * no broad "all files" permission (the app owns the entries it inserts, scoped-storage clean).
 *
 * This is the only place that touches MediaStore. [RecordingStore] sits on top for the
 * size-capped retention policy; the clip-library UI reads through [clips]/[delete].
 */
class ClipRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    /** Legacy flat directory used before the date-organized MediaStore layout. */
    private fun legacyDir(): File = File(appContext.getExternalFilesDir(null), LEGACY_DIR_NAME)

    // ---- Recording output -------------------------------------------------------------------

    /** Build the CameraX output target for the next segment: a MediaStore Video entry placed in
     *  the capture day's folder and named by capture datetime. */
    fun newClipOutput(nowMillis: Long): MediaStoreOutputOptions {
        val relPath = RecordingStore.relativePath(nowMillis)
        val name = RecordingStore.newClipName(nowMillis, existingNamesIn(relPath))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MIME)
            put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMillis)
        }
        return MediaStoreOutputOptions.Builder(resolver, collection())
            .setContentValues(values)
            .build()
    }

    // ---- Queries ----------------------------------------------------------------------------

    /** Finalized clips under the ADASEdge root, optionally filtered by day/month, sorted. */
    fun clips(filter: ClipFilter = ClipFilter()): List<Clip> {
        val out = ArrayList<Clip>()
        val sel = StringBuilder("${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?")
        val args = ArrayList<String>().apply { add("$ROOT/%") }
        sel.append(" AND ${MediaStore.Video.Media.IS_PENDING} = 0")
        when {
            filter.day != null -> { sel.append(" AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"); args.add("%/${filter.day}/") }
            filter.month != null -> { sel.append(" AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"); args.add("$ROOT/${filter.month.replace('-', '/')}/%") }
        }
        val order = "${MediaStore.Video.Media.DATE_TAKEN} ${if (filter.newestFirst) "DESC" else "ASC"}"
        try {
            resolver.query(collection(), PROJECTION, sel.toString(), args.toTypedArray(), order)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = c.getLong(takenCol).let { if (it > 0) it else c.getLong(addedCol) * 1000L }
                    out += Clip(
                        id = id,
                        uri = ContentUris.withAppendedId(collection(), id),
                        name = c.getString(nameCol) ?: "clip_$id.mp4",
                        sizeBytes = c.getLong(sizeCol),
                        captureMillis = taken,
                        durationMs = c.getLong(durCol),
                        relativePath = c.getString(pathCol) ?: "",
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clips query failed", t)
        }
        return out
    }

    /** Clips as the pure retention model (id = content-URI string). Finalized only. */
    fun clipInfos(): List<RecordingStore.ClipInfo> =
        clips(ClipFilter(newestFirst = false)).map {
            RecordingStore.ClipInfo(it.uri.toString(), it.sizeBytes, it.captureMillis)
        }

    /** Distinct day folders that contain clips (newest first), e.g. "2026-06-22". */
    fun days(): List<String> =
        clips().mapNotNull { it.day }.distinct()

    private fun existingNamesIn(relPath: String): Set<String> {
        val names = HashSet<String>()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                arrayOf(relPath),
                null,
            )?.use { c ->
                val n = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (c.moveToNext()) c.getString(n)?.let { names.add(it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "existing-name query failed", t)
        }
        return names
    }

    // ---- Mutations --------------------------------------------------------------------------

    /** Delete the given clip URIs (app-owned → no user consent needed). Returns count deleted. */
    fun delete(uris: Collection<Uri>): Int {
        var n = 0
        for (u in uris) {
            if (runCatching { resolver.delete(u, null, null) > 0 }.getOrDefault(false)) n++
            else Log.w(TAG, "failed to delete $u")
        }
        return n
    }

    /** Delete by content-URI string (used by retention's [RecordingStore.ClipInfo.id]). */
    fun deleteIds(ids: Collection<String>): Int =
        delete(ids.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })

    /** Delete every clip captured on [day] ("YYYY-MM-DD"). Returns count deleted. */
    fun deleteDay(day: String): Int =
        delete(clips(ClipFilter(day = day)).map { it.uri })

    // ---- Misc -------------------------------------------------------------------------------

    fun freeSpaceBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    /** A thumbnail for the clip, or null on failure (best-effort). */
    fun thumbnail(clip: Clip, px: Int): Bitmap? =
        runCatching { resolver.loadThumbnail(clip.uri, Size(px, px), null) }.getOrNull()

    private fun collection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // ---- Legacy migration -------------------------------------------------------------------

    /** Read clips still sitting in the pre-migration flat app-private dir (read-only secondary
     *  source so nothing is hidden until [importLegacy] runs). */
    fun legacyClips(): List<Clip> {
        val d = legacyDir()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(RecordingStore.EXT) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            Clip(
                id = -1L,
                uri = Uri.fromFile(f),
                name = f.name,
                sizeBytes = f.length(),
                captureMillis = RecordingStore.captureMillisFromName(f.name) ?: f.lastModified(),
                durationMs = 0L,
                relativePath = "",
                localFile = f,
            )
        }
    }

    /** Pushed replay clips in the USB-visible replay folder (`Android/media/<pkg>/replay/`),
     *  as File-backed [Clip]s so the library can play/delete them. Newest first. */
    fun replayClips(): List<Clip> {
        val dir = File(appContext.externalMediaDirs.firstOrNull() ?: appContext.getExternalFilesDir(null), ReplaySource.REPLAY_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(RecordingStore.EXT) } ?: return emptyList()
        return files.filter { it.length() > 0 }.sortedByDescending { it.lastModified() }.map { f ->
            Clip(
                id = -1L,
                uri = Uri.fromFile(f),
                name = f.name,
                sizeBytes = f.length(),
                captureMillis = f.lastModified(),
                durationMs = 0L,
                relativePath = "",
                localFile = f,
                isReplay = true,
            )
        }
    }

    /** Delete clips: File-backed (replay) via the filesystem, MediaStore clips via the resolver
     *  (app-owned → no consent prompt). Returns count deleted. */
    fun deleteClips(clips: Collection<Clip>): Int {
        var n = 0
        for (c in clips) {
            val ok = c.localFile?.let { runCatching { it.delete() }.getOrDefault(false) }
                ?: runCatching { resolver.delete(c.uri, null, null) > 0 }.getOrDefault(false)
            if (ok) n++ else Log.w(TAG, "failed to delete ${c.name}")
        }
        return n
    }

    /** Run the one-time legacy import and report whether the legacy dir is now fully drained
     *  (import-and-forget: the caller marks migration done only when this returns true). */
    fun migrateLegacyComplete(): Boolean {
        importLegacy()
        return legacyClips().isEmpty()
    }

    /** One-time, idempotent import of legacy flat clips into the dated MediaStore layout. Each
     *  source file is copied to its capture-day folder, then deleted on success. Best-effort:
     *  failures are logged and leave the source in place. Returns count imported. */
    fun importLegacy(): Int {
        var n = 0
        for (clip in legacyClips()) {
            val src = clip.localFile ?: continue
            val relPath = RecordingStore.relativePath(clip.captureMillis)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, clip.name)
                put(MediaStore.Video.Media.MIME_TYPE, MIME)
                put(MediaStore.Video.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Video.Media.DATE_TAKEN, clip.captureMillis)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection(), values) }.getOrNull() ?: continue
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                true
            }.getOrDefault(false)
            if (ok) {
                runCatching {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                }
                if (runCatching { src.delete() }.getOrDefault(false)) n++
            } else {
                runCatching { resolver.delete(uri, null, null) }   // roll back the half-written row
                Log.w(TAG, "legacy import failed for ${clip.name}")
            }
        }
        if (n > 0) Log.i(TAG, "migrated $n legacy clip(s) into $ROOT")
        return n
    }

    /** A clip in the library. MediaStore-backed recordings carry a content [uri]; File-backed
     *  clips (the pushed replay clip) carry a [localFile] and a `file://` [uri]. */
    data class Clip(
        val id: Long,
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val captureMillis: Long,
        val durationMs: Long,
        val relativePath: String,
        val localFile: File? = null,
        val isReplay: Boolean = false,
    ) {
        val isLocal: Boolean get() = localFile != null
        /** "YYYY-MM-DD" from the relative path's day folder, falling back to the name. */
        val day: String? get() =
            relativePath.trim('/').substringAfterLast('/', "").takeIf { DAY_RE.matches(it) }
                ?: RecordingStore.dayFromName(name)

        companion object { private val DAY_RE = Regex("""\d{4}-\d{2}-\d{2}""") }
    }

    data class ClipFilter(
        val day: String? = null,
        val month: String? = null,
        val newestFirst: Boolean = true,
    )

    companion object {
        private const val TAG = "ClipRepository"
        const val ROOT = "Movies/ADASEdge"
        const val MIME = "video/mp4"
        const val LEGACY_DIR_NAME = "dashcam"

        private val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
        )

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun formatDateTime(millis: Long): String = DATE_FMT.format(Date(millis))
    }
}
