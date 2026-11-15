package com.example.ui.components

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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tri-harmonic iridescent fluid silk AGSL plasma shader for Android 13+ Flagship devices.
 * Modulates field turbulence, luminous aurora ribbons, and dynamic energy cleanly via `uEnergy`.
 */
private const val AMBIENT_MESH_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float4 uColorA;
uniform float4 uColorB;
uniform float4 uColorC;
uniform float uIntensity;
uniform float uEnergy;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uResolution, float2(1.0));
    float t = uTime;
    float e = clamp(uEnergy, 0.25, 1.35);

    // Multi-harmonic curl-like domain warp with low ALU cost
    float2 warp1 = float2(
        0.055 * sin(uv.y * 4.1 + t * 0.62) + 0.024 * cos(uv.x * 7.2 - t * 0.88),
        0.055 * cos(uv.x * 3.7 - t * 0.54) + 0.024 * sin(uv.y * 6.5 + t * 0.76)
    ) * e;
    float2 wuv = uv + warp1;
    float2 wuv2 = wuv + float2(
        0.025 * sin((wuv.x - wuv.y) * 5.6 + t * 0.95),
        0.025 * cos((wuv.x + wuv.y) * 5.2 - t * 0.82)
    );

    float2 p1 = float2(0.28 + 0.16 * sin(t * 0.58), 0.26 + 0.13 * cos(t * 0.44));
    float2 p2 = float2(0.74 + 0.13 * cos(t * 0.51), 0.43 + 0.14 * sin(t * 0.69));
    float2 p3 = float2(0.45 + 0.17 * sin(t * 0.39 + 1.5), 0.78 + 0.12 * cos(t * 0.49));

    float d1 = exp(-dot(wuv2 - p1, wuv2 - p1) * 4.2);
    float d2 = exp(-dot(wuv2 - p2, wuv2 - p2) * 3.8);
    float d3 = exp(-dot(wuv2 - p3, wuv2 - p3) * 3.3);

    // Soft silk aurora caustic ribbon
    float ribbon = smoothstep(-0.35, 0.85, sin((wuv2.x * 3.4 - wuv2.y * 2.8) + t * 0.92)) * 0.14 * e;

    float4 accum = uColorA * (d1 * 0.88) + uColorB * (d2 * 0.80) + uColorC * (d3 * 0.72);
    accum.rgb += mix(uColorA.rgb, uColorC.rgb, 0.5 + 0.5 * sin(t * 0.6)) * ribbon * (d1 + d2 + 0.35);

    float alpha = clamp((d1 * 0.50 + d2 * 0.44 + d3 * 0.38 + ribbon * 0.4) * uIntensity, 0.0, 0.92);
    return half4(accum.rgb * alpha, alpha);
}
"""

/**
 * Capsule / Rounded-rect optical M3 Expressive rim & subtle iridescent top-arc highlight AGSL shader.
 */
private const val CAPSULE_RIM_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float4 uPrimary;
uniform float4 uTertiary;
uniform float uCornerPx;

half4 main(float2 fragCoord) {
    float2 halfSize = max(uResolution * 0.5, float2(4.0));
    float2 p = fragCoord - halfSize;
    float maxR = max(min(halfSize.x, halfSize.y), 2.0);
    float r = clamp(uCornerPx, 2.0, maxR);
    float2 q = abs(p) - (halfSize - float2(r));
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;

    // Crisp anti-aliased optical rim + subtle specular bloom along top curvature
    float rimMask = smoothstep(-2.4, -0.35, dist) * (1.0 - smoothstep(-0.2, 0.95, dist));
    float angle = atan(p.y, p.x);
    float shimmer = 0.5 + 0.5 * sin(angle * 2.0 + uTime * 1.15);
    float secondarySweep = 0.5 + 0.5 * cos(angle * 3.0 - uTime * 0.85);

    float4 rimCol = mix(uPrimary, uTertiary, shimmer);
    float topHighlight = clamp(1.0 - (fragCoord.y / max(uResolution.y * 0.38, 1.0)), 0.0, 1.0) * 0.085;
    float interiorMask = 1.0 - smoothstep(-1.0, 0.5, dist);

    float alpha = clamp(rimMask * (0.42 + 0.16 * secondarySweep) + topHighlight * interiorMask, 0.0, 0.64);
    float3 rgb = min(rimCol.rgb * (rimMask * 0.56) + float3(1.0) * (topHighlight * interiorMask), float3(alpha));
    return half4(rgb, alpha);
}
"""

