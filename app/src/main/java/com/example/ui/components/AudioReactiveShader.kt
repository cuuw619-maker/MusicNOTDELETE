package com.example.ui.components

import android.os.Build
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class AudioEnergySnapshot(
    val subBass: Float,
    val midVocal: Float,
    val highAir: Float,
    val beatPulse: Float
) {
    val compositeEnergy: Float
        get() = (subBass * 0.48f + midVocal * 0.34f + highAir * 0.18f).coerceIn(0.08f, 1.0f)
}

object AudioEnergyEngine {
    fun sample(
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        songId: Long,
        shimmerPhase: Float = 0.5f
    ): AudioEnergySnapshot {
        if (!isPlaying) {
            return AudioEnergySnapshot(
                subBass = 0.10f,
                midVocal = 0.08f,
                highAir = 0.06f,
                beatPulse = 0.05f
            )
        }
        val safePos = positionMs.coerceAtLeast(0L)
        val songSeed = (songId xor 0x5DEECE66DL).toInt()
        val tempoBpm = 108 + (abs(songSeed) % 44)
        val beatDurationMs = (60_000.0 / tempoBpm).toFloat().coerceAtLeast(300f)
        val beatPhase = if (safePos > 0L) {
            ((safePos % beatDurationMs.toLong()) / beatDurationMs).coerceIn(0f, 1f)
        } else {
            shimmerPhase.coerceIn(0f, 1f)
        }
        val kick = (1f - beatPhase).let { it * it * it }
        val progress = if (durationMs > 0L) (safePos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0.5f
        val sectionWeight = when {
            progress < 0.07f -> 0.58f
            progress in 0.24f..0.46f -> 0.96f
            progress in 0.54f..0.78f -> 1.0f
            progress > 0.92f -> 0.52f
            else -> 0.78f
        }
        val subBass = (0.24f + kick * 0.68f * sectionWeight + abs(sin(safePos / 210f)) * 0.10f).coerceIn(0.12f, 1.0f)
        val midVocal = (0.20f + abs(sin(safePos / 135f + (songSeed % 7))) * 0.52f * sectionWeight).coerceIn(0.10f, 0.96f)
        val highAir = (0.16f + abs(sin(safePos / 78f + shimmerPhase * 3.1f)) * 0.48f * sectionWeight).coerceIn(0.08f, 0.92f)
        val beatPulse = (kick * 0.75f + subBass * 0.25f).coerceIn(0.05f, 1.0f)

        return AudioEnergySnapshot(
            subBass = subBass,
            midVocal = midVocal,
            highAir = highAir,
            beatPulse = beatPulse
        )
    }
}

object ShaderSafetyGuard {
    private val runtimeTripped = AtomicBoolean(false)

    fun isEmulatedOrSoftwareGpu(): Boolean {
        val hw = Build.HARDWARE.lowercase()
        val fp = Build.FINGERPRINT.lowercase()
        val prod = Build.PRODUCT.lowercase()
        val model = Build.MODEL.lowercase()
        return hw.contains("ranchu") ||
            hw.contains("goldfish") ||
            hw.contains("vbox") ||
            hw.contains("cutf") ||
            fp.contains("generic") ||
            fp.contains("emulator") ||
            prod.contains("sdk_gphone") ||
            prod.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk built for")
    }

    fun isAgslAllowed(
        sdkInt: Int = Build.VERSION.SDK_INT,
        profileEnableShaders: Boolean,
        userShadersEnabled: Boolean,
        reducedMotion: Boolean,
        isEmulatedDevice: Boolean = isEmulatedOrSoftwareGpu()
    ): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            profileEnableShaders &&
            userShadersEnabled &&
            !reducedMotion &&
            !isEmulatedDevice &&
            !runtimeTripped.get()
    }

    fun markAgslFailure() {
        runtimeTripped.set(true)
    }

    fun isRuntimeTripped(): Boolean = runtimeTripped.get()

    internal fun resetForTesting() {
        runtimeTripped.set(false)
    }
}

/**
 * Zero-stall Hardware GPU Shader Aurora & Harmonic Beat Halo.
 * Uses Skia's native hardware RadialGradient & SweepGradient GPU shaders (pre-compiled in Skia)
 * instead of RuntimeShader pipeline compilation, ensuring 0ms freeze when opening the player
 * on all real devices and streaming/emulated GPUs while delivering vibrant audio-reactive motion.
 */
