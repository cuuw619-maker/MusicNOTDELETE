package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.SpringTokens
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Material 3 Expressive Morphing Play/Pause Button:
 * - Outer container shape morphs seamlessly between a full pill/circle (when paused)
 *   and an expressive rounded squircle (when playing).
 * - Inner glyph continuously morphs between a play triangle and pause bars.
 * - Optional buffering indicator arc for online streams.
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
    cornerRadius: Dp = 18.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    isBuffering: Boolean = false
) {
    val finalWidth = size ?: width
    val finalHeight = size ?: height

    val animatedCorner by animateDpAsState(
        targetValue = if (isPlaying) cornerRadius else (finalHeight / 2f),
        animationSpec = SpringTokens.spatialDefault(),
        label = "play_pause_container_morph"
    )
    val shape = RoundedCornerShape(animatedCorner)

    // 0f = Paused (Play triangle ▸), 1f = Playing (Pause bars ❚❚)
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "play_pause_geometric_morph"
    )

    val bufferingRotation = if (isBuffering) {
        val transition = rememberInfiniteTransition(label = "btn_buffering")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "buf_arc"
        ).value
    } else 0f

    Surface(
        modifier = modifier
            .size(width = finalWidth, height = finalHeight)
            .shadow(elevation = 8.dp, shape = shape, spotColor = containerColor.copy(alpha = 0.45f))
            .fluidPress(
                scaleDown = 0.93f,
                hapticType = HapticType.MEDIUM,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.testTag(if (isPlaying) "pause_button" else "play_button")
        ) {
            if (isBuffering) {
                Canvas(modifier = Modifier.size(iconSize + 14.dp)) {
                    drawArc(
                        color = contentColor.copy(alpha = 0.72f),
                        startAngle = bufferingRotation,
                        sweepAngle = 220f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            Canvas(modifier = Modifier.size(iconSize)) {
                val w = this.size.width
                val h = this.size.height
                val t = progress

                if (t <= 0.04f) {
                    val trianglePath = Path().apply {
                        moveTo(0.24f * w, 0.16f * h)
                        lineTo(0.84f * w, 0.50f * h)
                        lineTo(0.24f * w, 0.84f * h)
                        close()
                    }
                    drawPath(trianglePath, color = contentColor, style = Fill)
                } else if (t >= 0.96f) {
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
 * Material 3 Expressive Wavy Interactive Progress Bar:
 * - Renders an animated organic sinusoidal wave on the active portion while playing,
 *   flattening smoothly to a clean straight bar when paused or scrubbing.
 * - Vertical pill thumb expands smoothly on touch + floating time bubble.
 * - Tabular monospace timecodes prevent horizontal jitter.
 */
@Composable
fun InteractiveProgressBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
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

    // Smooth wave amplitude: ripples gently when playing, flattens cleanly on pause/drag
    val waveAmplitudeFraction by animateFloatAsState(
        targetValue = if (isPlaying && !isDragging) 1f else 0f,
        animationSpec = SpringTokens.effectsDefault(),
        label = "wave_amplitude"
    )

    val wavePhase = if (isPlaying && !isDragging) {
        val infinite = rememberInfiniteTransition(label = "squiggly_progress_phase")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_phase"
        ).value
    } else 0f

    val barThicknessDp by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 4.dp,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "bar_thickness"
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.5.dp,
        animationSpec = tween(150, easing = LinearOutSlowInEasing),
        label = "thumb_width"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (isDragging) 34.dp else 28.dp,
        animationSpec = SpringTokens.spatialFast(),
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
            val activeWidthPx = (totalWidthPx * displayFraction).coerceIn(0f, totalWidthPx)
            val strokePx = with(density) { barThicknessDp.toPx() }
            val maxAmplitudePx = with(density) { 3.2.dp.toPx() }
            val wavelengthPx = with(density) { 26.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .align(Alignment.Center)
            ) {
                val centerY = this.size.height / 2f

                // Inactive track (drawn from active tip to end so there's zero ghosting under wave)
                val inactiveStart = (activeWidthPx + strokePx * 0.5f).coerceAtMost(totalWidthPx)
                if (inactiveStart < totalWidthPx) {
                    drawLine(
                        color = inactiveColor,
                        start = Offset(inactiveStart, centerY),
                        end = Offset(totalWidthPx, centerY),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round
                    )
                }

                // Active M3 Expressive wavy progress path
                if (activeWidthPx > 1f) {
                    val amp = maxAmplitudePx * waveAmplitudeFraction
                    if (amp < 0.25f) {
                        drawLine(
                            color = activeColor,
                            start = Offset(0f, centerY),
                            end = Offset(activeWidthPx, centerY),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round
                        )
                    } else {
                        val wavePath = Path().apply {
                            val step = 4f
                            var x = 0f
                            val startY = centerY + sin(wavePhase) * amp
                            moveTo(0f, startY)
                            while (x < activeWidthPx) {
                                x = (x + step).coerceAtMost(activeWidthPx)
                                // Smooth envelope near the thumb so the wave flows cleanly into the thumb
                                val edgeTaper = ((activeWidthPx - x) / (wavelengthPx * 0.4f)).coerceIn(0f, 1f)
                                val y = centerY + sin((x / wavelengthPx) * (2f * PI.toFloat()) - wavePhase) * amp * edgeTaper
                                lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = wavePath,
                            color = activeColor,
                            style = Stroke(
                                width = strokePx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                // Glowing bookmark pins along the track
                for (bMs in bookmarks) {
                    val bFrac = (bMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                    val bx = bFrac * totalWidthPx
                    drawCircle(
                        color = Color.White,
                        radius = 2.6.dp.toPx(),
                        center = Offset(bx, centerY)
                    )
                }
            }

            if (isDragging) {
                val currentDragMs = (dragFraction * safeDuration).toLong()
                val bubbleOffset = (activeWidthPx - 36f).coerceIn(0f, (totalWidthPx - 72f).coerceAtLeast(0f))
                Box(
                    modifier = Modifier
                        .offset { IntOffset(bubbleOffset.roundToInt(), -56) }
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = Song.formatDuration(currentDragMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }

            val thumbWidthPx = with(density) { thumbWidth.toPx() }
            val thumbOffsetPx = (activeWidthPx - (thumbWidthPx / 2f)).coerceIn(
                0f,
                (totalWidthPx - thumbWidthPx).coerceAtLeast(0f)
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
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
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "-${Song.formatDuration(remainingMs)}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
