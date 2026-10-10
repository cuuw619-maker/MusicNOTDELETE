package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.ui.components.MorphingPlayPauseButton

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
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val view = LocalView.current
    var showMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 400f
            )
        ) { it / 2 } + fadeIn(tween(220)) + scaleIn(
            initialScale = 0.94f,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
        ),
        exit = slideOutVertically(
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
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
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Album art
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!song.albumArtUriString.isNullOrEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(song.albumArtUriString)
                                    .crossfade(true)
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
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title & Artist
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Only Essential Controls: Play/Pause
                    MorphingPlayPauseButton(
                        isPlaying = playbackState.isPlaying,
                        onClick = onPlayPause,
                        size = 42.dp,
                        iconSize = 20.dp
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Next button
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
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

                    // 3-dots Overflow Menu Button
                    Box {
                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                showMenu = true
                            },
                            modifier = Modifier.size(40.dp)
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

                // Progress line
                val progress = if (playbackState.durationMs > 0) {
                    (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
