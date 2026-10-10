package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.example.model.Song
import com.example.ui.animation.HapticType
import com.example.ui.animation.fluidPress
import com.example.ui.animation.performHapticFeedback
import kotlin.math.roundToInt

/**
 * Geometric Morphing Play/Pause Button:
 * - Rounded Rectangle shape (NOT circle)
 * - Exact symmetrical geometry:
 *     * In Pause (progress = 1): two identical, perfectly centered vertical bars of equal width and height.
 *     * In Play (progress = 0): symmetrical play triangle centered precisely in the button.
 *     * Morph continuously animates between the two shapes without fade or clipping.
 */
@Composable
fun MorphingPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 80.dp,
    height: Dp = 56.dp,
    size: Dp? = null,
    iconSize: Dp = 28.dp,
    cornerRadius: Dp = 20.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val finalWidth = size ?: width
    val finalHeight = size ?: height

    // 0f = Paused (Play triangle ▸), 1f = Playing (Pause bars ❚❚)
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "play_pause_geometric_morph"
    )

    Surface(
        modifier = modifier
            .size(width = finalWidth, height = finalHeight)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(cornerRadius))
            .fluidPress(
                scaleDown = 0.94f,
                hapticType = HapticType.MEDIUM,
                onClick = onClick
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.testTag(if (isPlaying) "pause_button" else "play_button")
        ) {
            Canvas(modifier = Modifier.size(iconSize)) {
                val w = this.size.width
                val h = this.size.height
                val t = progress

                if (t <= 0.04f) {
                    // Pristine, 100% seamless straight equilateral play triangle
                    val trianglePath = Path().apply {
                        moveTo(0.24f * w, 0.16f * h)
                        lineTo(0.84f * w, 0.50f * h)
                        lineTo(0.24f * w, 0.84f * h)
                        close()
                    }
                    drawPath(trianglePath, color = contentColor, style = Fill)
                } else if (t >= 0.96f) {
                    // Perfectly symmetrical pause bars
                    val barCorner = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())
                    drawRoundRect(
                        color = contentColor,
                        topLeft = Offset(0.20f * w, 0.15f * h),
                        size = Size(0.20f * w, 0.70f * h),
                        cornerRadius = barCorner
                    )
                    drawRoundRect(
                        color = contentColor,
                        topLeft = Offset(0.60f * w, 0.15f * h),
                        size = Size(0.20f * w, 0.70f * h),
                        cornerRadius = barCorner
                    )
                } else {
                    // Mathematically exact collinear morphing: top and bottom slopes are 100% straight!
                    val overlap = (1f - t) * 0.02f * w

                    val leftP1x = lerp(0.24f, 0.20f, t) * w
                    val leftP1y = lerp(0.16f, 0.15f, t) * h
                    val leftP2x = lerp(0.54f, 0.40f, t) * w + overlap
                    val leftP2y = lerp(0.33f, 0.15f, t) * h
                    val leftP3x = lerp(0.54f, 0.40f, t) * w + overlap
                    val leftP3y = lerp(0.67f, 0.85f, t) * h
                    val leftP4x = lerp(0.24f, 0.20f, t) * w
                    val leftP4y = lerp(0.84f, 0.85f, t) * h

                    val leftPath = Path().apply {
                        moveTo(leftP1x, leftP1y)
                        lineTo(leftP2x, leftP2y)
                        lineTo(leftP3x, leftP3y)
                        lineTo(leftP4x, leftP4y)
                        close()
                    }

                    val rightP1x = lerp(0.54f, 0.60f, t) * w - overlap
                    val rightP1y = lerp(0.33f, 0.15f, t) * h
                    val rightP2x = lerp(0.84f, 0.80f, t) * w
                    val rightP2y = lerp(0.50f, 0.15f, t) * h
                    val rightP3x = lerp(0.84f, 0.80f, t) * w
                    val rightP3y = lerp(0.50f, 0.85f, t) * h
                    val rightP4x = lerp(0.54f, 0.60f, t) * w - overlap
                    val rightP4y = lerp(0.67f, 0.85f, t) * h

                    val rightPath = Path().apply {
                        moveTo(rightP1x, rightP1y)
                        lineTo(rightP2x, rightP2y)
                        lineTo(rightP3x, rightP3y)
                        lineTo(rightP4x, rightP4y)
                        close()
                    }

                    drawPath(leftPath, color = contentColor, style = Fill)
                    drawPath(rightPath, color = contentColor, style = Fill)
                }
            }
        }
    }
}

