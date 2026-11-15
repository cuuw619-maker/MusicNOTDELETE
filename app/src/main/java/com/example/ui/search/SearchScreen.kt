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
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.SearchCategory
import com.example.model.Song
import com.example.online.YouTubeStreamManager
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
import com.example.ui.components.ExpressiveOvalChoiceChip
import com.example.ui.components.ExpressiveSectionHeader
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
import com.example.ui.theme.LocalPerformanceProfile
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
    val onlineSourceFilter by viewModel.onlineSourceFilter.collectAsStateWithLifecycle()
    val sourceHealthStatuses by viewModel.sourceHealthStatuses.collectAsStateWithLifecycle()
    val isCheckingSourceHealth by viewModel.isCheckingSourceHealth.collectAsStateWithLifecycle()
    var showSourceHealthDialog by remember { mutableStateOf(false) }

    val view = LocalView.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val profile = LocalPerformanceProfile.current
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
                items(SearchCategory.values(), key = { it.name }) { category ->
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
                                key = { idx, song -> "song_${song.id}_$idx" },
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
                                key = { idx, artist -> "artist_${artist.name}_$idx" },
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
            // ONLINE MULTI-SOURCE STREAMING SEARCH + LIVE SOURCE HEALTH CHIP
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "source_health_test_chip") {
                    val healthyCount = sourceHealthStatuses.count { it.isHealthy }
                    val testLabel = when {
                        isCheckingSourceHealth -> "Пинг..."
                        sourceHealthStatuses.isNotEmpty() -> "Тест: $healthyCount/${sourceHealthStatuses.size}"
                        else -> "Тест источников"
                    }
                    Surface(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            showSourceHealthDialog = true
                            if (sourceHealthStatuses.isEmpty() && !isCheckingSourceHealth) {
                                viewModel.runSourceHealthCheck()
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("online_source_health_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NetworkCheck,
                                contentDescription = "Тест источников",
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = testLabel,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.5.sp
                                )
                            )
                        }
                    }
                }
                items(YouTubeStreamManager.SOURCE_FILTERS, key = { it.id }) { source ->
                    ExpressiveOvalChoiceChip(
                        label = source.label,
                        selected = onlineSourceFilter == source.id,
                        onClick = { viewModel.setOnlineSourceFilter(source.id) },
                        selectedContainerColor = onlineContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        minHeight = 36.dp,
                        fontSizeSp = 12f,
                        modifier = Modifier.testTag("online_source_filter_${source.id}")
                    )
                }
            }

            if (isOnlineLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ExpressiveLoadingIndicator(
                            size = 54.dp,
                            color = onlineAccent,
                            containerColor = onlineContainer.copy(alpha = 0.45f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Онлайн-поиск по источникам...",
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
                        subtitle = "Бесплатные полные треки: YouTube Music (InnerTubeX), SoundCloud, Bandcamp, Audius, JioSaavn и Archive.org.",
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
                        subtitle = "Попробуйте другой источник или повторите поиск",
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
                    contentPadding = PaddingValues(bottom = 128.dp, top = 6.dp)
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
                        key = { idx, track -> "online_${track.sourceEngine}_${track.id}_$idx" },
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
                                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                                            model = ImageRequest.Builder(context)
                                                .data(track.thumbnailUrl)
                                                .size(profile.thumbnailMaxPx)
                                                .allowRgb565(profile.allowRgb565)
                                                .crossfade(profile.crossfadeImages)
                                                .build(),
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = onlineContainer.copy(alpha = 0.72f)
                                        ) {
                                            Text(
                                                text = track.sourceDisplayName,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (track.isPreviewOnly) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
                                            ) {
                                                Text(
                                                    text = "30с превью",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    ),
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${track.artist} • ${Song.formatDuration(track.durationMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        viewModel.openSourceSwitcherForOnlineTrack(track)
                                    },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("online_track_switcher_${track.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SwapHoriz,
                                        contentDescription = "Другие версии и источники",
                                        tint = onlineAccent
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        viewModel.setSongToAddToPlaylist(track.toSong())
                                    },
                                    modifier = Modifier.size(44.dp)
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
                                    modifier = Modifier.size(44.dp)
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

    if (showSourceHealthDialog) {
        com.example.ui.dialogs.ErrorDiagnosticsDialog(
            report = null,
            sourceHealthStatuses = sourceHealthStatuses,
            isCheckingHealth = isCheckingSourceHealth,
            onRunHealthCheck = { viewModel.runSourceHealthCheck() },
            onRetryPlayback = {},
            onOpenSourceSwitcher = {},
            onSelectAlternativeTrack = {},
            onDismiss = { showSourceHealthDialog = false }
        )
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
