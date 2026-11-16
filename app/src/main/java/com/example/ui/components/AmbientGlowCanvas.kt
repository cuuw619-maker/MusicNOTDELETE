package com.example.ui.components

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Multi-harmonic iridescent aurora & silk caustic AGSL background shader (Android 13+ Flagship).
 * Strictly uses integer-harmonic frequencies (1x, 2x, 3x) on `uPhase in [0, 2*PI]` so every loop wraps
 * with C1 continuity and zero seam jumps. Drawn safely onto `nativeCanvas` without Compose ShaderBrush cache races.
 */
private const val AMBIENT_MESH_AGSL = """
uniform float2 uResolution;
uniform float uPhase;
uniform float4 uColorA;
uniform float4 uColorB;
uniform float4 uColorC;
uniform float uIntensity;
uniform float uEnergy;

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(2.0));
    float2 uv = fragCoord / res;
    float t = uPhase;
    float e = clamp(uEnergy, 0.25, 1.45);

    // Pure integer harmonics over [0, 2*PI] -> 100% seamless loop
    float2 warp1 = float2(
        0.072 * sin(uv.y * 4.4 + t) + 0.032 * cos(uv.x * 7.2 - 2.0 * t),
        0.072 * cos(uv.x * 4.0 - t) + 0.032 * sin(uv.y * 6.6 + 2.0 * t)
    ) * e;
    float2 wuv = uv + warp1;
    float2 wuv2 = wuv + float2(
        0.038 * sin((wuv.x - wuv.y) * 5.8 + 2.0 * t),
        0.038 * cos((wuv.x + wuv.y) * 5.2 - t)
    );

    float2 p1 = float2(0.28 + 0.17 * sin(t), 0.25 + 0.15 * cos(t));
    float2 p2 = float2(0.75 + 0.15 * cos(t + 1.0), 0.43 + 0.15 * sin(2.0 * t));
    float2 p3 = float2(0.45 + 0.18 * sin(t + 2.1), 0.78 + 0.13 * cos(t + 0.8));
    float2 p4 = float2(0.50 + 0.13 * cos(2.0 * t), 0.22 + 0.13 * sin(t + 3.1));

    // Soft floating bokeh orb centers
    float2 b1 = float2(0.18 + 0.12 * cos(2.0 * t), 0.62 + 0.16 * sin(t + 0.5));
    float2 b2 = float2(0.82 + 0.10 * sin(t + 1.7), 0.20 + 0.12 * cos(2.0 * t));

    float d1 = exp(-dot(wuv2 - p1, wuv2 - p1) * 3.8);
    float d2 = exp(-dot(wuv2 - p2, wuv2 - p2) * 3.4);
    float d3 = exp(-dot(wuv2 - p3, wuv2 - p3) * 3.1);
    float d4 = exp(-dot(wuv2 - p4, wuv2 - p4) * 4.2);

    float bokeh1 = smoothstep(0.24, 0.02, length(uv - b1)) * 0.26 * e;
    float bokeh2 = smoothstep(0.20, 0.02, length(uv - b2)) * 0.22 * e;

    // Flowing aurora caustic ribbons + chromatic prism sheen
    float ribbonA = smoothstep(-0.28, 0.90, sin((wuv2.x * 3.8 - wuv2.y * 3.1) + t)) * 0.21 * e;
    float ribbonB = smoothstep(-0.18, 0.92, cos((wuv2.x * 2.6 + wuv2.y * 4.3) - 2.0 * t)) * 0.15 * e;
    float crest = pow(clamp(sin((wuv2.x * 5.2 + wuv2.y * 3.7) + 2.0 * t), 0.0, 1.0), 3.0) * 0.14 * e;

    float4 accum = uColorA * (d1 * 0.92 + d4 * 0.45) +
                   uColorB * (d2 * 0.85) +
                   uColorC * (d3 * 0.78 + d4 * 0.38);

    float shimmerMix = 0.5 + 0.5 * sin(t);
    accum.rgb += mix(uColorA.rgb, uColorC.rgb, shimmerMix) * ribbonA * (d1 + d2 + 0.40);
    accum.rgb += mix(uColorB.rgb, uColorA.rgb, 1.0 - shimmerMix) * ribbonB * (d2 + d3 + 0.34);
    accum.rgb += mix(uColorC.rgb, float3(1.0), 0.35) * (crest + bokeh1 + bokeh2);

    float alpha = clamp((d1 * 0.48 + d2 * 0.42 + d3 * 0.38 + d4 * 0.24 + (ribbonA + ribbonB + crest) * 0.36 + (bokeh1 + bokeh2) * 0.45) * uIntensity, 0.0, 0.94);
    return half4(accum.rgb * alpha, alpha);
}
"""

