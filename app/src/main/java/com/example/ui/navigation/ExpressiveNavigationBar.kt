package com.example.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.morphPolygonShape
import com.example.ui.components.rememberContinuousMorphShape
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

val MAIN_NAV_DESTINATIONS = listOf(
    NavDestination.HOME,
    NavDestination.SEARCH,
    NavDestination.LIBRARY,
    NavDestination.PLAYLISTS,
    NavDestination.SETTINGS
)

val DOCK_CATEGORY_DESTINATIONS = listOf(
    NavDestination.HOME,
    NavDestination.LIBRARY,
    NavDestination.PLAYLISTS,
    NavDestination.SETTINGS
)

private const val VISUAL_DRAG_FACTOR = 1.0f

/**
 * Material 3 Expressive floating dock adapted with separated "Oval Categories + Detached Circle"
 * geometry:
 * - Left: Elongated M3 Expressive tonal oval capsule containing category tabs (Home, Library, Playlists, Settings)
 *   with direct 1:1 finger-tracked selection indicator and AGSL Flagship rim sheen.
 * - Right: Detached 64.dp circular orbital Search action button with bouncy spring response & geometric wave glow.
 */
@Composable
fun ExpressiveFloatingNavigationBar(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticHelper()
    val destinations = DOCK_CATEGORY_DESTINATIONS
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val selectedIndex = destinations.indexOf(currentDestination)
    val isCategorySelected = selectedIndex >= 0
    val isSearchSelected = currentDestination == NavDestination.SEARCH

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ==========================================
        // LEFT: ELONGATED OVAL CATEGORIES CAPSULE
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1f)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ambientColor = Color.Black.copy(alpha = 0.10f)
                )
                .testTag("floating_navigation_bar"),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
            ),
            shape = CircleShape
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                val totalWidthPx = constraints.maxWidth.toFloat()
                val tabCount = destinations.size
                val slotWidthPx = totalWidthPx / tabCount.toFloat()

                val indicatorWidthPx = slotWidthPx * 0.92f
                val indicatorHeightDp = 46.dp

                val minAllowedLeft = 0f
                val maxAllowedLeft = (totalWidthPx - indicatorWidthPx).coerceAtLeast(0f)

                val indicatorOffsetXPx = remember { Animatable(0f) }
                var dragVisualXPx by remember { mutableFloatStateOf(0f) }
                var isPressed by remember { mutableStateOf(false) }
                var isDragging by remember { mutableStateOf(false) }
                var pressedSlotIndex by remember { mutableStateOf<Int?>(null) }

                val pillScaleAlpha by animateFloatAsState(
                    targetValue = if (isCategorySelected || isDragging) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 440f),
                    label = "dock_pill_visibility"
                )
                val capsuleScaleX by animateFloatAsState(
                    targetValue = when {
                        isDragging -> 1.13f
                        isPressed -> 1.11f
                        else -> 1.0f
                    },
                    animationSpec = spring(dampingRatio = 0.56f, stiffness = 460f),
                    label = "inner_oval_scale_x"
                )
                val capsuleScaleY by animateFloatAsState(
                    targetValue = when {
                        isDragging -> 1.16f
                        isPressed -> 1.13f
                        else -> 1.0f
                    },
                    animationSpec = spring(dampingRatio = 0.56f, stiffness = 460f),
                    label = "inner_oval_scale_y"
                )

                val effectiveIdx = selectedIndex.coerceAtLeast(0)
                LaunchedEffect(effectiveIdx, isCategorySelected, slotWidthPx, indicatorWidthPx, isDragging) {
                    if (!isDragging && isCategorySelected) {
                        val targetCenterPx = (effectiveIdx + 0.5f) * slotWidthPx
                        val targetLeftPx = targetCenterPx - (indicatorWidthPx / 2f)
                        val clampedTarget = targetLeftPx.coerceIn(minAllowedLeft, maxAllowedLeft)
                        indicatorOffsetXPx.animateTo(
                            targetValue = clampedTarget,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 450f)
                        )
                    }
                }

                val indicatorWidthDp = with(density) { indicatorWidthPx.toDp() }

                val targetPressIdx = pressedSlotIndex
                if (targetPressIdx != null && targetPressIdx != selectedIndex && !isDragging) {
                    val pressLeftPx = ((targetPressIdx + 0.5f) * slotWidthPx - indicatorWidthPx / 2f)
                        .coerceIn(minAllowedLeft, maxAllowedLeft)
                    val pressScale by animateFloatAsState(
                        targetValue = 1.08f,
                        animationSpec = spring(dampingRatio = 0.58f, stiffness = 480f),
                        label = "nav_press_ghost_scale"
                    )
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x = pressLeftPx.roundToInt(), y = 0) }
                            .width(indicatorWidthDp)
                            .height(indicatorHeightDp)
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    )
                }

                // Smooth sleek M3 Expressive active category indicator oval
                val dragCompressionX by animateFloatAsState(
                    targetValue = if (isDragging) 1.07f else if (isPressed) 1.04f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 460f),
                    label = "pill_comp_x"
                )
                val dragCompressionY by animateFloatAsState(
                    targetValue = if (isDragging) 0.94f else if (isPressed) 0.96f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 460f),
                    label = "pill_comp_y"
                )

                if (pillScaleAlpha > 0.02f) {
                    Box(
                        modifier = Modifier
                            .offset {
                                val currentX = if (isDragging) dragVisualXPx else indicatorOffsetXPx.value
                                IntOffset(x = currentX.roundToInt(), y = 0)
                            }
                            .width(indicatorWidthDp)
                            .height(indicatorHeightDp)
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                scaleX = capsuleScaleX * dragCompressionX * pillScaleAlpha.coerceIn(0.6f, 1f)
                                scaleY = capsuleScaleY * dragCompressionY * pillScaleAlpha.coerceIn(0.6f, 1f)
                                alpha = pillScaleAlpha
                                shadowElevation = if (isPressed || isDragging) 10f else 1.5f
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(
                                width = if (isPressed || isDragging) 1.25.dp else 0.8.dp,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (isPressed || isDragging) 0.36f else 0.16f
                                ),
                                shape = CircleShape
                            )
                            .testTag("nav_selected_indicator")
                    )
                }

                // 4 Category Slots
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    destinations.forEachIndexed { index, destination ->
                        val isSelected = selectedIndex == index
                        val isSlotPressed = pressedSlotIndex == index
                        val contentColor by animateColorAsState(
                            targetValue = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                isSlotPressed -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                            label = "nav_content_color_${destination.route}"
                        )

                        val tabIconScale by animateFloatAsState(
                            targetValue = when {
                                isSlotPressed -> 1.18f
                                isSelected -> 1.12f
                                else -> 1.0f
                            },
                            animationSpec = spring(dampingRatio = 0.52f, stiffness = 420f),
                            label = "tab_icon_scale_${destination.route}"
                        )
                        val tabIconOffsetY by animateDpAsState(
                            targetValue = if (isSelected) (-2).dp else 0.dp,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
                            label = "tab_icon_offset_y_${destination.route}"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("nav_${destination.route}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .graphicsLayer { translationY = tabIconOffsetY.toPx() },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title,
                                    tint = contentColor,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            scaleX = tabIconScale
                                            scaleY = tabIconScale
                                        }
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = destination.title,
                                    color = contentColor,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected || isSlotPressed) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 10.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Stationary Touch & Drag Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(destinations, slotWidthPx, indicatorWidthPx, totalWidthPx, selectedIndex) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val dragStartFingerX = down.position.x
                                val dragStartOvalX = indicatorOffsetXPx.value

                                val touchedSlot = (down.position.x / slotWidthPx).toInt()
                                    .coerceIn(0, destinations.size - 1)
                                pressedSlotIndex = touchedSlot
                                isPressed = true

                                var isDragStarted = false
                                var currentVisualX = dragStartOvalX
                                var lastHapticIndex = selectedIndex

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break

                                        val currentFingerX = change.position.x
                                        val totalFingerDeltaX = currentFingerX - dragStartFingerX
                                        val totalFingerDeltaY = change.position.y - down.position.y

                                        if (!isDragStarted) {
                                            if (abs(totalFingerDeltaX) > viewConfiguration.touchSlop &&
                                                abs(totalFingerDeltaX) > abs(totalFingerDeltaY)
                                            ) {
                                                isDragStarted = true
                                                isDragging = true
                                                change.consume()
                                                val newX = dragStartOvalX + totalFingerDeltaX * VISUAL_DRAG_FACTOR
                                                currentVisualX = newX.coerceIn(minAllowedLeft, maxAllowedLeft)
                                                dragVisualXPx = currentVisualX
                                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                            } else if (abs(totalFingerDeltaY) > viewConfiguration.touchSlop) {
                                                break
                                            }
                                        } else {
                                            change.consume()
                                            val newX = dragStartOvalX + totalFingerDeltaX * VISUAL_DRAG_FACTOR
                                            currentVisualX = newX.coerceIn(minAllowedLeft, maxAllowedLeft)
                                            dragVisualXPx = currentVisualX

                                            val currentCenter = currentVisualX + indicatorWidthPx / 2f
                                            val hoveredIndex = destinations.indices.minByOrNull { i ->
                                                val tabCenter = (i + 0.5f) * slotWidthPx
                                                abs(tabCenter - currentCenter)
                                            } ?: effectiveIdx
                                            pressedSlotIndex = hoveredIndex

                                            if (hoveredIndex != lastHapticIndex) {
                                                lastHapticIndex = hoveredIndex
                                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                            }
                                        }
                                    }
                                } finally {
                                    isPressed = false
                                    pressedSlotIndex = null
                                    if (isDragStarted) {
                                        isDragging = false
                                        val finalCenter = currentVisualX + indicatorWidthPx / 2f
                                        val closestIndex = destinations.indices.minByOrNull { i ->
                                            val tabCenter = (i + 0.5f) * slotWidthPx
                                            abs(tabCenter - finalCenter)
                                        } ?: effectiveIdx

                                        if (closestIndex != selectedIndex) {
                                            haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                            onNavigate(destinations[closestIndex])
                                        }

                                        scope.launch {
                                            indicatorOffsetXPx.snapTo(currentVisualX)
                                            val targetCenter = (closestIndex + 0.5f) * slotWidthPx
                                            val targetLeft = (targetCenter - indicatorWidthPx / 2f)
                                                .coerceIn(minAllowedLeft, maxAllowedLeft)
                                            indicatorOffsetXPx.animateTo(
                                                targetValue = targetLeft,
                                                animationSpec = spring(dampingRatio = 0.68f, stiffness = 400f)
                                            )
                                        }
                                    } else {
                                        val tappedIndex = (down.position.x / slotWidthPx).toInt()
                                            .coerceIn(0, destinations.size - 1)
                                        if (tappedIndex != selectedIndex) {
                                            haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                            onNavigate(destinations[tappedIndex])
                                        }
                                    }
                                }
                            }
                        }
                )
            }
        }

        // ==========================================
        // RIGHT: DETACHED CIRCULAR SEARCH BUTTON
        // ==========================================
        val searchInteraction = remember { MutableInteractionSource() }
        val searchPressed by searchInteraction.collectIsPressedAsState()
        val searchScale by animateFloatAsState(
            targetValue = when {
                searchPressed -> 0.90f
                isSearchSelected -> 1.07f
                else -> 1.0f
            },
            animationSpec = spring(dampingRatio = 0.54f, stiffness = 460f),
            label = "detached_search_circle_scale"
        )
        val searchBg by animateColorAsState(
            targetValue = if (isSearchSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            animationSpec = tween(180),
            label = "detached_search_bg"
        )
        val searchFg by animateColorAsState(
            targetValue = if (isSearchSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = tween(180),
            label = "detached_search_fg"
        )

        val searchShape = rememberContinuousMorphShape(
            startIndex = if (isSearchSelected) 4 else 3,
            cycleDurationMs = 9000,
            rotate = isSearchSelected
        )
        Surface(
            onClick = {
                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                onNavigate(NavDestination.SEARCH)
            },
            interactionSource = searchInteraction,
            shape = searchShape,
            color = searchBg,
            contentColor = searchFg,
            tonalElevation = if (isSearchSelected) 5.dp else 3.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = if (isSearchSelected) 1.5.dp else 1.dp,
                color = if (isSearchSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
                }
            ),
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = searchScale
                    scaleY = searchScale
                }
                .testTag("nav_${NavDestination.SEARCH.route}")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = NavDestination.SEARCH.icon,
                    contentDescription = NavDestination.SEARCH.title,
                    tint = searchFg,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * Expressive Navigation Rail for Foldables and Tablets.
 */
@Composable
fun ExpressiveNavigationRail(
    currentDestination: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticHelper()
    val destinations = MAIN_NAV_DESTINATIONS

    Surface(
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            destinations.forEach { destination ->
                val isSelected = currentDestination == destination
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                    label = "rail_container_${destination.route}"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 150, easing = LinearEasing),
                    label = "rail_content_${destination.route}"
                )

                Surface(
                    modifier = Modifier
                        .size(width = 64.dp, height = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                onNavigate(destination)
                            }
                        )
                        .testTag("rail_${destination.route}"),
                    color = containerColor,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.title,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = destination.title,
                            color = contentColor,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 10.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
