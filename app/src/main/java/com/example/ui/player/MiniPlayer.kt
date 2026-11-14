package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlaybackState
import com.example.ui.animation.HapticType
import com.example.ui.animation.fluidPress
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AppIcons
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.LinearWavyProgressIndicator
import com.example.ui.components.MorphingPlayPauseButton
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.SpringTokens
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onOpenLyrics: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onOpenSourceSwitcher: () -> Unit = {},
    playbackPositionFlow: StateFlow<Long>? = null,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val view = LocalView.current
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current
    var showMenu by remember { mutableStateOf(false) }

    val artCorner by animateDpAsState(
        targetValue = if (playbackState.isPlaying) 15.dp else 11.dp,
        animationSpec = SpringTokens.spatialDefault(),
        label = "mini_art_corner"
    )

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = 0.80f,
                stiffness = 380f
            )
        ) { it / 2 } + fadeIn(tween(220)) + scaleIn(
            initialScale = 0.94f,
            animationSpec = SpringTokens.spatialDefault()
        ),
        exit = slideOutVertically(
            animationSpec = SpringTokens.spatialFast()
        ) { it / 2 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.94f)
    ) {
        Card(
            onClick = {
                performHapticFeedback(view, HapticType.LIGHT)
                onClick()
            },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -18f) {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onClick()
                        }
                    }
                }
                .testTag("mini_player_card"),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (profile.isPotato) 1.dp else 8.dp
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(artCorner))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!song.albumArtUriString.isNullOrEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(song.albumArtUriString)
                                    .size(profile.thumbnailMaxPx)
                                    .allowRgb565(profile.allowRgb565)
                                    .crossfade(profile.crossfadeImages)
                                    .placeholder(com.example.R.drawable.ic_album_placeholder)
                                    .error(com.example.R.drawable.ic_album_placeholder)
                                    .build(),
                                contentDescription = song.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = AppIcons.Library,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        if (playbackState.isBuffering) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)),
                                contentAlignment = Alignment.Center
                            ) {
                                ExpressiveLoadingIndicator(size = 28.dp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    val hasError = !playbackState.isBuffering && !playbackState.errorMessage.isNullOrBlank()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                playbackState.isBuffering -> "Загрузка потока..."
                                hasError -> "Ошибка потока • Повторить"
                                else -> song.artist
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                playbackState.isBuffering -> MaterialTheme.colorScheme.primary
                                hasError -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                onClick = onSkipNext
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
                                showMenu = true
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
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Развернуть плеер") },
                                onClick = {
                                    showMenu = false
                                    onClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сменить источник / версию") },
                                onClick = {
                                    showMenu = false
                                    onOpenSourceSwitcher()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Очередь") },
                                onClick = {
                                    showMenu = false
                                    onOpenQueue()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Эквалайзер") },
                                onClick = {
                                    showMenu = false
                                    onOpenEqualizer()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Текст песни") },
                                onClick = {
                                    showMenu = false
                                    onOpenLyrics()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Таймер сна") },
                                onClick = {
                                    showMenu = false
                                    onOpenSleepTimer()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (song.isFavorite) "Удалить из избранного" else "В избранное") },
                                onClick = {
                                    showMenu = false
                                    onToggleFavorite()
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

                MiniPlayerProgressLine(
                    playbackPositionFlow = playbackPositionFlow,
                    fallbackPositionMs = playbackState.currentPositionMs,
                    durationMs = playbackState.durationMs,
                    isPlaying = playbackState.isPlaying,
                    isBuffering = playbackState.isBuffering
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressLine(
    playbackPositionFlow: StateFlow<Long>?,
    fallbackPositionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean
) {
    LinearWavyProgressIndicator(
        progress = if (isBuffering) null else {
            {
                val pos = playbackPositionFlow?.value ?: fallbackPositionMs
                if (durationMs > 0) (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            }
        },
        isPlaying = isPlaying || isBuffering,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        height = 7.dp,
        strokeWidth = 2.6.dp,
        amplitudeDp = 1.6.dp,
        wavelengthDp = 18.dp,
        modifier = Modifier.fillMaxWidth()
    )
}
