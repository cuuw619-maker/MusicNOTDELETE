package com.example.ui.library

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Folder
import com.example.model.Genre
import com.example.model.Song
import com.example.model.SortOrder
import com.example.ui.components.AlbumCard
import com.example.ui.components.ArtistListItem
import com.example.ui.components.EmptyStateView
import com.example.ui.components.FolderListItem
import com.example.ui.components.OvalTabItem
import com.example.ui.components.OvalTabRow
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
import com.example.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val songs by viewModel.filteredSongs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortOrder.collectAsStateWithLifecycle()

    val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
    val selectedArtist by viewModel.selectedArtist.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    val songsListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastSortOrder by remember { mutableStateOf(currentSort) }

    LaunchedEffect(currentSort, songs) {
        if (lastSortOrder != currentSort) {
            lastSortOrder = currentSort
            songsListState.scrollToItem(0, 0)
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }

    val currentDetail = when {
        selectedAlbum != null -> "album"
        selectedArtist != null -> "artist"
        selectedFolder != null -> "folder"
        selectedGenre != null -> "genre"
        else -> "main"
    }

    AnimatedContent(
        targetState = currentDetail,
        transitionSpec = {
            if (targetState != "main") {
                (slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { it / 3 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(180, easing = FastOutLinearInEasing)) { -it / 3 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { -it / 3 } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(180, easing = FastOutLinearInEasing)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "LibraryDetailTransition"
    ) { detailView ->
        when (detailView) {
            "album" -> {
                val album = selectedAlbum
                if (album != null) {
                    val albumSongs = viewModel.repository.getSongsForAlbum(album.id)
                    DetailListScreen(
                        title = album.title,
                        subtitle = "${album.artist} • ${albumSongs.size} треков",
                        songs = albumSongs,
                        playbackState = playbackState,
                        onBack = { viewModel.selectAlbum(null) },
                        onPlaySong = { song -> viewModel.playSong(song, albumSongs) },
                        onPlayAll = { viewModel.playQueue(albumSongs, 0) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                        onAddToQueue = { viewModel.addToQueue(it) }
                    )
                }
            }
            "artist" -> {
                val artist = selectedArtist
                if (artist != null) {
                    val artistSongs = viewModel.repository.getSongsForArtist(artist.name)
                    DetailListScreen(
                        title = artist.name,
                        subtitle = "${artistSongs.size} треков",
                        songs = artistSongs,
                        playbackState = playbackState,
                        onBack = { viewModel.selectArtist(null) },
                        onPlaySong = { song -> viewModel.playSong(song, artistSongs) },
                        onPlayAll = { viewModel.playQueue(artistSongs, 0) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                        onAddToQueue = { viewModel.addToQueue(it) }
                    )
                }
            }
            "folder" -> {
                val folder = selectedFolder
                if (folder != null) {
                    val folderSongs = viewModel.repository.getSongsForFolder(folder.name)
                    DetailListScreen(
                        title = folder.name,
                        subtitle = "${folderSongs.size} файлов • ${folder.path}",
                        songs = folderSongs,
                        playbackState = playbackState,
                        onBack = { viewModel.selectFolder(null) },
                        onPlaySong = { song -> viewModel.playSong(song, folderSongs) },
                        onPlayAll = { viewModel.playQueue(folderSongs, 0) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                        onAddToQueue = { viewModel.addToQueue(it) }
                    )
                }
            }
            "genre" -> {
                val genre = selectedGenre
                if (genre != null) {
                    val genreSongs = viewModel.repository.getSongsForGenre(genre.name)
                    DetailListScreen(
                        title = genre.name,
                        subtitle = "${genreSongs.size} треков",
                        songs = genreSongs,
                        playbackState = playbackState,
                        onBack = { viewModel.selectGenre(null) },
                        onPlaySong = { song -> viewModel.playSong(song, genreSongs) },
                        onPlayAll = { viewModel.playQueue(genreSongs, 0) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                        onAddToQueue = { viewModel.addToQueue(it) }
                    )
                }
            }
            else -> {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .testTag("library_screen")
                ) {
                    // Pill Search Bar & Expressive Sort Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Поиск трека, альбома, артиста...") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Поиск",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Очистить")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("library_search_input")
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Box {
                            FilledTonalIconButton(
                                onClick = { showSortMenu = true },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("library_sort_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sort,
                                    contentDescription = "Сортировка",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortOrder.values().forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.displayName,
                                                fontWeight = if (currentSort == order) FontWeight.Bold else FontWeight.Normal,
                                                color = if (currentSort == order) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                            scope.launch {
                                                songsListState.scrollToItem(0, 0)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    val libraryTabs = remember {
                        listOf(
                            OvalTabItem("Песни", Icons.Rounded.MusicNote),
                            OvalTabItem("Альбомы", Icons.Rounded.Album),
                            OvalTabItem("Артисты", Icons.Rounded.Person),
                            OvalTabItem("Папки", Icons.Rounded.Folder),
                            OvalTabItem("Жанры", Icons.Rounded.GraphicEq)
                        )
                    }

                    OvalTabRow(
                        tabs = libraryTabs,
                        selectedIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        containerHeightDp = 46
                    )

                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)) { it / 4 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { -it / 4 } + fadeOut(tween(140)))
                            } else {
                                (slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)) { -it / 4 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { it / 4 } + fadeOut(tween(140)))
                            }
                        },
                        label = "library_tab_transition"
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> SongsTab(
                                songs = songs,
                                playbackState = playbackState,
                                listState = songsListState,
                                onPlaySong = { song -> viewModel.playSong(song, songs) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                                onAddToQueue = { viewModel.addToQueue(it) }
                            )
                            1 -> AlbumsTab(
                                albums = albums,
                                onSelectAlbum = { viewModel.selectAlbum(it) }
                            )
                            2 -> ArtistsTab(
                                artists = artists,
                                onSelectArtist = { viewModel.selectArtist(it) }
                            )
                            3 -> FoldersTab(
                                folders = folders,
                                onSelectFolder = { viewModel.selectFolder(it) }
                            )
                            4 -> GenresTab(
                                genres = genres,
                                onSelectGenre = { viewModel.selectGenre(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    playbackState: com.example.model.PlaybackState,
    listState: LazyListState,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        EmptyStateView(
            icon = Icons.Rounded.MusicNote,
            title = "Песни не найдены",
            subtitle = "Попробуйте изменить запрос поиска"
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
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

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    onSelectAlbum: (Album) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyStateView(
            icon = Icons.Rounded.Album,
            title = "Альбомы не найдены",
            subtitle = "В медиатеке нет альбомов"
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 128.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    onClick = { onSelectAlbum(album) }
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    onSelectArtist: (Artist) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyStateView(
            icon = Icons.Rounded.Person,
            title = "Исполнители не найдены",
            subtitle = "В медиатеке нет исполнителей"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
        ) {
            itemsIndexed(
                items = artists,
                key = { _, artist -> artist.name },
                contentType = { _, _ -> "artist" }
            ) { index, artist ->
                ArtistListItem(
                    artist = artist,
                    onClick = { onSelectArtist(artist) },
                    shape = groupedItemShape(index, artists.size),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 1.5.dp)
                )
            }
        }
    }
}

@Composable
private fun FoldersTab(
    folders: List<Folder>,
    onSelectFolder: (Folder) -> Unit
) {
    if (folders.isEmpty()) {
        EmptyStateView(
            icon = Icons.Rounded.Folder,
            title = "Папки не найдены",
            subtitle = "В медиатеке нет папок с аудио"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
        ) {
            itemsIndexed(
                items = folders,
                key = { _, folder -> folder.path + folder.name },
                contentType = { _, _ -> "folder" }
            ) { index, folder ->
                FolderListItem(
                    folder = folder,
                    onClick = { onSelectFolder(folder) },
                    shape = groupedItemShape(index, folders.size),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 1.5.dp)
                )
            }
        }
    }
}

@Composable
private fun GenresTab(
    genres: List<Genre>,
    onSelectGenre: (Genre) -> Unit
) {
    if (genres.isEmpty()) {
        EmptyStateView(
            icon = Icons.Rounded.MusicNote,
            title = "Жанры не найдены",
            subtitle = "В медиатеке нет жанров"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
        ) {
            itemsIndexed(
                items = genres,
                key = { _, genre -> genre.name },
                contentType = { _, _ -> "genre" }
            ) { index, genre ->
                val shape = groupedItemShape(index, genres.size)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 1.5.dp)
                        .clip(shape)
                        .clickable { onSelectGenre(genre) }
                        .animateItem(),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = genre.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = "${genre.songCount} треков",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailListScreen(
    title: String,
    subtitle: String,
    songs: List<Song>,
    playbackState: com.example.model.PlaybackState,
    onBack: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit
) {
    BackHandler(onBack = onBack)

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
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (songs.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Воспроизвести всё",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

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