/**
 * Multi-shape M3 Expressive ambient background overlay:
 * Renders continuously rotating & drifting M3 organic shapes across all tabs and player screens
 * using monotonic frame-delta accumulation (`withFrameNanos`) so motion NEVER jumps or snaps on any loop boundary.
 */
@Composable
fun AmbientGeometricShapesOverlay(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    reducedMotion: Boolean = false
) {
    val profile = LocalPerformanceProfile.current
    val shapeCount = when {
        profile.isPotato -> 3
        profile.enableShaders -> 7
        else -> 5
    }

    // Monotonic continuous clock in seconds — zero periodic boundary reset
    var elapsedSec by remember { mutableFloatStateOf(0.85f) }
    LaunchedEffect(reducedMotion, profile.isPotato, isPlaying) {
        if (reducedMotion) return@LaunchedEffect
        var lastNs = 0L
        val speedMultiplier = when {
            profile.isPotato -> 0.52f
            isPlaying -> 1.15f
            else -> 0.72f
        }
        while (true) {
            withFrameNanos { frameNs ->
                if (lastNs != 0L) {
                    val dtSec = ((frameNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    elapsedSec += dtSec * speedMultiplier
                }
                lastNs = frameNs
            }
        }
    }

    val path = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val minDim = min(w, h)
        if (w <= 8f || h <= 8f) return@Canvas

        // Shape LUT IDs: 2=Clover, 3=Cookie, 4=Spark, 5=Flower, 7=Shield, 8=Diamond, 9=Sunburst
        val lutIndices = intArrayOf(2, 4, 3, 5, 8, 9, 7)
        val anchorXs = floatArrayOf(0.14f, 0.86f, 0.80f, 0.18f, 0.52f, 0.58f, 0.28f)
        val anchorYs = floatArrayOf(0.16f, 0.22f, 0.72f, 0.78f, 0.46f, 0.13f, 0.50f)
        val sizeScales = floatArrayOf(0.19f, 0.15f, 0.21f, 0.16f, 0.13f, 0.12f, 0.14f)
        val rotRadPerSec = floatArrayOf(0.36f, -0.48f, 0.30f, -0.40f, 0.44f, -0.34f, 0.28f)
        val orbitRadPerSec = floatArrayOf(0.42f, 0.34f, 0.38f, 0.31f, 0.46f, 0.36f, 0.29f)
        val driftRadii = floatArrayOf(0.06f, 0.07f, 0.055f, 0.065f, 0.05f, 0.055f, 0.048f)

        for (sIdx in 0 until shapeCount) {
            val lutId = lutIndices[sIdx]
            val radii = MorphShapeLut.radii[lutId]
            val drift = minDim * driftRadii[sIdx]
            val oAngle = elapsedSec * orbitRadPerSec[sIdx] + sIdx * 1.3f
            val cx = w * anchorXs[sIdx] + cos(oAngle) * drift
            val cy = h * anchorYs[sIdx] + sin(oAngle * 0.88f + sIdx * 0.9f) * drift
            val radius = minDim * sizeScales[sIdx]
            val rotRad = elapsedSec * rotRadPerSec[sIdx] + sIdx * 0.7f
            val cosR = cos(rotRad)
            val sinR = sin(rotRad)

            path.reset()
            for (step in 0 until MorphShapeLut.STEPS) {
                val r = radius * radii[step]
                val c0 = MorphShapeLut.cosTable[step]
                val s0 = MorphShapeLut.sinTable[step]
                val x = cx + r * (c0 * cosR - s0 * sinR)
                val y = cy + r * (s0 * cosR + c0 * sinR)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            val shapeColor = when (sIdx % 3) {
                0 -> primaryColor
                1 -> tertiaryColor
                else -> secondaryColor
            }
            val fillAlpha = when {
                profile.enableShaders && isPlaying -> 0.125f
                isPlaying -> 0.105f
                else -> 0.080f
            }
            val strokeAlpha = when {
                profile.enableShaders && isPlaying -> 0.26f
                isPlaying -> 0.22f
                else -> 0.16f
            }

            drawPath(path = path, color = shapeColor.copy(alpha = fillAlpha), style = Fill)
            if (!profile.isPotato) {
                drawPath(
                    path = path,
                    color = shapeColor.copy(alpha = strokeAlpha),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }
    }
}

/**
 * Dual-tier Dynamic Ambient Glow Backdrop + Multi-Shape Geometric Orbit Layer:
 * - Flagship Android 13+ (`profile.enableShaders == true`): runs tri-harmonic aurora AGSL shader + floating shapes.
 * - Balanced: zero-alloc multi-radial Canvas glow + rotating shapes.
 * - Potato: ultra-light cached radial Canvas + minimal geometric shapes.
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
    val effectivePlaying = isPlaying && !reducedMotion

    Box(modifier = modifier.fillMaxSize()) {
        if (profile.enableShaders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslAmbientGlowCanvas(
                isPlaying = effectivePlaying,
                modifier = Modifier.fillMaxSize(),
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                accentColor = accentColor,
                baseIntensity = baseIntensity
            )
        } else if (profile.isPotato) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val intensity = (baseIntensity * 0.42f).coerceIn(0.08f, 0.30f)
                        val radial = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = intensity),
                                secondaryColor.copy(alpha = intensity * 0.42f),
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
            val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow_cycle")
            val pulsePhase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = (2f * PI.toFloat()),
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 7200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse_phase"
            )
            val activePhase = if (reducedMotion) 0.6f else pulsePhase

            val intensity = (baseIntensity * 0.52f).coerceIn(0.10f, 0.40f)
            val colors1 = remember(primaryColor, intensity) {
                listOf(
                    primaryColor.copy(alpha = intensity * 0.85f),
                    primaryColor.copy(alpha = intensity * 0.30f),
                    Color.Transparent
                )
            }
            val colors2 = remember(secondaryColor, accentColor, intensity) {
                listOf(
                    secondaryColor.copy(alpha = intensity * 0.68f),
                    accentColor.copy(alpha = intensity * 0.24f),
                    Color.Transparent
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val pulse = sin(activePhase) * 0.07f + 1.0f

                val center1 = Offset(w * 0.36f + sin(activePhase) * 26f, h * 0.30f)
                val radius1 = (w * 0.56f * pulse).coerceAtLeast(100f)
                drawCircle(
                    brush = Brush.radialGradient(colors = colors1, center = center1, radius = radius1),
                    center = center1,
                    radius = radius1
                )

                val center2 = Offset(w * 0.64f - cos(activePhase) * 22f, h * 0.46f)
                val radius2 = (w * 0.58f * (2f - pulse)).coerceAtLeast(100f)
                drawCircle(
                    brush = Brush.radialGradient(colors = colors2, center = center2, radius = radius2),
                    center = center2,
                    radius = radius2
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AgslAmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    baseIntensity: Float = if (isPlaying) 0.64f else 0.28f
) {
    val shader = remember { runCatching { RuntimeShader(AMBIENT_MESH_AGSL) }.getOrNull() }
    val paint = remember(shader) { shader?.let { Paint().apply { isAntiAlias = true; this.shader = it } } }

    if (shader == null || paint == null) {
        val fallbackRadial = remember(primaryColor, secondaryColor, baseIntensity) {
            Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = (baseIntensity * 0.45f).coerceIn(0.05f, 0.6f)),
                    secondaryColor.copy(alpha = (baseIntensity * 0.25f).coerceIn(0.03f, 0.4f)),
                    Color.Transparent
                )
            )
        }
        Box(modifier = modifier.fillMaxSize().drawWithCache {
            onDrawBehind { drawRect(brush = fallbackRadial) }
        })
        return
    }

    val energy by animateFloatAsState(
        targetValue = if (isPlaying) 1.08f else 0.48f,
        animationSpec = SpringTokens.effectsDefault(),
        label = "agsl_energy"
    )

    // Continuous integer-harmonic phase [0..2*PI]
    val transition = rememberInfiniteTransition(label = "agsl_mesh_flow")
    val uPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agsl_phase"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width <= 2f || size.height <= 2f) return@Canvas
        drawIntoCanvas { canvas ->
            if (!canvas.nativeCanvas.isHardwareAccelerated) {
                // Software canvas (e.g., certain fallback layers): draw clean radial fallback without RuntimeShader abort
                drawCircle(
                    color = primaryColor.copy(alpha = (baseIntensity * 0.22f).coerceIn(0.05f, 0.35f)),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.4f, size.height * 0.35f)
                )
                return@drawIntoCanvas
            }
            val ok = runCatching {
                shader.setFloatUniform("uResolution", size.width, size.height)
                shader.setFloatUniform("uPhase", if (isPlaying) uPhase else 0.8f)
                shader.setFloatUniform("uColorA", primaryColor.red, primaryColor.green, primaryColor.blue, 1f)
                shader.setFloatUniform("uColorB", secondaryColor.red, secondaryColor.green, secondaryColor.blue, 1f)
                shader.setFloatUniform("uColorC", accentColor.red, accentColor.green, accentColor.blue, 1f)
                shader.setFloatUniform("uIntensity", baseIntensity.coerceIn(0.05f, 1.0f))
                shader.setFloatUniform("uEnergy", energy)
                paint.shader = shader
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                true
            }.getOrDefault(false)
            if (!ok) {
                drawCircle(
                    color = primaryColor.copy(alpha = (baseIntensity * 0.22f).coerceIn(0.05f, 0.35f)),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.4f, size.height * 0.35f)
                )
            }
        }
    }
}
