package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.animation.HapticType
import com.example.ui.animation.performHapticFeedback
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Material 3 Expressive Grouped Container Corner Hierarchy:
 * Outer items get large rounded corners (e.g. 22.dp), while inner shared edges
 * between stacked items get tight rounded corners (6.dp) creating a cohesive segmented list card.
 */
fun groupedItemShape(
    index: Int,
    totalCount: Int,
    outerRadius: Dp = 22.dp,
    innerRadius: Dp = 6.dp
): RoundedCornerShape {
    return when {
        totalCount <= 1 -> RoundedCornerShape(outerRadius)
        index <= 0 -> RoundedCornerShape(
            topStart = outerRadius,
            topEnd = outerRadius,
            bottomStart = innerRadius,
            bottomEnd = innerRadius
        )
        index >= totalCount - 1 -> RoundedCornerShape(
            topStart = innerRadius,
            topEnd = innerRadius,
            bottomStart = outerRadius,
            bottomEnd = outerRadius
        )
        else -> RoundedCornerShape(innerRadius)
    }
}

/**
 * 10-shape canonical M3 Expressive lookup table with zero per-frame trigonometric allocations:
 * 0: Circle, 1: Squircle, 2: 6-Lobe Clover, 3: 8-Wave Cookie, 4: 4-Point Spark,
 * 5: 12-Petal Flower, 6: Soft Pill/Stadium, 7: Arch/Shield, 8: Slanted Diamond, 9: Scalloped Sunburst
 */
object MorphShapeLut {
    const val SHAPE_COUNT = 10
    const val STEPS = 96

    val cosTable = FloatArray(STEPS) { i ->
        cos((i.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat()))
    }
    val sinTable = FloatArray(STEPS) { i ->
        sin((i.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat()))
    }

    val radii: Array<FloatArray> = Array(SHAPE_COUNT) { shapeIdx ->
        FloatArray(STEPS) { stepIdx ->
            val theta = (stepIdx.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat())
            when (shapeIdx) {
                0 -> 0.88f
                1 -> {
                    val c = abs(cos(theta)).pow(4f)
                    val s = abs(sin(theta)).pow(4f)
                    (0.84f / (c + s).pow(0.25f)).coerceAtMost(0.98f)
                }
                2 -> 0.80f + 0.17f * cos(theta * 6f) // 6-lobe clover
                3 -> 0.83f + 0.14f * cos(theta * 8f) // 8-wave cookie
                4 -> 0.68f + 0.28f * cos(theta * 4f) // 4-point spark
                5 -> 0.85f + 0.12f * cos(theta * 12f) // 12-petal flower
                6 -> 0.82f + 0.15f * cos(theta * 2f) // organic stadium pill
                7 -> (0.84f - 0.10f * sin(theta) + 0.08f * cos(theta * 3f)).coerceIn(0.68f, 0.98f) // shield/arch
                8 -> {
                    val c = abs(cos(theta - 0.35f)).pow(1.65f)
                    val s = abs(sin(theta - 0.35f)).pow(1.65f)
                    (0.76f / (c + s).pow(0.606f)).coerceIn(0.66f, 0.98f) // slanted diamond
                }
                else -> 0.86f + 0.10f * cos(theta * 10f) // 10-scallop sunburst badge
            }
        }
    }

    fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

/**
 * Generates a Material 3 Expressive organic polygon [Shape] indexed into [MorphShapeLut].
 */
fun morphPolygonShape(shapeIndex: Int): Shape = GenericShape { size, _ ->
    val idx = ((shapeIndex % MorphShapeLut.SHAPE_COUNT) + MorphShapeLut.SHAPE_COUNT) % MorphShapeLut.SHAPE_COUNT
    val table = MorphShapeLut.radii[idx]
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(cx, cy) * 1.08f
    for (i in 0 until MorphShapeLut.STEPS) {
        val r = maxR * table[i]
        val x = cx + r * MorphShapeLut.cosTable[i]
        val y = cy + r * MorphShapeLut.sinTable[i]
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Container framed with one of the 10 M3 Expressive organic shapes (ideal for artist avatars & hero badges).
 */
@Composable
fun ExpressiveMorphShapeSurface(
    shapeIndex: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = remember(shapeIndex) { morphPolygonShape(shapeIndex) }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier
        ) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    } else {
        Surface(
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier
        ) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

/**
 * Material 3 Expressive 10-Shape Morphing Loading Indicator.
 * Fluidly morphs through all 10 canonical shapes with dwell-and-glide easing and zero per-frame allocations.
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = color.copy(alpha = 0.14f)
) {
    val transition = rememberInfiniteTransition(label = "expressive_loader_v3")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loader_rot"
    )
    val cycleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = MorphShapeLut.SHAPE_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loader_morph_cycle"
    )
    val path = remember { Path() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.74f)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val maxR = minOf(cx, cy) * 0.94f

            val wrapped = ((cycleProgress % MorphShapeLut.SHAPE_COUNT) + MorphShapeLut.SHAPE_COUNT) % MorphShapeLut.SHAPE_COUNT
            val idxA = wrapped.toInt().coerceIn(0, MorphShapeLut.SHAPE_COUNT - 1)
            val idxB = (idxA + 1) % MorphShapeLut.SHAPE_COUNT
            val rawFrac = wrapped - idxA
            val glideT = if (rawFrac < 0.28f) 0f else MorphShapeLut.smoothStep((rawFrac - 0.28f) / 0.72f)

            val shapeA = MorphShapeLut.radii[idxA]
            val shapeB = MorphShapeLut.radii[idxB]

            val rotRad = (rotation * PI / 180.0).toFloat()
            val cosRot = cos(rotRad)
            val sinRot = sin(rotRad)

            path.reset()
            for (i in 0 until MorphShapeLut.STEPS) {
                val rNorm = shapeA[i] + (shapeB[i] - shapeA[i]) * glideT
                val r = maxR * rNorm
                val c0 = MorphShapeLut.cosTable[i]
                val s0 = MorphShapeLut.sinTable[i]
                val rx = c0 * cosRot - s0 * sinRot
                val ry = s0 * cosRot + c0 * sinRot
                val x = cx + r * rx
                val y = cy + r * ry
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path = path, color = color)
        }
    }
}

/**
 * Material 3 Expressive Linear Wavy Progress Indicator.
 * Supports determinate (0f..1f) and indeterminate modes with spring-damped wave flattening and reused Path.
 */
@Composable
fun LinearWavyProgressIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    isPlaying: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 10.dp,
    strokeWidth: Dp = 3.5.dp,
    amplitudeDp: Dp = 2.6.dp,
    wavelengthDp: Dp = 22.dp
) {
    val profile = LocalPerformanceProfile.current
    val effectiveAnim = isPlaying && !profile.isPotato
    val ampFactor by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = SpringTokens.effectsDefault(),
        label = "linear_wavy_amp"
    )
    val phase = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "linear_wavy_phase")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (effectiveAnim) 1400 else 1900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        ).value
    } else 0f

    val path = remember { Path() }
    val stepPx = if (profile.isPotato) 8f else 4f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val cy = size.height / 2f
        val strokePx = strokeWidth.toPx()
        val amp = amplitudeDp.toPx() * ampFactor
        val wavelength = wavelengthDp.toPx().coerceAtLeast(10f)

        val startFrac: Float
        val endFrac: Float
        if (progress != null) {
            startFrac = 0f
            endFrac = progress().coerceIn(0f, 1f)
        } else {
            val norm = (phase / (2f * PI.toFloat())).coerceIn(0f, 1f)
            startFrac = (norm - 0.18f).coerceIn(0f, 0.82f)
            endFrac = (startFrac + 0.36f).coerceAtMost(1f)
        }

        // Background track
        drawLine(
            color = trackColor,
            start = Offset(0f, cy),
            end = Offset(w, cy),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )

        val activeStart = w * startFrac
        val activeEnd = w * endFrac
        if (activeEnd - activeStart > 2f) {
            if (amp < 0.25f) {
                drawLine(
                    color = color,
                    start = Offset(activeStart, cy),
                    end = Offset(activeEnd, cy),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            } else {
                path.reset()
                var x = activeStart
                path.moveTo(x, cy + sin((x / wavelength) * (2f * PI.toFloat()) - phase) * amp)
                while (x < activeEnd) {
                    x = (x + stepPx).coerceAtMost(activeEnd)
                    val taper = minOf(
                        (x - activeStart) / (wavelength * 0.35f),
                        (activeEnd - x) / (wavelength * 0.35f)
                    ).coerceIn(0f, 1f)
                    val y = cy + sin((x / wavelength) * (2f * PI.toFloat()) - phase) * amp * taper
                    path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/**
 * Material 3 Expressive Circular Wavy Progress Indicator.
 * Zero per-frame Path allocations. Supports indeterminate spinner or determinate radial progress.
 */
@Composable
fun CircularWavyProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    size: Dp = 44.dp,
    strokeWidth: Dp = 3.5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.18f)
) {
    val profile = LocalPerformanceProfile.current
    val transition = rememberInfiniteTransition(label = "circular_wavy")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "circ_spin"
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI.toFloat()),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "circ_wave"
    )
    val path = remember { Path() }
    val segments = if (profile.isPotato) 48 else 72

    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val strokePx = strokeWidth.toPx()
        val amp = 1.6.dp.toPx()
        val baseR = minOf(cx, cy) - strokePx - amp

        drawCircle(
            color = trackColor,
            radius = baseR,
            center = Offset(cx, cy),
            style = Stroke(width = strokePx)
        )

        val sweepDeg = ((progress?.coerceIn(0.05f, 1f)) ?: 0.72f) * 360f
        val startDeg = if (progress == null) spin else -90f
        val stepCount = ((segments * (sweepDeg / 360f)).toInt()).coerceAtLeast(10)

        path.reset()
        for (i in 0..stepCount) {
            val frac = i.toFloat() / stepCount.toFloat()
            val angleDeg = startDeg + sweepDeg * frac
            val angleRad = (angleDeg * PI / 180.0).toFloat()
            val taper = if (progress != null && progress >= 0.99f) 1f else minOf(frac * 4f, (1f - frac) * 4f).coerceIn(0f, 1f)
            val r = baseR + sin(angleRad * 8f - wavePhase) * amp * taper
            val x = cx + r * cos(angleRad)
            val y = cy + r * sin(angleRad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Material 3 Expressive SplitButton:
 * Joined capsule with primary action button + narrow gap + trailing morphing chevron button.
 */
@Composable
fun ExpressiveSplitButton(
    label: String,
    onPrimaryClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    menuExpanded: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    primaryTestTag: String = "split_primary_btn",
    menuTestTag: String = "split_menu_btn"
) {
    val view = LocalView.current
    val primaryInteraction = remember { MutableInteractionSource() }
    val menuInteraction = remember { MutableInteractionSource() }
    val primaryPressed by primaryInteraction.collectIsPressedAsState()
    val menuPressed by menuInteraction.collectIsPressedAsState()

    val innerLeftRadius by animateDpAsState(
        targetValue = if (primaryPressed) 14.dp else 6.dp,
        animationSpec = SpringTokens.spatialFast(),
        label = "split_left_corner"
    )
    val innerRightRadius by animateDpAsState(
        targetValue = if (menuPressed || menuExpanded) 18.dp else 6.dp,
        animationSpec = SpringTokens.spatialFast(),
        label = "split_right_corner"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (menuExpanded) 180f else 0f,
        animationSpec = SpringTokens.spatialFast(),
        label = "split_chevron_rot"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = {
                performHapticFeedback(view, HapticType.LIGHT)
                onPrimaryClick()
            },
            interactionSource = primaryInteraction,
            shape = RoundedCornerShape(
                topStart = 24.dp,
                bottomStart = 24.dp,
                topEnd = innerLeftRadius,
                bottomEnd = innerLeftRadius
            ),
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .testTag(primaryTestTag)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            onClick = {
                performHapticFeedback(view, HapticType.LIGHT)
                onMenuClick()
            },
            interactionSource = menuInteraction,
            shape = RoundedCornerShape(
                topStart = innerRightRadius,
                bottomStart = innerRightRadius,
                topEnd = 24.dp,
                bottomEnd = 24.dp
            ),
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .defaultMinSize(minWidth = 42.dp, minHeight = 40.dp)
                .testTag(menuTestTag)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Выбрать вариант",
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }
        }
    }
}

enum class FloatingToolbarStyle {
    STANDARD,
    VIBRANT
}

data class ToolbarActionItem(
    val icon: ImageVector,
    val contentDescription: String,
    val label: String? = null,
    val isActive: Boolean = false,
    val testTag: String = "",
    val onClick: () -> Unit
)

/**
 * Material 3 Expressive Horizontal Floating Toolbar (Standard or Vibrant).
 * Can optionally attach a pill-detached accent FAB right beside the toolbar capsule.
 */
@Composable
fun ExpressiveHorizontalFloatingToolbar(
    actions: List<ToolbarActionItem>,
    modifier: Modifier = Modifier,
    style: FloatingToolbarStyle = FloatingToolbarStyle.STANDARD,
    containerColor: Color = if (style == FloatingToolbarStyle.VIBRANT)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = if (style == FloatingToolbarStyle.VIBRANT)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface,
    activeContainerColor: Color = if (style == FloatingToolbarStyle.VIBRANT)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.primaryContainer,
    activeContentColor: Color = if (style == FloatingToolbarStyle.VIBRANT)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onPrimaryContainer,
    trailingFab: (@Composable RowScope.() -> Unit)? = null
) {
    val view = LocalView.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { item ->
                    val pillBg by animateColorAsState(
                        targetValue = if (item.isActive) activeContainerColor else Color.Transparent,
                        animationSpec = SpringTokens.effectsDefault(),
                        label = "toolbar_pill_bg"
                    )
                    val pillFg by animateColorAsState(
                        targetValue = if (item.isActive) activeContentColor else contentColor.copy(alpha = 0.85f),
                        animationSpec = SpringTokens.effectsDefault(),
                        label = "toolbar_pill_fg"
                    )
                    Surface(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            item.onClick()
                        },
                        shape = CircleShape,
                        color = pillBg,
                        contentColor = pillFg,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 42.dp)
                            .let { if (item.testTag.isNotBlank()) it.testTag(item.testTag) else it }
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = if (!item.label.isNullOrBlank()) 12.dp else 10.dp,
                                vertical = 9.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.contentDescription,
                                modifier = Modifier.size(20.dp)
                            )
                            if (!item.label.isNullOrBlank()) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (item.isActive) FontWeight.ExtraBold else FontWeight.SemiBold
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        if (trailingFab != null) {
            trailingFab()
        }
    }
}

