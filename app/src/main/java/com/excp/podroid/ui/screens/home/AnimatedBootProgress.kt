package com.excp.podroid.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Indeterminate boot indicator for the Home screen. Sized to fill the parent
 * Box (caller controls the dimension) — the previous version hard-coded a
 * 120 dp Canvas inside a 72 dp parent, which clipped the ring.
 */
@Composable
fun AnimatedBootProgress(bootStage: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "boot")

    val pulseScale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    val isReady = bootStage == "Ready"
    val primary = MaterialTheme.colorScheme.primary
    val track   = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = (size.minDimension * 0.07f).coerceAtLeast(4f)
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            drawCircle(
                color = track,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = primary,
                startAngle = if (isReady) 0f else rotationAngle,
                sweepAngle = if (isReady) 360f else 270f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Text(
            text = if (isReady) "✓" else "P",
            color = primary,
            fontSize = if (isReady) 32.sp else (28 * pulseScale).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
