package com.example.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Playlist
import com.example.model.Song
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
import com.example.ui.dialogs.ConfirmDeleteDialog
import com.example.ui.dialogs.RenamePlaylistDialog
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.viewmodel.MusicViewModel

@Composable
fun PlaylistsScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsStateWithLifecycle()
    val playlistSongs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    playlistToRename?.let { playlist ->
        RenamePlaylistDialog(
            initialName = playlist.name,
            onDismiss = { playlistToRename = null },
            onConfirm = { newName ->
                viewModel.renamePlaylist(playlist.id, newName)
                playlistToRename = null
            }
        )
    }

    playlistToDelete?.let { playlist ->
        ConfirmDeleteDialog(
            title = "Удалить плейлист?",
            message = "Вы уверены, что хотите удалить плейлист «${playlist.name}»?",
            onDismiss = { playlistToDelete = null },
            onConfirm = {
                viewModel.deletePlaylist(playlist.id)
                playlistToDelete = null
            }
        )
    }

    AnimatedContent(
        targetState = selectedPlaylist != null,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { it / 3 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(180, easing = FastOutLinearInEasing)) { -it / 3 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(180, easing = FastOutLinearInEasing)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "PlaylistDetailTransition"
    ) { isDetail ->
        if (isDetail && selectedPlaylist != null) {
            val playlist = selectedPlaylist!!
            PlaylistDetailScreen(
                playlist = playlist,
                songs = playlistSongs,
                playbackState = playbackState,
                onBack = { viewModel.selectPlaylist(null) },
                onPlaySong = { song -> viewModel.playSong(song, playlistSongs) },
                onPlayAll = { viewModel.playQueue(playlistSongs, 0) },
                onShuffleAll = { viewModel.playQueue(playlistSongs.shuffled(), 0) },
                onRemoveSong = { songId -> viewModel.removeSongFromPlaylist(playlist.id, songId) },
                onRename = { playlistToRename = playlist },
                onDelete = { playlistToDelete = playlist },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                onAddToQueue = { viewModel.addToQueue(it) }
            )
        } else {
            Scaffold(
                modifier = modifier
                    .fillMaxSize()
                    .testTag("playlists_screen"),
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { viewModel.setCreatePlaylistDialogVisible(true) },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(bottom = 90.dp)
                            .testTag("create_playlist_fab")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Создать плейлист")
                    }
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Плейлисты",
                                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold)
                            )
                            Text(
                                text = "${playlists.size} коллекций",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            onClick = { viewModel.setSpotifySheetVisible(true) },
                            shape = CircleShape,
                            color = Color(0xFF1DB954).copy(alpha = 0.16f),
                            contentColor = Color(0xFF1DB954),
                            modifier = Modifier.testTag("open_spotify_sync")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Spotify",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }

                    if (playlists.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Rounded.QueueMusic,
                            title = "Нет плейлистов",
                            subtitle = "Создавайте свои подборки или импортируйте плейлисты напрямую из Spotify",
                            actionButton = {
                                Button(
                                    onClick = { viewModel.setCreatePlaylistDialogVisible(true) },
                                    shape = CircleShape
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Создать плейлист")
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 128.dp, top = 4.dp)
                        ) {
                            itemsIndexed(
                                items = playlists,
                                key = { _, item -> item.id },
                                contentType = { _, _ -> "playlist" }
                            ) { index, playlist ->
                                PlaylistItem(
                                    playlist = playlist,
                                    shape = groupedItemShape(index, playlists.size),
                                    onClick = { viewModel.selectPlaylist(playlist) },
                                    onRename = { playlistToRename = playlist },
                                    onDelete = { playlistToDelete = playlist },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistItem(
    playlist: Playlist,
    shape: Shape,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.5.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!playlist.firstSongAlbumArt.isNullOrEmpty()) {
                    val profile = LocalPerformanceProfile.current
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(playlist.firstSongAlbumArt)
                            .size(profile.thumbnailMaxPx)
                            .allowRgb565(profile.allowRgb565)
                            .crossfade(profile.crossfadeImages)
                            .build(),
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.songCount} треков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Опции",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: Playlist,
    songs: List<Song>,
    playbackState: com.example.model.PlaybackState,
    onBack: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onRemoveSong: (Long) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    BackHandler(onBack = onBack)

    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Назад"
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${songs.size} треков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить плейлист", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }

        // Connected Action Pills (Play all & Shuffle)
        if (songs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        bottomStart = 24.dp,
                        topEnd = 10.dp,
                        bottomEnd = 10.dp
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Воспроизвести", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onShuffleAll,
                    shape = RoundedCornerShape(
                        topStart = 10.dp,
                        bottomStart = 10.dp,
                        topEnd = 24.dp,
                        bottomEnd = 24.dp
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Перемешать", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (songs.isEmpty()) {
            EmptyStateView(
                icon = Icons.Rounded.MusicNote,
                title = "В плейлисте нет песен",
                subtitle = "Добавьте песни через контекстное меню (три точки) в Медиатеке или на Главной",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 128.dp, top = 4.dp)
            ) {
                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id },
                    contentType = { _, _ -> "song" }
                ) { index, song ->
                    SongListItem(
                        song = song,
                        isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                        isCurrent = playbackState.currentSong?.id == song.id,
                        onClick = { onPlaySong(song) },
                        onToggleFavorite = { onToggleFavorite(song.id) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onAddToQueue = { onAddToQueue(song) },
                        shape = groupedItemShape(index, songs.size),
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 1.5.dp)
                    )
                }
            }
        }
    }
}
