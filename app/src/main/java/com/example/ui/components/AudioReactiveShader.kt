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
 * Zero-Allocation Audio-Reactive Beat Halo & Ambient Energy Layer.
 * Draws layered audio-reactive energy halos and harmonic beat rings driven by [AudioEnergyEngine]
 * with ZERO RuntimeShader compilation and ZERO per-frame Brush allocations, eliminating freezes
 * and GPU driver crashes on all devices while remaining responsive to music & touch gestures.
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
        val alpha = (intensity * 0.20f).coerceIn(0.04f, 0.22f)
        Canvas(modifier = modifier.fillMaxSize()) {
            val minDim = size.minDimension
            if (minDim <= 8f) return@Canvas
            drawCircle(
                color = primaryColor.copy(alpha = alpha * revealAlphaState.value),
                radius = minDim * 0.42f,
                center = Offset(size.width * 0.5f, size.height * 0.34f)
            )
        }
        return
    }

    val phaseState = if (isPlaying && !isPaused) {
        val transition = rememberInfiniteTransition(label = "gpu_aurora_phase")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 10_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "aurora_phase"
        )
    } else {
        null
    }

    val baseAlpha = (intensity * 0.32f).coerceIn(0.06f, 0.34f)
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

        val focusX = touchOffset.x * w * 0.08f
        val minDim = size.minDimension

        // Soft ambient energy washes (pure solid-color feathered circles, zero Brush alloc)
        val c1 = Offset(w * (0.30f + 0.08f * sin(phase)) + focusX, h * (0.26f + 0.06f * cos(phase)))
        val r1 = minDim * (0.44f + 0.08f * snap.subBass)
        drawCircle(
            color = primaryColor.copy(alpha = (baseAlpha * 0.42f * reveal).coerceIn(0.02f, 0.20f)),
            radius = r1,
            center = c1
        )
        drawCircle(
            color = primaryColor.copy(alpha = (baseAlpha * 0.28f * snap.beatPulse * reveal).coerceIn(0.02f, 0.16f)),
            radius = r1 * 0.64f,
            center = c1
        )

        val c2 = Offset(w * (0.72f - 0.08f * cos(phase)) - focusX * 0.65f, h * (0.50f - 0.07f * sin(phase)))
        val r2 = minDim * (0.40f + 0.07f * snap.midVocal)
        drawCircle(
            color = secondaryColor.copy(alpha = (baseAlpha * 0.38f * reveal).coerceIn(0.02f, 0.18f)),
            radius = r2,
            center = c2
        )

        // Concentric harmonic beat halo rings behind artwork stage
        val haloCenter = Offset(w * 0.5f + focusX * 0.55f, h * 0.33f)
        val haloRadius = (minDim * (0.36f + 0.05f * snap.beatPulse)).coerceAtLeast(36f)
        drawCircle(
            color = primaryColor.copy(alpha = (0.14f + 0.22f * snap.beatPulse) * reveal),
            radius = haloRadius,
            center = haloCenter,
            style = Stroke(width = (2.5.dp.toPx() + 3.dp.toPx() * snap.beatPulse))
        )
        drawCircle(
            color = accentColor.copy(alpha = (0.10f + 0.16f * snap.highAir) * reveal),
            radius = haloRadius * (1.14f + 0.04f * sin(phase * 2f)),
            center = haloCenter,
            style = Stroke(width = 1.6.dp.toPx())
        )
    }
}

/**
 * Ultra-fast zero-allocation reactive energy aura for the collapsed Mini-Player capsule.
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
        val cx1 = w * (0.24f + 0.16f * sin(phase))
        val cx2 = w * (0.76f - 0.14f * cos(phase))
        drawCircle(
            color = primaryColor.copy(alpha = (0.09f + 0.11f * snap.beatPulse).coerceIn(0.04f, 0.22f)),
            center = Offset(cx1, h * 0.5f),
            radius = (w * 0.30f).coerceAtLeast(36f)
        )
        drawCircle(
            color = secondaryColor.copy(alpha = (0.07f + 0.08f * snap.midVocal).coerceIn(0.03f, 0.16f)),
            center = Offset(cx2, h * 0.5f),
            radius = (w * 0.26f).coerceAtLeast(30f)
        )
    }
}
