package com.equalsos.audiovisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.log10

class VisualizerView(context: Context, attrs: AttributeSet?) :
    View(context, attrs), Choreographer.FrameCallback {

    private var targetBarHeights: FloatArray? = null
    private var currentBarHeights: FloatArray? = null
    private var currentPeakHeights: FloatArray? = null // <-- NEW: Array for peak heights
    private var orientation: Orientation = Orientation.VERTICAL
    private var drawDirection: DrawDirection = DrawDirection.LEFT_TO_RIGHT

    private var numBars = 32 // Default value

    private var isMirrorVert = false
    private var isMirrorHoriz = false

    private val paint = Paint()
    private val peakPaint = Paint() // <-- NEW: Paint for peaks
    private var lastFrameTime: Long = 0

    // --- MODIFIED: Tweaked speeds for gravity effect ---
    private val fallSpeed = 0.9f // Main bar falls fast
    private val riseSpeed = 0.9f
    private val peakFallSpeed = 0.2f // Peak falls slow
    private val peakHeight = 4.0f // Height of the peak in pixels
    // --- END MODIFIED ---

    enum class Orientation { VERTICAL, HORIZONTAL }
    enum class DrawDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

    init {
        paint.color = Color.parseColor("#26a269")
        paint.style = Paint.Style.FILL

        // --- NEW: Init peak paint ---
        peakPaint.color = lighterColor(paint.color)
        peakPaint.style = Paint.Style.FILL
        // --- END NEW ---

        Choreographer.getInstance().postFrameCallback(this)
    }

    // --- NEW: Helper to make peak color lighter ---
    private fun lighterColor(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = (hsl[2] + 0.15f).coerceAtMost(1.0f) // Increase lightness
        return ColorUtils.HSLToColor(hsl)
    }
    // --- END NEW ---

    fun setColor(color: Int) {
        paint.color = color
        peakPaint.color = lighterColor(color) // <-- Also update peak color
    }

    fun setMirrored(vert: Boolean, horiz: Boolean) {
        isMirrorVert = vert
        isMirrorHoriz = horiz
    }

    fun setNumBars(newNumBars: Int) {
        val clampedNumBars = newNumBars.coerceIn(6, 100)

        if (clampedNumBars == numBars) return

        numBars = clampedNumBars
        // Reset all arrays
        targetBarHeights = null
        currentBarHeights = null
        currentPeakHeights = null // <-- NEW: Reset peaks
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTime == 0L) {
            lastFrameTime = frameTimeNanos
        }
        val deltaTimeMillis = (frameTimeNanos - lastFrameTime) / 1_000_000f
        lastFrameTime = frameTimeNanos

        var needsRedraw = false

        val targets = targetBarHeights
        var currentBars = currentBarHeights
        var currentPeaks = currentPeakHeights // <-- NEW: Get peaks

        // --- MODIFIED: Init/Check all arrays ---
        if (targets != null && targets.size != numBars) {
            targetBarHeights = null
            currentBarHeights = null
            currentPeakHeights = null
            currentBars = null
            currentPeaks = null
        }

        if (targets != null && currentBars == null) {
            currentBars = FloatArray(numBars) { 0f }
            currentBarHeights = currentBars
        }

        if (targets != null && currentPeaks == null) {
            currentPeaks = FloatArray(numBars) { 0f }
            currentPeakHeights = currentPeaks
        }
        // --- END MODIFIED ---

        if (currentBars != null && currentPeaks != null && targets != null) {
            // Safety check
            if (currentBars.size != numBars || currentPeaks.size != numBars || targets.size != numBars) {
                targetBarHeights = null
                currentBarHeights = null
                currentPeakHeights = null
                Choreographer.getInstance().postFrameCallback(this)
                return
            }

            for (i in 0 until numBars) {
                val targetHeight = targets[i]

                // --- Animate Main Bar ---
                val currentBarHeight = currentBars[i]
                if (currentBarHeight < targetHeight) {
                    val newHeight = (currentBarHeight + riseSpeed * deltaTimeMillis).coerceAtMost(targetHeight)
                    if (currentBars[i] != newHeight) {
                        currentBars[i] = newHeight
                        needsRedraw = true
                    }
                } else if (currentBarHeight > targetHeight) {
                    val newHeight = (currentBarHeight - fallSpeed * deltaTimeMillis).coerceAtLeast(targetHeight)
                    if (currentBars[i] != newHeight) {
                        currentBars[i] = newHeight
                        needsRedraw = true
                    }
                }

                // --- Animate Peak ---
                val currentPeakHeight = currentPeaks[i]

                if (targetHeight > currentPeakHeight) {
                    // Peak "sticks" to the bar when rising
                    val newPeakHeight = currentBars[i] // Use the (new) bar height
                    if (currentPeaks[i] != newPeakHeight) {
                        currentPeaks[i] = newPeakHeight
                        needsRedraw = true // Bar animation will already request this
                    }
                } else {
                    // Peak falls slowly when bar drops
                    val newPeakHeight = (currentPeakHeight - peakFallSpeed * deltaTimeMillis).coerceAtLeast(targetHeight)
                    // Make sure peak doesn't fall *below* the main bar
                    val finalPeakHeight = newPeakHeight.coerceAtLeast(currentBars[i])
                    if (currentPeaks[i] != finalPeakHeight) {
                        currentPeaks[i] = finalPeakHeight
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

        if (targetBarHeights == null || targetBarHeights?.size != numBars) {
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

        val barHeights = currentBarHeights
        val peakHeights = currentPeakHeights // <-- NEW: Get peak heights

        if (barHeights == null || peakHeights == null) return
        if (width == 0 || height == 0 || numBars == 0) return
        if (barHeights.size != numBars || peakHeights.size != numBars) return

        if (orientation == Orientation.VERTICAL) {
            drawVertical(canvas, barHeights, peakHeights) // <-- Pass peaks
        } else {
            drawHorizontal(canvas, barHeights, peakHeights) // <-- Pass peaks
        }
    }

    // --- MODIFIED: Add peakHeights parameter ---
    private fun drawVertical(canvas: Canvas, barHeights: FloatArray, peakHeights: FloatArray) {
        val barWidth = width.toFloat() / numBars
        val barPadding = barWidth * 0.1f
        val effectiveBarWidth = barWidth - barPadding

        for (i in 0 until numBars) {
            val heightIndex = if (isMirrorHoriz) (numBars - 1) - i else i

            val left = i * barWidth + (barPadding / 2)
            val right = left + effectiveBarWidth

            val barHeight = barHeights[heightIndex]
            val peakHeightVal = peakHeights[heightIndex]

            val barTop: Float
            val barBottom: Float
            val peakTop: Float
            val peakBottom: Float

            if (isMirrorVert) {
                // Drawing from Top -> Down
                barTop = 0f
                barBottom = barHeight

                // Peak is a small rect *at* the peak height
                peakTop = (peakHeightVal - peakHeight).coerceAtLeast(0f)
                peakBottom = peakHeightVal
            } else {
                // Drawing from Bottom -> Up
                barTop = height - barHeight
                barBottom = height.toFloat()

                // Peak is a small rect *at* the peak height
                peakTop = height - peakHeightVal
                peakBottom = (height - peakHeightVal + peakHeight).coerceAtMost(height.toFloat())
            }

            // Draw main bar
            canvas.drawRect(left, barTop, right, barBottom, paint)
            // Draw peak
            canvas.drawRect(left, peakTop, right, peakBottom, peakPaint)
        }
    }

    // --- MODIFIED: Add peakHeights parameter ---
    private fun drawHorizontal(canvas: Canvas, barHeights: FloatArray, peakHeights: FloatArray) {
        val barWidth = height.toFloat() / numBars
        val barPadding = barWidth * 0.1f
        val effectiveBarWidth = barWidth - barPadding

        for (i in 0 until numBars) {
            val heightIndex = if (isMirrorHoriz) (numBars - 1) - i else i

            val top = i * barWidth + (barPadding / 2)
            val bottom = top + effectiveBarWidth

            val barHeight = barHeights[heightIndex]
            val peakHeightVal = peakHeights[heightIndex]

            val barLeft: Float
            val barRight: Float
            val peakLeft: Float
            val peakRight: Float

            var effectiveDirection = drawDirection
            if (isMirrorVert) {
                effectiveDirection = if (drawDirection == DrawDirection.LEFT_TO_RIGHT) DrawDirection.RIGHT_TO_LEFT else DrawDirection.LEFT_TO_RIGHT
            }

            if (effectiveDirection == DrawDirection.LEFT_TO_RIGHT) {
                // Drawing from Left -> Right
                barLeft = 0f
                barRight = barHeight

                peakLeft = (peakHeightVal - peakHeight).coerceAtLeast(0f)
                peakRight = peakHeightVal
            } else {
                // Drawing from Right -> Left
                barLeft = width - barHeight
                barRight = width.toFloat()

                peakLeft = width - peakHeightVal
                peakRight = (width - peakHeightVal + peakHeight).coerceAtMost(width.toFloat())
            }

            // Draw main bar
            canvas.drawRect(barLeft, top, barRight, bottom, paint)
            // Draw peak
            canvas.drawRect(peakLeft, top, peakRight, bottom, peakPaint)
        }
    }
}
