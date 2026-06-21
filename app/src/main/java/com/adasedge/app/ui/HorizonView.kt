package com.adasedge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Guided-calibration overlay: two draggable horizontal lines. The top line is the
 * horizon/skyline ([normalizedY], persisted as the horizon ratio) and the bottom
 * line is the road bottom / car-hood top ([hoodY], persisted as the road-bottom
 * ratio). The lane band is between them; the dropped regions (sky above, hood
 * below) are dimmed so the effect is visible. Drag the handle nearest the touch.
 */
class HorizonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var normalizedY: Float = 0.45f
        set(v) { field = v.coerceIn(0.05f, 0.9f); invalidate() }
    var hoodY: Float = 1.0f
        set(v) { field = v.coerceIn(0.1f, 1.0f); invalidate() }
    var centerX: Float = 0.5f
        set(v) { field = v.coerceIn(0.2f, 0.8f); invalidate() }

    private var dragging = 0   // 0 = none, 1 = horizon, 2 = hood, 3 = center

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER; strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val dim = Paint().apply { color = Color.parseColor("#66000000") }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val hy = normalizedY * height
        val dy = hoodY * height
        canvas.drawRect(0f, 0f, width.toFloat(), hy, dim)                       // dropped sky
        if (hoodY < 1f) canvas.drawRect(0f, dy, width.toFloat(), height.toFloat(), dim)  // dropped hood
        canvas.drawLine(0f, hy, width.toFloat(), hy, line)
        canvas.drawCircle(width / 2f, hy, 18f, handle)
        canvas.drawText("Drag to the horizon / skyline", 32f, hy - 20f, label)
        canvas.drawLine(0f, dy, width.toFloat(), dy, line)
        canvas.drawCircle(width / 2f, dy, 18f, handle)
        canvas.drawText("Drag to the hood / road bottom", 32f, dy - 20f, label)
        // Vertical line: "straight ahead" (camera centre).
        val cx = centerX * width
        canvas.drawLine(cx, hy, cx, dy, line)
        canvas.drawCircle(cx, (hy + dy) / 2f, 18f, handle)
        canvas.drawText("Centre", cx + 24f, (hy + dy) / 2f - 24f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Pick the nearest of the three handles (two horizontal, one vertical).
                val ty = e.y / height; val tx = e.x / width
                val dH = abs(ty - normalizedY); val dB = abs(ty - hoodY); val dC = abs(tx - centerX)
                dragging = if (dC <= dH && dC <= dB) 3 else if (dH <= dB) 1 else 2
                apply(e); performClick(); true
            }
            MotionEvent.ACTION_MOVE -> { if (dragging != 0) apply(e); true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = 0; true }
            else -> super.onTouchEvent(e)
        }
    }

    private fun apply(e: MotionEvent) {
        when (dragging) {
            1 -> normalizedY = e.y / height
            2 -> hoodY = e.y / height
            3 -> centerX = e.x / width
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private companion object { val AMBER = Color.parseColor("#FFB300") }
}
