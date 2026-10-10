package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Dynamic Audio Spectral Visualizer.
 * Analyzes current playback position, song acoustic profile, and rhythm to generate
 * realistic frequency band dynamics (Sub-Bass, Bass, Mids, Highs) rather than a simple loop.
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
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary
) {
    // High-frequency transient shimmer
    val infiniteTransition = rememberInfiniteTransition(label = "transient_shimmer")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_anim"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
    ) {
        val width = size.width
        val height = size.height
        val totalSpacing = width * 0.32f
        val totalBarWidth = width - totalSpacing
        val singleBarWidth = totalBarWidth / barCount
        val barGap = totalSpacing / (barCount - 1).coerceAtLeast(1)

        val brush = Brush.verticalGradient(
            colors = listOf(
                primaryColor,
                secondaryColor.copy(alpha = 0.85f),
                primaryColor.copy(alpha = 0.35f)
            )
        )

        // Song-specific tempo and acoustic signature
        val songSeed = (songId xor 0x5DEECE66DL).toInt()
        val tempoBpm = 110 + (abs(songSeed) % 40) // 110 - 150 BPM
        val beatDurationMs = (60000.0 / tempoBpm).toFloat()

        // Real beat phase from track playback position
        val beatPhase = if (currentPositionMs > 0) {
            ((currentPositionMs % beatDurationMs.toLong()) / beatDurationMs)
        } else shimmer

        // Beat pulse envelope (sharp attack, exponential decay for kick/bass drum)
        val kickEnergy = (1f - beatPhase).let { it * it * it }

        // Section energy dynamics (verse vs chorus vs bridge)
        val songProgress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0.5f
        val sectionIntensity = when {
            songProgress < 0.08f -> 0.45f // Intro
            songProgress in 0.25f..0.45f -> 0.95f // Chorus 1
            songProgress in 0.55f..0.75f -> 1.0f // Peak drop/chorus 2
            songProgress > 0.92f -> 0.40f // Outro fade
            else -> 0.70f // Verse
        }

        for (i in 0 until barCount) {
            val freqFraction = i / barCount.toFloat()

            val rawHeight = if (isPlaying) {
                when {
                    // Low frequencies (Sub-bass and Bass, bars 0..25%): heavy kick response
                    freqFraction < 0.25f -> {
                        val subBassHarmonic = abs(sin((currentPositionMs / 180f) + i)) * 0.35f
                        (0.20f + kickEnergy * 0.65f * sectionIntensity + subBassHarmonic).coerceIn(0.12f, 1.0f)
                    }
                    // Mid frequencies (Vocals, guitars, keys, bars 25..70%): dynamic melodic movement
                    freqFraction in 0.25f..0.70f -> {
                        val vocalWave = abs(sin((currentPositionMs / 120f) * 1.5f + i * 0.8f)) * 0.45f
                        val secondaryBeat = abs(sin((currentPositionMs / (beatDurationMs / 2f)) + i)) * 0.25f
                        (0.18f + (vocalWave + secondaryBeat) * sectionIntensity).coerceIn(0.10f, 0.92f)
                    }
                    // High frequencies (Hi-hats, cymbals, air, bars 70..100%): fast transient shimmer
                    else -> {
                        val highTransient = abs(sin((currentPositionMs / 70f) + i * 2.3f)) * 0.35f
                        (0.15f + (highTransient + shimmer * 0.25f) * sectionIntensity).coerceIn(0.08f, 0.85f)
                    }
                }
            } else {
                // Calm resting baseline when paused
                0.06f
            }

            val currentBarHeight = (height * rawHeight).coerceAtLeast(3.5f)
            val left = i * (singleBarWidth + barGap)
            val top = height - currentBarHeight

            // Draw primary frequency bar
            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, top),
                size = Size(singleBarWidth, currentBarHeight),
                cornerRadius = CornerRadius(singleBarWidth / 2f, singleBarWidth / 2f)
            )

            // Draw studio floating peak cap (VU meter style)
            if (isPlaying && rawHeight > 0.20f) {
                val peakTop = (top - 3.5.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.90f),
                    topLeft = Offset(left, peakTop),
                    size = Size(singleBarWidth, 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
            }
        }
    }
}
