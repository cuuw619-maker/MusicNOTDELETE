package com.example.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SongListItem
import com.example.ui.components.groupedItemShape
import com.example.ui.viewmodel.MusicViewModel

@Composable
fun FavoritesScreen(
    viewModel: MusicViewModel,
    onNavigateToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favoriteSongs by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("favorites_screen")
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = "Избранное",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold)
            )
            Text(
                text = "${favoriteSongs.size} треков сохранено",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (favoriteSongs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { viewModel.playQueue(favoriteSongs, 0) },
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
                    onClick = { viewModel.playQueue(favoriteSongs.shuffled(), 0) },
                    shape = RoundedCornerShape(
                        topStart = 10.dp,
                        bottomStart = 10.dp,
                        topEnd = 24.dp,
                        bottomEnd = 24.dp
                    ),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
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

        if (favoriteSongs.isEmpty()) {
            EmptyStateView(
                icon = Icons.Filled.Favorite,
                title = "Нет избранных треков",
                subtitle = "Нажимайте сердечко рядом с понравившимися песнями, чтобы собирать их здесь",
                actionButton = {
                    Button(
                        onClick = onNavigateToLibrary,
                        shape = CircleShape
                    ) {
                        Text("Перейти к медиатеке")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 128.dp, top = 8.dp)
            ) {
                itemsIndexed(
                    items = favoriteSongs,
                    key = { _, song -> song.id },
                    contentType = { _, _ -> "song" }
                ) { index, song ->
                    SongListItem(
                        song = song,
                        isPlaying = playbackState.currentSong?.id == song.id && playbackState.isPlaying,
                        isCurrent = playbackState.currentSong?.id == song.id,
                        onClick = { viewModel.playSong(song, favoriteSongs) },
                        onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                        onAddToPlaylist = { viewModel.setSongToAddToPlaylist(song) },
                        onAddToQueue = { viewModel.addToQueue(song) },
                        shape = groupedItemShape(index, favoriteSongs.size),
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 1.5.dp)
                    )
                }
            }
        }
    }
}