/**
 * Modern Interactive Progress Bar with Vertical Pill/Thumb:
 * - Vertical pill thumb (6dp width, 28dp height, rounded capsule)
 * - Dynamic feedback on touch (thumb height 28dp -> 34dp, width 6dp -> 8dp)
 * - Large 48dp hit area for effortless touch
 * - Real-time floating time bubble indicator during scrub
 * - Smooth, responsive drag and seek with haptic ticks
 */
@Composable
fun InteractiveProgressBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bookmarks: List<Long> = emptyList(),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    val density = LocalDensity.current

    val safeDuration = durationMs.coerceAtLeast(1L)
    val playbackFraction = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayFraction = if (isDragging) dragFraction else playbackFraction

    // Subtle track height expansion on touch
    val barHeightDp by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "bar_thickness"
    )

    // Vertical pill thumb dimensions
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 6.dp,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "thumb_width"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (isDragging) 34.dp else 28.dp,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "thumb_height"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        performHapticFeedback(view, HapticType.LIGHT)
                        dragFraction = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Released
                                isDragging = false
                                val targetMs = (dragFraction * safeDuration).toLong()
                                performHapticFeedback(view, HapticType.MEDIUM)
                                onSeekTo(targetMs)
                                break
                            } else {
                                change.consume()
                                dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val activeWidthPx = totalWidthPx * displayFraction
            val barHeightPx = with(density) { barHeightDp.toPx() }

            // High-performance GPU Canvas track rendering (zero layout passes during continuous drag)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeightDp)
                    .align(Alignment.Center)
            ) {
                val corner = CornerRadius(barHeightPx / 2f, barHeightPx / 2f)
                // Inactive track background
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(totalWidthPx, barHeightPx),
                    cornerRadius = corner
                )
                // Active track progress
                if (activeWidthPx > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(activeWidthPx.coerceIn(0f, totalWidthPx), barHeightPx),
                        cornerRadius = corner
                    )
                }

                // Render glowing bookmark pins along the track
                for (bMs in bookmarks) {
                    val bFrac = (bMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                    val bx = bFrac * totalWidthPx
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = Offset(bx, barHeightPx / 2f)
                    )
                }
            }

            // Floating Time Bubble Indicator on Drag
            if (isDragging) {
                val currentDragMs = (dragFraction * safeDuration).toLong()
                val bubbleOffset = (activeWidthPx - 32f).coerceIn(0f, totalWidthPx - 64f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(bubbleOffset.roundToInt(), -55) }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = Song.formatDuration(currentDragMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }

            // Draggable Thumb: Vertical Pill / Stick
            val thumbWidthPx = with(density) { thumbWidth.toPx() }
            val thumbOffsetPx = (activeWidthPx - (thumbWidthPx / 2f)).coerceIn(
                0f,
                totalWidthPx - thumbWidthPx
            )

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(thumbOffsetPx.roundToInt(), 0)
                    }
                    .size(width = thumbWidth, height = thumbHeight)
                    .shadow(
                        elevation = if (isDragging) 6.dp else 3.dp,
                        shape = RoundedCornerShape(thumbWidth / 2),
                        spotColor = activeColor.copy(alpha = 0.5f)
                    )
                    .clip(RoundedCornerShape(thumbWidth / 2))
                    .background(activeColor)
            )
        }

        // Time labels: elapsed & remaining
        val currentMs = if (isDragging) (dragFraction * safeDuration).toLong() else currentPositionMs
        val remainingMs = (durationMs - currentMs).coerceAtLeast(0L)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Song.formatDuration(currentMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "-${Song.formatDuration(remainingMs)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
