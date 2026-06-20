package com.adasedge.app.perception

import android.graphics.Bitmap
import com.adasedge.app.model.LaneGeometry
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Classical-CV lane fallback (design D5) used when the UFLDv2 NPU model is
 * unavailable. Canny edges within a trapezoidal ROI + probabilistic Hough,
 * separated into left/right by slope. Coordinates returned normalized (0..1).
 */
class ClassicalLaneFallback {

    fun detect(src: Bitmap): LaneGeometry? {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val w = rgba.cols(); val h = rgba.rows()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, org.opencv.core.Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 150.0)

        // ROI: lower-center trapezoid.
        val mask = Mat.zeros(edges.size(), edges.type())
        val poly = org.opencv.core.MatOfPoint(
            Point(w * 0.08, h.toDouble()),
            Point(w * 0.45, h * 0.6),
            Point(w * 0.55, h * 0.6),
            Point(w * 0.92, h.toDouble()),
        )
        Imgproc.fillPoly(mask, listOf(poly), Scalar(255.0))
        val roi = Mat()
        Core.bitwise_and(edges, mask, roi)

        val lines = Mat()
        Imgproc.HoughLinesP(roi, lines, 1.0, Math.PI / 180, 40, 40.0, 120.0)

        val left = ArrayList<FloatArray>(); val right = ArrayList<FloatArray>()
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0) ?: continue
            val x1 = l[0]; val y1 = l[1]; val x2 = l[2]; val y2 = l[3]
            val slope = if (x2 - x1 == 0.0) continue else (y2 - y1) / (x2 - x1)
            if (kotlin.math.abs(slope) < 0.4) continue
            val target = if (slope < 0) left else right
            target += floatArrayOf((x1 / w).toFloat(), (y1 / h).toFloat())
            target += floatArrayOf((x2 / w).toFloat(), (y2 / h).toFloat())
        }
        listOf(rgba, gray, edges, mask, roi, lines).forEach { it.release() }
        if (left.isEmpty() && right.isEmpty()) return null
        return LaneGeometry(left.sortedBy { it[1] }, right.sortedBy { it[1] }, confidence = 0.4f)
    }
}
