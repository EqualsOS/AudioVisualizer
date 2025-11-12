package com.equalsos.audiovisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.log10

class VisualizerView(context: Context, attrs: AttributeSet?) :
    View(context, attrs), Choreographer.FrameCallback {

    private var targetBarHeights: FloatArray? = null
    private var currentBarHeights: FloatArray? = null
    private var orientation: Orientation = Orientation.VERTICAL
    private var drawDirection: DrawDirection = DrawDirection.LEFT_TO_RIGHT
    private var numBars = 32
    private var isMirrorVert = false
    private var isMirrorHoriz = false

    private val paint = Paint()
    private var lastFrameTime: Long = 0
    private val fallSpeed = 0.3f
    private val riseSpeed = 0.9f

    enum class Orientation { VERTICAL, HORIZONTAL }
    enum class DrawDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

    init {
        paint.color = Color.parseColor("#26a269")
        paint.style = Paint.Style.FILL
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun setColor(color: Int) {
        paint.color = color
    }

    fun setMirrored(vert: Boolean, horiz: Boolean) {
        isMirrorVert = vert
        isMirrorHoriz = horiz
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTime == 0L) {
            lastFrameTime = frameTimeNanos
        }
        val deltaTimeMillis = (frameTimeNanos - lastFrameTime) / 1_000_000f
        lastFrameTime = frameTimeNanos

        var needsRedraw = false

        val targets = targetBarHeights
        var current = currentBarHeights

        if (targets != null && current == null) {
            current = FloatArray(numBars) { 0f }
            currentBarHeights = current
        }

        if (current != null && targets != null) {
            for (i in 0 until numBars) {
                val currentHeight = current[i]
                val targetHeight = targets[i]

                if (currentHeight < targetHeight) {
                    val newHeight = (currentHeight + riseSpeed * deltaTimeMillis).coerceAtMost(targetHeight)
                    if (current[i] != newHeight) {
                        current[i] = newHeight
                        needsRedraw = true
                    }
                } else if (currentHeight > targetHeight) {
                    val newHeight = (currentHeight - fallSpeed * deltaTimeMillis).coerceAtLeast(targetHeight)
                    if (current[i] != newHeight) {
                        current[i] = newHeight
                        needsRedraw = true
                    }
                }
            }
        }

        if (needsRedraw) {
            invalidate()
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    fun updateVisualizer(bytes: ByteArray) {
        val data = bytes

        if (targetBarHeights == null) {
            targetBarHeights = FloatArray(numBars) { 0f }
        }

        if (data.isEmpty()) {
            for (i in 0 until numBars) {
                targetBarHeights?.set(i, 0f)
            }
            return
        }

        val dataPointsToUse = data.size / 2 - 1
        if (dataPointsToUse <= 0) return

        val minFreq = 1
        val maxFreq = dataPointsToUse
        val logMin = log10(minFreq.toDouble())
        val logMax = log10(maxFreq.toDouble())
        val logRange = logMax - logMin

        val maxMagnitude = 25000f
        val maxHeight = if (orientation == Orientation.VERTICAL) height else width

        for (i in 0 until numBars) {
            val logStart = logMin + (logRange / numBars) * i
            val logEnd = logMin + (logRange / numBars) * (i + 1)

            val startIndex = (Math.pow(10.0, logStart).toInt()).coerceAtLeast(minFreq)
            val endIndex = (Math.pow(10.0, logEnd).toInt()).coerceAtMost(maxFreq)

            var magnitudeSum = 0.0
            var count = 0

            for (j in startIndex..endIndex) {
                if (j*2 + 1 >= data.size) break

                val dataIndex = j * 2
                val real = data[dataIndex].toInt()
                val imaginary = data[dataIndex + 1].toInt()
                val magnitude = (real * real + imaginary * imaginary).toDouble()
                magnitudeSum += magnitude
                count++
            }

            val avgMagnitude = if (count > 0) (magnitudeSum / count).toFloat() else 0f

            val targetHeight = (avgMagnitude / maxMagnitude) * maxHeight

            targetBarHeights?.set(i, targetHeight.coerceAtMost(maxHeight.toFloat()))
        }
    }

    fun setOrientation(orientation: Orientation, drawDirection: DrawDirection) {
        this.orientation = orientation
        this.drawDirection = drawDirection
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val heights = currentBarHeights ?: return
        if (width == 0 || height == 0) return

        if (orientation == Orientation.VERTICAL) {
            drawVertical(canvas, heights)
        } else {
            drawHorizontal(canvas, heights)
        }
    }

    private fun drawVertical(canvas: Canvas, heights: FloatArray) {
        val barWidth = width.toFloat() / numBars
        val barPadding = barWidth * 0.1f
        val effectiveBarWidth = barWidth - barPadding

        for (i in 0 until numBars) {
            val heightIndex = if (isMirrorHoriz) (numBars - 1) - i else i

            val left = i * barWidth + (barPadding / 2)
            val right = left + effectiveBarWidth

            val barHeight = heights[heightIndex]

            val top: Float
            val bottom: Float

            if (isMirrorVert) {
                top = 0f
                bottom = barHeight
            } else {
                top = height - barHeight
                bottom = height.toFloat()
            }

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun drawHorizontal(canvas: Canvas, heights: FloatArray) {
        val barWidth = height.toFloat() / numBars
        val barPadding = barWidth * 0.1f
        val effectiveBarWidth = barWidth - barPadding

        for (i in 0 until numBars) {
            val heightIndex = if (isMirrorHoriz) (numBars - 1) - i else i

            val top = i * barWidth + (barPadding / 2)
            val bottom = top + effectiveBarWidth

            val barHeight = heights[heightIndex]

            val left: Float
            val right: Float

            var effectiveDirection = drawDirection
            if (isMirrorVert) {
                effectiveDirection = if (drawDirection == DrawDirection.LEFT_TO_RIGHT) DrawDirection.RIGHT_TO_LEFT else DrawDirection.LEFT_TO_RIGHT
            }

            if (effectiveDirection == DrawDirection.LEFT_TO_RIGHT) {
                left = 0f
                right = barHeight
            } else {
                left = width - barHeight
                right = width.toFloat()
            }

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}
