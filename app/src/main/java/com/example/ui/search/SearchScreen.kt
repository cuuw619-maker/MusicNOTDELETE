package com.example.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.model.SearchCategory
import com.example.model.Song
import com.example.ui.animation.HapticType
import com.example.ui.animation.bouncyClickable
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AlbumCard
import com.example.ui.components.ArtistListItem
import com.example.ui.components.EmptyStateView
import com.example.ui.components.OvalTabItem
import com.example.ui.components.OvalTabRow
import com.example.ui.components.SongListItem
import com.example.ui.viewmodel.MusicViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: MusicViewModel,
    onSongSelected: (Song) -> Unit,
    onAlbumSelected: (Long, String) -> Unit,
    onArtistSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchCategory by viewModel.searchCategory.collectAsStateWithLifecycle()
    val searchResult by viewModel.searchResult.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    val onlineResults by viewModel.onlineSearchResults.collectAsStateWithLifecycle()
    val isOnlineLoading by viewModel.isOnlineSearchLoading.collectAsStateWithLifecycle()

    var searchModeIndex by remember { mutableIntStateOf(0) } // 0: Local, 1: YouTube Music
    val view = LocalView.current

    // Trigger online search whenever query changes while online mode is active
    LaunchedEffect(searchQuery, searchModeIndex) {
        if (searchModeIndex == 1 && searchQuery.isNotBlank()) {
            viewModel.searchOnline(searchQuery)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen")
    ) {
        // Mode Selector: Local vs Online YouTube Music
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            OvalTabRow(
                tabs = listOf(
                    OvalTabItem("Медиатека", Icons.Rounded.Folder),
                    OvalTabItem("YouTube Music", Icons.Rounded.Cloud)
                ),
                selectedIndex = searchModeIndex,
                onTabSelected = {
                    performHapticFeedback(view, HapticType.LIGHT)
                    searchModeIndex = it
                    if (it == 1 && searchQuery.isNotBlank()) {
                        viewModel.searchOnline(searchQuery)
                    }
                },
                containerHeightDp = 42
            )
        }

        // Search Input Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_text_field"),
            placeholder = {
                Text(if (searchModeIndex == 0) "Поиск по медиатеке..." else "Поиск в YouTube Music...")
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = if (searchModeIndex == 0) MaterialTheme.colorScheme.primary else Color(0xFFFF0033)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = if (searchModeIndex == 0) MaterialTheme.colorScheme.primary else Color(0xFFFF0033)
            )
        )

        if (searchModeIndex == 0) {
            // ==========================================
            // LOCAL MEDIA SEARCH
            // ==========================================
            // Filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SearchCategory.values()) { category ->
                    FilterChip(
                        selected = searchCategory == category,
                        onClick = { viewModel.setSearchCategory(category) },
                        label = { Text(category.title) },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (searchQuery.isBlank()) {
                // Display Search History
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (searchHistory.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "История поиска",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                Text("Очистить")
                            }
                        }

                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            searchHistory.forEach { item ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.bouncyClickable(
                                        scaleDown = 0.95f,
                                        hapticType = HapticType.LIGHT,
                                        onClick = { viewModel.onSearchQueryChanged(item) }
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            Icons.Rounded.Clear,
                                            contentDescription = "Удалить",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .clickable { viewModel.deleteSearchHistory(item) },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        EmptyStateView(
                            icon = Icons.Rounded.Search,
                            title = "Быстрый поиск по медиатеке",
                            subtitle = "Начните вводить название песни, исполнителя или альбома",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                if (searchResult.isEmpty) {
                    EmptyStateView(
                        icon = Icons.Rounded.SearchOff,
                        title = "Ничего не найдено",
                        subtitle = "По запросу «$searchQuery» ничего не нашлось. Попробуйте вкладку «YouTube Music».",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
                    ) {
                        if (searchResult.songs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Треки (${searchResult.songs.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(searchResult.songs, key = { "song_${it.id}" }) { song ->
                                SongListItem(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                                    isCurrent = playbackState.currentSong?.id == song.id,
                                    onClick = {
                                        viewModel.playSong(song, searchResult.songs)
                                        onSongSelected(song)
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                                    onAddToPlaylist = { viewModel.setSongToAddToPlaylist(song) },
                                    onAddToQueue = { viewModel.addToQueue(song) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }

                        if (searchResult.albums.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Альбомы (${searchResult.albums.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(searchResult.albums, key = { "album_${it.id}" }) { album ->
                                        AlbumCard(
                                            album = album,
                                            onClick = { onAlbumSelected(album.id, album.title) },
                                            modifier = Modifier.width(140.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (searchResult.artists.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Исполнители (${searchResult.artists.size})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(searchResult.artists, key = { "artist_${it.name}" }) { artist ->
                                ArtistListItem(
                                    artist = artist,
                                    onClick = { onArtistSelected(artist.name) },
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // ONLINE YOUTUBE MUSIC SEARCH & STREAMING
            // ==========================================
            if (isOnlineLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFFFF0033))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Поиск в YouTube Music...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchQuery.isBlank()) {
                EmptyStateView(
                    icon = Icons.Rounded.Cloud,
                    title = "Онлайн-стриминг YouTube Music",
                    subtitle = "Введите название песни или исполнителя, чтобы слушать музыку онлайн в высоком качестве",
                    modifier = Modifier.fillMaxSize()
                )
            } else if (onlineResults.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Rounded.SearchOff,
                    title = "Ничего не найдено онлайн",
                    subtitle = "Попробуйте изменить поисковый запрос",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
                ) {
                    item {
                        Text(
                            text = "Результаты YouTube Music (${onlineResults.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFFF0033),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(onlineResults, key = { "yt_${it.id}" }) { track ->
                        val isCurrentPlaying = playbackState.currentSong?.title == track.title && playbackState.isPlaying

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    performHapticFeedback(view, HapticType.LIGHT)
                                    viewModel.playOnlineTrack(track)
                                },
                            color = if (isCurrentPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f) else MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!track.thumbnailUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = track.thumbnailUrl,
                                            contentDescription = track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${track.artist} • ${Song.formatDuration(track.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Actions: Add to playlist & play
                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        viewModel.setSongToAddToPlaylist(track.toSong())
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlaylistAdd,
                                        contentDescription = "В плейлист",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        viewModel.addToQueue(track.toSong())
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = "В очередь",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
