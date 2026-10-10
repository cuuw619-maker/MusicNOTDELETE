package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin

/**
 * Dynamic Living Ambient Aura Canvas:
 * Renders a dreamy, soft, breathing ambient lighting gradient that gently radiates
 * behind the album artwork in Now Playing and MiniPlayer, reacting to playback dynamics.
 */
@Composable
fun AmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow_cycle")

    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val intensity = if (isPlaying) 0.35f else 0.12f
        val pulse = sin(pulsePhase) * 0.08f + 1.0f

        // Top-left ambient orb
        val center1 = Offset(w * 0.35f + sin(pulsePhase) * 30f, h * 0.30f)
        val radius1 = (w * 0.55f * pulse).coerceAtLeast(100f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = intensity * 0.85f),
                    primaryColor.copy(alpha = intensity * 0.35f),
                    Color.Transparent
                ),
                center = center1,
                radius = radius1
            ),
            center = center1,
            radius = radius1
        )

        // Bottom-right complementary ambient orb
        val center2 = Offset(w * 0.65f - sin(pulsePhase) * 25f, h * 0.45f)
        val radius2 = (w * 0.60f * (2f - pulse)).coerceAtLeast(100f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = intensity * 0.70f),
                    accentColor.copy(alpha = intensity * 0.25f),
                    Color.Transparent
                ),
                center = center2,
                radius = radius2
            ),
            center = center2,
            radius = radius2
        )
    }
}
