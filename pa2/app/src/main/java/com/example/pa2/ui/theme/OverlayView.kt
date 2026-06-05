package com.example.pa2.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.pa2.detector.Detection

/**
 * Draws YOLO detections over the camera preview. Detections arrive in
 * normalized model-space ([0,1]) and are mapped to view space.
 *
 * The camera preview uses CameraX's PreviewView with FILL_CENTER scaling
 * so the visible image is a center crop of the analysis frame. Because
 * we run detection on a square 640×640 input that we generated from the
 * full analysis frame, we draw boxes in *analysis* space; the small crop
 * mismatch at the edges is acceptable for a demo.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        // Default detection color: yellow (matches TA reference UI).
        color = Color.parseColor("#FFEA00")
        isAntiAlias = true
    }
    private val targetBoxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        // Target highlight color: green (matches TA reference UI).
        color = Color.parseColor("#00E676")
        isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
        isAntiAlias = true
    }
    /** Text color is set per-detection so it matches the box color. */
    private val labelTextPaint = Paint().apply {
        textSize = 38f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val targetTextColor = Color.parseColor("#00E676")
    private val nonTargetTextColor = Color.parseColor("#FFEA00")

    private var detections: List<Detection> = emptyList()
    private var targetClassId: Int = -1
    /** When true, only the target class is drawn; non-target detections are hidden. */
    private var filterMode: Boolean = false

    fun update(dets: List<Detection>, targetClassId: Int, filterMode: Boolean = false) {
        this.detections = dets
        this.targetClassId = targetClassId
        this.filterMode = filterMode
        postInvalidate()
    }

    fun clear() {
        detections = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        for (d in detections) {
            val isTarget = d.classId == targetClassId
            if (filterMode && !isTarget) continue
            val r = RectF(d.box.left * w, d.box.top * h, d.box.right * w, d.box.bottom * h)
            canvas.drawRect(r, if (isTarget) targetBoxPaint else boxPaint)

            val label = "${d.classLabel} ${(d.score * 100).toInt()}%"
            labelTextPaint.color = if (isTarget) targetTextColor else nonTargetTextColor
            val tw = labelTextPaint.measureText(label)
            val th = labelTextPaint.fontMetrics.run { descent - ascent }
            val pad = 8f
            val bgL = r.left
            val bgT = (r.top - th - pad * 2).coerceAtLeast(0f)
            val bgR = bgL + tw + pad * 2
            val bgB = bgT + th + pad * 2
            canvas.drawRect(bgL, bgT, bgR, bgB, labelBgPaint)
            canvas.drawText(label, bgL + pad, bgB - pad - labelTextPaint.fontMetrics.descent, labelTextPaint)
        }
    }
}
