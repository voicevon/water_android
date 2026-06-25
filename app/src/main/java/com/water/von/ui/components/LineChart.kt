package com.water.von.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlin.math.min

data class SensorDataPoint(
    val ch0: Int,
    val ch1: Int,
    val ch2: Int,
    val ch3: Int
)

@Composable
fun MultiLineChart(
    dataPoints: List<SensorDataPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        return
    }

    // Determine min and max for Y-axis scaling
    var minY = Int.MAX_VALUE
    var maxY = Int.MIN_VALUE

    for (point in dataPoints) {
        minY = min(minY, min(point.ch0, min(point.ch1, min(point.ch2, point.ch3))))
        maxY = max(maxY, max(point.ch0, max(point.ch1, max(point.ch2, point.ch3))))
    }

    // Add some padding to Y-axis
    val range = max(10, maxY - minY)
    val yMax = maxY + (range * 0.1f)
    val yMin = max(0f, minY - (range * 0.1f))
    val yRange = max(1f, yMax - yMin)

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val paddingStart = 100f
        val paddingEnd = 20f
        val paddingTop = 40f
        val paddingBottom = 60f

        val chartWidth = width - paddingStart - paddingEnd
        val chartHeight = height - paddingTop - paddingBottom

        // Draw grid lines and labels
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = android.graphics.Color.GRAY
                textSize = 30f
                typeface = Typeface.DEFAULT
            }
            val gridPaint = androidx.compose.ui.graphics.Paint().apply {
                color = Color.LightGray
                strokeWidth = 1f
            }

            val steps = 5
            for (i in 0..steps) {
                val yFraction = i / steps.toFloat()
                val yVal = yMin + yFraction * yRange
                val yPos = paddingTop + chartHeight - (yFraction * chartHeight)

                // Grid line
                canvas.drawLine(
                    Offset(paddingStart, yPos),
                    Offset(width - paddingEnd, yPos),
                    gridPaint
                )

                // Label
                canvas.nativeCanvas.drawText(
                    String.format("%.0f", yVal),
                    10f,
                    yPos + 10f,
                    paint
                )
            }
        }

        if (dataPoints.size < 2) return@Canvas

        val xStep = chartWidth / (dataPoints.size - 1).coerceAtLeast(1)

        val path0 = Path()
        val path1 = Path()
        val path2 = Path()
        val path3 = Path()

        dataPoints.forEachIndexed { index, point ->
            val x = paddingStart + index * xStep
            
            val y0 = paddingTop + chartHeight - ((point.ch0 - yMin) / yRange * chartHeight)
            val y1 = paddingTop + chartHeight - ((point.ch1 - yMin) / yRange * chartHeight)
            val y2 = paddingTop + chartHeight - ((point.ch2 - yMin) / yRange * chartHeight)
            val y3 = paddingTop + chartHeight - ((point.ch3 - yMin) / yRange * chartHeight)

            if (index == 0) {
                path0.moveTo(x, y0)
                path1.moveTo(x, y1)
                path2.moveTo(x, y2)
                path3.moveTo(x, y3)
            } else {
                path0.lineTo(x, y0)
                path1.lineTo(x, y1)
                path2.lineTo(x, y2)
                path3.lineTo(x, y3)
            }
        }

        drawPath(
            path = path0,
            color = Color(0xFFD4AF37), // Dark Yellow / Gold (Raw)
            style = Stroke(width = 3f)
        )
        drawPath(
            path = path1,
            color = Color(0xFF4CAF50), // Green (Filtered)
            style = Stroke(width = 3f)
        )
        drawPath(
            path = path2,
            color = Color(0xFF2196F3), // Blue (Baseline)
            style = Stroke(width = 3f)
        )
        drawPath(
            path = path3,
            color = Color.Red, // Red (Threshold)
            style = Stroke(width = 3f)
        )
    }
}
