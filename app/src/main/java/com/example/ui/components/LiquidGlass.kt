package com.example.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalPerformanceProfile

/**
 * Liquid Glass optical configuration inspired by Kyant0/AndroidLiquidGlass.
 * Strictly OFF (`enabled = false`) by default on initial launch until enabled by the user in Settings.
 */
@Immutable
data class LiquidGlassConfig(
    val enabled: Boolean = false,
    val preset: String = PRESET_CRYSTAL,
    val blurDp: Float = 18f,
    val refraction: Float = 0.58f,
    val frostAlpha: Float = 0.34f,
    val highlight: Float = 0.72f
) {
    companion object {
        const val PRESET_CRYSTAL = "CRYSTAL"
        const val PRESET_SILK = "SILK"
        const val PRESET_PRISM = "PRISM"
        const val PRESET_OBSIDIAN = "OBSIDIAN"
    }
}

val LocalLiquidGlassConfig = staticCompositionLocalOf { LiquidGlassConfig() }

/**
 * Safe Liquid Glass background & optical Fresnel rim modifier inspired by Kyant0/AndroidLiquidGlass.
 * - When `LocalLiquidGlassConfig.current.enabled == false`, leaves [fallbackColor] and [fallbackBorder] intact with zero overhead.
 * - When enabled, paints a multi-layer frosted lens backdrop with spectral chromatic dispersion edges,
 *   diagonal specular highlight reflection, and optional hardware blur ONLY on a decorative background layer
 *   (or crisp optical gradient dispersion on lower APIs/emulators) so foreground text stays 100% sharp.
 */
fun Modifier.liquidGlassBackground(
    shape: Shape,
    fallbackColor: Color,
    tintOverride: Color? = null,
    borderWidth: Dp = 1.2.dp,
    fallbackBorder: BorderStroke? = null
): Modifier = composed {
    val config = LocalLiquidGlassConfig.current
    if (!config.enabled) {
        val base = this
            .clip(shape)
            .background(fallbackColor, shape)
        return@composed if (fallbackBorder != null) {
            base.border(fallbackBorder, shape)
        } else {
            base
        }
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.45f
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val baseTone = tintOverride ?: fallbackColor

    val frost = config.frostAlpha.coerceIn(0.05f, 0.90f)
    val refract = config.refraction.coerceIn(0f, 1f)
    val hi = config.highlight.coerceIn(0f, 1f)
    val blurNorm = (config.blurDp / 32f).coerceIn(0f, 1f)

    val presetPalette = when (config.preset.uppercase()) {
        LiquidGlassConfig.PRESET_SILK -> Triple(
            if (isDark) baseTone.copy(alpha = (0.34f + frost * 0.46f).coerceIn(0.18f, 0.88f))
            else Color.White.copy(alpha = (0.48f + frost * 0.40f).coerceIn(0.28f, 0.92f)),
            Color(0xFFFFE7D6),
            primary.copy(alpha = 0.55f)
        )
        LiquidGlassConfig.PRESET_PRISM -> Triple(
            baseTone.copy(alpha = (0.24f + frost * 0.44f).coerceIn(0.14f, 0.84f)),
            Color(0xFF7CE8FF),
            Color(0xFFFF88E8)
        )
        LiquidGlassConfig.PRESET_OBSIDIAN -> Triple(
            Color(0xFF0E121B).copy(alpha = (0.46f + frost * 0.42f).coerceIn(0.28f, 0.90f)),
            Color(0xFF9FB8FF),
            tertiary.copy(alpha = 0.72f)
        )
        else -> Triple( // CRYSTAL
            baseTone.copy(alpha = (0.26f + frost * 0.48f).coerceIn(0.16f, 0.86f)),
            if (isDark) Color(0xFFBDE8FF) else Color.White,
            primary
        )
    }

    val glassFillColor = presetPalette.first
    val specAccentA = presetPalette.second
    val specAccentB = presetPalette.third
    val isObsidian = config.preset.equals(LiquidGlassConfig.PRESET_OBSIDIAN, ignoreCase = true)

    val rimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = (0.58f * hi + 0.14f).coerceIn(0.12f, 0.88f)),
            specAccentA.copy(alpha = (0.42f * refract + 0.10f).coerceIn(0.08f, 0.72f)),
            specAccentB.copy(alpha = (0.36f * refract + 0.08f).coerceIn(0.06f, 0.65f)),
            Color.White.copy(alpha = (0.28f * hi + 0.08f).coerceIn(0.08f, 0.55f))
        )
    )

    this
        .clip(shape)
        .background(glassFillColor, shape)
        .drawWithCache {
            val w = size.width.coerceAtLeast(1f)
            val h = size.height.coerceAtLeast(1f)

            val specularDiagonal = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = ((if (isDark || isObsidian) 0.22f else 0.44f) * hi).coerceIn(0f, 0.56f)),
                    specAccentA.copy(alpha = (0.14f * (hi * 0.6f + blurNorm * 0.4f)).coerceIn(0f, 0.26f)),
                    Color.Transparent,
                    specAccentB.copy(alpha = (0.12f * refract).coerceIn(0f, 0.24f))
                ),
                start = Offset.Zero,
                end = Offset(w, h)
            )

            val causticSweep = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = (0.16f * refract + 0.06f * blurNorm).coerceIn(0f, 0.30f)),
                    tertiary.copy(alpha = (0.11f * refract).coerceIn(0f, 0.20f)),
                    Color.Transparent
                ),
                center = Offset(w * 0.82f, h * 0.18f),
                radius = (maxOf(w, h) * (0.65f + 0.35f * blurNorm)).coerceAtLeast(60f)
            )

            val bottomFresnel = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.18f * hi).coerceIn(0f, 0.28f)),
                    Color.Transparent,
                    secondary.copy(alpha = (0.10f * refract).coerceIn(0f, 0.18f))
                )
            )

            onDrawWithContent {
                drawRect(brush = specularDiagonal)
                if (refract > 0.02f || blurNorm > 0.05f) {
                    drawRect(brush = causticSweep)
                }
                drawRect(brush = bottomFresnel)
                drawContent()
            }
        }
        .border(
            width = borderWidth,
            brush = rimBrush,
            shape = shape
        )
}

