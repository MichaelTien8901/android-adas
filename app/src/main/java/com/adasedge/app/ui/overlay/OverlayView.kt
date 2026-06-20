package com.adasedge.app.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.adasedge.app.model.PerceptionResult
import com.adasedge.app.model.RuntimeStatus
import com.adasedge.app.model.SpeedValidity
import com.adasedge.app.model.Warning
import com.adasedge.app.model.WarningLevel
import com.adasedge.app.model.WarningType

/**
 * Transparent realtime overlay (driver-alert-hmi). Two render modes:
 *  - detailed (default): detection boxes, ego-lane geometry, a warning banner
 *    with an alert glyph, lead distance, and color-coded status chips.
 *  - HUD mirror ([hudMirror]=true): a simplified, high-contrast, horizontally
 *    mirrored layout for windshield reflection (spec task 8.3) — only the
 *    essentials (top warning word, TTC/distance, big speed).
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile private var result: PerceptionResult? = null
    @Volatile private var warnings: List<Warning> = emptyList()
    @Volatile private var status: RuntimeStatus = RuntimeStatus()
    @Volatile var hudMirror: Boolean = false

    /** Replay-validation backdrop (null in live-camera mode). */
    private var backdrop: Bitmap? = null
    private val backdropDst = RectF()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 30f; isFakeBoldText = true
    }
    private val distText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CYAN; style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bannerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 52f; isFakeBoldText = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1B1B1B") }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; isFakeBoldText = true }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val scrim = Paint().apply { color = Color.parseColor("#A6000000") }

    fun submit(r: PerceptionResult?, w: List<Warning>, s: RuntimeStatus) {
        result = r; warnings = w; status = s
        postInvalidateOnAnimation()
    }

    /**
     * Replay validation only: set the decoded frame drawn behind the overlay.
     * Called on the main thread (serialized with [onDraw]), so recycling the
     * previous frame here is safe. Pass null to clear.
     */
    fun submitBackground(frame: Bitmap?) {
        val old = backdrop
        backdrop = frame
        if (old != null && old !== frame && !old.isRecycled) old.recycle()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backdrop?.takeIf { !it.isRecycled }?.let {
            backdropDst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(it, null, backdropDst, null)
        }
        if (hudMirror) drawHud(canvas) else drawDetailed(canvas)
    }

    // ---------------- detailed mode ----------------
    private fun drawDetailed(canvas: Canvas) {
        result?.let { r ->
            r.lanes?.let { drawLane(canvas, it.left); drawLane(canvas, it.right) }
            for (d in r.detections) {
                val col = if (d.cls.isVulnerable) RED else GREEN
                boxPaint.color = col
                val l = d.box.left * width; val t = d.box.top * height
                val rt = d.box.right * width; val b = d.box.bottom * height
                canvas.drawRoundRect(l, t, rt, b, 8f, 8f, boxPaint)
                drawLabel(canvas, "${d.cls.name.lowercase()} ${(d.score * 100).toInt()}%", l, t, col)
            }
            r.lead?.let { lead ->
                canvas.drawText("%.0f m".format(lead.distanceMeters),
                    lead.detection.box.centerX() * width,
                    lead.detection.box.bottom * height + 40f, distText.apply { textAlign = Paint.Align.CENTER })
                distText.textAlign = Paint.Align.LEFT
            }
        }
        drawBanner(canvas)
        drawStatusChips(canvas)
    }

    private fun drawLabel(canvas: Canvas, s: String, x: Float, top: Float, color: Int) {
        val w = labelText.measureText(s)
        val y = (top - 8f).coerceAtLeast(34f)
        labelBg.color = color
        canvas.drawRoundRect(x, y - 30f, x + w + 16f, y + 6f, 6f, 6f, labelBg)
        canvas.drawText(s, x + 8f, y, labelText)
    }

    private fun drawLane(canvas: Canvas, pts: List<FloatArray>) {
        if (pts.size < 2) return
        val path = Path()
        path.moveTo(pts[0][0] * width, pts[0][1] * height)
        for (i in 1 until pts.size) path.lineTo(pts[i][0] * width, pts[i][1] * height)
        canvas.drawPath(path, lanePaint)
    }

    private fun drawBanner(canvas: Canvas) {
        val top = warnings.maxByOrNull { it.level.ordinal } ?: return
        if (top.level < WarningLevel.ADVISORY) return
        val imminent = top.level == WarningLevel.IMMINENT
        bannerPaint.color = if (imminent) RED_BANNER else AMBER_BANNER
        val h = 78f
        canvas.drawRect(0f, 0f, width.toFloat(), h, bannerPaint)
        drawAlertTriangle(canvas, 38f, h / 2f, 22f, if (imminent) Color.WHITE else Color.BLACK)
        bannerText.color = if (imminent) Color.WHITE else Color.BLACK
        canvas.drawText(bannerLabel(top), 72f, h / 2f + 18f, bannerText)
    }

    /**
     * Banner text with LIVE distance/time pulled from the current frame's lead
     * (not the warning's message — Warning.equals ignores message, so the warning
     * StateFlow conflates message-only updates and the value would otherwise freeze).
     */
    private fun bannerLabel(top: Warning): String {
        val lead = result?.lead
        fun dist() = lead?.let { "%.0f m".format(it.distanceMeters) } ?: "-- m"
        return when (top.type) {
            WarningType.FORWARD_COLLISION -> {
                val ttc = lead?.ttcSeconds
                val ttcStr = if (ttc != null && ttc.isFinite()) "%.1f s".format(ttc) else "-- s"
                "COLLISION   $ttcStr   ${dist()}"
            }
            WarningType.HEADWAY -> {
                val mps = status.speedKmh / 3.6f
                val thw = if (lead != null && mps > 0.5f) lead.distanceMeters / mps else null
                val thwStr = if (thw != null && thw.isFinite()) "%.1f s".format(thw) else "-- s"
                "TOO CLOSE   $thwStr   ${dist()}"
            }
            else -> top.message.ifEmpty { top.type.name.replace('_', ' ') }
        }
    }

    private fun drawAlertTriangle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPaint.color = color; iconPaint.style = Paint.Style.FILL
        val p = Path().apply {
            moveTo(cx, cy - r); lineTo(cx - r, cy + r * 0.8f); lineTo(cx + r, cy + r * 0.8f); close()
        }
        canvas.drawPath(p, iconPaint)
        iconPaint.color = if (color == Color.WHITE) RED_BANNER else AMBER_BANNER
        canvas.drawRect(cx - 2.5f, cy - r * 0.3f, cx + 2.5f, cy + r * 0.35f, iconPaint)
        canvas.drawCircle(cx, cy + r * 0.55f, 2.6f, iconPaint)
    }

    private fun drawStatusChips(canvas: Canvas) {
        val chips = buildList {
            when (status.speedValidity) {
                SpeedValidity.INVALID -> add("SPEED LOST" to RED)
                SpeedValidity.DEAD_RECKONED -> add("SPEED EST" to AMBER)
                else -> {}
            }
            if (status.thermalThrottled) add("THERMAL" to AMBER)
            if (status.reducedPerformance && !status.thermalThrottled) add("NO NPU" to AMBER)
            if (!status.lanesAvailable) add("NO LANES" to AMBER)
            add("${status.fps.toInt()} FPS" to GREEN)
        }
        var y = height - 24f
        for ((label, col) in chips) {
            val w = chipText.measureText(label) + 24f
            canvas.drawRoundRect(18f, y - 34f, 18f + w, y + 4f, 10f, 10f, chipBg)
            chipText.color = col
            canvas.drawText(label, 30f, y - 8f, chipText)
            y -= 46f
        }
    }

    // ---------------- HUD mirror mode ----------------
    private fun drawHud(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f) // mirror for windshield reflection
        val cx = width / 2f
        hudPaint.textAlign = Paint.Align.CENTER

        val top = warnings.maxByOrNull { it.level.ordinal }
        if (top != null && top.level >= WarningLevel.ADVISORY) {
            val imminent = top.level == WarningLevel.IMMINENT
            hudPaint.color = if (imminent) RED else AMBER
            hudPaint.textSize = 96f
            canvas.drawText(hudWord(top), cx, height * 0.30f, hudPaint)
            result?.lead?.let { lead ->
                hudPaint.color = Color.WHITE; hudPaint.textSize = 56f
                val ttc = if (lead.ttcSeconds.isFinite()) "%.1f s   ".format(lead.ttcSeconds) else ""
                canvas.drawText("$ttc%.0f m".format(lead.distanceMeters), cx, height * 0.46f, hudPaint)
            }
        }

        // Big speed, color-coded by validity.
        hudPaint.color = when (status.speedValidity) {
            SpeedValidity.VALID -> Color.WHITE
            SpeedValidity.DEAD_RECKONED -> AMBER
            SpeedValidity.INVALID -> RED
        }
        hudPaint.textSize = 92f
        val spd = if (status.speedValidity == SpeedValidity.INVALID) "-- km/h"
                  else "${status.speedKmh.toInt()} km/h"
        canvas.drawText(spd, cx, height * 0.84f, hudPaint)
        canvas.restore()

        // Tag (not mirrored) so it's identifiable on-screen.
        chipText.color = Color.parseColor("#9099A2")
        canvas.drawText("HUD MIRROR", 22f, 40f, chipText)
        hudPaint.textAlign = Paint.Align.LEFT
    }

    private fun hudWord(w: Warning): String = when (w.type) {
        com.adasedge.app.model.WarningType.FORWARD_COLLISION -> "COLLISION"
        com.adasedge.app.model.WarningType.LANE_DEPARTURE -> "LANE"
        com.adasedge.app.model.WarningType.HEADWAY -> "TOO CLOSE"
        com.adasedge.app.model.WarningType.OVER_SPEED -> "SLOW DOWN"
        else -> w.type.name.replace('_', ' ')
    }

    companion object {
        private val CYAN = Color.parseColor("#33C3FF")
        private val GREEN = Color.parseColor("#69F0AE")
        private val RED = Color.parseColor("#FF5252")
        private val AMBER = Color.parseColor("#FFB300")
        private val RED_BANNER = Color.parseColor("#D32F2F")
        private val AMBER_BANNER = Color.parseColor("#FFB300")
    }
}
