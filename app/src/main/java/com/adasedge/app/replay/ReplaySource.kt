package com.adasedge.app.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.adasedge.app.camera.CameraController
import java.io.File

/**
 * Debug validation source (tasks 9.6 / 9.2): replays a video clip through the
 * same frame sink the camera uses, so each warning's activation/clear can be
 * exercised deterministically against known road footage — no driving required.
 *
 * Decodes with MediaCodec + MediaExtractor into an ImageReader (sequential, at
 * video framerate — much faster than the old MediaMetadataRetriever seek-per-frame
 * path). Frames are stamped with a synthetic monotonic timestamp advancing by the
 * frame interval so TTC / headway temporal math stays consistent. The clip loops.
 *
 * Push a clip:
 *   adb push road.mp4 /sdcard/Android/data/com.adasedge.app/files/replay.mp4
 */
class ReplaySource(context: Context) {

    private val appCtx = context.applicationContext
    @Volatile private var running = false
    private var thread: Thread? = null

    fun file(): File = File(appCtx.getExternalFilesDir(null), FILE_NAME)
    fun available(): Boolean = file().let { it.exists() && it.length() > 0 }

    /** Start feeding decoded frames to [sink]. Returns false if no clip is present. */
    fun start(@Suppress("UNUSED_PARAMETER") fps: Int = 30, sink: CameraController.FrameSink): Boolean {
        if (!available()) return false
        running = true
        thread = Thread({ loop(sink) }, "replay-source").apply { start() }
        return true
    }

    private fun loop(sink: CameraController.FrameSink) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file().absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run { Log.e(TAG, "no video track"); return }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val srcFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 60) else 30
            val stepNanos = 1_000_000_000L / srcFps
            Log.i(TAG, "replay start: ${file().name} ${w}x${h} @${srcFps}fps (MediaCodec)")

            // ByteBuffer mode (no surface) — read frames via getOutputImage.
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var tsNanos = 0L

            while (running) {
                // Feed input (loop the clip at EOS by seeking back to start).
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    var n = extractor.readSampleData(buf, 0)
                    var pts = extractor.sampleTime
                    if (n < 0) {
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        n = extractor.readSampleData(buf, 0); pts = extractor.sampleTime
                    }
                    if (n >= 0) { codec.queueInputBuffer(inIdx, 0, n, pts, 0); extractor.advance() }
                }
                // Drain output -> YUV Image -> Bitmap -> sink.
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val t0 = System.nanoTime()
                    if (info.size > 0) {
                        val img = codec.getOutputImage(outIdx)
                        if (img != null) {
                            val bmp = yuvToBitmap(img, w, h)
                            img.close()
                            if (bmp != null) { tsNanos += stepNanos; sink.onFrame(bmp, tsNanos) }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    val sleep = stepNanos / 1_000_000L - (System.nanoTime() - t0) / 1_000_000L
                    if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "replay failed", t)
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { extractor.release() }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt(); thread?.join(700); thread = null
    }

    // Reused scratch buffers (avoid per-frame allocation).
    private var yA = ByteArray(0); private var uA = ByteArray(0); private var vA = ByteArray(0)
    private var argb = IntArray(0)

    /**
     * YUV_420_888 Image -> ARGB Bitmap. Bulk-copies each plane to a byte array
     * (one JNI call vs per-pixel ByteBuffer.get) and uses fixed-point BT.601 math.
     */
    private fun yuvToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        return try {
            val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
            val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
            if (yA.size < yb.remaining()) yA = ByteArray(yb.remaining())
            if (uA.size < ub.remaining()) uA = ByteArray(ub.remaining())
            if (vA.size < vb.remaining()) vA = ByteArray(vb.remaining())
            if (argb.size < w * h) argb = IntArray(w * h)
            val yn = yb.remaining(); val un = ub.remaining(); val vn = vb.remaining()
            yb.get(yA, 0, yn); ub.get(uA, 0, un); vb.get(vA, 0, vn)
            val yRow = y.rowStride; val uRow = u.rowStride; val uPix = u.pixelStride
            for (j in 0 until h) {
                val yp = j * yRow; val uvp = (j shr 1) * uRow; val o = j * w
                for (i in 0 until w) {
                    val yv = yA[yp + i].toInt() and 0xFF
                    val uvi = uvp + (i shr 1) * uPix
                    val uu = (uA[uvi].toInt() and 0xFF) - 128
                    val vv = (vA[uvi].toInt() and 0xFF) - 128
                    var r = yv + ((1436 * vv) shr 10)
                    var g = yv - ((352 * uu + 731 * vv) shr 10)
                    var b = yv + ((1814 * uu) shr 10)
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    argb[o + i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(argb, 0, w, w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "yuvToBitmap failed", t); null
        }
    }

    companion object {
        private const val TAG = "ReplaySource"
        const val FILE_NAME = "replay.mp4"
    }
}
