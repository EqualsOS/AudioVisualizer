package com.equalsos.audiovisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.log10
import kotlin.math.max

class VisualizerView(context: Context, attrs: AttributeSet?) :
    View(context, attrs), Choreographer.FrameCallback {

    // --- State ---
    private var targetBarHeights: FloatArray? = null
    private var currentBarHeights: FloatArray? = null
    private var orientation: Orientation = Orientation.VERTICAL
    private var drawDirection: DrawDirection = DrawDirection.LEFT_TO_RIGHT
    private var numBars = 32 // Default number of bars

    // --- Animation ---
    private val paint = Paint()
    private var lastFrameTime: Long = 0
    private val fallSpeed = 0.3f // Pixels per millisecond
    private val riseSpeed = 0.9f // Pixels per millisecond

    // --- Drawing Enums ---
    enum class Orientation { VERTICAL, HORIZONTAL }
    enum class DrawDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

    init {
        paint.color = 0xFFFFFFFF.toInt() // White color
        paint.style = Paint.Style.FILL
        // Start the animation loop
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * This is the "game loop" (the requestAnimationFrame equivalent).
     * It runs every single frame to animate the bars smoothly.
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTime == 0L) {
            lastFrameTime = frameTimeNanos
        }
        val deltaTimeMillis = (frameTimeNanos - lastFrameTime) / 1_000_000f
        lastFrameTime = frameTimeNanos

        var needsRedraw = false

        val targets = targetBarHeights
        var current = currentBarHeights

        // Ensure current heights array is initialized
        if (targets != null && current == null) {
            current = FloatArray(numBars) { 0f }
            currentBarHeights = current
        }

        if (current != null && targets != null) {
            for (i in 0 until numBars) {
                val currentHeight = current[i]
                val targetHeight = targets[i]

                if (currentHeight < targetHeight) {
                    // Rise up
                    val newHeight = (currentHeight + riseSpeed * deltaTimeMillis).coerceAtMost(targetHeight)
                    if (current[i] != newHeight) {
                        current[i] = newHeight
                        needsRedraw = true
                    }
                } else if (currentHeight > targetHeight) {
                    // Fall down
                    val newHeight = (currentHeight - fallSpeed * deltaTimeMillis).coerceAtLeast(targetHeight)
                    if (current[i] != newHeight) {
                        current[i] = newHeight
                        needsRedraw = true
                    }
                }
            }
        }

        // If any bar moved, redraw the view
        if (needsRedraw) {
            invalidate()
        }

        // Schedule the next frame
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * Called by the service to update the *target* heights based on new audio data.
     * This does NOT redraw the view directly.
     */
    fun updateVisualizer(bytes: ByteArray) {
        val data = bytes

        if (targetBarHeights == null) {
            targetBarHeights = FloatArray(numBars) { 0f }
        }

        // --- Logarithmic mapping from original code ---
        val dataPointsToUse = data.size / 2 - 1
        val minFreq = 1 // Start from the 1st bin (skip 0/DC)
        val maxFreq = dataPointsToUse
        val logMin = log10(minFreq.toDouble())
        val logMax = log10(maxFreq.toDouble())
        val logRange = logMax - logMin

        val maxMagnitude = 25000f // Empirical max magnitude for scaling
        val maxHeight = if (orientation == Orientation.VERTICAL) height else width

        for (i in 0 until numBars) {
            // Map bar index to log scale
            val logStart = logMin + (logRange / numBars) * i
            val logEnd = logMin + (logRange / numBars) * (i + 1)

            val startIndex = (Math.pow(10.0, logStart).toInt()).coerceAtLeast(minFreq)
            val endIndex = (Math.pow(10.0, logEnd).toInt()).coerceAtMost(maxFreq)

            var magnitudeSum = 0.0
            var count = 0

            for (j in startIndex..endIndex) {
                val dataIndex = j * 2
                val real = data[dataIndex].toInt()
                val imaginary = data[dataIndex + 1].toInt()
                val magnitude = (real * real + imaginary * imaginary).toDouble()
                magnitudeSum += magnitude
                count++
            }

            val avgMagnitude = if (count > 0) (magnitudeSum / count).toFloat() else 0f

            // Scale the height
            val targetHeight = (avgMagnitude / maxMagnitude) * maxHeight

            // Set the target height (we'll animate to this in doFrame)
            targetBarHeights?.set(i, targetHeight.coerceAtMost(maxHeight.toFloat()))
        }
        // Do not call invalidate() here. The Choreographer loop will handle it.
    }

    fun setOrientation(orientation: Orientation, drawDirection: DrawDirection) {
        this.orientation = orientation
        this.drawDirection = drawDirection
    }

    /**
     * The main draw loop. This just reads the *current* heights and draws.
     * All the animation logic is in doFrame().
     */
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
            val left = i * barWidth + (barPadding / 2)
            val right = left + effectiveBarWidth

            val barHeight = heights[i]
            val top = height - barHeight
            val bottom = height.toFloat()

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun drawHorizontal(canvas: Canvas, heights: FloatArray) {
        val barWidth = height.toFloat() / numBars // Bars are horizontal, so width is based on view height
        val barPadding = barWidth * 0.1f
        val effectiveBarWidth = barWidth - barPadding

        for (i in 0 until numBars) {
            val top = i * barWidth + (barPadding / 2)
            val bottom = top + effectiveBarWidth

            val barHeight = heights[i] // This is now "length"

            val left: Float
            val right: Float

            if (drawDirection == DrawDirection.LEFT_TO_RIGHT) {
                // Drawing from the LEFT edge (e.g., ROTATION_270)
                left = 0f
                right = barHeight
            } else {
                // Drawing from the RIGHT edge (e.g., ROTATION_90)
                left = width - barHeight
                right = width.toFloat()
            }

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}