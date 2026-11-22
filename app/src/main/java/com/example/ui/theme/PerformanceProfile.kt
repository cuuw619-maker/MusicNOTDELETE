package com.example.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.data.preferences.PreferencesManager.PerformanceMode

enum class PerformanceTier {
    POTATO,
    BALANCED,
    FLAGSHIP
}

@Immutable
data class PerformanceProfile(
    val tier: PerformanceTier = PerformanceTier.BALANCED,
    val enableShaders: Boolean = false,
    val enableLiveBlur: Boolean = false,
    val enableHeavyShadows: Boolean = false,
    val visualizerBars: Int = 20,
    val kineticLetterAnimation: Boolean = true,
    val detailedPerCharWave: Boolean = false,
    val lyricBackdropResolutionPx: Int = 96,
    val thumbnailMaxPx: Int = 180,
    val allowRgb565: Boolean = true,
    val crossfadeImages: Boolean = false,
    val exoCacheMb: Int = 64
) {
    val isPotato: Boolean get() = tier == PerformanceTier.POTATO
    val isFlagship: Boolean get() = tier == PerformanceTier.FLAGSHIP
}

fun detectHardwarePerformanceTier(context: Context): PerformanceTier {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    val isLowRam = am?.isLowRamDevice == true
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    return when {
        isLowRam || (totalRamGb > 0.0 && totalRamGb <= 3.6) || cores <= 4 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
            PerformanceTier.POTATO
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && totalRamGb >= 6.5 && cores >= 8 ->
            PerformanceTier.FLAGSHIP
        else -> PerformanceTier.BALANCED
    }
}

fun resolvePerformanceProfile(context: Context, mode: PerformanceMode): PerformanceProfile {
    val resolvedTier = when (mode) {
        PerformanceMode.AUTO -> detectHardwarePerformanceTier(context)
        PerformanceMode.POTATO -> PerformanceTier.POTATO
        PerformanceMode.BALANCED -> PerformanceTier.BALANCED
        PerformanceMode.FLAGSHIP -> PerformanceTier.FLAGSHIP
    }
    val supportsRenderBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val supportsSafeAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    return when (resolvedTier) {
        PerformanceTier.POTATO -> PerformanceProfile(
            tier = PerformanceTier.POTATO,
            enableShaders = false,
            enableLiveBlur = false,
            enableHeavyShadows = false,
            visualizerBars = 14,
            kineticLetterAnimation = true,
            detailedPerCharWave = false,
            lyricBackdropResolutionPx = 64,
            thumbnailMaxPx = 128,
            allowRgb565 = true,
            crossfadeImages = false,
            exoCacheMb = 48
        )
        PerformanceTier.BALANCED -> PerformanceProfile(
            tier = PerformanceTier.BALANCED,
            enableShaders = supportsSafeAgsl,
            enableLiveBlur = supportsRenderBlur,
            enableHeavyShadows = false,
            visualizerBars = 22,
            kineticLetterAnimation = true,
            detailedPerCharWave = true,
            lyricBackdropResolutionPx = 112,
            thumbnailMaxPx = 192,
            allowRgb565 = false,
            crossfadeImages = true,
            exoCacheMb = 96
        )
        PerformanceTier.FLAGSHIP -> PerformanceProfile(
            tier = PerformanceTier.FLAGSHIP,
            enableShaders = supportsSafeAgsl,
            enableLiveBlur = supportsRenderBlur,
            enableHeavyShadows = true,
            visualizerBars = 30,
            kineticLetterAnimation = true,
            detailedPerCharWave = true,
            lyricBackdropResolutionPx = 180,
            thumbnailMaxPx = 280,
            allowRgb565 = false,
            crossfadeImages = true,
            exoCacheMb = 160
        )
    }
}

val LocalPerformanceProfile = staticCompositionLocalOf { PerformanceProfile() }
