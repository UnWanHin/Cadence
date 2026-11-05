package com.campus.sport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var mirrorHorizontally = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80E0B5")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8F36A")
        style = Paint.Style.FILL
    }

    fun update(results: List<NormalizedLandmark>) {
        landmarks = results
        invalidate()
    }

    fun clear() {
        landmarks = emptyList()
        invalidate()
    }

    /**
     * The front camera preview is mirrored by PreviewView, while ImageAnalysis
     * returns unmirrored frames. Mirror the overlay to keep the skeleton aligned
     * with what the user sees.
     */
    fun setMirrorHorizontally(mirror: Boolean) {
        if (mirrorHorizontally == mirror) return
        mirrorHorizontally = mirror
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.size < 33) return

        val width = width.toFloat()
        val height = height.toFloat()
        for ((a, b) in CONNECTIONS) {
            if (visible(a) && visible(b)) {
                canvas.drawLine(
                    xOf(a, width),
                    landmarks[a].y() * height,
                    xOf(b, width),
                    landmarks[b].y() * height,
                    linePaint,
                )
            }
        }
        for (index in landmarks.indices) {
            if (!visible(index)) continue
            canvas.drawCircle(
                xOf(index, width),
                landmarks[index].y() * height,
                5f,
                pointPaint,
            )
        }
    }

    private fun xOf(index: Int, width: Float): Float {
        val x = landmarks[index].x()
        return (if (mirrorHorizontally) 1f - x else x) * width
    }

    private fun visible(index: Int): Boolean {
        if (index !in landmarks.indices) return false
        val visibility = landmarks[index].visibility()
        return (if (visibility.isPresent) visibility.get() else 0f) >= 0.50f
    }

    private companion object {
        val CONNECTIONS = listOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28,
        )
    }
}
