package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Song
import com.example.ui.animation.HapticType
import com.example.ui.animation.bouncyClickable
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ExpressiveHeroCard
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.ExpressiveMorphShapeSurface
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.LinearWavyProgressIndicator
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
import com.example.ui.components.liquidGlassBackground
import com.example.ui.components.morphPolygonShape
import com.example.ui.components.rememberContinuousMorphShape
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.viewmodel.MusicViewModel

@Composable
fun HomeScreen(
    viewModel: MusicViewModel,
    onNavigateToLibrary: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val recentlyAdded by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val mostPlayed by viewModel.mostPlayed.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val profile = LocalPerformanceProfile.current
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 128.dp)
    ) {
        // Expressive Top Header
        item(key = "home_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Музыка",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Text(
                        text = if (allSongs.isNotEmpty()) "${allSongs.size} треков • Studio DSP" else "Готово к прослушиванию",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isScanning) {
                    LiquidGlassSurface(
                        shape = CircleShape,
                        fallbackColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExpressiveLoadingIndicator(
                                size = 18.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = Color.Transparent
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Поиск медиа...",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
            if (isScanning) {
                LinearWavyProgressIndicator(
                    isPlaying = true,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    height = 8.dp,
                    strokeWidth = 2.6.dp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
        }

        // Expressive Hero Card: Quick Mix / Resume Last Track
        val heroSong = playbackState.currentSong ?: recentlyPlayed.firstOrNull() ?: allSongs.firstOrNull()
        if (heroSong != null) {
            item(key = "home_hero") {
                ExpressiveHeroCard(
                    onClick = {
                        if (playbackState.currentSong != null) {
                            viewModel.playPause()
                        } else if (allSongs.isNotEmpty()) {
                            viewModel.playSong(heroSong, recentlyPlayed.ifEmpty { allSongs })
                        }
                    },
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!heroSong.albumArtUriString.isNullOrEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(heroSong.albumArtUriString)
                                        .size(profile.thumbnailMaxPx)
                                        .allowRgb565(profile.allowRgb565)
                                        .crossfade(profile.crossfadeImages)
                                        .build(),
                                    contentDescription = heroSong.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                ExpressiveMorphShapeSurface(
                                    shapeIndex = 4,
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = if (playbackState.isPlaying) "Сейчас в эфире" else "Продолжить микс",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = heroSong.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = heroSong.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        FilledIconButton(
                            onClick = {
                                if (playbackState.currentSong != null) viewModel.playPause()
                                else viewModel.playSong(heroSong, recentlyPlayed.ifEmpty { allSongs })
                            },
                            modifier = Modifier.size(50.dp),
                            shape = rememberContinuousMorphShape(
                                startIndex = 3,
                                cycleDurationMs = 9000,
                                rotate = playbackState.isPlaying
                            ),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Воспроизвести")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Quick Action Squircle Cards (Shuffle, Favorites, Playlists)
        item(key = "home_quick_actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionCard(
                    title = "Микс",
                    subtitle = "${allSongs.size} треков",
                    icon = Icons.Filled.Shuffle,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    shapeIndex = 4,
                    onClick = {
                        if (allSongs.isNotEmpty()) {
                            viewModel.controllerManager.toggleShuffle()
                            viewModel.playQueue(allSongs.shuffled(), 0)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("home_shuffle_card")
                )

                QuickActionCard(
                    title = "Любимые",
                    subtitle = "${favoriteSongs.size} песен",
                    icon = Icons.Filled.Favorite,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    shapeIndex = 2,
                    onClick = onNavigateToFavorites,
                    modifier = Modifier.weight(1f).testTag("home_favorites_card")
                )

                QuickActionCard(
                    title = "Плейлисты",
                    subtitle = "${playlists.size} создано",
                    icon = Icons.Rounded.QueueMusic,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    shapeIndex = 5,
                    onClick = onNavigateToPlaylists,
                    modifier = Modifier.weight(1f).testTag("home_playlists_card")
                )
            }
        }

        // Recently Played Section
        if (recentlyPlayed.isNotEmpty()) {
            item(key = "home_recent_section") {
                Spacer(modifier = Modifier.height(26.dp))
                ExpressiveSectionHeader(title = "Недавно прослушано")
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(recentlyPlayed.take(10), key = { idx, song -> "recent_${song.id}_$idx" }) { _, song ->
                        RecentSongCard(
                            song = song,
                            isCurrent = playbackState.currentSong?.id == song.id,
                            onClick = { viewModel.playSong(song, recentlyPlayed) }
                        )
                    }
                }
            }
        }

        // Current Queue Card if playback is active
        if (playbackState.queue.isNotEmpty()) {
            item(key = "home_queue_section") {
                Spacer(modifier = Modifier.height(26.dp))
                ExpressiveSectionHeader(
                    title = "Текущая очередь",
                    actionText = "Открыть",
                    onActionClick = { viewModel.setQueueSheetVisible(true) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                LiquidGlassSurface(
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                    fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .bouncyClickable(
                            hapticType = HapticType.LIGHT,
                            onClick = { viewModel.setQueueSheetVisible(true) }
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Воспроизведение очереди",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${playbackState.queue.size} треков • ${playbackState.currentSong?.title ?: "Нет"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Most Played / Frequently Played Section (Expressive Grouped Cards)
        val topMostPlayed = mostPlayed.take(5)
        if (topMostPlayed.isNotEmpty()) {
            item(key = "home_most_header") {
                Spacer(modifier = Modifier.height(26.dp))
                ExpressiveSectionHeader(title = "Часто воспроизводимые")
                Spacer(modifier = Modifier.height(10.dp))
            }
            itemsIndexed(
                items = topMostPlayed,
                key = { idx, song -> "most_${song.id}_$idx" },
                contentType = { _, _ -> "song" }
            ) { index, song ->
                SongListItem(
                    song = song,
                    isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    onClick = { viewModel.playSong(song, mostPlayed) },
                    onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                    onAddToPlaylist = { viewModel.setSongToAddToPlaylist(song) },
                    onAddToQueue = { viewModel.addToQueue(song) },
                    shape = groupedItemShape(index, topMostPlayed.size),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 20.dp, vertical = 1.5.dp)
                )
            }
        }

        // Recently Added Section (Expressive Grouped Cards)
        val topRecentAdded = recentlyAdded.take(5)
        if (topRecentAdded.isNotEmpty()) {
            item(key = "home_added_header") {
                Spacer(modifier = Modifier.height(26.dp))
                ExpressiveSectionHeader(
                    title = "Недавно добавленные",
                    actionText = "Все",
                    onActionClick = onNavigateToLibrary
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            itemsIndexed(
                items = topRecentAdded,
                key = { idx, song -> "added_${song.id}_$idx" },
                contentType = { _, _ -> "song" }
            ) { index, song ->
                SongListItem(
                    song = song,
                    isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    onClick = { viewModel.playSong(song, recentlyAdded) },
                    onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                    onAddToPlaylist = { viewModel.setSongToAddToPlaylist(song) },
                    onAddToQueue = { viewModel.addToQueue(song) },
                    shape = groupedItemShape(index, topRecentAdded.size),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 20.dp, vertical = 1.5.dp)
                )
            }
        }

        // Empty Library State
        if (allSongs.isEmpty() && !isScanning) {
            item(key = "home_empty") {
                Spacer(modifier = Modifier.height(36.dp))
                EmptyStateView(
                    icon = Icons.Rounded.MusicNote,
                    title = "Медиатека пуста",
                    subtitle = "На вашем устройстве не найдено локальных аудиофайлов. Вы также можете слушать музыку в разделе «Онлайн» или импортировать Spotify.",
                    actionButton = {
                        Button(
                            onClick = { viewModel.scanLibrary() },
                            shape = CircleShape
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Сканировать медиатеку")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shapeIndex: Int = 1
) {
    val cardShape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(108.dp)
            .bouncyClickable(
                scaleDown = 0.94f,
                hapticType = HapticType.LIGHT,
                onClick = onClick
            )
            .liquidGlassBackground(
                shape = cardShape,
                fallbackColor = color,
                tintOverride = color
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = rememberContinuousMorphShape(
                    startIndex = shapeIndex,
                    cycleDurationMs = 9600 + shapeIndex * 350,
                    rotate = false
                ),
                color = tint.copy(alpha = 0.16f),
                contentColor = tint,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RecentSongCard(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .width(138.dp)
            .bouncyClickable(
                scaleDown = 0.96f,
                hapticType = HapticType.LIGHT,
                onClick = onClick
            )
            .liquidGlassBackground(
                shape = cardShape,
                fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow,
                fallbackBorder = BorderStroke(
                    width = 1.dp,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (!song.albumArtUriString.isNullOrEmpty()) {
                    val itemProfile = LocalPerformanceProfile.current
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(song.albumArtUriString)
                            .size(itemProfile.thumbnailMaxPx)
                            .allowRgb565(itemProfile.allowRgb565)
                            .crossfade(itemProfile.crossfadeImages)
                            .placeholder(com.example.R.drawable.ic_album_placeholder)
                            .error(com.example.R.drawable.ic_album_placeholder)
                            .build(),
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                FilledIconButton(
                    onClick = onClick,
                    shape = rememberContinuousMorphShape(
                        startIndex = 2,
                        cycleDurationMs = 10000,
                        rotate = isCurrent
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Воспроизвести",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = song.title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = song.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