@Composable
fun AudioReactiveShaderLayer(
    isPlaying: Boolean,
    songId: Long,
    durationMs: Long,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
    shadersEnabled: Boolean = true,
    reducedMotion: Boolean = false,
    isPaused: Boolean = false,
    touchOffsetProvider: () -> Offset = { Offset.Zero },
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    accentColor: Color = MaterialTheme.colorScheme.secondary,
    intensity: Float = if (isPlaying) 0.72f else 0.26f
) {
    if (!shadersEnabled) return
    val profile = LocalPerformanceProfile.current

    val revealAlphaState = animateFloatAsState(
        targetValue = if (isPaused) 0.15f else 1f,
        animationSpec = tween(durationMillis = 280),
        label = "shader_reveal_alpha"
    )

    if (profile.isPotato || reducedMotion) {
        val alpha = (intensity * 0.24f).coerceIn(0.04f, 0.26f)
        val radial = remember(primaryColor, secondaryColor, alpha) {
            Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = alpha),
                    secondaryColor.copy(alpha = alpha * 0.45f),
                    Color.Transparent
                )
            )
        }
        Box(modifier = modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = radial, alpha = revealAlphaState.value)
            }
        }
        return
    }

    val phaseState = if (isPlaying && !isPaused) {
        val transition = rememberInfiniteTransition(label = "gpu_aurora_phase")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 8800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "aurora_phase"
        )
    } else {
        null
    }

    val sweepBrush = remember(primaryColor, secondaryColor, accentColor) {
        Brush.sweepGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.36f),
                secondaryColor.copy(alpha = 0.28f),
                accentColor.copy(alpha = 0.16f),
                primaryColor.copy(alpha = 0.36f)
            )
        )
    }

    val baseAlpha = (intensity * 0.42f).coerceIn(0.08f, 0.46f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val reveal = revealAlphaState.value
        val phase = phaseState?.value ?: 0.65f
        val touchOffset = touchOffsetProvider()
        val snap = AudioEnergyEngine.sample(
            isPlaying = isPlaying,
            positionMs = positionProvider(),
            durationMs = durationMs,
            songId = songId,
            shimmerPhase = phase / (2f * PI.toFloat())
        )

        val focusX = (touchOffset.x * w * 0.09f)

        // Lobe 1: sub-bass reactive warm radial shader
        val r1 = (size.minDimension * (0.56f + 0.14f * snap.beatPulse)).coerceAtLeast(40f)
        val c1 = Offset(w * (0.30f + 0.10f * sin(phase)) + focusX, h * (0.26f + 0.08f * cos(phase)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = (baseAlpha * (0.72f + 0.55f * snap.subBass) * reveal).coerceIn(0.04f, 0.48f)),
                    primaryColor.copy(alpha = (baseAlpha * 0.24f * reveal).coerceIn(0.01f, 0.18f)),
                    Color.Transparent
                ),
                center = c1,
                radius = r1
            ),
            center = c1,
            radius = r1
        )

        // Lobe 2: mid-vocal reactive contrasting radial shader
        val r2 = (size.minDimension * (0.52f + 0.11f * snap.midVocal)).coerceAtLeast(40f)
        val c2 = Offset(w * (0.72f - 0.10f * cos(phase)) - focusX * 0.7f, h * (0.54f - 0.08f * sin(phase)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = (baseAlpha * (0.68f + 0.48f * snap.midVocal) * reveal).coerceIn(0.04f, 0.44f)),
                    secondaryColor.copy(alpha = (baseAlpha * 0.20f * reveal).coerceIn(0.01f, 0.16f)),
                    Color.Transparent
                ),
                center = c2,
                radius = r2
            ),
            center = c2,
            radius = r2
        )

        // Lobe 3: rotating sweep-shader beat halo ring centered behind the artwork
        val haloCenter = Offset(w * 0.5f + focusX * 0.65f, h * 0.34f)
        val haloRadius = (size.minDimension * (0.35f + 0.055f * snap.beatPulse)).coerceAtLeast(36f)
        val angleDeg = (phase * (180f / PI.toFloat()))
        rotate(degrees = angleDeg, pivot = haloCenter) {
            drawCircle(
                brush = sweepBrush,
                radius = haloRadius,
                center = haloCenter,
                alpha = (0.35f + 0.55f * snap.beatPulse) * reveal,
                style = Stroke(width = (4.dp.toPx() + 5.dp.toPx() * snap.beatPulse))
            )
        }
    }
}

/**
 * Ultra-fast hardware gradient reactive aura for the collapsed Mini-Player capsule.
 */
@Composable
fun MiniPlayerReactiveAura(
    isPlaying: Boolean,
    songId: Long,
    durationMs: Long,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary
) {
    val profile = LocalPerformanceProfile.current
    val phaseState = if (isPlaying && !reducedMotion && !profile.isPotato) {
        val transition = rememberInfiniteTransition(label = "mini_aura_transition")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 7200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "mini_aura_phase"
        )
    } else {
        null
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas
        val phase = phaseState?.value ?: 0.5f
        val snap = AudioEnergyEngine.sample(
            isPlaying = isPlaying,
            positionMs = positionProvider(),
            durationMs = durationMs,
            songId = songId,
            shimmerPhase = phase / (2f * PI.toFloat())
        )
        val cx = w * (0.28f + 0.14f * sin(phase))
        val radius = (w * 0.52f).coerceAtLeast(48f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = (0.14f + 0.14f * snap.beatPulse).coerceIn(0.06f, 0.30f)),
                    secondaryColor.copy(alpha = (0.06f + 0.08f * snap.midVocal).coerceIn(0.02f, 0.18f)),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.5f),
                radius = radius
            ),
            center = Offset(cx, h * 0.5f),
            radius = radius
        )
    }
}
