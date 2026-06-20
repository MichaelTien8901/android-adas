package com.adasedge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Guided-calibration overlay: a draggable horizontal line the user aligns with the
 * skyline. [normalizedY] (0..1 of view height) is persisted as the horizon ratio
 * and drives the lane crop. The region above the line (which the lane preprocessing
 * drops) is dimmed so the effect is visible.
 */
class HorizonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var normalizedY: Float = 0.45f
        set(v) { field = v.coerceIn(0.05f, 0.95f); invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMBER; strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val skyDim = Paint().apply { color = Color.parseColor("#66000000") }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val y = normalizedY * height
        canvas.drawRect(0f, 0f, width.toFloat(), y, skyDim)        // dropped (sky) region
        canvas.drawLine(0f, y, width.toFloat(), y, line)
        canvas.drawCircle(width / 2f, y, 18f, handle)
        canvas.drawText("Drag to the horizon / skyline", 32f, y - 20f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                normalizedY = e.y / height
                performClick(); true
            }
            else -> super.onTouchEvent(e)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private companion object { val AMBER = Color.parseColor("#FFB300") }
}
