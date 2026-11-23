package com.example.ui.animation

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import com.example.ui.theme.LocalMotionConfig
import kotlinx.coroutines.launch

/**
 * Standardized Fluid Spring Physics Constants
 */
object FluidMotionConstants {
    const val PRESS_DAMPING = 0.58f
    val PRESS_STIFFNESS = Spring.StiffnessMediumLow

    const val DRAG_DAMPING = 0.70f
    val DRAG_STIFFNESS = Spring.StiffnessMedium

    const val RUBBER_BAND_FACTOR = 0.15f

    const val DEFAULT_SCALE_DOWN = 0.96f
    const val DEFAULT_ALPHA_DOWN = 0.92f
    const val DRAG_CLICK_THRESHOLD_PX = 24f

    fun <T> pressSpring(): SpringSpec<T> = spring(
        dampingRatio = PRESS_DAMPING,
        stiffness = PRESS_STIFFNESS
    )

    fun <T> dragSpring(): SpringSpec<T> = spring(
        dampingRatio = DRAG_DAMPING,
        stiffness = DRAG_STIFFNESS
    )

    fun <T> navIndicatorSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.58f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> artworkSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.68f,
        stiffness = Spring.StiffnessLow
    )
}

enum class HapticType {
    LIGHT,
    MEDIUM,
    HEAVY,
    SUCCESS,
    TOGGLE
}

fun performHapticFeedback(view: View, type: HapticType = HapticType.LIGHT) {
    try {
        val constant = when (type) {
            HapticType.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticType.MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticType.HEAVY -> HapticFeedbackConstants.LONG_PRESS
            HapticType.SUCCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else HapticFeedbackConstants.VIRTUAL_KEY
            HapticType.TOGGLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.TOGGLE_ON
            } else HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    } catch (_: Exception) {
        // Fallback gracefully on devices without custom haptic constants
    }
}

/**
 * High-performance Fluid Press Modifier:
 * - Built on Compose's standard `MutableInteractionSource` + `Modifier.clickable` + `rememberUpdatedState`
 *   so recompositions or updated lambdas NEVER cancel `pointerInput` or drop taps.
 * - Spring scale down & alpha response on press, with clean overshoot recovery on release/cancel.
 * - Triggers haptic feedback once per press gesture when enabled.
 */
fun Modifier.fluidPress(
    scaleDown: Float = FluidMotionConstants.DEFAULT_SCALE_DOWN,
    alphaDown: Float = FluidMotionConstants.DEFAULT_ALPHA_DOWN,
    @Suppress("UNUSED_PARAMETER") rubberBandMultiplier: Float = FluidMotionConstants.RUBBER_BAND_FACTOR,
    hapticType: HapticType = HapticType.LIGHT,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val motionConfig = LocalMotionConfig.current
    val view = LocalView.current

    val scaleAnim = remember { Animatable(1f) }
    val alphaAnim = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }
    val latestOnClick by rememberUpdatedState(onClick)
    val latestHapticType by rememberUpdatedState(hapticType)
    val latestHapticEnabled by rememberUpdatedState(motionConfig.hapticEnabled)

    val targetScaleDown = if (motionConfig.reducedMotion) 0.99f else scaleDown
    val targetAlphaDown = if (motionConfig.reducedMotion) 0.98f else alphaDown

    LaunchedEffect(interactionSource, targetScaleDown, targetAlphaDown, enabled) {
        if (!enabled) {
            scaleAnim.snapTo(1f)
            alphaAnim.snapTo(1f)
            return@LaunchedEffect
        }
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (latestHapticEnabled) {
                        performHapticFeedback(view, latestHapticType)
                    }
                    launch {
                        scaleAnim.animateTo(
                            targetValue = targetScaleDown,
                            animationSpec = FluidMotionConstants.pressSpring()
                        )
                    }
                    launch {
                        alphaAnim.animateTo(
                            targetValue = targetAlphaDown,
                            animationSpec = FluidMotionConstants.pressSpring()
                        )
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    launch {
                        scaleAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = FluidMotionConstants.pressSpring()
                        )
                    }
                    launch {
                        alphaAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = FluidMotionConstants.pressSpring()
                        )
                    }
                }
            }
        }
    }

    val graphicsMod = this.graphicsLayer {
        scaleX = scaleAnim.value
        scaleY = scaleAnim.value
        alpha = alphaAnim.value
    }

    if (onClick != null) {
        graphicsMod.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = { latestOnClick?.invoke() }
        )
    } else {
        graphicsMod
    }
}

/**
 * Backward-compatible alias for existing call sites.
 */
fun Modifier.bouncyClickable(
    scaleDown: Float = FluidMotionConstants.DEFAULT_SCALE_DOWN,
    hapticType: HapticType = HapticType.LIGHT,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = fluidPress(
    scaleDown = scaleDown,
    hapticType = hapticType,
    enabled = enabled,
    onClick = onClick
)

/**
 * Rubber band displacement function:
 * visualOffset = rawOffset * resistance
 */
fun calculateRubberBandOffset(
    rawOffset: Float,
    resistance: Float = FluidMotionConstants.RUBBER_BAND_FACTOR
): Float {
    return rawOffset * resistance
}
