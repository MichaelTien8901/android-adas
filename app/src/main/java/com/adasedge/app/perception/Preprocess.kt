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
}
