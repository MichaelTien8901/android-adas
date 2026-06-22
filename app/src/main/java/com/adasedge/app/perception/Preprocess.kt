package com.adasedge.app.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Converts a camera frame Bitmap into a normalized NCHW float input for a model,
 * letterboxing to a square/target while preserving aspect ratio. Returns the
 * input array plus the scale/pad used so detections can be mapped back to the
 * original frame.
 */
object Preprocess {

    data class Letterbox(val scale: Float, val padX: Float, val padY: Float, val w: Int, val h: Int)

    /** Result of preprocessing: model input + the transform to undo letterboxing. */
    class Prepared(val input: FloatArray, val box: Letterbox)

    /**
     * @param meanZeroToOne when true outputs [0,1] (YOLO/UFLD style); channel order RGB.
     */
    fun toNchw(src: Bitmap, dstW: Int, dstH: Int): Prepared {
        val scale = minOf(dstW / src.width.toFloat(), dstH / src.height.toFloat())
        val newW = (src.width * scale)
        val newH = (src.height * scale)
        val padX = (dstW - newW) / 2f
        val padY = (dstH - newH) / 2f

        val canvasBmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val pixels = IntArray(dstW * dstH)
        canvasBmp.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        canvasBmp.recycle()

        // NCHW, RGB, normalized to [0,1].
        val plane = dstW * dstH
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f          // R
            out[plane + i] = ((p shr 8) and 0xFF) / 255f    // G
            out[2 * plane + i] = (p and 0xFF) / 255f         // B
        }
        return Prepared(out, Letterbox(scale, padX, padY, dstW, dstH))
    }

    /**
     * UFLDv2 lane preprocessing. First drops everything above the horizon
     * ([horizonRatio]..1 of the frame) — empirically the road should start near the
     * top of the model input — then stretches that band to [dstW] x round([dstH]/
     * [cropRatio]) (no aspect preservation — the model is trained that way) and keeps
     * the bottom [dstH] rows. Returns an NCHW [0,1] RGB input. Lane-y is remapped to
     * full-frame space in LaneDetector using the same [horizonRatio]/[cropRatio].
     */
    /**
     * Segmentation input (TwinLiteNet): resize the WHOLE frame to dstW×dstH (no crop, no
     * letterbox — the model is trained on the full frame), RGB, scaled to [0,1], NCHW.
     * Camera frames are 16:9 (1280×720) so 640×360 preserves aspect with no distortion.
     */
    fun toSegInput(src: Bitmap, dstW: Int, dstH: Int): FloatArray {
        val scaled = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawBitmap(src, Matrix().apply {
            setScale(dstW.toFloat() / src.width, dstH.toFloat() / src.height)
        }, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(dstW * dstH)
        scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        scaled.recycle()
        val plane = dstW * dstH
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }

    fun toLaneInput(src: Bitmap, dstW: Int, dstH: Int, cropRatio: Float): FloatArray {
        // Match UFLDv2's training/deploy preprocessing exactly: resize the WHOLE frame
        // to (dstH/cropRatio) tall then take the bottom dstH rows (drop the top sky =
        // 1-cropRatio). The model's row anchors are full-frame positions, so feeding
        // the full frame (NOT a horizon-cropped band) keeps the lanes aligned.
        val fullH = Math.round(dstH / cropRatio)
        val cropTop = fullH - dstH
        val sy = fullH.toFloat() / src.height
        val scaled = Bitmap.createBitmap(dstW, fullH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        // Map the whole source [0, srcH] -> dst rows [0, fullH]; we keep the bottom dstH.
        val m = Matrix().apply {
            setScale(dstW.toFloat() / src.width, sy)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val pixels = IntArray(dstW * dstH)
        scaled.getPixels(pixels, 0, dstW, 0, cropTop, dstW, dstH)   // bottom dstH rows
        scaled.recycle()

        val plane = dstW * dstH
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }
}