/**
 * Container Surface that seamlessly switches between clean Material 3 [Surface]
 * and Kyant0-inspired Liquid Glass optical lens rendering when `LocalLiquidGlassConfig.current.enabled` is true.
 * Never blurs foreground children directly so typography & icons stay razor-sharp.
 */
@Composable
fun LiquidGlassSurface(
    shape: Shape,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tintOverride: Color? = null,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    tonalElevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalLiquidGlassConfig.current
    val profile = LocalPerformanceProfile.current

    val effectiveColor = if (glass.enabled) Color.Transparent else fallbackColor
    val effectiveBorder = if (glass.enabled) null else border
    val effectiveTonal = if (glass.enabled) 0.dp else tonalElevation
    val effectiveShadow = when {
        profile.isPotato -> 0.dp
        glass.enabled -> shadowElevation.coerceAtMost(6.dp)
        else -> shadowElevation
    }

    val surfaceModifier = modifier.then(
        if (glass.enabled) {
            Modifier.liquidGlassBackground(
                shape = shape,
                fallbackColor = fallbackColor,
                tintOverride = tintOverride
            )
        } else {
            Modifier
        }
    )

    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = surfaceModifier,
            shape = shape,
            color = effectiveColor,
            contentColor = contentColor,
            border = effectiveBorder,
            shadowElevation = effectiveShadow,
            tonalElevation = effectiveTonal
        ) {
            Box(content = content)
        }
    } else {
        Surface(
            modifier = surfaceModifier,
            shape = shape,
            color = effectiveColor,
            contentColor = contentColor,
            border = effectiveBorder,
            shadowElevation = effectiveShadow,
            tonalElevation = effectiveTonal
        ) {
            Box(content = content)
        }
    }
}

/**
 * Decorative blurred backdrop glow helper for showcase/preview cards.
 * Uses Android 12+ `RenderEffect` strictly on real non-emulated hardware & HIGH graphics mode,
 * with pure Skia gradient fallback elsewhere so it never freezes or crashes.
 */
@Composable
fun LiquidGlassCausticBackdrop(
    modifier: Modifier = Modifier
) {
    val config = LocalLiquidGlassConfig.current
    val profile = LocalPerformanceProfile.current
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    val canUseHardwareBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !profile.isPotato &&
        config.blurDp >= 2f &&
        !ShaderSafetyGuard.isEmulatedOrSoftwareGpu() &&
        !ShaderSafetyGuard.isRuntimeTripped()

    val blurModifier = if (canUseHardwareBlur) {
        Modifier.graphicsLayer {
            val radiusPx = (config.blurDp * density * 0.45f).coerceIn(1f, 36f)
            runCatching {
                renderEffect = RenderEffect.createBlurEffect(
                    radiusPx,
                    radiusPx,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }.onFailure {
                ShaderSafetyGuard.markAgslFailure()
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(blurModifier)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val b1 = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(w * 0.25f, h * 0.35f),
                    radius = (maxOf(w, h) * 0.55f).coerceAtLeast(32f)
                )
                val b2 = Brush.radialGradient(
                    colors = listOf(tertiary.copy(alpha = 0.50f), Color.Transparent),
                    center = Offset(w * 0.78f, h * 0.65f),
                    radius = (maxOf(w, h) * 0.52f).coerceAtLeast(32f)
                )
                val b3 = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.38f), Color.Transparent),
                    center = Offset(w * 0.52f, h * 0.50f),
                    radius = (maxOf(w, h) * 0.42f).coerceAtLeast(24f)
                )
                onDrawBehind {
                    drawRect(brush = b1)
                    drawRect(brush = b2)
                    drawRect(brush = b3)
                }
            }
    )
}
