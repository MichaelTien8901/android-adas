package com.adasedge.app.perception

import android.graphics.RectF
import com.adasedge.app.core.Config
import com.adasedge.app.inference.ModelRunner
import com.adasedge.app.inference.TensorOut
import com.adasedge.app.model.Detection
import com.adasedge.app.model.ObjectClass

/**
 * Decodes YOLO11n output into [Detection]s in normalized frame coordinates
 * (adas-perception: "Object detection of road agents"). Handles both
 * [1,84,8400] and [1,8400,84] output layouts, applies confidence thresholding
 * and class-aware NMS.
 */
class ObjectDetector(private val runner: ModelRunner) {

    fun detect(prepared: Preprocess.Prepared, srcW: Int, srcH: Int): List<Detection> {
        val out = runner.run(prepared.input).firstOrNull() ?: return emptyList()
        val raw = decode(out, prepared.box, srcW, srcH)
        return nms(raw)
    }

    private fun decode(out: TensorOut, box: Preprocess.Letterbox, srcW: Int, srcH: Int): List<Detection> {
        // shape [1, C, N] or [1, N, C]; C = 4 + numClasses.
        val shape = out.shape
        val (channels, anchors, channelMajor) = when {
            shape.size == 3 && shape[1] < shape[2] -> Triple(shape[1], shape[2], true)
            shape.size == 3 -> Triple(shape[2], shape[1], false)
            else -> return emptyList()
        }
        val numClasses = channels - 4
        val data = out.data
        val results = ArrayList<Detection>()

        fun at(c: Int, a: Int): Float =
            if (channelMajor) data[c * anchors + a] else data[a * channels + c]

        for (a in 0 until anchors) {
            var bestCls = -1; var bestScore = 0f
            for (k in 0 until numClasses) {
                val s = at(4 + k, a)
                if (s > bestScore) { bestScore = s; bestCls = k }
            }
            if (bestScore < Config.DETECTION_CONF_THRESHOLD) continue
            val cls = cocoToClass(bestCls) ?: continue

            // box in letterboxed input pixels (cx,cy,w,h) -> undo letterbox -> normalize.
            val cx = at(0, a); val cy = at(1, a); val w = at(2, a); val h = at(3, a)
            val x0 = ((cx - w / 2f) - box.padX) / box.scale
            val y0 = ((cy - h / 2f) - box.padY) / box.scale
            val x1 = ((cx + w / 2f) - box.padX) / box.scale
            val y1 = ((cy + h / 2f) - box.padY) / box.scale
            val r = RectF(
                (x0 / srcW).coerceIn(0f, 1f), (y0 / srcH).coerceIn(0f, 1f),
                (x1 / srcW).coerceIn(0f, 1f), (y1 / srcH).coerceIn(0f, 1f),
            )
            if (r.width() <= 0f || r.height() <= 0f) continue
            results += Detection(cls, r, bestScore)
        }
        return results
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep += best
            sorted.removeAll { it.cls == best.cls && iou(it.box, best.box) > Config.NMS_IOU_THRESHOLD }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** COCO index -> our subset; null = ignore. */
    private fun cocoToClass(i: Int): ObjectClass? = when (i) {
        0 -> ObjectClass.PERSON
        1 -> ObjectClass.BICYCLE
        2 -> ObjectClass.CAR
        3 -> ObjectClass.MOTORCYCLE
        5 -> ObjectClass.BUS
        7 -> ObjectClass.TRUCK
        9 -> ObjectClass.TRAFFIC_LIGHT
        11 -> ObjectClass.STOP_SIGN
        else -> null
    }
}
