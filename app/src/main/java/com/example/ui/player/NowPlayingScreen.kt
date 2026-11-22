package com.example.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.Song
import com.example.online.YouTubeStreamManager
import com.example.ui.animation.FluidMotionConstants
import com.example.ui.animation.HapticType
import com.example.ui.animation.calculateRubberBandOffset
import com.example.ui.animation.fluidPress
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AmbientGeometricShapesOverlay
import com.example.ui.components.AppIcons
import com.example.ui.components.AudioReactiveShaderLayer
import com.example.ui.components.AudioVisualizer
import com.example.ui.components.BlurredArtworkBackdrop
import com.example.ui.components.ExpressiveHorizontalFloatingToolbar
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.ExpressiveSplitButton
import com.example.ui.components.FloatingToolbarStyle
import com.example.ui.components.InteractiveProgressBar
import com.example.ui.components.MorphingPlayPauseButton
import com.example.ui.components.ToolbarActionItem
import com.example.ui.components.TrackArtwork
import com.example.ui.components.VinylRecordArtwork
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackState,
    onCollapse: () -> Unit,
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
    reducedMotion: Boolean = false,
    hideArtwork: Boolean = false,
    isTransitioning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentSong = playbackState.currentSong ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val profile = LocalPerformanceProfile.current

    var showSpeedPitchSheet by remember { mutableStateOf(false) }
    val artOffsetX = remember { Animatable(0f) }
    var isForwardTransition by remember { mutableStateOf(true) }
    var thresholdHapticFired by remember { mutableStateOf(false) }

    var showVolumeHud by remember { mutableStateOf(false) }
    var volumeHudLevel by remember { mutableFloatStateOf(currentVolume) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.50f),
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                delay(1200L)
                                showVolumeHud = false
                            }
                        },
                        onDragCancel = {
                            showVolumeHud = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            val screenWidth = size.width
                            if (change.position.x > screenWidth * 0.70f) {
                                change.consume()
                                showVolumeHud = true
                                val step = -dragAmount / 350f
                                val updated = (volumeHudLevel + step).coerceIn(0f, 1f)
                                volumeHudLevel = updated
                                onVolumeChanged(updated)
                            }
                        }
                    )
                }
        ) {
            BlurredArtworkBackdrop(
                artworkUri = currentSong.albumArtUriString,
                blurRadius = 14.dp,
                allowLiveBlur = !isTransitioning,
                artworkAlpha = 0.52f,
                scrimTopColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
                scrimMidColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f),
                scrimBottomColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                fallbackBaseColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxSize()
            )

            if (!reducedMotion) {
                AmbientGeometricShapesOverlay(
                    isPlaying = playbackState.isPlaying,
                    reducedMotion = reducedMotion || isTransitioning,
                    alphaMultiplier = 1.25f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (audioReactiveShadersEnabled && !reducedMotion) {
                AudioReactiveShaderLayer(
                    isPlaying = playbackState.isPlaying,
                    songId = currentSong.id,
                    durationMs = playbackState.durationMs,
                    positionProvider = { playbackPositionFlow?.value ?: playbackState.currentPositionMs },
                    shadersEnabled = audioReactiveShadersEnabled,
                    reducedMotion = reducedMotion,
                    isPaused = isTransitioning,
                    touchOffsetProvider = { Offset((artOffsetX.value / 140f).coerceIn(-1f, 1f), 0f) },
                    intensity = if (playbackState.isPlaying) 0.56f else 0.20f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "СЕЙЧАС ИГРАЕТ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (currentSong.album.isNotBlank()) {
                                Text(
                                    text = currentSong.album,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onCollapse()
                            },
                            modifier = Modifier.testTag("now_playing_collapse")
                        ) {
                            Icon(
                                imageVector = AppIcons.ExpandMore,
                                contentDescription = "Свернуть",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onOpenCarMode()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DirectionsCar,
                                contentDescription = "Режим вождения",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onOpenSleepTimer()
                            }
                        ) {
                            Icon(
                                imageVector = AppIcons.SleepTimer,
                                contentDescription = "Таймер сна",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                showSpeedPitchSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = AppIcons.Speed,
                                contentDescription = "Скорость и тональность",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onOpenAudioInspector()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Характеристики аудио",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onOpenBookmarks()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BookmarkBorder,
                                contentDescription = "Аудио-закладки",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 20f) {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onCollapse()
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 380.dp)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            alpha = if (hideArtwork) 0f else 1f
                            if (!reducedMotion && !profile.isPotato) {
                                cameraDistance = 14f * density
                                rotationY = (artOffsetX.value / 11f).coerceIn(-16f, 16f)
                                val scaleLift = 1f - (abs(artOffsetX.value) / 1400f).coerceIn(0f, 0.05f)
                                scaleX = scaleLift
                                scaleY = scaleLift
                            }
                        }
                        .offset { IntOffset(artOffsetX.value.roundToInt(), 0) }
                        .pointerInput(currentSong.id, swipeDirectionPref) {
                            val tracker = VelocityTracker()
                            var rawDragX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    rawDragX = 0f
                                    thresholdHapticFired = false
                                    tracker.resetTracking()
                                },
                                onDragEnd = {
                                    val vx = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
                                    val action = SwipeTrackPolicy.resolveHorizontalSwipeAction(
                                        dragOffsetPx = artOffsetX.value,
                                        velocityPxPerSec = vx,
                                        thresholdPx = 68f,
                                        swipeDirectionPref = swipeDirectionPref
                                    )
                                    when (action) {
                                        SwipeTrackAction.PREVIOUS -> {
                                            isForwardTransition = false
                                            performHapticFeedback(view, HapticType.MEDIUM)
                                            onSkipPrevious()
                                        }
                                        SwipeTrackAction.NEXT -> {
                                            isForwardTransition = true
                                            performHapticFeedback(view, HapticType.MEDIUM)
                                            onSkipNext()
                                        }
                                        SwipeTrackAction.NONE -> Unit
                                    }
                                    scope.launch {
                                        artOffsetX.animateTo(0f, FluidMotionConstants.dragSpring())
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { artOffsetX.animateTo(0f) }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    rawDragX += dragAmount
                                    val damped = calculateRubberBandOffset(rawDragX, 0.42f)
                                    if (!thresholdHapticFired && abs(damped) >= 68f) {
                                        thresholdHapticFired = true
                                        performHapticFeedback(view, HapticType.TOGGLE)
                                    }
                                    scope.launch {
                                        artOffsetX.snapTo(damped)
                                    }
                                }
                            )
                        }
                        .pointerInput(currentSong.id) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    val boxWidth = size.width.toFloat()
                                    if (offset.x < boxWidth * 0.35f) {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onRewind10()
                                    } else if (offset.x > boxWidth * 0.65f) {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onForward10()
                                    } else {
                                        performHapticFeedback(view, HapticType.TOGGLE)
                                        onToggleFavorite(currentSong.id)
                                    }
                                },
                                onLongPress = {
                                    performHapticFeedback(view, HapticType.HEAVY)
                                    onOpenAudioInspector()
                                }
                            )
                        }
                        .testTag("now_playing_artwork_stage"),
                    contentAlignment = Alignment.Center
                ) {
                    if (vinylModeEnabled) {
                        VinylRecordArtwork(
                            artUriString = currentSong.albumArtUriString,
                            isPlaying = playbackState.isPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val artworkCorner by animateDpAsState(
                            targetValue = if (playbackState.isPlaying) 30.dp else 20.dp,
                            animationSpec = SpringTokens.spatialDefault(),
                            label = "now_playing_art_corner"
                        )
                        val shadowElevation = when {
                            profile.isPotato || isTransitioning -> 0.dp
                            playbackState.isPlaying -> 18.dp
                            else -> 8.dp
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .let {
                                    if (shadowElevation > 0.dp) {
                                        it.shadow(
                                            elevation = shadowElevation,
                                            shape = RoundedCornerShape(artworkCorner),
                                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                                        )
                                    } else it
                                }
                                .clip(RoundedCornerShape(artworkCorner))
                        ) {
                            AnimatedContent(
                                targetState = currentSong,
                                transitionSpec = {
                                    if (isForwardTransition) {
                                        (slideInHorizontally(FluidMotionConstants.artworkSpring()) { width -> (width * 0.32f).toInt() } + fadeIn(FluidMotionConstants.pressSpring())) togetherWith
                                        (slideOutHorizontally(FluidMotionConstants.artworkSpring()) { width -> (-width * 0.32f).toInt() } + fadeOut(FluidMotionConstants.pressSpring()))
                                    } else {
                                        (slideInHorizontally(FluidMotionConstants.artworkSpring()) { width -> (-width * 0.32f).toInt() } + fadeIn(FluidMotionConstants.pressSpring())) togetherWith
                                        (slideOutHorizontally(FluidMotionConstants.artworkSpring()) { width -> (width * 0.32f).toInt() } + fadeOut(FluidMotionConstants.pressSpring()))
                                    }
                                },
                                label = "now_playing_art_anim"
                            ) { song ->
                                TrackArtwork(
                                    artUriString = song.albumArtUriString,
                                    contentDescription = song.title,
                                    cornerRadius = artworkCorner,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // Swipe track preview badge floating over artwork during horizontal drag
                    val swipeHint = SwipeTrackPolicy.resolveDragDirectionHint(
                        dragOffsetPx = artOffsetX.value,
                        swipeDirectionPref = swipeDirectionPref
                    )
                    val swipeTargetSong = SwipeTrackPolicy.previewTargetSong(
                        queue = playbackState.queue,
                        currentIndex = playbackState.currentQueueIndex,
                        action = swipeHint
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = swipeHint != SwipeTrackAction.NONE && abs(artOffsetX.value) > 18f,
                        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.9f),
                        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.9f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    ) {
                        val badgeText = when (swipeHint) {
                            SwipeTrackAction.NEXT -> if (swipeTargetSong != null) "Следующий: ${swipeTargetSong.title} →" else "Следующий →"
                            SwipeTrackAction.PREVIOUS -> if (swipeTargetSong != null) "← Назад: ${swipeTargetSong.title}" else "← Предыдущий"
                            SwipeTrackAction.NONE -> ""
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            shadowElevation = 6.dp
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Box(modifier = Modifier.padding(horizontal = 32.dp)) {
                    LiveAudioVisualizerWrapper(
                        playbackPositionFlow = playbackPositionFlow,
                        isPlaying = playbackState.isPlaying,
                        fallbackPositionMs = playbackState.currentPositionMs,
                        durationMs = playbackState.durationMs,
                        songId = currentSong.id
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("now_playing_title")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentSong.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("now_playing_artist")
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.TOGGLE)
                            onToggleFavorite(currentSong.id)
                        }
                    ) {
                        Icon(
                            imageVector = if (currentSong.isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                            contentDescription = if (currentSong.isFavorite) "Удалить из избранного" else "В избранное",
                            tint = if (currentSong.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stream Engine SplitButton / Buffering / Error & A-B Repeat Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasStreamError = !playbackState.isBuffering && !playbackState.errorMessage.isNullOrBlank()
                    if (playbackState.isBuffering) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.example.ui.components.ExpressiveLoadingIndicator(
                                    size = 22.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Загрузка потока...",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else if (hasStreamError) {
                        ExpressiveSplitButton(
                            label = "Повторить",
                            leadingIcon = Icons.Rounded.SwapHoriz,
                            onPrimaryClick = onRetryPlayback,
                            onMenuClick = onOpenSourceSwitcher,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            primaryTestTag = "now_playing_retry_btn",
                            menuTestTag = "now_playing_source_switcher_btn"
                        )
                    } else {
                        val isOnline = activeStreamEngine != "local" ||
                            currentSong.contentUriString.startsWith("online://") ||
                            currentSong.contentUriString.startsWith("http")
                        val engineLabel = if (isOnline) {
                            YouTubeStreamManager.formatEngineDisplay(activeStreamEngine)
                        } else {
                            "Studio DSP"
                        }
                        ExpressiveSplitButton(
                            label = engineLabel,
                            leadingIcon = if (isOnline) Icons.Rounded.CloudQueue else Icons.Rounded.GraphicEq,
                            onPrimaryClick = onOpenAudioInspector,
                            onMenuClick = onOpenSourceSwitcher,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.primary,
                            primaryTestTag = "now_playing_inspector_btn",
                            menuTestTag = "now_playing_source_switcher_btn"
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (autoDjEnabled) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.80f),
                            border = BorderStroke(
                                1.dp,
                                if (autoDjEnabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .clickable {
                                    performHapticFeedback(view, HapticType.TOGGLE)
                                    onToggleAutoDj()
                                }
                                .testTag("now_playing_autodj_chip")
                        ) {
                            Text(
                                text = if (autoDjEnabled) "Auto-DJ • Вкл" else "Auto-DJ",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (autoDjEnabled) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = if (abPointA != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.clickable {
                                performHapticFeedback(view, HapticType.LIGHT)
                                when {
                                    abPointA == null -> onSetABPointA()
                                    abPointB == null -> onSetABPointB()
                                    else -> onClearABRepeat()
                                }
                            }
                        ) {
                            val abText = when {
                                abPointA == null -> "A-B Цикл"
                                abPointB == null -> "A: %02d:%02d → B?".format(abPointA / 60000, (abPointA % 60000) / 1000)
                                else -> "A ⇄ B ✕"
                            }
                            Text(
                                text = abText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (abPointA != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    LiveInteractiveProgressBarWrapper(
                        playbackPositionFlow = playbackPositionFlow,
                        fallbackPositionMs = playbackState.currentPositionMs,
                        durationMs = playbackState.durationMs,
                        isPlaying = playbackState.isPlaying,
                        onSeekTo = onSeekTo,
                        bookmarks = bookmarks
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle -> Continuously morphing M3 geometric badge with tonal border
                    val shuffleShape = com.example.ui.components.rememberContinuousMorphShape(
                        startIndex = 4,
                        cycleDurationMs = 9600,
                        rotate = playbackState.shuffleModeEnabled,
                        paused = isTransitioning || reducedMotion
                    )
                    Surface(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onToggleShuffle()
                        },
                        shape = shuffleShape,
                        color = if (playbackState.shuffleModeEnabled) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (playbackState.shuffleModeEnabled) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                            }
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_shuffle")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = AppIcons.Shuffle,
                                contentDescription = "Случайный порядок",
                                tint = if (playbackState.shuffleModeEnabled) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                }
                            )
                        }
                    }

                    // Previous -> Continuously morphing geometric container with crisp tonal rim
                    val prevShape = com.example.ui.components.rememberContinuousMorphShape(
                        startIndex = 7,
                        cycleDurationMs = 10400,
                        rotate = false,
                        paused = isTransitioning || reducedMotion
                    )
                    Surface(
                        shape = prevShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .size(56.dp)
                            .fluidPress(
                                scaleDown = 0.90f,
                                hapticType = HapticType.LIGHT,
                                onClick = onSkipPrevious
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = AppIcons.Previous,
                                contentDescription = "Предыдущий",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    MorphingPlayPauseButton(
                        isPlaying = playbackState.isPlaying,
                        isBuffering = playbackState.isBuffering,
                        onClick = onPlayPause,
                        width = 90.dp,
                        height = 66.dp,
                        iconSize = 32.dp,
                        cornerRadius = 22.dp,
                        modifier = Modifier.testTag("now_playing_play_pause")
                    )

                    // Next -> Continuously morphing geometric container with crisp tonal rim
                    val nextShape = com.example.ui.components.rememberContinuousMorphShape(
                        startIndex = 3,
                        cycleDurationMs = 10400,
                        rotate = false,
                        paused = isTransitioning || reducedMotion
                    )
                    Surface(
                        shape = nextShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .size(56.dp)
                            .fluidPress(
                                scaleDown = 0.90f,
                                hapticType = HapticType.LIGHT,
                                onClick = onSkipNext
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = AppIcons.Next,
                                contentDescription = "Следующий",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Repeat -> Continuously morphing Clover badge with tonal border
                    val repeatShape = com.example.ui.components.rememberContinuousMorphShape(
                        startIndex = 2,
                        cycleDurationMs = 9600,
                        rotate = playbackState.repeatMode != RepeatMode.OFF,
                        paused = isTransitioning || reducedMotion
                    )
                    Surface(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onCycleRepeatMode()
                        },
                        shape = repeatShape,
                        color = if (playbackState.repeatMode != RepeatMode.OFF) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (playbackState.repeatMode != RepeatMode.OFF) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                            }
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_repeat")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val repeatIcon = when (playbackState.repeatMode) {
                                RepeatMode.ONE -> AppIcons.RepeatOne
                                else -> AppIcons.Repeat
                            }
                            val repeatTint = when (playbackState.repeatMode) {
                                RepeatMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                            }
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Режим повтора",
                                tint = repeatTint
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // M3 Expressive Floating Action Toolbar with detached Vibrant Lyrics FAB
                ExpressiveHorizontalFloatingToolbar(
                    actions = listOf(
                        ToolbarActionItem(
                            icon = AppIcons.Queue,
                            contentDescription = "Очередь воспроизведения",
                            testTag = "now_playing_queue",
                            onClick = onOpenQueue
                        ),
                        ToolbarActionItem(
                            icon = AppIcons.Equalizer,
                            contentDescription = "Эквалайзер",
                            testTag = "now_playing_equalizer",
                            onClick = onOpenEqualizer
                        ),
                        ToolbarActionItem(
                            icon = Icons.Rounded.Album,
                            contentDescription = "Режим винила",
                            isActive = vinylModeEnabled,
                            onClick = onToggleVinylMode
                        ),
                        ToolbarActionItem(
                            icon = Icons.Rounded.SwapHoriz,
                            contentDescription = "Источник и версии",
                            testTag = "now_playing_toolbar_source",
                            onClick = onOpenSourceSwitcher
                        ),
                        ToolbarActionItem(
                            icon = AppIcons.PlaylistAdd,
                            contentDescription = "Добавить в плейлист",
                            testTag = "now_playing_add_to_playlist",
                            onClick = { onAddToPlaylist(currentSong) }
                        )
                    ),
                    style = FloatingToolbarStyle.STANDARD,
                    trailingFab = {
                        val lyricsShape = com.example.ui.components.rememberContinuousMorphShape(
                            startIndex = 5,
                            cycleDurationMs = 8800,
                            rotate = true,
                            paused = isTransitioning || reducedMotion
                        )
                        Surface(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onOpenLyrics()
                            },
                            shape = lyricsShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            tonalElevation = if (profile.isPotato) 1.dp else 4.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                            modifier = Modifier
                                .size(58.dp)
                                .testTag("now_playing_lyrics_fab")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Lyrics,
                                    contentDescription = "Текст песни",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            AnimatedVisibility(
                visible = showVolumeHud,
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.85f),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shadowElevation = if (profile.isPotato) 2.dp else 10.dp,
                    modifier = Modifier.width(46.dp).height(180.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            imageVector = when {
                                volumeHudLevel <= 0.02f -> Icons.Rounded.VolumeMute
                                volumeHudLevel < 0.5f -> Icons.Rounded.VolumeDown
                                else -> Icons.Rounded.VolumeUp
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )

                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxSize(volumeHudLevel)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }

                        Text(
                            text = "${(volumeHudLevel * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    if (showSpeedPitchSheet) {
        SpeedPitchBottomSheet(
            currentSpeed = currentSpeed,
            currentPitchSemitones = currentPitchSemitones,
            currentPitchCents = currentPitchCents,
            onSpeedChanged = onSpeedChanged,
            onPitchChanged = onPitchChanged,
            onPitchCentsChanged = onPitchCentsChanged,
            onDismiss = { showSpeedPitchSheet = false }
        )
    }
}

@Composable
private fun LiveAudioVisualizerWrapper(
    playbackPositionFlow: StateFlow<Long>?,
    isPlaying: Boolean,
    fallbackPositionMs: Long,
    durationMs: Long,
    songId: Long
) {
    AudioVisualizer(
        isPlaying = isPlaying,
        currentPositionMs = fallbackPositionMs,
        durationMs = durationMs,
        songId = songId,
        barCount = 28,
        barHeight = 32.dp,
        primaryColor = MaterialTheme.colorScheme.primary,
        secondaryColor = MaterialTheme.colorScheme.tertiary,
        currentPositionProvider = { playbackPositionFlow?.value ?: fallbackPositionMs }
    )
}

@Composable
private fun LiveInteractiveProgressBarWrapper(
    playbackPositionFlow: StateFlow<Long>?,
    fallbackPositionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    bookmarks: List<Long>
) {
    val livePos by (playbackPositionFlow?.collectAsStateWithLifecycle()
        ?: remember(fallbackPositionMs) { mutableStateOf(fallbackPositionMs) })

    InteractiveProgressBar(
        currentPositionMs = livePos,
        durationMs = durationMs,
        isPlaying = isPlaying,
        onSeekTo = onSeekTo,
        bookmarks = bookmarks
    )
}
