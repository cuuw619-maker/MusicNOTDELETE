package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.example.ui.theme.SpringTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState

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

private object MorphShapeLut {
    const val SHAPE_COUNT = 6
    const val STEPS = 96

    val cosTable = FloatArray(STEPS) { i ->
        cos((i.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat()))
    }
    val sinTable = FloatArray(STEPS) { i ->
        sin((i.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat()))
    }

    // 6 canonical M3 Expressive shapes:
    // 0: Circle, 1: Squircle, 2: 6-Lobe Clover, 3: 8-Wave Cookie, 4: 4-Point Spark, 5: 12-Petal Flower
    val radii: Array<FloatArray> = Array(SHAPE_COUNT) { shapeIdx ->
        FloatArray(STEPS) { stepIdx ->
            val theta = (stepIdx.toFloat() / STEPS.toFloat()) * (2f * PI.toFloat())
            when (shapeIdx) {
                0 -> 0.88f
                1 -> {
                    // Superellipse (Squircle, n=4) scaled neatly inside unit circle
                    val c = abs(cos(theta)).pow(4f)
                    val s = abs(sin(theta)).pow(4f)
                    (0.84f / (c + s).pow(0.25f)).coerceAtMost(0.98f)
                }
                2 -> 0.80f + 0.17f * cos(theta * 6f) // 6-lobe clover
                3 -> 0.83f + 0.14f * cos(theta * 8f) // 8-wave cookie
                4 -> 0.66f + 0.30f * cos(theta * 4f) // 4-point spark/star
                else -> 0.85f + 0.12f * cos(theta * 12f) // 12-petal burst/flower
            }
        }
    }

    fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

/**
 * Material 3 Expressive 6-Shape Morphing Loading Indicator.
 * Fluidly morphs through 6 canonical shapes (Circle -> Squircle -> Clover -> Cookie -> Spark -> Flower)
 * with dwell-and-glide easing, continuous rotation, and zero per-frame allocations.
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = color.copy(alpha = 0.14f)
) {
    val transition = rememberInfiniteTransition(label = "expressive_loader_v2")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "loader_rot"
    )
    val cycleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = MorphShapeLut.SHAPE_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5400, easing = LinearEasing),
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
        Canvas(modifier = Modifier.size(size * 0.72f)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val maxR = minOf(cx, cy) * 0.94f

            val wrapped = ((cycleProgress % MorphShapeLut.SHAPE_COUNT) + MorphShapeLut.SHAPE_COUNT) % MorphShapeLut.SHAPE_COUNT
            val idxA = wrapped.toInt().coerceIn(0, MorphShapeLut.SHAPE_COUNT - 1)
            val idxB = (idxA + 1) % MorphShapeLut.SHAPE_COUNT
            val rawFrac = wrapped - idxA
            // Dwell softly at each canonical shape (first 32%), then glide smoothly (remaining 68%)
            val glideT = if (rawFrac < 0.32f) 0f else MorphShapeLut.smoothStep((rawFrac - 0.32f) / 0.68f)

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
                // Rotate precomputed unit circle coordinates via rotation matrix (avoids per-vertex trig)
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
 * Reusable M3 Expressive Oval Choice Pill for selection grids (speed, pitch, equalizer, dialogs).
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
            isPressed -> 1.14f
            selected -> 1.03f
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
 * Material 3 Expressive Connected Button Group:
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
    val view = LocalView.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
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
                    onSelect(index)
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
