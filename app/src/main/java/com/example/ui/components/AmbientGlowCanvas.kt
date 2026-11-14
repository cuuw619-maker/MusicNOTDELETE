package com.example.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.example.ui.theme.LocalPerformanceProfile
import kotlin.math.cos
import kotlin.math.sin

private const val AMBIENT_MESH_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float4 uColorA;
uniform float4 uColorB;
uniform float4 uColorC;
uniform float uIntensity;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uResolution, float2(1.0));
    float t = uTime;

    float2 p1 = float2(0.32 + 0.14 * sin(t * 0.7), 0.28 + 0.10 * cos(t * 0.5));
    float2 p2 = float2(0.70 + 0.12 * cos(t * 0.6), 0.44 + 0.12 * sin(t * 0.8));
    float2 p3 = float2(0.45 + 0.16 * sin(t * 0.4 + 1.2), 0.78 + 0.10 * cos(t * 0.55));

    float d1 = exp(-dot(uv - p1, uv - p1) * 5.2);
    float d2 = exp(-dot(uv - p2, uv - p2) * 4.4);
    float d3 = exp(-dot(uv - p3, uv - p3) * 3.8);

    float4 accum = uColorA * (d1 * 0.85) + uColorB * (d2 * 0.75) + uColorC * (d3 * 0.65);
    float alpha = clamp((d1 * 0.50 + d2 * 0.42 + d3 * 0.35) * uIntensity, 0.0, 0.92);
    return half4(accum.rgb * alpha, alpha);
}
"""

/**
 * Dual-tier Dynamic Ambient Glow Backdrop:
 * - On Flagship Android 13+ (`profile.enableShaders == true`): runs real-time AGSL RuntimeShader flowing plasma mesh.
 * - On Balanced / Potato phones: zero-allocation `drawWithCache` radial Canvas (static soft gradient on Potato).
 */
@Composable
fun AmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondary
) {
    val profile = LocalPerformanceProfile.current

    if (profile.enableShaders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslAmbientGlowCanvas(
            isPlaying = isPlaying,
            modifier = modifier,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            accentColor = accentColor
        )
        return
    }

    if (profile.isPotato) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawWithCache {
                    val intensity = if (isPlaying) 0.26f else 0.12f
                    val radial = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = intensity),
                            secondaryColor.copy(alpha = intensity * 0.45f),
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
        return
    }

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

    Canvas(modifier = modifier.fillMaxSize()) {
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AgslAmbientGlowCanvas(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    primaryColor: Color,
    secondaryColor: Color,
    accentColor: Color,
    baseIntensity: Float = if (isPlaying) 0.62f else 0.28f
) {
    val shader = remember { RuntimeShader(AMBIENT_MESH_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val transition = rememberInfiniteTransition(label = "agsl_mesh_flow")
    val uTime by transition.animateFloat(
        initialValue = 0f,
        targetValue = 62.8318f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 32000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agsl_time"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("uResolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        shader.setFloatUniform("uTime", if (isPlaying) uTime else 0.8f)
        shader.setFloatUniform("uColorA", primaryColor.red, primaryColor.green, primaryColor.blue, 1f)
        shader.setFloatUniform("uColorB", secondaryColor.red, secondaryColor.green, secondaryColor.blue, 1f)
        shader.setFloatUniform("uColorC", accentColor.red, accentColor.green, accentColor.blue, 1f)
        shader.setFloatUniform("uIntensity", baseIntensity)
        drawRect(brush = brush)
    }
}
