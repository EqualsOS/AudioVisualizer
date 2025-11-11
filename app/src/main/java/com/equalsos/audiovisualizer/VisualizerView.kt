package com.equalsos.audiovisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class VisualizerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var fftData: ByteArray? = null
    private val paint = Paint()
    private val numBars = 32 // Number of bars to draw

    init {
        paint.color = 0xFFFFFFFF.toInt() // White color
        paint.style = Paint.Style.FILL
    }

    /**
     * Updates the FFT data to be rendered.
     * @param bytes The raw FFT data from the Visualizer class.
     */
    fun updateVisualizer(bytes: ByteArray) {
        fftData = bytes
        invalidate() // Request a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (fftData == null || width == 0 || height == 0) {
            return
        }

        val data = fftData ?: return

        val barWidth = width.toFloat() / numBars
        val barPadding = barWidth * 0.1f // 10% padding between bars
        val effectiveBarWidth = barWidth - barPadding

        // We only use the first half of the FFT data (the second half is a mirror image)
        // And we skip the first element (DC offset)
        val dataPointsToUse = data.size / 2 - 1

        for (i in 0 until numBars) {
            val left = i * barWidth + (barPadding / 2)
            val right = left + effectiveBarWidth

            // Map the bar index to the FFT data index
            val dataIndex = (i.toFloat() / numBars * dataPointsToUse).toInt() * 2

            // Get the magnitude of the frequency bin
            val real = data[dataIndex].toInt()
            val imaginary = data[dataIndex + 1].toInt()
            val magnitude = (real * real + imaginary * imaginary).toFloat()

            // Calculate bar height (scaling it down and adding a minimum)
            // The max magnitude is around 32640, but we scale it empirically
            val barHeight = (magnitude / 150.0f).coerceAtMost(height.toFloat())

            val top = height - barHeight - 1 // -1 to ensure it's visible even at 0
            val bottom = height.toFloat()

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}