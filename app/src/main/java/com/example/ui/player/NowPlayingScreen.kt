package com.example.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.Song
import com.example.ui.animation.FluidMotionConstants
import com.example.ui.animation.HapticType
import com.example.ui.animation.calculateRubberBandOffset
import com.example.ui.animation.fluidPress
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AmbientGlowCanvas
import com.example.ui.components.AppIcons
import com.example.ui.components.AudioVisualizer
import com.example.ui.components.InteractiveProgressBar
import com.example.ui.components.MorphingPlayPauseButton
import com.example.ui.components.TrackArtwork
import com.example.ui.components.VinylRecordArtwork
import androidx.compose.material.icons.rounded.BookmarkBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    modifier: Modifier = Modifier
) {
    val currentSong = playbackState.currentSong ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var showSpeedPitchSheet by remember { mutableStateOf(false) }
    val artOffsetX = remember { Animatable(0f) }
    var isForwardTransition by remember { mutableStateOf(true) }

    // Volume HUD state on right edge swipe
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
                // Right-edge Vertical Volume Swipe Handler
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
                            // Only trigger when finger drags in the right 30% of the screen
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
            // Ambient Aura Glow Canvas
            AmbientGlowCanvas(
                isPlaying = playbackState.isPlaying,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top App Bar
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
                        // Car Mode Button
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

                        // Sleep Timer Button
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

                        // Speed & Pitch Button
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

                        // Audio Inspector Button
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

                        // Audio Bookmarks Button
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

                // Artwork or Vinyl Record Mode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .aspectRatio(1f)
                        .offset { IntOffset(artOffsetX.value.roundToInt(), 0) }
                        .pointerInput(currentSong.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val threshold = 70f
                                    if (artOffsetX.value < -threshold) {
                                        // Swipe Left -> Previous Track (user preference)
                                        isForwardTransition = false
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onSkipPrevious()
                                    } else if (artOffsetX.value > threshold) {
                                        // Swipe Right -> Next Track
                                        isForwardTransition = true
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onSkipNext()
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
                                    scope.launch {
                                        artOffsetX.snapTo(calculateRubberBandOffset(artOffsetX.value + dragAmount, 0.25f))
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
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (vinylModeEnabled) {
                        VinylRecordArtwork(
                            artUriString = currentSong.albumArtUriString,
                            isPlaying = playbackState.isPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .shadow(
                                    elevation = 16.dp,
                                    shape = RoundedCornerShape(24.dp),
                                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                                .clip(RoundedCornerShape(24.dp))
                        ) {
                            AnimatedContent(
                                targetState = currentSong,
                                transitionSpec = {
                                    if (isForwardTransition) {
                                        (slideInHorizontally(FluidMotionConstants.artworkSpring()) { width -> (width * 0.3f).toInt() } + fadeIn(FluidMotionConstants.pressSpring())) togetherWith
                                        (slideOutHorizontally(FluidMotionConstants.artworkSpring()) { width -> (-width * 0.3f).toInt() } + fadeOut(FluidMotionConstants.pressSpring()))
                                    } else {
                                        (slideInHorizontally(FluidMotionConstants.artworkSpring()) { width -> (-width * 0.3f).toInt() } + fadeIn(FluidMotionConstants.pressSpring())) togetherWith
                                        (slideOutHorizontally(FluidMotionConstants.artworkSpring()) { width -> (width * 0.3f).toInt() } + fadeOut(FluidMotionConstants.pressSpring()))
                                    }
                                },
                                label = "now_playing_art_anim"
                            ) { song ->
                                TrackArtwork(
                                    artUriString = song.albumArtUriString,
                                    contentDescription = song.title,
                                    cornerRadius = 24.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Live Audio Waveform Visualizer with real song acoustic dynamics
                Box(modifier = Modifier.padding(horizontal = 32.dp)) {
                    AudioVisualizer(
                        isPlaying = playbackState.isPlaying,
                        currentPositionMs = playbackState.currentPositionMs,
                        durationMs = playbackState.durationMs,
                        songId = currentSong.id,
                        barCount = 28,
                        barHeight = 32.dp,
                        primaryColor = MaterialTheme.colorScheme.primary,
                        secondaryColor = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Title, Artist and Favorite
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

                // Hi-Res Audio Specs & A-B Repeat Badge Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Audio Inspector pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { onOpenAudioInspector() }
                    ) {
                        Text(
                            text = "320 kbps • Studio DSP • 44.1 kHz",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // A-B Repeat Controller
                    Surface(
                        shape = RoundedCornerShape(10.dp),
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
                            else -> "A ⇄ B Активен ✕"
                        }
                        Text(
                            text = abText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (abPointA != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Progress Bar
                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    InteractiveProgressBar(
                        currentPositionMs = playbackState.currentPositionMs,
                        durationMs = playbackState.durationMs,
                        onSeekTo = onSeekTo,
                        bookmarks = bookmarks
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onToggleShuffle()
                        },
                        modifier = Modifier.testTag("now_playing_shuffle")
                    ) {
                        Icon(
                            imageVector = AppIcons.Shuffle,
                            contentDescription = "Случайный порядок",
                            tint = if (playbackState.shuffleModeEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .fluidPress(
                                scaleDown = 0.90f,
                                hapticType = HapticType.LIGHT,
                                onClick = onSkipPrevious
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Previous,
                            contentDescription = "Предыдущий",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    MorphingPlayPauseButton(
                        isPlaying = playbackState.isPlaying,
                        onClick = onPlayPause,
                        width = 86.dp,
                        height = 62.dp,
                        iconSize = 32.dp,
                        cornerRadius = 24.dp,
                        modifier = Modifier.testTag("now_playing_play_pause")
                    )

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .fluidPress(
                                scaleDown = 0.90f,
                                hapticType = HapticType.LIGHT,
                                onClick = onSkipNext
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Next,
                            contentDescription = "Следующий",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onCycleRepeatMode()
                        },
                        modifier = Modifier.testTag("now_playing_repeat")
                    ) {
                        val repeatIcon = when (playbackState.repeatMode) {
                            RepeatMode.ONE -> AppIcons.RepeatOne
                            else -> AppIcons.Repeat
                        }
                        val repeatTint = when (playbackState.repeatMode) {
                            RepeatMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = "Режим повтора",
                            tint = repeatTint
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Quick Action Bar: Queue, Equalizer, Lyrics, Vinyl Toggle, Playlist
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onOpenQueue()
                        },
                        modifier = Modifier.testTag("now_playing_queue")
                    ) {
                        Icon(
                            imageVector = AppIcons.Queue,
                            contentDescription = "Очередь воспроизведения",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onOpenEqualizer()
                        },
                        modifier = Modifier.testTag("now_playing_equalizer")
                    ) {
                        Icon(
                            imageVector = AppIcons.Equalizer,
                            contentDescription = "Эквалайзер",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onOpenLyrics()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = "Текст песни",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onToggleVinylMode()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Album,
                            contentDescription = "Режим винила",
                            tint = if (vinylModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onAddToPlaylist(currentSong)
                        },
                        modifier = Modifier.testTag("now_playing_add_to_playlist")
                    ) {
                        Icon(
                            imageVector = AppIcons.PlaylistAdd,
                            contentDescription = "Добавить в плейлист",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Floating Animated Volume HUD (Appears when dragging on the right edge of screen)
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shadowElevation = 10.dp,
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

                        // Vertical bar indicator
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
