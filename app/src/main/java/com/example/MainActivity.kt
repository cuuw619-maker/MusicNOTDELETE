package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.Song
import com.example.ui.dialogs.AddToPlaylistDialog
import com.example.ui.dialogs.CreatePlaylistDialog
import com.example.ui.dialogs.SleepTimerBottomSheet
import com.example.ui.equalizer.EqualizerSheet
import com.example.ui.favorites.FavoritesScreen
import com.example.ui.home.HomeScreen
import com.example.ui.library.LibraryScreen
import com.example.ui.navigation.NavDestination
import com.example.ui.permissions.PermissionScreen
import com.example.ui.player.MiniPlayer
import com.example.ui.player.NowPlayingScreen
import com.example.ui.player.QueueSheet
import com.example.ui.playlists.PlaylistsScreen
import com.example.ui.search.SearchScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MusicAppTheme
import com.example.ui.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.data?.let { uri ->
            if (uri.scheme == "imux" && uri.host == "spotify-callback") {
                viewModel.handleSpotifyRedirect(uri)
            }
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            val amoledDark by viewModel.amoledDark.collectAsStateWithLifecycle()
            val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
            val performanceProfile = remember(performanceMode, this) {
                com.example.ui.theme.resolvePerformanceProfile(this, performanceMode)
            }

            MusicAppTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                amoledDark = amoledDark
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.example.ui.theme.LocalPerformanceProfile provides performanceProfile
                ) {
                    com.example.ui.theme.ProvideResponsiveLayout {
                        MainApp(viewModel = viewModel, checkPermission = ::hasAudioPermission)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "imux" && uri.host == "spotify-callback") {
                viewModel.handleSpotifyRedirect(uri)
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun MainApp(
    viewModel: MusicViewModel,
    checkPermission: () -> Boolean
) {
    var hasPermission by remember { mutableStateOf(checkPermission()) }
    var currentDestination by remember { mutableStateOf(viewModel.preferencesManager.selectedDestination.value) }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val isNowPlayingExpanded by viewModel.isNowPlayingExpanded.collectAsStateWithLifecycle()
    val isQueueVisible by viewModel.isQueueSheetVisible.collectAsStateWithLifecycle()
    val isEqualizerVisible by viewModel.isEqualizerSheetVisible.collectAsStateWithLifecycle()
    val isSleepTimerVisible by viewModel.isSleepTimerSheetVisible.collectAsStateWithLifecycle()
    val isCreatePlaylistVisible by viewModel.isCreatePlaylistDialogVisible.collectAsStateWithLifecycle()
    val songToAddToPlaylist by viewModel.songToAddToPlaylist.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val pitchSemitones by viewModel.pitchSemitones.collectAsStateWithLifecycle()
    val pitchCents by viewModel.pitchCents.collectAsStateWithLifecycle()
    val isLyricsVisible by viewModel.isLyricsSheetVisible.collectAsStateWithLifecycle()
    val isAudioInspectorVisible by viewModel.isAudioInspectorVisible.collectAsStateWithLifecycle()
    val isCarModeActive by viewModel.isCarModeActive.collectAsStateWithLifecycle()
    val vinylModeEnabled by viewModel.vinylModeEnabled.collectAsStateWithLifecycle()
    val abPointA by viewModel.abPointA.collectAsStateWithLifecycle()
    val abPointB by viewModel.abPointB.collectAsStateWithLifecycle()
    val currentVolume by viewModel.preferencesManager.playerVolume.collectAsStateWithLifecycle()
    val bookmarksMap by viewModel.bookmarksState.collectAsStateWithLifecycle()
    val isBookmarksVisible by viewModel.isBookmarksSheetVisible.collectAsStateWithLifecycle()
    val currentSongBookmarks = playbackState.currentSong?.let { bookmarksMap[it.id] } ?: emptyList()
    val isSpotifySheetVisible by viewModel.isSpotifySheetVisible.collectAsStateWithLifecycle()
    val spatialReverbPreset by viewModel.spatialReverbPreset.collectAsStateWithLifecycle()
    val stereoWidener by viewModel.stereoWidener.collectAsStateWithLifecycle()
    val audioBalance by viewModel.audioBalance.collectAsStateWithLifecycle()
    val activeStreamEngine by viewModel.activeStreamEngine.collectAsStateWithLifecycle()
    val isSourceSwitcherVisible by viewModel.isSourceSwitcherVisible.collectAsStateWithLifecycle()
    val diagnosticReport by viewModel.diagnosticReport.collectAsStateWithLifecycle()
    val sourceHealthStatuses by viewModel.sourceHealthStatuses.collectAsStateWithLifecycle()
    val isCheckingSourceHealth by viewModel.isCheckingSourceHealth.collectAsStateWithLifecycle()
    val reducedMotion by viewModel.reducedMotion.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pendingCrashReport by remember {
        mutableStateOf(com.example.util.CrashHandler.getLastCrashReport(context))
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playbackState.errorMessage, diagnosticReport) {
        val err = playbackState.errorMessage
        if (!err.isNullOrEmpty() && diagnosticReport == null) {
            val result = snackbarHostState.showSnackbar(
                message = err,
                actionLabel = "Повторить",
                withDismissAction = true
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.retryPlayback()
            } else {
                viewModel.clearPlaybackError()
            }
        } else if (diagnosticReport != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    BackHandler(enabled = isNowPlayingExpanded) {
        viewModel.setNowPlayingExpanded(false)
    }

    if (!hasPermission) {
        PermissionScreen(
            onPermissionGranted = {
                hasPermission = true
                viewModel.scanLibrary()
            }
        )
        return
    }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        com.example.ui.components.AmbientGlowCanvas(
            isPlaying = playbackState.isPlaying,
            showFloatingGeometry = true,
            reducedMotion = reducedMotion,
            baseIntensity = if (playbackState.isPlaying) 0.34f else 0.20f,
            modifier = Modifier.fillMaxSize()
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 600.dp

            if (isWideScreen) {
                // Adaptive Tablet / Foldable layout: Side Navigation Rail
                Row(modifier = Modifier.fillMaxSize()) {
                    com.example.ui.navigation.ExpressiveNavigationRail(
                        currentDestination = currentDestination,
                        onNavigate = { currentDestination = it; viewModel.preferencesManager.setSelectedDestination(it) }
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(bottom = if (playbackState.currentSong != null) 80.dp else 0.dp)
                            ) {
                                AppScreenContent(
                                    destination = currentDestination,
                                    viewModel = viewModel,
                                    onNavigate = { currentDestination = it; viewModel.preferencesManager.setSelectedDestination(it) }
                                )
                            }
                        }

                        if (playbackState.currentSong != null && !isNowPlayingExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                MiniPlayer(
                                    playbackState = playbackState,
                                    onClick = { viewModel.setNowPlayingExpanded(true) },
                                    onPlayPause = { viewModel.playPause() },
                                    onSkipNext = { viewModel.skipToNext() },
                                    onOpenQueue = { viewModel.setQueueSheetVisible(true) },
                                    onOpenEqualizer = { viewModel.setEqualizerSheetVisible(true) },
                                    onOpenLyrics = { viewModel.setLyricsSheetVisible(true) },
                                    onOpenSleepTimer = { viewModel.setSleepTimerSheetVisible(true) },
                                    onToggleFavorite = { playbackState.currentSong?.let { viewModel.toggleFavorite(it.id) } },
                                    onRetryPlayback = { viewModel.retryPlayback() },
                                    onOpenSourceSwitcher = { viewModel.openSourceSwitcherForCurrent() },
                                    playbackPositionFlow = viewModel.playbackPositionMs,
                                    modifier = Modifier.testTag("mini_player")
                                )
                            }
                        }
                    }
                }
            } else {
                // Phone layout: Expressive Floating Navigation Bar + Mini Player
                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(bottom = if (playbackState.currentSong != null) 144.dp else 76.dp)
                        ) {
                            AppScreenContent(
                                destination = currentDestination,
                                viewModel = viewModel,
                                onNavigate = { currentDestination = it; viewModel.preferencesManager.setSelectedDestination(it) }
                            )
                        }
                    }

                    // Bottom Navigation + Mini Player stacked cleanly
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    ) {
                        if (playbackState.currentSong != null && !isNowPlayingExpanded) {
                            MiniPlayer(
                                playbackState = playbackState,
                                onClick = { viewModel.setNowPlayingExpanded(true) },
                                onPlayPause = { viewModel.playPause() },
                                onSkipNext = { viewModel.skipToNext() },
                                onOpenQueue = { viewModel.setQueueSheetVisible(true) },
                                onOpenEqualizer = { viewModel.setEqualizerSheetVisible(true) },
                                onOpenLyrics = { viewModel.setLyricsSheetVisible(true) },
                                onOpenSleepTimer = { viewModel.setSleepTimerSheetVisible(true) },
                                onToggleFavorite = { playbackState.currentSong?.let { viewModel.toggleFavorite(it.id) } },
                                onRetryPlayback = { viewModel.retryPlayback() },
                                onOpenSourceSwitcher = { viewModel.openSourceSwitcherForCurrent() },
                                playbackPositionFlow = viewModel.playbackPositionMs,
                                modifier = Modifier.testTag("mini_player")
                            )
                        }

                        com.example.ui.navigation.ExpressiveFloatingNavigationBar(
                            currentDestination = currentDestination,
                            onNavigate = { currentDestination = it; viewModel.preferencesManager.setSelectedDestination(it) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (playbackState.currentSong != null && !isNowPlayingExpanded) 160.dp else 88.dp
                )
        )

        // Full Now Playing Screen (Slides up smoothly over everything on phone & tablet)
        androidx.compose.animation.AnimatedVisibility(
            visible = isNowPlayingExpanded && playbackState.currentSong != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200)) + scaleIn(
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, 1f),
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(160)) + scaleOut(
                targetScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, 1f),
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
        ) {
            NowPlayingScreen(
                playbackState = playbackState,
                onCollapse = { viewModel.setNowPlayingExpanded(false) },
                onPlayPause = { viewModel.playPause() },
                onSkipNext = { viewModel.skipToNext() },
                onSkipPrevious = { viewModel.skipToPrevious() },
                onSeekTo = { viewModel.seekTo(it) },
                onRewind10 = { viewModel.rewind10() },
                onForward10 = { viewModel.forward10() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onCycleRepeatMode = { viewModel.cycleRepeatMode() },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onOpenQueue = { viewModel.setQueueSheetVisible(true) },
                onOpenEqualizer = { viewModel.setEqualizerSheetVisible(true) },
                onAddToPlaylist = { viewModel.setSongToAddToPlaylist(it) },
                onOpenSleepTimer = { viewModel.setSleepTimerSheetVisible(true) },
                onOpenLyrics = { viewModel.setLyricsSheetVisible(true) },
                onOpenAudioInspector = { viewModel.setAudioInspectorVisible(true) },
                onOpenCarMode = { viewModel.setCarModeActive(true) },
                currentSpeed = playbackSpeed,
                currentPitchSemitones = pitchSemitones,
                currentPitchCents = pitchCents,
                onSpeedChanged = { viewModel.setPlaybackSpeed(it) },
                onPitchChanged = { viewModel.setPitchSemitones(it) },
                onPitchCentsChanged = { viewModel.setPitchCents(it) },
                abPointA = abPointA,
                abPointB = abPointB,
                onSetABPointA = { viewModel.setABPointA(viewModel.playbackPositionMs.value) },
                onSetABPointB = { viewModel.setABPointB(viewModel.playbackPositionMs.value) },
                onClearABRepeat = { viewModel.clearABRepeat() },
                vinylModeEnabled = vinylModeEnabled,
                onToggleVinylMode = { viewModel.setVinylModeEnabled(!vinylModeEnabled) },
                currentVolume = currentVolume,
                onVolumeChanged = { viewModel.preferencesManager.setPlayerVolume(it) },
                onRetryPlayback = { viewModel.retryPlayback() },
                activeStreamEngine = activeStreamEngine,
                onOpenSourceSwitcher = { viewModel.openSourceSwitcherForCurrent() },
                bookmarks = currentSongBookmarks,
                onOpenBookmarks = { viewModel.setBookmarksSheetVisible(true) },
                playbackPositionFlow = viewModel.playbackPositionMs,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    }


    // Modal Bottom Sheets & Dialogs
    if (isQueueVisible) {
        QueueSheet(
            playbackState = playbackState,
            onDismiss = { viewModel.setQueueSheetVisible(false) },
            onPlayQueueIndex = { viewModel.playQueueIndex(it) },
            onRemoveFromQueue = { viewModel.removeFromQueue(it) },
            onReorderQueue = { from, to -> viewModel.reorderQueue(from, to) },
            onClearQueue = { viewModel.clearQueue() }
        )
    }

    if (isEqualizerVisible) {
        EqualizerSheet(
            equalizerState = equalizerState,
            onToggleEnabled = { viewModel.setEqualizerEnabled(it) },
            onBandLevelChange = { band, level -> viewModel.setEqualizerBandLevel(band, level) },
            onBassBoostChange = { viewModel.setBassBoost(it) },
            onVirtualizerChange = { viewModel.setVirtualizer(it) },
            onLoudnessEnhancerChange = { viewModel.setLoudnessEnhancer(it) },
            spatialReverbPreset = spatialReverbPreset,
            stereoWidener = stereoWidener,
            audioBalance = audioBalance,
            onReverbPresetChange = { viewModel.setSpatialReverbPreset(it) },
            onStereoWidenerChange = { viewModel.setStereoWidener(it) },
            onAudioBalanceChange = { viewModel.setAudioBalance(it) },
            onSelectPreset = { viewModel.useEqualizerPreset(it) },
            onDismiss = { viewModel.setEqualizerSheetVisible(false) }
        )
    }

    if (isSleepTimerVisible) {
        SleepTimerBottomSheet(
            sleepTimerState = sleepTimerState,
            onSelectOption = { viewModel.setSleepTimer(it) },
            onCancelTimer = { viewModel.cancelSleepTimer() },
            onDismiss = { viewModel.setSleepTimerSheetVisible(false) }
        )
    }

    if (isCreatePlaylistVisible) {
        CreatePlaylistDialog(
            onDismiss = { viewModel.setCreatePlaylistDialogVisible(false) },
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                viewModel.setCreatePlaylistDialogVisible(false)
            }
        )
    }

    songToAddToPlaylist?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlists,
            onDismiss = { viewModel.setSongToAddToPlaylist(null) },
            onSelectPlaylist = { playlistId ->
                viewModel.addSongToPlaylist(playlistId, song.id)
                viewModel.setSongToAddToPlaylist(null)
            },
            onCreateNewPlaylist = {
                viewModel.setSongToAddToPlaylist(null)
                viewModel.setCreatePlaylistDialogVisible(true)
            }
        )
    }

    if (isLyricsVisible) {
        playbackState.currentSong?.let { activeSong ->
            com.example.ui.player.LyricsBottomSheet(
                song = activeSong,
                currentPositionMs = playbackState.currentPositionMs,
                viewModel = viewModel,
                onSeekTo = { viewModel.seekTo(it) },
                onDismiss = { viewModel.setLyricsSheetVisible(false) }
            )
        }
    }

    if (isSpotifySheetVisible) {
        com.example.ui.spotify.SpotifySyncSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.setSpotifySheetVisible(false) }
        )
    }

    if (isAudioInspectorVisible) {
        playbackState.currentSong?.let { activeSong ->
            com.example.ui.player.AudioInspectorDialog(
                song = activeSong,
                onDismiss = { viewModel.setAudioInspectorVisible(false) }
            )
        }
    }

    if (isCarModeActive) {
        com.example.ui.player.CarModeScreen(
            playbackState = playbackState,
            onPlayPause = { viewModel.playPause() },
            onSkipNext = { viewModel.skipToNext() },
            onSkipPrevious = { viewModel.skipToPrevious() },
            onClose = { viewModel.setCarModeActive(false) }
        )
    }

    if (isBookmarksVisible) {
        playbackState.currentSong?.let { activeSong ->
            com.example.ui.player.BookmarksSheet(
                song = activeSong,
                currentPositionMs = playbackState.currentPositionMs,
                bookmarks = currentSongBookmarks,
                onAddBookmark = { viewModel.addBookmark(activeSong.id, it) },
                onRemoveBookmark = { viewModel.removeBookmark(activeSong.id, it) },
                onSeekTo = { viewModel.seekTo(it) },
                onDismiss = { viewModel.setBookmarksSheetVisible(false) }
            )
        }
    }

    if (isSourceSwitcherVisible) {
        com.example.ui.player.TrackSourceSwitcherSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.closeSourceSwitcher() }
        )
    }

    if (diagnosticReport != null) {
        com.example.ui.dialogs.ErrorDiagnosticsDialog(
            report = diagnosticReport,
            sourceHealthStatuses = sourceHealthStatuses,
            isCheckingHealth = isCheckingSourceHealth,
            onRunHealthCheck = { viewModel.runSourceHealthCheck() },
            onRetryPlayback = { viewModel.retryPlayback() },
            onOpenSourceSwitcher = { viewModel.openSourceSwitcherForCurrent() },
            onSelectAlternativeTrack = { alt ->
                viewModel.dismissDiagnosticReport()
                viewModel.playOnlineTrack(alt)
            },
            onDismiss = { viewModel.dismissDiagnosticReport() }
        )
    }

    pendingCrashReport?.let { crashText ->
        com.example.ui.dialogs.CrashReportDialog(
            report = crashText,
            onDismiss = {
                com.example.util.CrashHandler.clearLastCrashReport(context)
                pendingCrashReport = null
            }
        )
    }
}

