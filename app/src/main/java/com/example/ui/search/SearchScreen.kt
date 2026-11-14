package com.example.ui.search

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.model.SearchCategory
import com.example.model.Song
import com.example.ui.animation.HapticType
import com.example.ui.animation.bouncyClickable
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.AlbumCard
import com.example.ui.components.AnimatedSoundwaveBars
import com.example.ui.components.ArtistListItem
import com.example.ui.components.ConnectedGroupItem
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ExpressiveConnectedButtonGroup
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
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
    val searchModeIndex by viewModel.searchMode.collectAsStateWithLifecycle()
    val searchCategory by viewModel.searchCategory.collectAsStateWithLifecycle()
    val searchResult by viewModel.searchResult.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    val onlineResults by viewModel.onlineSearchResults.collectAsStateWithLifecycle()
    val isOnlineLoading by viewModel.isOnlineSearchLoading.collectAsStateWithLifecycle()

    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val onlineAccent = MaterialTheme.colorScheme.tertiary
    val onlineContainer = MaterialTheme.colorScheme.tertiaryContainer

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen")
    ) {
        Text(
            text = "Поиск",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        // M3 Expressive Connected Switcher: Local Library vs Online Streaming
        val modeItems = remember {
            listOf(
                ConnectedGroupItem("Медиатека", Icons.Rounded.Folder, "search_mode_local"),
                ConnectedGroupItem("Онлайн", Icons.Rounded.Cloud, "search_mode_online")
            )
        }
        ExpressiveConnectedButtonGroup(
            items = modeItems,
            selectedIndex = searchModeIndex,
            onSelect = { viewModel.setSearchMode(it) },
            selectedContainerColor = if (searchModeIndex == 1) onlineContainer else MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = if (searchModeIndex == 1) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Pill Search Input Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_text_field"),
            placeholder = {
                Text(if (searchModeIndex == 0) "Поиск по медиатеке..." else "Поиск музыки онлайн...")
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = if (searchModeIndex == 0) MaterialTheme.colorScheme.primary else onlineAccent
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.submitSearchQuery()
                }
            ),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = if (searchModeIndex == 0) MaterialTheme.colorScheme.primary else onlineAccent
            )
        )

        if (searchModeIndex == 0) {
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
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (searchQuery.isBlank()) {
                if (searchHistory.isNotEmpty()) {
                    SearchHistorySection(
                        searchHistory = searchHistory,
                        onHistorySelected = { item -> viewModel.onSearchQueryChanged(item) },
                        onDeleteHistory = { item -> viewModel.deleteSearchHistory(item) },
                        onClearAll = { viewModel.clearSearchHistory() }
                    )
                } else {
                    EmptyStateView(
                        icon = Icons.Rounded.Search,
                        title = "Быстрый поиск по медиатеке",
                        subtitle = "Начните вводить название песни, исполнителя или альбома",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                if (searchResult.isEmpty) {
                    EmptyStateView(
                        icon = Icons.Rounded.SearchOff,
                        title = "Ничего не найдено",
                        subtitle = "По запросу «$searchQuery» ничего не нашлось. Попробуйте вкладку «Онлайн».",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
                    ) {
                        if (searchResult.songs.isNotEmpty()) {
                            item(key = "local_songs_header", contentType = "header") {
                                ExpressiveSectionHeader(
                                    title = "Треки (${searchResult.songs.size})",
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            itemsIndexed(
                                items = searchResult.songs,
                                key = { _, song -> "song_${song.id}" },
                                contentType = { _, _ -> "song" }
                            ) { index, song ->
                                SongListItem(
                                    song = song,
                                    isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                                    isCurrent = playbackState.currentSong?.id == song.id,
                                    onClick = {
                                        viewModel.commitHistoryOnResultSelected()
                                        viewModel.playSong(song, searchResult.songs)
                                        onSongSelected(song)
                                    },
                                    onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                                    onAddToPlaylist = { viewModel.setSongToAddToPlaylist(song) },
                                    onAddToQueue = { viewModel.addToQueue(song) },
                                    shape = groupedItemShape(index, searchResult.songs.size),
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 16.dp, vertical = 1.5.dp)
                                )
                            }
                        }

                        if (searchResult.albums.isNotEmpty()) {
                            item(key = "local_albums_section", contentType = "albums_row") {
                                Spacer(modifier = Modifier.height(14.dp))
                                ExpressiveSectionHeader(
                                    title = "Альбомы (${searchResult.albums.size})",
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(searchResult.albums, key = { "album_${it.id}" }) { album ->
                                        AlbumCard(
                                            album = album,
                                            onClick = {
                                                viewModel.commitHistoryOnResultSelected()
                                                onAlbumSelected(album.id, album.title)
                                            },
                                            modifier = Modifier.width(148.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (searchResult.artists.isNotEmpty()) {
                            item(key = "local_artists_header", contentType = "header") {
                                Spacer(modifier = Modifier.height(14.dp))
                                ExpressiveSectionHeader(
                                    title = "Исполнители (${searchResult.artists.size})",
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            itemsIndexed(
                                items = searchResult.artists,
                                key = { _, artist -> "artist_${artist.name}" },
                                contentType = { _, _ -> "artist" }
                            ) { index, artist ->
                                ArtistListItem(
                                    artist = artist,
                                    onClick = {
                                        viewModel.commitHistoryOnResultSelected()
                                        onArtistSelected(artist.name)
                                    },
                                    shape = groupedItemShape(index, searchResult.artists.size),
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 16.dp, vertical = 1.5.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ONLINE MULTI-ENGINE STREAMING SEARCH
            if (isOnlineLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ExpressiveLoadingIndicator(
                            size = 58.dp,
                            color = onlineAccent,
                            containerColor = onlineContainer.copy(alpha = 0.55f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Онлайн-поиск...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchQuery.isBlank()) {
                if (searchHistory.isNotEmpty()) {
                    SearchHistorySection(
                        searchHistory = searchHistory,
                        onHistorySelected = { item ->
                            viewModel.onSearchQueryChanged(item)
                            viewModel.searchOnline(item, immediate = true)
                        },
                        onDeleteHistory = { item -> viewModel.deleteSearchHistory(item) },
                        onClearAll = { viewModel.clearSearchHistory() }
                    )
                } else {
                    EmptyStateView(
                        icon = Icons.Rounded.Cloud,
                        title = "Онлайн-стриминг музыки",
                        subtitle = "Введите название песни или исполнителя, чтобы слушать музыку онлайн из нескольких источников",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (onlineResults.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyStateView(
                        icon = Icons.Rounded.SearchOff,
                        title = "Ничего не найдено онлайн",
                        subtitle = "Попробуйте изменить запрос или повторить поиск",
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            viewModel.searchOnline(searchQuery, immediate = true)
                        }
                    ) {
                        Text("Повторить поиск")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
                ) {
                    item(key = "online_header", contentType = "header") {
                        Text(
                            text = "Онлайн • ${onlineResults.size}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = onlineAccent,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    itemsIndexed(
                        items = onlineResults,
                        key = { _, track -> "online_${track.sourceEngine}_${track.id}" },
                        contentType = { _, _ -> "online_track" }
                    ) { index, track ->
                        val currentSong = playbackState.currentSong
                        val isCurrentTrack = currentSong != null && (
                            currentSong.id == track.toSong().id ||
                                (currentSong.title.equals(track.title, ignoreCase = true) &&
                                    currentSong.artist.equals(track.artist, ignoreCase = true))
                            )
                        val isCurrentPlaying = isCurrentTrack && playbackState.isPlaying
                        val itemShape = groupedItemShape(index, onlineResults.size)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .padding(horizontal = 16.dp, vertical = 1.5.dp)
                                .clip(itemShape)
                                .clickable {
                                    performHapticFeedback(view, HapticType.LIGHT)
                                    viewModel.playOnlineTrack(track)
                                },
                            color = if (isCurrentTrack) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = itemShape
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(13.dp))
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

                                    if (isCurrentTrack) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.48f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AnimatedSoundwaveBars(isAnimating = isCurrentPlaying)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = if (isCurrentTrack) FontWeight.ExtraBold else FontWeight.Bold
                                        ),
                                        color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${track.artist} • ${Song.formatDuration(track.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        viewModel.setSongToAddToPlaylist(track.toSong())
                                    },
                                    modifier = Modifier.size(48.dp)
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
                                    },
                                    modifier = Modifier.size(48.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    searchHistory: List<String>,
    onHistorySelected: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Недавние запросы",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            TextButton(onClick = onClearAll) {
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
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.bouncyClickable(
                        scaleDown = 0.95f,
                        hapticType = HapticType.LIGHT,
                        onClick = { onHistorySelected(item) }
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
                                .clickable { onDeleteHistory(item) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