/**
 * Material 3 Expressive Vertical Floating Toolbar for wide displays and quick rails.
 */
@Composable
fun ExpressiveVerticalFloatingToolbar(
    actions: List<ToolbarActionItem>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val view = LocalView.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            actions.forEach { item ->
                Surface(
                    onClick = {
                        performHapticFeedback(view, HapticType.LIGHT)
                        item.onClick()
                    },
                    shape = CircleShape,
                    color = if (item.isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (item.isActive) MaterialTheme.colorScheme.onPrimaryContainer else contentColor,
                    modifier = Modifier
                        .size(44.dp)
                        .let { if (item.testTag.isNotBlank()) it.testTag(item.testTag) else it }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.contentDescription,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Material 3 Expressive Docked Toolbar spanning full width cleanly inside sheets or bottom headers.
 */
@Composable
fun ExpressiveDockedToolbar(
    actions: List<ToolbarActionItem>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { item ->
                IconButton(
                    onClick = item.onClick,
                    modifier = Modifier
                        .size(44.dp)
                        .let { if (item.testTag.isNotBlank()) it.testTag(item.testTag) else it }
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription,
                        tint = if (item.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Material 3 Expressive Oval Choice Pill for selection grids (speed, pitch, equalizer, dialogs).
 * Enlarges noticeably with a bouncy spring and raises zIndex while pressed so the oval swells over neighbors cleanly.
 */
@Composable
fun ExpressiveOvalChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    minHeight: Dp = 42.dp,
    fontSizeSp: Float = 12f
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1.0f
            isPressed -> 1.15f
            selected -> 1.04f
            else -> 1.0f
        },
        animationSpec = SpringTokens.bouncy(),
        label = "oval_choice_scale"
    )

    val cornerRadius by animateDpAsState(
        targetValue = when {
            isPressed -> 24.dp
            selected -> 20.dp
            else -> 14.dp
        },
        animationSpec = SpringTokens.spatialFast(),
        label = "oval_choice_corner"
    )

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> unselectedContainerColor.copy(alpha = 0.5f)
            selected -> selectedContainerColor
            isPressed -> selectedContainerColor.copy(alpha = 0.45f)
            else -> unselectedContainerColor
        },
        animationSpec = SpringTokens.effectsDefault(),
        label = "oval_choice_bg"
    )

    val fg by animateColorAsState(
        targetValue = when {
            !enabled -> unselectedContentColor.copy(alpha = 0.45f)
            selected -> selectedContentColor
            else -> unselectedContentColor
        },
        animationSpec = SpringTokens.effectsDefault(),
        label = "oval_choice_fg"
    )

    Surface(
        onClick = {
            if (enabled) {
                performHapticFeedback(view, HapticType.LIGHT)
                onClick()
            }
        },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(cornerRadius),
        color = bg,
        contentColor = fg,
        tonalElevation = if (isPressed) 6.dp else if (selected) 3.dp else 0.dp,
        shadowElevation = if (isPressed) 6.dp else if (selected) 2.dp else 0.dp,
        border = if (selected || isPressed) {
            BorderStroke(
                width = if (isPressed) 1.5.dp else 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isPressed) 0.55f else 0.30f)
            )
        } else null,
        modifier = modifier
            .zIndex(if (isPressed) 2f else if (selected) 1f else 0f)
            .defaultMinSize(minHeight = minHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected || isPressed) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = fontSizeSp.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = fg.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

data class ConnectedGroupItem(
    val label: String,
    val icon: ImageVector? = null,
    val testTag: String = ""
)

/**
 * Material 3 Expressive Connected Button Group (Single Selection):
 * Adjacent buttons share tight inner corners (8.dp) and pill outer corners (24.dp),
 * while the active/selected button dynamically morphs toward full pill corners.
 */
@Composable
fun ExpressiveConnectedButtonGroup(
    items: List<ConnectedGroupItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    ExpressiveToggleButtonGroup(
        items = items,
        selectedIndices = setOf(selectedIndex),
        onToggle = { idx -> onSelect(idx) },
        modifier = modifier,
        containerColor = containerColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
        unselectedContentColor = unselectedContentColor
    )
}

/**
 * Material 3 Expressive Toggle Button Group (supports both single and multi-select toggles).
 */
@Composable
fun ExpressiveToggleButtonGroup(
    items: List<ConnectedGroupItem>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val view = LocalView.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index in selectedIndices
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()

            val innerRadius by animateDpAsState(
                targetValue = when {
                    isPressed -> 18.dp
                    isSelected -> 22.dp
                    else -> 8.dp
                },
                animationSpec = SpringTokens.spatialFast(),
                label = "btn_group_inner_radius_$index"
            )

            val shape = RoundedCornerShape(
                topStart = if (index == 0 || isSelected) 24.dp else innerRadius,
                bottomStart = if (index == 0 || isSelected) 24.dp else innerRadius,
                topEnd = if (index == items.lastIndex || isSelected) 24.dp else innerRadius,
                bottomEnd = if (index == items.lastIndex || isSelected) 24.dp else innerRadius
            )

            val bg by animateColorAsState(
                targetValue = if (isSelected) selectedContainerColor else containerColor,
                animationSpec = SpringTokens.effectsDefault(),
                label = "btn_group_bg_$index"
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) selectedContentColor else unselectedContentColor,
                animationSpec = SpringTokens.effectsDefault(),
                label = "btn_group_fg_$index"
            )

            Surface(
                onClick = {
                    performHapticFeedback(view, HapticType.LIGHT)
                    onToggle(index)
                },
                interactionSource = interactionSource,
                shape = shape,
                color = bg,
                contentColor = fg,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .let { if (item.testTag.isNotBlank()) it.testTag(item.testTag) else it }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.icon != null) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Material 3 Expressive Hero Surface Card with subtle tonal gradient and crisp border.
 */
@Composable
fun ExpressiveHeroCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    secondaryColor: Color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(
            Brush.linearGradient(listOf(containerColor, secondaryColor))
        )

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = cardModifier
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = cardModifier
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun ExpressiveSectionHeader(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionText != null && onActionClick != null) {
            Surface(
                onClick = onActionClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}
