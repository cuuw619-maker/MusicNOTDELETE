package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Material 3 Expressive Ambient Geometric Figures Overlay (strictly >= 5 vertices/lobes):
 * - Zero recompositions per frame: uses `rememberInfiniteTransition` `State<Float>` read ONLY inside `Canvas`.
 * - Zero Espresso/Robolectric idling hangs (no `while (true) withFrameNanos` loops).
 * - Seamless integer-harmonic orbits, soft beat pulsation, & rotations across 7 layered figures:
 *   Pentagon (5), Hexagon (6), 5-Point Star, 6-Point Star, Heptagon (7), 8-Point Rosette Star, 10-Point Sunburst.
 */
@Composable
fun AmbientGeometricShapesOverlay(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    reducedMotion: Boolean = false,
    alphaMultiplier: Float = 1.0f
) {
    val profile = LocalPerformanceProfile.current
    val shapeCount = when {
        profile.isPotato -> 4
        profile.enableShaders -> 7
        else -> 6
    }

    // Integer-harmonic [0..2*PI] cycle so all 1x/2x/3x rotations & orbits wrap seamlessly without snapping
    val cycleState = if (!reducedMotion) {
        val transition = rememberInfiniteTransition(label = "ambient_geo_cycle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when {
                        profile.isPotato -> 32_000
                        isPlaying -> 18_000
                        else -> 28_000
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "geo_phase"
        )
    } else {
        null
    }

    val path = remember { Path() }
    val innerPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val minDim = min(w, h)
        if (w <= 8f || h <= 8f) return@Canvas

        val phase = cycleState?.value ?: 0.85f
        val mult = alphaMultiplier.coerceIn(0.25f, 1.6f)

        // Strictly >= 5 corner shapes from MorphShapeLut:
        // 0=Pentagon(5), 1=Hexagon(6), 2=5-Point Star, 3=6-Point Star, 4=Heptagon(7), 8=8-Point Rosette, 9=10-Point Sunburst
        val lutIndices = intArrayOf(0, 2, 1, 3, 4, 8, 9)
        val anchorXs = floatArrayOf(0.14f, 0.86f, 0.82f, 0.16f, 0.50f, 0.64f, 0.27f)
        val anchorYs = floatArrayOf(0.15f, 0.20f, 0.74f, 0.79f, 0.45f, 0.12f, 0.52f)
        val sizeScales = floatArrayOf(0.180f, 0.165f, 0.195f, 0.168f, 0.130f, 0.135f, 0.128f)
        // Strictly integer rotation & orbit harmonics over [0..2*PI] -> 100% seamless loop
        val rotHarmonics = floatArrayOf(1f, -2f, 1f, -1f, 2f, -2f, 1f)
        val orbitHarmonics = floatArrayOf(1f, 2f, 1f, 2f, 3f, 1f, 2f)
        val driftRadii = floatArrayOf(0.052f, 0.062f, 0.048f, 0.056f, 0.042f, 0.046f, 0.045f)

        val baseFillAlpha = (when {
            profile.enableShaders && isPlaying -> 0.125f
            isPlaying -> 0.100f
            else -> 0.072f
        } * mult).coerceIn(0.02f, 0.24f)

        val baseStrokeAlpha = (when {
            profile.enableShaders && isPlaying -> 0.28f
            isPlaying -> 0.22f
            else -> 0.16f
        } * mult).coerceIn(0.04f, 0.45f)

        for (sIdx in 0 until shapeCount) {
            val lutId = lutIndices[sIdx]
            val radii = MorphShapeLut.radii[lutId]
            val drift = minDim * driftRadii[sIdx]
            val oAngle = phase * orbitHarmonics[sIdx] + sIdx * 1.15f
            val cx = w * anchorXs[sIdx] + cos(oAngle) * drift
            val cy = h * anchorYs[sIdx] + sin(oAngle + sIdx * 0.75f) * drift
            // Soft harmonic pulsation (more lively while playing, gentle idle breath when paused)
            val pulseAmp = if (isPlaying) 0.095f else 0.032f
            val pulseWave = sin(phase * if (isPlaying) 4f else 2f + sIdx * 0.9f)
            val breathe = if (!reducedMotion) 1f + pulseAmp * pulseWave else 1f
            val radius = minDim * sizeScales[sIdx] * breathe
            val rotRad = phase * rotHarmonics[sIdx] + sIdx * 0.65f
            val cosR = cos(rotRad)
            val sinR = sin(rotRad)

            path.reset()
            innerPath.reset()
            var first = true
            var step = 0
            while (step < MorphShapeLut.STEPS) {
                val r = radius * radii[step]
                val c0 = MorphShapeLut.cosTable[step]
                val s0 = MorphShapeLut.sinTable[step]
                val rx = c0 * cosR - s0 * sinR
                val ry = s0 * cosR + c0 * sinR
                val x = cx + r * rx
                val y = cy + r * ry
                val ix = cx + r * 0.46f * rx
                val iy = cy + r * 0.46f * ry
                if (first) {
                    path.moveTo(x, y)
                    innerPath.moveTo(ix, iy)
                    first = false
                } else {
                    path.lineTo(x, y)
                    innerPath.lineTo(ix, iy)
                }
                step += 2
            }
            path.close()
            innerPath.close()

            val shapeColor = when (sIdx % 3) {
                0 -> primaryColor
                1 -> tertiaryColor
                else -> secondaryColor
            }

            // Soft outer glow halo circle for depth (zero Shader/Brush allocation)
            if (!profile.isPotato) {
                drawCircle(
                    color = shapeColor.copy(alpha = (baseFillAlpha * 0.44f).coerceAtMost(0.09f)),
                    radius = radius * 1.24f,
                    center = Offset(cx, cy)
                )
            }

            // Main 5+ vertex polygon / star fill
            drawPath(path = path, color = shapeColor.copy(alpha = baseFillAlpha), style = Fill)

            // Crisp tonal rim + inner geometric polygon ring
            if (!profile.isPotato) {
                drawPath(
                    path = path,
                    color = shapeColor.copy(alpha = baseStrokeAlpha),
                    style = Stroke(width = 1.4.dp.toPx())
                )
                if (sIdx % 2 == 0) {
                    drawPath(
                        path = innerPath,
                        color = shapeColor.copy(alpha = (baseStrokeAlpha * 0.68f).coerceAtMost(0.30f)),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Safe Ambient Glow Backdrop + Multi-Shape Geometric Orbit Layer.
 * Uses pure Skia solid-color washes and M3 geometric polygons with ZERO RuntimeShader/AGSL compilation.
 */
@Composable
fun AmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondary,
    showFloatingGeometry: Boolean = true,
    reducedMotion: Boolean = false,
    baseIntensity: Float = if (isPlaying) 0.64f else 0.28f
) {
    val profile = LocalPerformanceProfile.current

    Box(modifier = modifier.fillMaxSize()) {
        if (profile.isPotato) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val intensity = (baseIntensity * 0.36f).coerceIn(0.06f, 0.26f)
                        val radial = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = intensity),
                                secondaryColor.copy(alpha = intensity * 0.40f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.32f),
                            radius = (size.width * 0.72f).coerceAtLeast(120f)
                        )
                        onDrawBehind {
                            drawRect(brush = radial)
                        }
                    }
            )
        } else {
            val pulseState = if (!reducedMotion) {
                val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow_cycle")
                infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2f * PI.toFloat()),
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 12_000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulse_phase"
                )
            } else {
                null
            }

            val intensity = (baseIntensity * 0.34f).coerceIn(0.06f, 0.26f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 4f || h <= 4f) return@Canvas
                val activePhase = pulseState?.value ?: 0.6f
                val pulse = sin(activePhase) * 0.06f + 1.0f

                drawCircle(
                    color = primaryColor.copy(alpha = intensity * 0.55f),
                    center = Offset(w * 0.36f + sin(activePhase) * 20f, h * 0.30f),
                    radius = (w * 0.48f * pulse).coerceAtLeast(80f)
                )
                drawCircle(
                    color = secondaryColor.copy(alpha = intensity * 0.45f),
                    center = Offset(w * 0.64f - cos(activePhase) * 18f, h * 0.48f),
                    radius = (w * 0.46f * (2f - pulse)).coerceAtLeast(80f)
                )
            }
        }

        if (showFloatingGeometry) {
            AmbientGeometricShapesOverlay(
                isPlaying = isPlaying,
                primaryColor = primaryColor,
                secondaryColor = accentColor,
                tertiaryColor = secondaryColor,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