private val SCREEN_ORDER = listOf(
    NavDestination.HOME,
    NavDestination.SEARCH,
    NavDestination.LIBRARY,
    NavDestination.PLAYLISTS,
    NavDestination.FAVORITES,
    NavDestination.SETTINGS
)

@Composable
private fun AppScreenContent(
    destination: NavDestination,
    viewModel: MusicViewModel,
    onNavigate: (NavDestination) -> Unit
) {
    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            (fadeIn(animationSpec = tween(130, easing = LinearOutSlowInEasing)) + scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(130, easing = LinearOutSlowInEasing)
            )) togetherWith
            fadeOut(animationSpec = tween(90, easing = FastOutLinearInEasing))
        },
        label = "TabScreenTransition"
    ) { targetDest ->
        when (targetDest) {
            NavDestination.HOME -> HomeScreen(
                viewModel = viewModel,
                onNavigateToLibrary = { onNavigate(NavDestination.LIBRARY) },
                onNavigateToFavorites = { onNavigate(NavDestination.FAVORITES) },
                onNavigateToPlaylists = { onNavigate(NavDestination.PLAYLISTS) }
            )
            NavDestination.SEARCH -> SearchScreen(
                viewModel = viewModel,
                onSongSelected = { song -> viewModel.playSong(song) },
                onAlbumSelected = { albumId, _ ->
                    val album = viewModel.albums.value.find { it.id == albumId }
                    viewModel.selectAlbum(album)
                    onNavigate(NavDestination.LIBRARY)
                },
                onArtistSelected = { artistName ->
                    val artist = viewModel.artists.value.find { it.name.equals(artistName, ignoreCase = true) }
                    viewModel.selectArtist(artist)
                    onNavigate(NavDestination.LIBRARY)
                }
            )
            NavDestination.LIBRARY -> LibraryScreen(viewModel = viewModel)
            NavDestination.PLAYLISTS -> PlaylistsScreen(viewModel = viewModel)
            NavDestination.FAVORITES -> FavoritesScreen(
                viewModel = viewModel,
                onNavigateToLibrary = { onNavigate(NavDestination.LIBRARY) }
            )
            NavDestination.SETTINGS -> SettingsScreen(viewModel = viewModel)
        }
    }
}
