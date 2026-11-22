package com.example.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.model.PlaybackState
import com.example.model.Song
import com.example.ui.animation.HapticType
import com.example.ui.animation.calculateRubberBandOffset
import com.example.ui.animation.fluidPress
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AppIcons
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.LinearWavyProgressIndicator
import com.example.ui.components.MiniPlayerLiveSpectrum
import com.example.ui.components.MiniPlayerReactiveAura
import com.example.ui.components.MorphingPlayPauseButton
import com.example.ui.components.TrackArtwork
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pure GPU-composited Morphing Container: Asymmetric 3-Corner Mini Player Capsule <-> Fullscreen Now Playing.
 *
 * Reworked opening architecture:
 * - Zero per-frame layout re-measurement (`height(lerp)` removed)
 * - Zero per-frame Compose recompositions (`expansionAnim.value` read inside `graphicsLayer` only)
 * - Zero per-frame shadow path re-tessellation
 * - NowPlayingScreen stays measured at fixed full-screen bounds and rises smoothly from the bottom
 *   capsule via GPU TransformOrigin(0.5f, 0.92f) scale + translationY + alpha.
 */
@Composable
fun ExpandingPlayerContainer(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenLyrics: () -> Unit = {},
    onOpenAudioInspector: () -> Unit = {},
    onOpenCarMode: () -> Unit = {},
    bookmarks: List<Long> = emptyList(),
    onOpenBookmarks: () -> Unit = {},
    currentSpeed: Float = 1.0f,
    currentPitchSemitones: Int = 0,
    currentPitchCents: Int = 0,
    onSpeedChanged: (Float) -> Unit = {},
    onPitchChanged: (Int) -> Unit = {},
    onPitchCentsChanged: (Int) -> Unit = {},
    abPointA: Long? = null,
    abPointB: Long? = null,
    onSetABPointA: () -> Unit = {},
    onSetABPointB: () -> Unit = {},
    onClearABRepeat: () -> Unit = {},
    vinylModeEnabled: Boolean = false,
    onToggleVinylMode: () -> Unit = {},
    currentVolume: Float = 1.0f,
    onVolumeChanged: (Float) -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    activeStreamEngine: String = "local",
    onOpenSourceSwitcher: () -> Unit = {},
    playbackPositionFlow: StateFlow<Long>? = null,
    swipeDirectionPref: String = SwipeTrackPolicy.DIR_RIGHT_NEXT_LEFT_PREV,
    audioReactiveShadersEnabled: Boolean = true,
    autoDjEnabled: Boolean = false,
    onToggleAutoDj: () -> Unit = {},
    miniSpectrumMode: String = "BARS",
    onCycleMiniSpectrumMode: () -> Unit = {},
    collapsedStartInset: Dp = 14.dp,
    collapsedEndInset: Dp = 14.dp,
    collapsedBottomOffset: Dp = 80.dp,
    reducedMotion: Boolean = false,
    onExpansionFractionChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val profile = LocalPerformanceProfile.current
    val density = LocalDensity.current

    BackHandler(enabled = isExpanded) {
        onExpandedChange(false)
    }

    val expansionAnim = remember { Animatable(if (isExpanded) 1f else 0f) }

    LaunchedEffect(isExpanded, reducedMotion) {
        val target = if (isExpanded) 1f else 0f
        if (abs(expansionAnim.value - target) > 0.001f) {
            if (reducedMotion) {
                expansionAnim.snapTo(target)
            } else {
                expansionAnim.animateTo(
                    targetValue = target,
                    animationSpec = if (isExpanded) {
                        tween(durationMillis = 360, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))
                    } else {
                        tween(durationMillis = 260, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
                    }
                )
            }
        }
    }

    LaunchedEffect(expansionAnim) {
        snapshotFlow { expansionAnim.value }
            .distinctUntilChanged()
            .collect { fraction ->
                onExpansionFractionChanged(fraction.coerceIn(0f, 1f))
            }
    }

    val isFullPlayerVisible by remember {
        derivedStateOf { isExpanded || expansionAnim.value > 0.005f }
    }
    val isMiniPlayerVisible by remember {
        derivedStateOf { !isExpanded || expansionAnim.value < 0.92f }
    }
    val isTransitioning by remember {
        derivedStateOf { expansionAnim.value > 0.005f && expansionAnim.value < 0.97f }
    }

    var showMiniMenu by remember { mutableStateOf(false) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Horizontal mini-player swipe state
    val miniHorizontalOffsetPx = remember { Animatable(0f) }
    var miniLastSwipeForward by remember { mutableStateOf(true) }
    var miniThresholdHapticFired by remember { mutableStateOf(false) }

    val asymmetricMiniShape = remember {
        RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomStart = 28.dp,
            bottomEnd = 6.dp
        )
    }
    val expandingSheetClipShape = remember { RoundedCornerShape(28.dp) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeight = maxHeight
        val totalHeightPx = with(density) { (totalHeight - collapsedBottomOffset).coerceAtLeast(240.dp).toPx() }
        val swipeThresholdPx = with(density) { 56.dp.toPx() }

        // Scrim backdrop behind rising fullscreen player (drawn in Canvas-free graphicsLayer alpha)
        if (isFullPlayerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (expansionAnim.value * 0.55f).coerceIn(0f, 0.55f)
                    }
                    .background(Color.Black)
            )
        }

        // Fixed-bounds Fullscreen NowPlayingSheet animated purely via GPU graphicsLayer
        if (isFullPlayerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = expansionAnim.value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                        scaleX = lerp(0.92f, 1.0f, f)
                        scaleY = lerp(0.84f, 1.0f, f)
                        translationY = (1f - f) * (totalHeightPx * 0.18f)
                        alpha = (f * 1.6f).coerceIn(0f, 1f)
                        if (f < 0.992f) {
                            clip = true
                            shape = expandingSheetClipShape
                        } else {
                            clip = false
                        }
                    }
                    .testTag("now_playing_container")
            ) {
                NowPlayingScreen(
                    playbackState = playbackState,
                    onCollapse = { onExpandedChange(false) },
                    onPlayPause = onPlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSeekTo = onSeekTo,
                    onRewind10 = onRewind10,
                    onForward10 = onForward10,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeatMode = onCycleRepeatMode,
                    onToggleFavorite = onToggleFavorite,
                    onOpenQueue = onOpenQueue,
                    onOpenEqualizer = onOpenEqualizer,
                    onAddToPlaylist = onAddToPlaylist,
                    onOpenSleepTimer = onOpenSleepTimer,
                    onOpenLyrics = onOpenLyrics,
                    onOpenAudioInspector = onOpenAudioInspector,
                    onOpenCarMode = onOpenCarMode,
                    bookmarks = bookmarks,
                    onOpenBookmarks = onOpenBookmarks,
                    currentSpeed = currentSpeed,
                    currentPitchSemitones = currentPitchSemitones,
                    currentPitchCents = currentPitchCents,
                    onSpeedChanged = onSpeedChanged,
                    onPitchChanged = onPitchChanged,
                    onPitchCentsChanged = onPitchCentsChanged,
                    abPointA = abPointA,
                    abPointB = abPointB,
                    onSetABPointA = onSetABPointA,
                    onSetABPointB = onSetABPointB,
                    onClearABRepeat = onClearABRepeat,
                    vinylModeEnabled = vinylModeEnabled,
                    onToggleVinylMode = onToggleVinylMode,
                    currentVolume = currentVolume,
                    onVolumeChanged = onVolumeChanged,
                    onRetryPlayback = onRetryPlayback,
                    activeStreamEngine = activeStreamEngine,
                    onOpenSourceSwitcher = onOpenSourceSwitcher,
                    playbackPositionFlow = playbackPositionFlow,
                    swipeDirectionPref = swipeDirectionPref,
                    audioReactiveShadersEnabled = audioReactiveShadersEnabled,
                    autoDjEnabled = autoDjEnabled,
                    onToggleAutoDj = onToggleAutoDj,
                    reducedMotion = reducedMotion,
                    hideArtwork = false,
                    isTransitioning = isTransitioning,
                    modifier = Modifier.fillMaxSize()
                )

                // Top drag-to-collapse handle zone when expanded
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusBarTop + 56.dp)
                        .pointerInput(totalHeightPx) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    if (dragAmount > 0f || expansionAnim.value < 0.99f) {
                                        change.consume()
                                        val delta = -dragAmount / totalHeightPx
                                        scope.launch {
                                            expansionAnim.snapTo((expansionAnim.value + delta).coerceIn(0f, 1f))
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val keepExpanded = expansionAnim.value > 0.78f
                                    if (!keepExpanded) {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                    }
                                    onExpandedChange(keepExpanded)
                                    scope.launch {
                                        expansionAnim.animateTo(
                                            if (keepExpanded) 1f else 0f,
                                            SpringTokens.spatialDefault()
                                        )
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        expansionAnim.animateTo(if (isExpanded) 1f else 0f)
                                    }
                                }
                            )
                        }
                )
            }
        }

        // Fixed-height Asymmetric 3-Corner Mini-Player Capsule
        if (isMiniPlayerVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = collapsedStartInset,
                        end = collapsedEndInset,
                        bottom = collapsedBottomOffset
                    )
                    .height(76.dp)
                    .graphicsLayer {
                        val f = expansionAnim.value.coerceIn(0f, 1f)
                        alpha = (1f - f * 2.4f).coerceIn(0f, 1f)
                        translationY = -f * 72f
                        scaleX = 1f + f * 0.05f
                        scaleY = 1f + f * 0.10f
                    }
            ) {
                // Directional swipe preview pill hovering above mini-player during horizontal drag
                val dragHint = SwipeTrackPolicy.resolveDragDirectionHint(
                    dragOffsetPx = miniHorizontalOffsetPx.value,
                    swipeDirectionPref = swipeDirectionPref
                )
                val previewSong = SwipeTrackPolicy.previewTargetSong(
                    queue = playbackState.queue,
                    currentIndex = playbackState.currentQueueIndex,
                    action = dragHint
                )
                val showPreviewPill = dragHint != SwipeTrackAction.NONE && abs(miniHorizontalOffsetPx.value) > 16f
                AnimatedVisibility(
                    visible = showPreviewPill,
                    enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.88f),
                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.88f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-34).dp)
                ) {
                    val label = when (dragHint) {
                        SwipeTrackAction.NEXT -> if (previewSong != null) "След: ${previewSong.title} →" else "Следующий →"
                        SwipeTrackAction.PREVIOUS -> if (previewSong != null) "← Пред: ${previewSong.title}" else "← Предыдущий"
                        SwipeTrackAction.NONE -> ""
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val dragX = miniHorizontalOffsetPx.value
                            translationX = dragX
                            rotationZ = (dragX / 48f).coerceIn(-3.5f, 3.5f)
                        }
                        .clip(asymmetricMiniShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onExpandedChange(true)
                        }
                        .pointerInput(totalHeightPx, swipeDirectionPref, song.id) {
                            var axisLock = 0
                            var rawHorizontal = 0f
                            val velocityTracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = {
                                    axisLock = 0
                                    rawHorizontal = 0f
                                    miniThresholdHapticFired = false
                                    velocityTracker.resetTracking()
                                },
                                onDrag = { change, dragAmount ->
                                    if (axisLock == 0) {
                                        axisLock = if (abs(dragAmount.x) >= abs(dragAmount.y) * 1.1f) 1 else 2
                                    }
                                    change.consume()
                                    if (axisLock == 1) {
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        rawHorizontal += dragAmount.x
                                        val damped = calculateRubberBandOffset(rawHorizontal, 0.52f)
                                        if (!miniThresholdHapticFired && abs(damped) >= swipeThresholdPx) {
                                            miniThresholdHapticFired = true
                                            performHapticFeedback(view, HapticType.TOGGLE)
                                        }
                                        scope.launch {
                                            miniHorizontalOffsetPx.snapTo(damped)
                                        }
                                    } else {
                                        val delta = -dragAmount.y / totalHeightPx
                                        scope.launch {
                                            expansionAnim.snapTo((expansionAnim.value + delta).coerceIn(0f, 1f))
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (axisLock == 1) {
                                        val vx = runCatching { velocityTracker.calculateVelocity().x }.getOrDefault(0f)
                                        val action = SwipeTrackPolicy.resolveHorizontalSwipeAction(
                                            dragOffsetPx = miniHorizontalOffsetPx.value,
                                            velocityPxPerSec = vx,
                                            thresholdPx = swipeThresholdPx,
                                            swipeDirectionPref = swipeDirectionPref
                                        )
                                        when (action) {
                                            SwipeTrackAction.NEXT -> {
                                                miniLastSwipeForward = true
                                                performHapticFeedback(view, HapticType.MEDIUM)
                                                onSkipNext()
                                            }
                                            SwipeTrackAction.PREVIOUS -> {
                                                miniLastSwipeForward = false
                                                performHapticFeedback(view, HapticType.MEDIUM)
                                                onSkipPrevious()
                                            }
                                            SwipeTrackAction.NONE -> Unit
                                        }
                                        scope.launch {
                                            miniHorizontalOffsetPx.animateTo(0f, SpringTokens.spatialDefault())
                                        }
                                    } else {
                                        val shouldExpand = expansionAnim.value > 0.16f
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onExpandedChange(shouldExpand)
                                        scope.launch {
                                            expansionAnim.animateTo(
                                                if (shouldExpand) 1f else 0f,
                                                tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                            )
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        miniHorizontalOffsetPx.animateTo(0f, SpringTokens.spatialDefault())
                                        expansionAnim.animateTo(if (isExpanded) 1f else 0f)
                                    }
                                }
                            )
                        }
                        .testTag("mini_player"),
                    shape = asymmetricMiniShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.primary.copy(alpha = if (playbackState.isPlaying) 0.65f else 0.30f)
                            )
                        )
                    ),
                    shadowElevation = if (profile.isPotato) 0.dp else 6.dp,
                    tonalElevation = 4.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (audioReactiveShadersEnabled && !profile.isPotato && !reducedMotion) {
                            MiniPlayerReactiveAura(
                                isPlaying = playbackState.isPlaying,
                                songId = song.id,
                                durationMs = playbackState.durationMs,
                                positionProvider = { playbackPositionFlow?.value ?: playbackState.currentPositionMs },
                                reducedMotion = reducedMotion,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        val hasError = !playbackState.isBuffering && !playbackState.errorMessage.isNullOrBlank()
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(48.dp)) {
                                    TrackArtwork(
                                        artUriString = song.albumArtUriString,
                                        contentDescription = song.title,
                                        cornerRadius = 15.dp,
                                        decodeSizePx = profile.thumbnailMaxPx,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (playbackState.isBuffering) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(15.dp))
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ExpressiveLoadingIndicator(size = 26.dp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                AnimatedContent(
                                    targetState = Triple(song.id, song.title, song.artist),
                                    transitionSpec = {
                                        val enter = slideInHorizontally(tween(220)) { w ->
                                            if (miniLastSwipeForward) (w * 0.28f).toInt() else (-w * 0.28f).toInt()
                                        } + fadeIn(tween(180))
                                        val exit = slideOutHorizontally(tween(170)) { w ->
                                            if (miniLastSwipeForward) (-w * 0.25f).toInt() else (w * 0.25f).toInt()
                                        } + fadeOut(tween(140))
                                        enter togetherWith exit
                                    },
                                    label = "mini_player_track_meta",
                                    modifier = Modifier.weight(1f)
                                ) { (_, title, artist) ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = when {
                                                    playbackState.isBuffering -> "Загрузка потока..."
                                                    hasError -> "Ошибка потока • Повторить"
                                                    else -> artist
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when {
                                                    playbackState.isBuffering -> MaterialTheme.colorScheme.primary
                                                    hasError -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (!hasError && !playbackState.isBuffering && !miniSpectrumMode.equals("OFF", true)) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(46.dp)
                                                        .clickable {
                                                            performHapticFeedback(view, HapticType.LIGHT)
                                                            onCycleMiniSpectrumMode()
                                                        }
                                                        .testTag("mini_player_live_spectrum")
                                                ) {
                                                    MiniPlayerLiveSpectrum(
                                                        isPlaying = playbackState.isPlaying,
                                                        currentPositionMs = playbackState.currentPositionMs,
                                                        durationMs = playbackState.durationMs,
                                                        songId = song.id,
                                                        mode = miniSpectrumMode,
                                                        currentPositionProvider = {
                                                            playbackPositionFlow?.value ?: playbackState.currentPositionMs
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                MorphingPlayPauseButton(
                                    isPlaying = playbackState.isPlaying,
                                    isBuffering = playbackState.isBuffering,
                                    onClick = if (hasError) onRetryPlayback else onPlayPause,
                                    size = 44.dp,
                                    iconSize = 20.dp,
                                    cornerRadius = 14.dp
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .fluidPress(
                                            scaleDown = 0.92f,
                                            hapticType = HapticType.LIGHT,
                                            onClick = {
                                                miniLastSwipeForward = true
                                                onSkipNext()
                                            }
                                        )
                                        .testTag("mini_player_next"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Next,
                                        contentDescription = "Следующий",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Box {
                                    IconButton(
                                        onClick = {
                                            performHapticFeedback(view, HapticType.LIGHT)
                                            showMiniMenu = true
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MoreVert,
                                            contentDescription = "Ещё",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMiniMenu,
                                        onDismissRequest = { showMiniMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Развернуть плеер") },
                                            onClick = {
                                                showMiniMenu = false
                                                onExpandedChange(true)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.OpenInFull, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Стиль спектра: $miniSpectrumMode") },
                                            onClick = {
                                                showMiniMenu = false
                                                onCycleMiniSpectrumMode()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Сменить источник / версию") },
                                            onClick = {
                                                showMiniMenu = false
                                                onOpenSourceSwitcher()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Очередь") },
                                            onClick = {
                                                showMiniMenu = false
                                                onOpenQueue()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Эквалайзер") },
                                            onClick = {
                                                showMiniMenu = false
                                                onOpenEqualizer()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Текст песни") },
                                            onClick = {
                                                showMiniMenu = false
                                                onOpenLyrics()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Таймер сна") },
                                            onClick = {
                                                showMiniMenu = false
                                                onOpenSleepTimer()
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (song.isFavorite) "Удалить из избранного" else "В избранное") },
                                            onClick = {
                                                showMiniMenu = false
                                                onToggleFavorite(song.id)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (song.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                    contentDescription = null,
                                                    tint = if (song.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            LinearWavyProgressIndicator(
                                progress = if (playbackState.isBuffering) null else {
                                    {
                                        val pos = playbackPositionFlow?.value ?: playbackState.currentPositionMs
                                        if (playbackState.durationMs > 0) (pos.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                                    }
                                },
                                isPlaying = playbackState.isPlaying || playbackState.isBuffering,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                height = 6.dp,
                                strokeWidth = 2.5.dp,
                                amplitudeDp = 1.5.dp,
                                wavelengthDp = 18.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
