package com.vera.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Clean, flat vector icon representing multi-channel telemetry time-series plotting.
 */
@Composable
fun TelemetryGraphIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f

        // Axes (bottom & left border)
        drawLine(
            color = tint.copy(alpha = 0.4f),
            start = Offset(w * 0.10f, h * 0.10f),
            end = Offset(w * 0.10f, h * 0.88f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint.copy(alpha = 0.4f),
            start = Offset(w * 0.10f, h * 0.88f),
            end = Offset(w * 0.90f, h * 0.88f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )

        // Telemetry line wave
        val wavePath = Path().apply {
            moveTo(w * 0.12f, h * 0.72f)
            lineTo(w * 0.32f, h * 0.60f)
            lineTo(w * 0.48f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.52f)
            lineTo(w * 0.88f, h * 0.32f)
        }
        drawPath(
            path = wavePath,
            color = tint,
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Peak telemetry data node
        drawCircle(
            color = tint,
            radius = w * 0.08f,
            center = Offset(w * 0.48f, h * 0.28f)
        )
    }
}