/**
 * Reusable M3 Expressive optical rim sheen modifier:
 * - Flagship Android 13+ (`profile.enableShaders == true`): animated AGSL optical rim/specular sheen.
 * - Balanced / Potato phones: crisp zero-shader tonal gradient border.
 */
@Composable
fun Modifier.agslGlassRim(
    cornerRadiusDp: Dp = 999.dp,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    tertiaryColor: Color = MaterialTheme.colorScheme.tertiary,
    enabled: Boolean = true
): Modifier {
    val profile = LocalPerformanceProfile.current
    if (!enabled) return this

    if (profile.enableShaders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { runCatching { RuntimeShader(CAPSULE_RIM_AGSL) }.getOrNull() }
        val brush = remember(shader) { shader?.let { runCatching { ShaderBrush(it) }.getOrNull() } }
        if (shader != null && brush != null) {
            val transition = rememberInfiniteTransition(label = "capsule_rim_time")
            val time by transition.animateFloat(
                initialValue = 0f,
                targetValue = 62.8318f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 28000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rim_t"
            )
            return this.drawWithCache {
                val cornerPx = if (cornerRadiusDp.value >= 900f) {
                    size.minDimension * 0.5f
                } else {
                    cornerRadiusDp.toPx()
                }
                onDrawWithContent {
                    drawContent()
                    if (size.width > 2f && size.height > 2f) {
                        val applied = runCatching {
                            shader.setFloatUniform("uResolution", size.width, size.height)
                            shader.setFloatUniform("uTime", time)
                            shader.setFloatUniform("uPrimary", primaryColor.red, primaryColor.green, primaryColor.blue, 1f)
                            shader.setFloatUniform("uTertiary", tertiaryColor.red, tertiaryColor.green, tertiaryColor.blue, 1f)
                            shader.setFloatUniform("uCornerPx", cornerPx)
                            true
                        }.getOrDefault(false)
                        if (applied) {
                            drawRoundRect(
                                brush = brush,
                                cornerRadius = CornerRadius(cornerPx, cornerPx)
                            )
                        }
                    }
                }
            }
        }
    }

    val fallbackShape = if (cornerRadiusDp.value >= 900f) CircleShape else RoundedCornerShape(cornerRadiusDp)
    return this.border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.26f),
                tertiaryColor.copy(alpha = 0.14f)
            )
        ),
        shape = fallbackShape
    )
}

