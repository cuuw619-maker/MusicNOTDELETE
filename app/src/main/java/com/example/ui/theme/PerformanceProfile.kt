package com.example.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.data.preferences.PreferencesManager.PerformanceMode

enum class PerformanceTier {
    LOW,
    HIGH
}

@Immutable
data class PerformanceProfile(
    val tier: PerformanceTier = PerformanceTier.HIGH,
    val enableShaders: Boolean = false,
    val enableLiveBlur: Boolean = false,
    val enableHeavyShadows: Boolean = false,
    val visualizerBars: Int = 28,
    val kineticLetterAnimation: Boolean = true,
    val detailedPerCharWave: Boolean = true,
    val lyricBackdropResolutionPx: Int = 180,
    val thumbnailMaxPx: Int = 256,
    val allowRgb565: Boolean = false,
    val crossfadeImages: Boolean = true,
    val exoCacheMb: Int = 144
) {
    val isPotato: Boolean get() = tier == PerformanceTier.LOW
    val isFlagship: Boolean get() = tier == PerformanceTier.HIGH
}

fun detectHardwarePerformanceTier(context: Context): PerformanceTier {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am?.getMemoryInfo(memInfo)
    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    val isLowRam = am?.isLowRamDevice == true
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    return if (isLowRam || (totalRamGb > 0.0 && totalRamGb <= 3.0) || cores <= 4 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        PerformanceTier.LOW
    } else {
        PerformanceTier.HIGH
    }
}

fun resolvePerformanceProfile(context: Context, mode: PerformanceMode): PerformanceProfile {
    val resolvedTier = when (mode) {
        PerformanceMode.LOW -> PerformanceTier.LOW
        PerformanceMode.HIGH -> PerformanceTier.HIGH
    }
    val supportsRenderBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val supportsSafeAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    return when (resolvedTier) {
        PerformanceTier.LOW -> PerformanceProfile(
            tier = PerformanceTier.LOW,
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
        PerformanceTier.HIGH -> PerformanceProfile(
            tier = PerformanceTier.HIGH,
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
