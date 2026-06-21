package com.adasedge.app.perception

import android.graphics.Bitmap
import android.graphics.RectF
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.model.Detection
import com.adasedge.app.model.ObjectClass
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.min

/**
 * Speed-limit sign recognition (task 7.6 / research/02 Phase 2). Two-stage: OpenCV
 * proposes circular sign regions (HoughCircles), and a GTSRB-trained CNN reads the
 * value. Emits [ObjectClass.SPEED_LIMIT_SIGN] detections carrying the recognized
 * limit (km/h) in [Detection.attribute] — the input TrafficSignWarning needs for
 * the over-speed warning. COCO YOLO11n can't do this (no speed-limit class).
 */
class SpeedLimitRecognizer(private val runner: ModelRunner) {

    fun detect(src: Bitmap): List<Detection> {
        val w = src.width; val h = src.height
        val rgba = Mat(); Utils.bitmapToMat(src, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.medianBlur(gray, gray, 5)
        val circles = Mat()
        Imgproc.HoughCircles(
            gray, circles, Imgproc.HOUGH_GRADIENT, 1.0,
            /*minDist=*/ h / 16.0, /*param1=*/ 100.0, /*param2=*/ 30.0,
            /*minRadius=*/ (h * 0.02).toInt(), /*maxRadius=*/ (h * 0.18).toInt(),
        )
        val out = ArrayList<Detection>()
        val n = minOf(circles.cols(), MAX_CANDIDATES)
        for (i in 0 until n) {
            val c = circles.get(0, i) ?: continue
            val cx = c[0]; val cy = c[1]; val r = c[2] * 1.15   // pad past the red ring
            val x0 = (cx - r).toInt().coerceIn(0, w - 1); val y0 = (cy - r).toInt().coerceIn(0, h - 1)
            val x1 = (cx + r).toInt().coerceIn(x0 + 1, w); val y1 = (cy + r).toInt().coerceIn(y0 + 1, h)
            // A 43-class GTSRB classifier has no "background" class, so any circular
            // road feature (wheels, manholes) gets forced into a class — sometimes
            // confidently. Real speed-limit signs have a RED RING; gate on it to
            // reject those false positives (validated: drops road false-positives to 0).
            if (redRingFraction(rgba, x0, y0, x1, y1) < RED_RING_MIN) continue
            val (cls, conf) = classify(src, x0, y0, x1 - x0, y1 - y0) ?: continue
            val limit = CLASS_TO_LIMIT[cls] ?: continue       // not a speed-limit sign
            if (conf < MIN_CONF) continue
            out += Detection(
                ObjectClass.SPEED_LIMIT_SIGN,
                RectF(x0 / w.toFloat(), y0 / h.toFloat(), x1 / w.toFloat(), y1 / h.toFloat()),
                conf, attribute = limit.toString(),
            )
        }
        listOf(rgba, gray, circles).forEach { it.release() }
        return out
    }

    /** Crop -> 48x48 NCHW [0,1] -> GTSRB CNN -> (classIdx, softmax prob). */
    private fun classify(src: Bitmap, x: Int, y: Int, cw: Int, ch: Int): Pair<Int, Float>? {
        if (cw < 8 || ch < 8) return null
        val crop = Bitmap.createBitmap(src, x, y, cw, ch)
        val s = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
        crop.recycle()
        val px = IntArray(SIZE * SIZE); s.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE); s.recycle()
        val plane = SIZE * SIZE
        val input = FloatArray(3 * plane)
        for (j in 0 until plane) {
            val p = px[j]
            input[j] = ((p shr 16) and 0xFF) / 255f
            input[plane + j] = ((p shr 8) and 0xFF) / 255f
            input[2 * plane + j] = (p and 0xFF) / 255f
        }
        val logits = runner.run(input).firstOrNull()?.data ?: return null
        var best = 0; for (k in logits.indices) if (logits[k] > logits[best]) best = k
        // softmax prob of the argmax (numerically stable enough for confidence gating).
        var sum = 0f; val mx = logits[best]
        for (v in logits) sum += exp(v - mx)
        return best to (1f / sum)
    }

    /**
     * Fraction of red pixels in the outer annulus (0.6–1.0 of radius) of the crop —
     * the signature of a speed-limit sign's red ring. A cheap colour/shape prior the
     * closed-set CNN lacks; drives the false-positive rejection in [detect].
     */
    private fun redRingFraction(rgba: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val sub = rgba.submat(y0, y1, x0, x1)
        val rgb = Mat(); Imgproc.cvtColor(sub, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val m1 = Mat(); Core.inRange(hsv, Scalar(0.0, 70.0, 50.0), Scalar(10.0, 255.0, 255.0), m1)
        val m2 = Mat(); Core.inRange(hsv, Scalar(170.0, 70.0, 50.0), Scalar(180.0, 255.0, 255.0), m2)
        val red = Mat(); Core.bitwise_or(m1, m2, red)
        val hh = sub.rows(); val ww = sub.cols()
        val ring = Mat.zeros(hh, ww, CvType.CV_8UC1)
        val outer = (min(ww, hh) / 2.0)
        Imgproc.circle(ring, Point(ww / 2.0, hh / 2.0), outer.toInt(), Scalar(255.0), -1)
        Imgproc.circle(ring, Point(ww / 2.0, hh / 2.0), (outer * 0.6).toInt(), Scalar(0.0), -1)
        val ringCount = Core.countNonZero(ring).toDouble()
        Core.bitwise_and(red, ring, red)
        val frac = if (ringCount > 0) Core.countNonZero(red) / ringCount else 0.0
        listOf(sub, rgb, hsv, m1, m2, red, ring).forEach { it.release() }
        return frac
    }

    companion object {
        private const val SIZE = 48
        private const val MIN_CONF = 0.6f
        private const val RED_RING_MIN = 0.15   // min red-ring fraction to accept a circle as a sign
        private const val MAX_CANDIDATES = 6
        // GTSRB class id -> speed limit (km/h). Must match tools/train_gtsrb.py.
        private val CLASS_TO_LIMIT = mapOf(0 to 20, 1 to 30, 2 to 50, 3 to 60, 4 to 70, 5 to 80, 7 to 100, 8 to 120)
    }
}