/**
 * Multi-shape M3 Expressive ambient background overlay:
 * Renders multiple rotating & gently drifting M3 organic shapes (Clover, Cookie, Spark, Flower, Diamond)
 * across the interface backdrop so geometric figures animate smoothly behind the UI.
 * Zero per-frame Path/Array allocations; adapts shape count & motion cleanly for Potato devices.
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
        profile.enableShaders -> 6
        else -> 4
    }

    val orbitPhase = if (!reducedMotion) {
        val transition = rememberInfiniteTransition(label = "ambient_geom_orbit")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when {
                        profile.isPotato -> 22000
                        isPlaying -> 13000
                        else -> 17000
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbit_phase"
        ).value
    } else {
        0.85f
    }

    val path = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val minDim = min(w, h)
        if (w <= 8f || h <= 8f) return@Canvas

        // Shape LUT IDs: 2=Clover, 3=Cookie, 4=Spark, 5=Flower, 8=Slanted Diamond, 9=Sunburst
        val lutIndices = intArrayOf(2, 4, 3, 5, 8, 9)
        val anchorXs = floatArrayOf(0.14f, 0.86f, 0.80f, 0.18f, 0.52f, 0.56f)
        val anchorYs = floatArrayOf(0.15f, 0.22f, 0.72f, 0.78f, 0.46f, 0.14f)
        val sizeScales = floatArrayOf(0.20f, 0.16f, 0.22f, 0.17f, 0.14f, 0.12f)
        val rotSpeeds = floatArrayOf(1.0f, -1.6f, 0.85f, -1.2f, 1.45f, -1.0f)
        val driftRadii = floatArrayOf(0.06f, 0.07f, 0.055f, 0.065f, 0.05f, 0.055f)

        for (sIdx in 0 until shapeCount) {
            val lutId = lutIndices[sIdx]
            val radii = MorphShapeLut.radii[lutId]
            val drift = minDim * driftRadii[sIdx]
            val cx = w * anchorXs[sIdx] + cos(orbitPhase * (1f + sIdx * 0.2f) + sIdx * 1.3f) * drift
            val cy = h * anchorYs[sIdx] + sin(orbitPhase * (0.9f + sIdx * 0.15f) + sIdx * 2.1f) * drift
            val radius = minDim * sizeScales[sIdx]
            val rotRad = orbitPhase * rotSpeeds[sIdx] + sIdx * 0.7f
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
            val fillAlpha = if (isPlaying) 0.105f else 0.080f
            val strokeAlpha = if (isPlaying) 0.22f else 0.16f

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
 * - Flagship Android 13+ (`profile.enableShaders == true`): runs tri-harmonic AGSL shader + 5 rotating/drifting shapes.
 * - Balanced: zero-alloc radial Canvas glow + 3 rotating shapes.
 * - Potato: ultra-light cached radial Canvas + 2 static shapes with zero live animation overhead.
 */
@Composable
fun AmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondary,
    showFloatingGeometry: Boolean = true
) {
    val profile = LocalPerformanceProfile.current

    Box(modifier = modifier.fillMaxSize()) {
        if (profile.enableShaders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslAmbientGlowCanvas(
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                accentColor = accentColor
            )
        } else if (profile.isPotato) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val intensity = if (isPlaying) 0.24f else 0.11f
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
                targetValue = 6.28318f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 6800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse_phase"
            )

            val intensity = if (isPlaying) 0.34f else 0.12f
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
                val pulse = sin(pulsePhase) * 0.07f + 1.0f

                val center1 = Offset(w * 0.36f + sin(pulsePhase) * 26f, h * 0.30f)
                val radius1 = (w * 0.56f * pulse).coerceAtLeast(100f)
                drawCircle(
                    brush = Brush.radialGradient(colors = colors1, center = center1, radius = radius1),
                    center = center1,
                    radius = radius1
                )

                val center2 = Offset(w * 0.64f - cos(pulsePhase) * 22f, h * 0.46f)
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
    val brush = remember(shader) { shader?.let { runCatching { ShaderBrush(it) }.getOrNull() } }
    if (shader == null || brush == null) {
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
        targetValue = if (isPlaying) 1.05f else 0.45f,
        animationSpec = SpringTokens.effectsDefault(),
        label = "agsl_energy"
    )
    val transition = rememberInfiniteTransition(label = "agsl_mesh_flow")
    val uTime by transition.animateFloat(
        initialValue = 0f,
        targetValue = 62.8318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agsl_time"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val ok = runCatching {
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", if (isPlaying) uTime else 0.8f)
            shader.setFloatUniform("uColorA", primaryColor.red, primaryColor.green, primaryColor.blue, 1f)
            shader.setFloatUniform("uColorB", secondaryColor.red, secondaryColor.green, secondaryColor.blue, 1f)
            shader.setFloatUniform("uColorC", accentColor.red, accentColor.green, accentColor.blue, 1f)
            shader.setFloatUniform("uIntensity", baseIntensity)
            shader.setFloatUniform("uEnergy", energy)
            true
        }.getOrDefault(false)
        if (ok) {
            drawRect(brush = brush)
        }
    }
}
