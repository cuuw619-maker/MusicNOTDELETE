package com.example.ui.spotify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.spotify.SpotifyManager
import com.example.ui.animation.HapticType
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.ConnectedGroupItem
import com.example.ui.components.ExpressiveConnectedButtonGroup
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.groupedItemShape
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.MusicViewModel

private val SpotifyGreen = Color(0xFF1DB954)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySyncSheet(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val view = LocalView.current
    val authState by viewModel.spotifyAuthState.collectAsStateWithLifecycle()

    var playlistLinkInput by remember { mutableStateOf("") }
    var isImportingLink by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }
    var importingPlaylistId by remember { mutableStateOf<String?>(null) }

    var selectedAuthTab by remember { mutableIntStateOf(0) }
    var spDcInput by remember { mutableStateOf("") }
    var customClientIdInput by remember { mutableStateOf(viewModel.spotifyManager.customClientId) }
    var showWebLoginDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier.testTag("spotify_sync_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SpotifyGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Spotify Синхронизация",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                        )
                        Text(
                            text = if (authState.isConnected)
                                "${authState.user?.displayName ?: "Spotify"} • ${authState.authMethod.ifBlank { "Подключен" }}"
                            else "Импорт плейлистов и онлайн-стриминг",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AnimatedVisibility(visible = !authState.errorMessage.isNullOrBlank()) {
                authState.errorMessage?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Quick Public Playlist Import by URL
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Импорт плейлиста по ссылке (без входа)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = SpotifyGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Вставьте ссылку на публичный плейлист Spotify — треки автоматически найдутся и заиграют через онлайн-источники.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = playlistLinkInput,
                        onValueChange = { playlistLinkInput = it },
                        placeholder = { Text("https://open.spotify.com/playlist/...") },
                        leadingIcon = { Icon(imageVector = Icons.Rounded.Link, contentDescription = null) },
                        trailingIcon = {
                            if (isImportingLink) {
                                ExpressiveLoadingIndicator(
                                    size = 28.dp,
                                    color = SpotifyGreen,
                                    containerColor = Color.Transparent
                                )
                            } else if (playlistLinkInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        isImportingLink = true
                                        statusMessage = null
                                        viewModel.importSpotifyPlaylistByUrl(playlistLinkInput) { success ->
                                            isImportingLink = false
                                            if (success) {
                                                isErrorStatus = false
                                                statusMessage = "Плейлист и все его треки добавлены!"
                                                playlistLinkInput = ""
                                            } else {
                                                isErrorStatus = true
                                                statusMessage = "Не удалось прочитать плейлист. Проверьте ссылку и публичность."
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Импортировать",
                                        tint = SpotifyGreen
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!authState.isConnected) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Подключение личного аккаунта",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Войдите прямо внутри плеера, чтобы без лимитов загрузить все личные плейлисты и «Любимые треки».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        com.example.ui.components.ExpressiveCapsuleLoadingButton(
                            text = "Войти в аккаунт Spotify (Web)",
                            isLoading = authState.isLoading,
                            onClick = { showWebLoginDialog = true },
                            containerColor = SpotifyGreen,
                            contentColor = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_open_webview_login_btn")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val authMethods = remember {
                            listOf(
                                ConnectedGroupItem("Web-токен (sp_dc)", Icons.Rounded.VpnKey),
                                ConnectedGroupItem("Свой Client ID", Icons.Rounded.Key)
                            )
                        }
                        ExpressiveConnectedButtonGroup(
                            items = authMethods,
                            selectedIndex = selectedAuthTab,
                            onSelect = { selectedAuthTab = it },
                            selectedContainerColor = SpotifyGreen.copy(alpha = 0.2f),
                            selectedContentColor = SpotifyGreen
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (selectedAuthTab == 0) {
                            Text(
                                text = "Вставьте cookie `sp_dc` из браузера (open.spotify.com) или Bearer токен, чтобы загрузить личные плейлисты и «Любимые треки».",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = spDcInput,
                                onValueChange = { spDcInput = it },
                                placeholder = { Text("Вставьте sp_dc=... или Bearer токен") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            com.example.ui.components.ExpressiveCapsuleLoadingButton(
                                text = "Подключить через Web-токен",
                                isLoading = authState.isLoading,
                                enabled = spDcInput.isNotBlank(),
                                onClick = {
                                    viewModel.connectSpotifyWithWebToken(spDcInput) { ok ->
                                        if (ok) spDcInput = ""
                                    }
                                },
                                containerColor = SpotifyGreen,
                                contentColor = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("spotify_connect_token_btn")
                            )
                        } else {
                            Text(
                                text = "Для официального OAuth добавьте в Spotify Developer Dashboard Redirect URI ниже и введите свой Client ID:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = SpotifyManager.REDIRECT_URI,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Redirect URI", SpotifyManager.REDIRECT_URI))
                                            statusMessage = "Redirect URI скопирован в буфер обмена"
                                            isErrorStatus = false
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "Копировать URI",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customClientIdInput,
                                onValueChange = {
                                    customClientIdInput = it
                                    viewModel.spotifyManager.customClientId = it
                                },
                                label = { Text("Spotify Client ID (32 символа)") },
                                placeholder = { Text("Например: 8a2f...c41b") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    performHapticFeedback(view, HapticType.MEDIUM)
                                    viewModel.spotifyManager.customClientId = customClientIdInput
                                    val authUri = viewModel.spotifyManager.createAuthorizationUri()
                                    if (authUri != null) {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, authUri).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(browserIntent)
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Войти через Spotify OAuth", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!authState.user?.avatarUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = authState.user?.avatarUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(SpotifyGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = authState.user?.displayName?.take(1)?.uppercase() ?: "S",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = authState.user?.displayName ?: "Spotify",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Row {
                        IconButton(onClick = { viewModel.fetchSpotifyPlaylists() }) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Обновить")
                        }
                        OutlinedButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                viewModel.disconnectSpotify()
                            },
                            shape = CircleShape
                        ) {
                            Text("Выйти", fontSize = 12.sp)
                        }
                    }
                }

                if (authState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ExpressiveLoadingIndicator(
                            size = 48.dp,
                            color = SpotifyGreen,
                            containerColor = SpotifyGreen.copy(alpha = 0.14f)
                        )
                    }
                } else if (authState.playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Плейлисты не найдены.\nНажмите «Обновить» или импортируйте по ссылке выше.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    if (authState.importProgressTotal > 0) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SpotifyGreen.copy(alpha = 0.14f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = "Импортировано ${authState.importProgressLoaded} / ${authState.importProgressTotal} треков",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = SpotifyGreen
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                com.example.ui.components.LinearWavyProgressIndicator(
                                    progress = {
                                        (authState.importProgressLoaded.toFloat() /
                                            authState.importProgressTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                    },
                                    isPlaying = true,
                                    color = SpotifyGreen,
                                    trackColor = SpotifyGreen.copy(alpha = 0.22f)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Ваши плейлисты (${authState.playlists.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    ) {
                        itemsIndexed(authState.playlists, key = { _, pl -> pl.id }) { index, pl ->
                            Surface(
                                shape = groupedItemShape(index, authState.playlists.size),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!pl.coverUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = pl.coverUrl,
                                                contentDescription = pl.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.QueueMusic,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pl.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${pl.trackCount} треков • ${pl.ownerName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val isThisImporting = importingPlaylistId == pl.id
                                    IconButton(
                                        onClick = {
                                            performHapticFeedback(view, HapticType.LIGHT)
                                            importingPlaylistId = pl.id
                                            statusMessage = null
                                            viewModel.importSpotifyPlaylist(pl) { success ->
                                                importingPlaylistId = null
                                                isErrorStatus = !success
                                                statusMessage = if (success) {
                                                    "«${pl.name}» добавлен в ваши плейлисты!"
                                                } else {
                                                    "Плейлист пуст или недоступен для импорта"
                                                }
                                            }
                                        },
                                        enabled = !isThisImporting,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        if (isThisImporting) {
                                            ExpressiveLoadingIndicator(
                                                size = 26.dp,
                                                color = SpotifyGreen,
                                                containerColor = Color.Transparent
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Download,
                                                contentDescription = "Импортировать",
                                                tint = SpotifyGreen
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    Surface(
                        color = if (isErrorStatus) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isErrorStatus) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = if (isErrorStatus) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isErrorStatus) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showWebLoginDialog) {
        SpotifyWebLoginDialog(
            onSessionCaptured = { accessToken, clientToken, spDc ->
                showWebLoginDialog = false
                viewModel.connectSpotifyWithWebSession(
                    accessToken = accessToken,
                    clientToken = clientToken,
                    spDcCookie = spDc
                ) { ok ->
                    if (ok) {
                        isErrorStatus = false
                        statusMessage = "Аккаунт Spotify подключен через Web!"
                    }
                }
            },
            onDismiss = { showWebLoginDialog = false }
        )
    }
}
