package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile
import kotlin.math.abs
import kotlin.math.sin

/**
 * Dynamic Audio Spectral Visualizer.
 * Optimized for low-end devices (cached Brush, adaptive bar count, zero recomposition via [currentPositionProvider]).
 */
@Composable
fun AudioVisualizer(
    isPlaying: Boolean,
    currentPositionMs: Long = 0L,
    durationMs: Long = 0L,
    songId: Long = 0L,
    modifier: Modifier = Modifier,
    barCount: Int = 28,
    barHeight: Dp = 36.dp,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    currentPositionProvider: (() -> Long)? = null
) {
    val profile = LocalPerformanceProfile.current
    val effectiveBarCount = barCount.coerceAtMost(profile.visualizerBars).coerceAtLeast(8)

    val shimmer = if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "transient_shimmer")
        val animValue by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (profile.isPotato) 650 else 450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmer_anim"
        )
        animValue
    } else {
        0f
    }

    val barBrush = remember(primaryColor, secondaryColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryColor,
                secondaryColor.copy(alpha = 0.85f),
                primaryColor.copy(alpha = 0.35f)
            )
        )
    }
    val peakColor = remember(primaryColor) { primaryColor.copy(alpha = 0.90f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        val posMs = currentPositionProvider?.invoke() ?: currentPositionMs
        val width = size.width
        val height = size.height
        val totalSpacing = width * 0.32f
        val totalBarWidth = width - totalSpacing
        val singleBarWidth = totalBarWidth / effectiveBarCount
        val barGap = totalSpacing / (effectiveBarCount - 1).coerceAtLeast(1)

        val songSeed = (songId xor 0x5DEECE66DL).toInt()
        val tempoBpm = 110 + (abs(songSeed) % 40)
        val beatDurationMs = (60000.0 / tempoBpm).toFloat()

        val beatPhase = if (posMs > 0) {
            ((posMs % beatDurationMs.toLong()) / beatDurationMs)
        } else shimmer

        val kickEnergy = (1f - beatPhase).let { it * it * it }
        val songProgress = if (durationMs > 0) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0.5f
        val sectionIntensity = when {
            songProgress < 0.08f -> 0.45f
            songProgress in 0.25f..0.45f -> 0.95f
            songProgress in 0.55f..0.75f -> 1.0f
            songProgress > 0.92f -> 0.40f
            else -> 0.70f
        }

        val radius = CornerRadius(singleBarWidth / 2f, singleBarWidth / 2f)
        val peakCapOffset = 3.5.dp.toPx()
        val peakCapHeight = 2.dp.toPx()
        val peakRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())

        for (i in 0 until effectiveBarCount) {
            val freqFraction = i / effectiveBarCount.toFloat()

            val rawHeight = if (isPlaying) {
                when {
                    freqFraction < 0.25f -> {
                        val subBassHarmonic = abs(sin((posMs / 180f) + i)) * 0.35f
                        (0.20f + kickEnergy * 0.65f * sectionIntensity + subBassHarmonic).coerceIn(0.12f, 1.0f)
                    }
                    freqFraction in 0.25f..0.70f -> {
                        val vocalWave = abs(sin((posMs / 120f) * 1.5f + i * 0.8f)) * 0.45f
                        val secondaryBeat = abs(sin((posMs / (beatDurationMs / 2f)) + i)) * 0.25f
                        (0.18f + (vocalWave + secondaryBeat) * sectionIntensity).coerceIn(0.10f, 0.92f)
                    }
                    else -> {
                        val highTransient = abs(sin((posMs / 70f) + i * 2.3f)) * 0.35f
                        (0.15f + (highTransient + shimmer * 0.25f) * sectionIntensity).coerceIn(0.08f, 0.85f)
                    }
                }
            } else {
                0.06f
            }

            val currentBarHeight = (height * rawHeight).coerceAtLeast(3.5f)
            val left = i * (singleBarWidth + barGap)
            val top = height - currentBarHeight

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(left, top),
                size = Size(singleBarWidth, currentBarHeight),
                cornerRadius = radius
            )

            if (!profile.isPotato && isPlaying && rawHeight > 0.20f) {
                val peakTop = (top - peakCapOffset).coerceAtLeast(0f)
                drawRoundRect(
                    color = peakColor,
                    topLeft = Offset(left, peakTop),
                    size = Size(singleBarWidth, peakCapHeight),
                    cornerRadius = peakRadius
                )
            }
        }
    }
}
